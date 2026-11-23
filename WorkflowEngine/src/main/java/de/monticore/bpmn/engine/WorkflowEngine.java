/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import de.monticore.bpmn.engine.definition.InMemoryProcessDefinitionRepository;
import de.monticore.bpmn.engine.definition.ProcessDefinition;
import de.monticore.bpmn.engine.definition.ProcessDefinitionRepository;
import de.monticore.bpmn.engine.history.HistoryEvent;
import de.monticore.bpmn.engine.history.HistoryLog;
import de.monticore.bpmn.engine.history.InMemoryHistoryLog;
import de.monticore.bpmn.engine.instance.InMemoryProcessInstanceRepository;
import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.instance.ProcessInstanceRepository;
import de.monticore.bpmn.engine.instance.ProcessInstanceStatus;
import de.monticore.bpmn.engine.job.InMemoryJobRepository;
import de.monticore.bpmn.engine.job.Job;
import de.monticore.bpmn.engine.job.JobRepository;
import de.monticore.bpmn.engine.job.TimerCalculator;
import de.monticore.bpmn.engine.state.DataObjectResolver;
import de.monticore.bpmn.engine.state.PersistentProcessState;
import de.monticore.bpmn.engine.state.ProcessStateCodec;
import de.monticore.bpmn.interpreter.BPMNInterpreter;
import de.monticore.bpmn.interpreter.BPMNInterpreterBuilder;
import de.monticore.bpmn.interpreter.BPMNState;
import de.monticore.bpmn.interpreter.BPMNTraceEntry;
import de.monticore.bpmn.workflow._ast.ASTFlowElement;
import de.monticore.bpmn.workflow._ast.ASTWFActivity;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerTimer;
import de.monticore.bpmn.workflow._ast.ASTWFProcess;
import de.monticore.bpmn.workflow._ast.ASTWorkflowCompilationUnit;
import de.se_rwth.commons.logging.Finding;
import de.se_rwth.commons.logging.Log;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A workflow engine for the Workflow language, embedded as a library into an application.
 *
 * <p>The engine itself holds no state between two calls: everything it needs is in the repositories
 * (process definitions, process instances, jobs). A call loads the instance, rebuilds the
 * interpreter state from the stored state, performs one step and stores the new state again. That
 * is what makes the state durable — the engine can be restarted at any point and instances continue
 * where they were.
 *
 * <pre>
 * WorkflowEngine engine = new WorkflowEngine();
 * ProcessDefinition definition = engine.deploy(source); // version 1
 * ProcessInstance instance = engine.start("Order", Map.of("amount", 150));
 * instance = engine.completeTask(instance.getId(), "Check");
 * instance = engine.triggerEvent(instance.getId(), "PaymentReceived");
 * engine.executeDueJobs(); // timers that elapsed
 * </pre>
 *
 * <p>A step is atomic. If it cannot be performed, e.g., because a condition cannot be evaluated,
 * the engine throws a {@link ProcessExecutionException} and the instance stays as it was.
 *
 * <p>The engine may be called from several threads. It performs one call at a time, because the
 * language infrastructure it builds on keeps its state in static fields.
 *
 * <p>Replace the repositories to persist into a database; the in-memory ones are the default.
 */
public class WorkflowEngine {
  
  /** MontiCore keeps the symbol table and the log in static fields; they are used one at a time. */
  protected static final Object MONTICORE = new Object();
  
  protected final ProcessModelLoader loader;
  
  protected final ProcessDefinitionRepository definitions;
  
  protected final ProcessInstanceRepository instances;
  
  protected final JobRepository jobs;
  
  protected final HistoryLog history;
  
  protected final TimerCalculator timers;
  
  /** The parsed models by definition id, so that a stored definition is parsed only once. */
  protected final Map<String, ASTWFProcess> models = new ConcurrentHashMap<>();
  
  protected Clock clock = Clock.systemUTC();
  
  /** Looks up the objects that the application keeps itself; absent if there are none. */
  protected DataObjectResolver resolver;
  
  public WorkflowEngine() {
    this(new ProcessModelLoader(), new InMemoryProcessDefinitionRepository(),
        new InMemoryProcessInstanceRepository(), new InMemoryJobRepository(),
        new InMemoryHistoryLog(), new TimerCalculator());
  }
  
  public WorkflowEngine(final ProcessModelLoader loader,
      final ProcessDefinitionRepository definitions, final ProcessInstanceRepository instances,
      final JobRepository jobs, final HistoryLog history, final TimerCalculator timers) {
    this.loader = loader;
    this.definitions = definitions;
    this.instances = instances;
    this.jobs = jobs;
    this.history = history;
    this.timers = timers;
  }
  
  /** Where symbol files of imported models are looked up. */
  public WorkflowEngine withSymbolPath(final Path... paths) {
    loader.withSymbolPath(paths);
    return this;
  }
  
  /** Replaces the clock, e.g., to test timers without waiting. */
  public WorkflowEngine withClock(final Clock clock) {
    this.clock = clock;
    return this;
  }
  
  /**
   * Lets the application keep the objects that the data objects of a process refer to. The engine
   * then stores a reference only and has the object looked up before every step.
   */
  public WorkflowEngine withDataObjectResolver(final DataObjectResolver resolver) {
    this.resolver = resolver;
    return this;
  }
  
  public ProcessDefinitionRepository getDefinitions() { return definitions; }
  
  public ProcessInstanceRepository getInstances() { return instances; }
  
  public JobRepository getJobs() { return jobs; }
  
  public HistoryLog getHistory() { return history; }
  
  /* ======================================================================
   * Deployment and versioning
   * ====================================================================== */
  
  /**
   * Deploys a process model. Every deployment of the same process gets the next version; instances
   * that are already running keep the version they were started with.
   *
   * @param source the source of the process model
   * @return the deployed version
   * @throws ProcessDeploymentException if the model cannot be parsed or violates a context
   * condition; nothing is deployed then
   */
  public ProcessDefinition deploy(final String source) {
    return guarded(() -> {
      final int findings = Log.getFindings().size();
      final Optional<ASTWorkflowCompilationUnit> ast;
      try {
        ast = loader.load(source);
      }
      catch (RuntimeException e) {
        // the front end gives up on some models instead of reporting what is wrong with them
        throw new ProcessDeploymentException("The process model could not be deployed: " + e,
            errorsSince(findings));
      }
      final List<String> errors = errorsSince(findings);
      if (ast.isEmpty() || !errors.isEmpty()) {
        throw new ProcessDeploymentException("The process model could not be deployed.", errors);
      }
      final ASTWFProcess process = ast.get().getWFProcess();
      final String key = process.getName();
      final ProcessDefinition definition = new ProcessDefinition(key, definitions.nextVersion(key),
          source, now(), process);
      try {
        definition.setLimitations(newInterpreter(definition).getLimitations());
      }
      catch (RuntimeException e) {
        throw new ProcessDeploymentException("The process model could not be deployed: " + e,
            errorsSince(findings));
      }
      definition.getLimitations().forEach(Log::warn);
      definitions.save(definition);
      models.put(definition.getId(), process);
      return definition;
    });
  }
  
  public ProcessDefinition deployFile(final Path file) {
    try {
      return deploy(java.nio.file.Files.readString(file));
    }
    catch (java.io.IOException e) {
      throw new IllegalArgumentException("The file '" + file + "' could not be read.", e);
    }
  }
  
  /** @return the deployed version, parsing its source again if it is not in memory */
  public ProcessDefinition getDefinition(final String definitionId) {
    return guarded(() -> {
      final ProcessDefinition definition = definitions.findById(definitionId).orElseThrow(
          () -> new IllegalArgumentException("Unknown process definition '" + definitionId + "'."));
      if (!definition.isPresentProcess()) {
        // the definition came from a store that only kept the source, e.g., after a restart
        if (!models.containsKey(definitionId)) {
          final int findings = Log.getFindings().size();
          final Optional<ASTWorkflowCompilationUnit> ast = loader.load(definition.getSource());
          if (ast.isEmpty()) {
            throw new IllegalStateException("The stored model of '" + definitionId
                + "' could not be parsed. " + String.join(" ", errorsSince(findings)));
          }
          models.put(definitionId, ast.get().getWFProcess());
        }
        definition.setProcess(models.get(definitionId));
        definition.setLimitations(newInterpreter(definition).getLimitations());
      }
      return definition;
    });
  }
  
  /* ======================================================================
   * Instances
   * ====================================================================== */
  
  public ProcessInstance start(final String key) { return start(key, Map.of()); }
  
  /**
   * Starts an instance of the latest version of a process.
   *
   * @param key the name of the process
   * @param variables the initial values of the data objects
   * @return the new instance
   * @throws ProcessExecutionException if the instance cannot be started; no instance exists then
   */
  public ProcessInstance start(final String key, final Map<String, Object> variables) {
    return guarded(() -> {
      final ProcessDefinition definition = definitions.findLatest(key).orElseThrow(
          () -> new IllegalArgumentException("No process '" + key + "' has been deployed."));
      return startInstance(definition.getId(), variables);
    });
  }
  
  /** Starts an instance of one specific version, cf. {@link #start(String, Map)}. */
  public ProcessInstance startVersion(final String key, final int version,
      final Map<String, Object> variables) {
    return guarded(() -> {
      final ProcessDefinition definition = definitions.find(key, version).orElseThrow(
          () -> new IllegalArgumentException("Version " + version + " of '" + key
              + "' has not been deployed."));
      return startInstance(definition.getId(), variables);
    });
  }
  
  protected ProcessInstance startInstance(final String definitionId,
      final Map<String, Object> variables) {
    final ProcessDefinition definition = getDefinition(definitionId);
    final BPMNInterpreter interpreter = newInterpreter(definition);
    final ProcessStateCodec codec = newCodec(interpreter);
    
    final int findings = Log.getFindings().size();
    final BPMNState state;
    final PersistentProcessState captured;
    try {
      variables.forEach(codec::setVariable);
      state = interpreter.start();
      captured = codec.capture(state);
    }
    catch (IllegalArgumentException e) {
      throw e; // the caller passed a value that does not fit
    }
    catch (RuntimeException e) {
      throw new ProcessExecutionException("The process '" + definition.getKey()
          + "' could not be started.", e);
    }
    final List<String> errors = errorsSince(findings);
    if (!errors.isEmpty()) {
      throw new ProcessExecutionException("The process '" + definition.getKey()
          + "' could not be started.", errors);
    }
    
    final ProcessInstance instance = new ProcessInstance(UUID.randomUUID().toString(), definitionId,
        captured, now());
    log(instance, HistoryEvent.Type.INSTANCE_STARTED, "", "");
    finishStep(instance, interpreter, codec, state, captured);
    return instance;
  }
  
  /**
   * Signals that a user or a system finished an activity of an instance.
   *
   * @param instanceId the instance
   * @param activityName the name of the activity in the model
   * @return the updated instance
   * @throws ProcessExecutionException if the step cannot be performed; the instance is unchanged
   */
  public ProcessInstance completeTask(final String instanceId, final String activityName) {
    return completeTask(instanceId, activityName, Map.of());
  }
  
  /**
   * Signals that a user or a system finished an activity of an instance and hands over its result.
   *
   * @param instanceId the instance
   * @param activityName the name of the activity in the model
   * @param variables the values the activity produced, by the name of the data object
   * @return the updated instance
   * @throws ProcessExecutionException if the step cannot be performed; the instance is unchanged
   */
  public ProcessInstance completeTask(final String instanceId, final String activityName,
      final Map<String, Object> variables) {
    return step(instanceId, activityName, variables, (interpreter, codec) -> {
      final ASTWFActivity activity = codec.findActivity(activityName).orElseThrow(
          () -> new IllegalArgumentException("The process has no activity '" + activityName
              + "'."));
      return state -> interpreter.interpret(state, activity);
    });
  }
  
  /**
   * Signals that an event of an instance occurred, e.g., that a message arrived.
   *
   * @param instanceId the instance
   * @param eventName the name of the event in the model
   * @return the updated instance
   * @throws ProcessExecutionException if the step cannot be performed; the instance is unchanged
   */
  public ProcessInstance triggerEvent(final String instanceId, final String eventName) {
    return triggerEvent(instanceId, eventName, Map.of());
  }
  
  /**
   * Signals that an event of an instance occurred and hands over the data that came with it.
   *
   * @param instanceId the instance
   * @param eventName the name of the event in the model
   * @param variables the values that came with the event, by the name of the data object
   * @return the updated instance
   * @throws ProcessExecutionException if the step cannot be performed; the instance is unchanged
   */
  public ProcessInstance triggerEvent(final String instanceId, final String eventName,
      final Map<String, Object> variables) {
    return step(instanceId, eventName, variables, (interpreter, codec) -> {
      final ASTWFEvent event = codec.findEvent(eventName).orElseThrow(
          () -> new IllegalArgumentException("The process has no event '" + eventName + "'."));
      return state -> interpreter.interpret(state, event);
    });
  }
  
  /**
   * Signals an activity or an event by its name: completes the activity of that name, or triggers
   * the event of that name if the process has no such activity.
   *
   * @param instanceId the instance
   * @param name the name of an activity or of an event in the model
   * @param variables the values that come with the signal, by the name of the data object
   * @return the updated instance
   */
  public ProcessInstance signal(final String instanceId, final String name,
      final Map<String, Object> variables) {
    return step(instanceId, name, variables, (interpreter, codec) -> {
      final Optional<ASTWFActivity> activity = codec.findActivity(name);
      if (activity.isPresent()) {
        return state -> interpreter.interpret(state, activity.get());
      }
      final ASTWFEvent event = codec.findEvent(name).orElseThrow(() -> new IllegalArgumentException(
          "The process has neither an activity nor an event '" + name + "'."));
      return state -> interpreter.interpret(state, event);
    });
  }
  
  /**
   * Changes the values of data objects of an instance without moving it forward.
   *
   * @param instanceId the instance
   * @param variables the new values, by the name of the data object
   * @return the updated instance
   */
  public ProcessInstance setVariables(final String instanceId,
      final Map<String, Object> variables) {
    return step(instanceId, "", variables, (interpreter, codec) -> BPMNState::copy);
  }
  
  public Optional<ProcessInstance> findInstance(final String instanceId) {
    return instances.findById(instanceId);
  }
  
  /**
   * @return the current values of the data objects of an instance that have a value. An object the
   * application keeps itself is given by its reference, cf. {@link DataObjectResolver}.
   */
  public Map<String, Object> getVariables(final String instanceId) {
    final ProcessInstance instance = requireInstance(instanceId);
    final Map<String, Object> values = new LinkedHashMap<>();
    instance.getState().getVariables().forEach((name, value) -> values.put(name, value
        .toJavaObject()));
    return values;
  }
  
  /**
   * @return what the environment may signal next for this instance: the activities that wait for
   * their completion, the events that wait for their trigger and the boundary events of the
   * running activities
   */
  public List<String> getEnabledSignals(final String instanceId) {
    return enabledSignals(instanceId, element -> true);
  }
  
  /** @return the activities of an instance that wait for {@link #completeTask} */
  public List<String> getOpenActivities(final String instanceId) {
    return enabledSignals(instanceId, element -> element instanceof ASTWFActivity);
  }
  
  /** @return the events of an instance that may be triggered with {@link #triggerEvent} */
  public List<String> getOpenEvents(final String instanceId) {
    return enabledSignals(instanceId, element -> element instanceof ASTWFEvent);
  }
  
  protected List<String> enabledSignals(final String instanceId,
      final java.util.function.Predicate<ASTFlowElement> filter) {
    return guarded(() -> {
      final ProcessInstance instance = requireInstance(instanceId);
      if (!instance.isActive()) {
        return List.<String> of();
      }
      final BPMNInterpreter interpreter = newInterpreter(getDefinition(instance.getDefinitionId()));
      final ProcessStateCodec codec = newCodec(interpreter);
      final BPMNState state = codec.restoreControlFlow(instance.getState());
      return interpreter.getEnabledSignals(state).stream().filter(filter).map(codec::nameOf)
          .collect(Collectors.toList());
    });
  }
  
  /* ======================================================================
   * Scheduling
   * ====================================================================== */
  
  /** Triggers every timer event that is due now, cf. {@link #executeDueJobs(Instant)}. */
  public List<Job> executeDueJobs() {
    return executeDueJobs(now());
  }
  
  /**
   * Triggers every timer event that is due. Call this from a timer thread, a cron job or a message
   * queue; the engine does not start threads on its own.
   *
   * <p>A job that cannot be executed does not stop the others. It is kept and tried again with the
   * next call until its retries are used up, cf. {@link Job}.
   *
   * @param now the point in time to execute for
   * @return the jobs that were executed
   */
  public List<Job> executeDueJobs(final Instant now) {
    return guarded(() -> {
      final List<Job> executed = new ArrayList<>();
      for (Job job : jobs.findDue(now)) {
        if (!job.isExecutable()) {
          continue; // failed too often, kept for inspection
        }
        final Optional<ProcessInstance> instance = instances.findById(job.getInstanceId());
        if (instance.isEmpty() || !instance.get().isActive()) {
          jobs.delete(job.getId()); // the instance is gone, the job is stale
          continue;
        }
        // a job is executed once; a timer that occurs repeatedly gets a job for its next occurrence
        jobs.delete(job.getId());
        try {
          nextOccurrence(instance.get(), job).ifPresent(jobs::save);
          triggerEvent(job.getInstanceId(), job.getEventName());
          executed.add(job);
        }
        catch (RuntimeException e) {
          jobs.save(job.afterFailure(e.getMessage()));
        }
      }
      return executed;
    });
  }
  
  /** @return the job for the next occurrence of a repeating timer, empty for all other timers */
  protected Optional<Job> nextOccurrence(final ProcessInstance instance, final Job job) {
    final ProcessStateCodec codec = newCodec(newInterpreter(getDefinition(instance
        .getDefinitionId())));
    final Optional<ASTWFEventTriggerTimer> timer = codec.findEvent(job.getEventName()).flatMap(
        this::timerOf);
    if (timer.isEmpty() || !timers.isRepeating(timer.get().getCondition())) {
      return Optional.empty();
    }
    return timers.nextOccurrence(timer.get().getCondition(), job.getDueAt(), job.getOccurrence())
        .map(job::nextOccurrence);
  }
  
  /**
   * Keeps the job table in sync with the state. A timer starts to run when its event is armed: when
   * a catching event gets its token and when the activity of a boundary event starts. It gets a job
   * at that moment, and it loses the job as soon as the event is not armed anymore.
   */
  protected void syncJobs(final ProcessInstance instance, final BPMNInterpreter interpreter,
      final ProcessStateCodec codec, final BPMNState state) {
    if (!instance.isActive()) {
      jobs.deleteByInstanceId(instance.getId());
      return;
    }
    final Set<ASTWFEvent> armedInThisStep = new LinkedHashSet<>();
    for (BPMNTraceEntry entry : state.getTrace()) {
      if (entry.getKind() == BPMNTraceEntry.Kind.EVENT_ARMED) {
        armedInThisStep.add((ASTWFEvent) entry.getElement());
      }
      else if (entry.getKind() == BPMNTraceEntry.Kind.ACTIVITY_STARTED) {
        interpreter.getBoundaryOwners().forEach((event, owner) -> {
          if (owner == entry.getElement()) {
            armedInThisStep.add(event);
          }
        });
      }
    }
    final Set<String> armed = new LinkedHashSet<>();
    for (ASTWFEvent event : armedEvents(interpreter, state)) {
      final Optional<ASTWFEventTriggerTimer> timer = timerOf(event);
      if (timer.isEmpty()) {
        continue;
      }
      final String name = codec.nameOf(event);
      armed.add(name);
      if (armedInThisStep.contains(event)) {
        timers.nextDueDate(timer.get().getCondition(), now()).ifPresent(dueAt -> jobs.save(new Job(
            instance.getId(), name, dueAt)));
      }
    }
    for (Job job : jobs.findByInstanceId(instance.getId())) {
      if (!armed.contains(job.getEventName())) {
        jobs.delete(job.getId());
      }
    }
  }
  
  protected Optional<ASTWFEventTriggerTimer> timerOf(final ASTWFEvent event) {
    if (event.isPresentTrigger() && event.getTrigger() instanceof ASTWFEventTriggerTimer timer) {
      return Optional.of(timer);
    }
    return Optional.empty();
  }
  
  /** @return the events that may be triggered right now, including armed boundary events */
  protected Set<ASTWFEvent> armedEvents(final BPMNInterpreter interpreter, final BPMNState state) {
    final Set<ASTWFEvent> armed = new LinkedHashSet<>(state.getWaiting());
    interpreter.getBoundaryOwners().forEach((event, owner) -> {
      if (state.isRunning(owner)) {
        armed.add(event);
      }
    });
    return armed;
  }
  
  /* ======================================================================
   * Internals
   * ====================================================================== */
  
  /**
   * Runs one step of an instance: load, restore, interpret, capture, store. Nothing is stored if
   * the step fails.
   *
   * @param instanceId the instance to advance
   * @param element the name of the element the step is about, for the audit trail
   * @param variables values to give to data objects before the step
   * @param action resolves what the interpreter should do; resolving fails for unknown names
   * @return the updated instance
   */
  protected ProcessInstance step(final String instanceId, final String element,
      final Map<String, Object> variables,
      final BiFunction<BPMNInterpreter, ProcessStateCodec, java.util.function.UnaryOperator<BPMNState>> action) {
    return guarded(() -> {
      final ProcessInstance instance = requireInstance(instanceId);
      if (!instance.isActive()) {
        throw new IllegalStateException("The instance '" + instanceId + "' is " + instance
            .getStatus() + " and cannot be advanced.");
      }
      final ProcessDefinition definition = getDefinition(instance.getDefinitionId());
      final BPMNInterpreter interpreter = newInterpreter(definition);
      final ProcessStateCodec codec = newCodec(interpreter);
      final java.util.function.UnaryOperator<BPMNState> operation = action.apply(interpreter,
          codec);
      
      final int findings = Log.getFindings().size();
      final BPMNState after;
      final PersistentProcessState captured;
      try {
        final BPMNState before = codec.restore(instance.getState());
        variables.forEach(codec::setVariable);
        after = operation.apply(before);
        captured = codec.capture(after);
      }
      catch (IllegalArgumentException e) {
        throw e; // the caller passed a value that does not fit
      }
      catch (RuntimeException e) {
        log(instance, HistoryEvent.Type.STEP_FAILED, element, String.valueOf(e));
        throw new ProcessExecutionException("The step '" + element + "' of the instance '"
            + instanceId + "' failed.", e);
      }
      final List<String> errors = errorsSince(findings);
      if (!errors.isEmpty()) {
        log(instance, HistoryEvent.Type.STEP_FAILED, element, String.join(" ", errors));
        throw new ProcessExecutionException("The step '" + element + "' of the instance '"
            + instanceId + "' failed.", errors);
      }
      finishStep(instance, interpreter, codec, after, captured);
      return instance;
    });
  }
  
  /** Stores the new state, writes the history and updates the jobs. */
  protected void finishStep(final ProcessInstance instance, final BPMNInterpreter interpreter,
      final ProcessStateCodec codec, final BPMNState after, final PersistentProcessState captured) {
    instance.setState(captured, now());
    if (after.isFailed()) {
      instance.setStatus(ProcessInstanceStatus.FAILED);
      instance.setFailure(after.getFailure().orElse(""));
    }
    else if (after.isTerminated()) {
      instance.setStatus(ProcessInstanceStatus.TERMINATED);
    }
    else if (after.isFinished()) {
      instance.setStatus(ProcessInstanceStatus.COMPLETED);
    }
    else if (interpreter.isDeadlocked(after)) {
      instance.setStatus(ProcessInstanceStatus.FAILED);
      instance.setFailure("Deadlock: nothing can be signalled anymore, but tokens are left on "
          + captured.getTokens() + (captured.getRunning().isEmpty() ? "" : " and in " + captured
              .getRunning()) + ".");
    }
    else {
      instance.setStatus(ProcessInstanceStatus.ACTIVE);
    }
    
    writeHistory(instance, codec, after);
    syncJobs(instance, interpreter, codec, after);
    instances.save(instance);
  }
  
  /** Writes the audit trail of a step from what the interpreter recorded during it. */
  protected void writeHistory(final ProcessInstance instance, final ProcessStateCodec codec,
      final BPMNState after) {
    for (BPMNTraceEntry entry : after.getTrace()) {
      final String name = codec.nameOf(entry.getElement());
      switch (entry.getKind()) {
        case ACTIVITY_STARTED -> log(instance, HistoryEvent.Type.ACTIVITY_STARTED, name, "");
        case ACTIVITY_REPEATED -> {
          log(instance, HistoryEvent.Type.ACTIVITY_ENDED, name, "");
          log(instance, HistoryEvent.Type.ACTIVITY_STARTED, name, "");
        }
        case ACTIVITY_COMPLETED -> log(instance, HistoryEvent.Type.ACTIVITY_ENDED, name, "");
        case ACTIVITY_CANCELLED -> log(instance, HistoryEvent.Type.ACTIVITY_CANCELLED, name, "");
        case EVENT_OCCURRED -> log(instance, HistoryEvent.Type.EVENT_THROWN, name, "");
        default -> {
          // arming and disarming an event is no entry of the audit trail
        }
      }
    }
    switch (instance.getStatus()) {
      case COMPLETED -> log(instance, HistoryEvent.Type.INSTANCE_COMPLETED, "", "");
      case TERMINATED -> log(instance, HistoryEvent.Type.INSTANCE_TERMINATED, "", "");
      case FAILED -> log(instance, HistoryEvent.Type.INSTANCE_FAILED, "", instance.getFailure()
          .orElse(""));
      default -> {
        // the instance continues
      }
    }
  }
  
  protected void log(final ProcessInstance instance, final HistoryEvent.Type type,
      final String elementName, final String detail) {
    history.append(new HistoryEvent(instance.getId(), type, elementName, detail, now()));
  }
  
  protected BPMNInterpreter newInterpreter(final ProcessDefinition definition) {
    return BPMNInterpreterBuilder.buildDefault(definition.getProcess());
  }
  
  protected ProcessStateCodec newCodec(final BPMNInterpreter interpreter) {
    return new ProcessStateCodec(interpreter, resolver);
  }
  
  protected ProcessInstance requireInstance(final String instanceId) {
    return instances.findById(instanceId).orElseThrow(() -> new IllegalArgumentException(
        "Unknown process instance '" + instanceId + "'."));
  }
  
  /**
   * Performs an operation of the engine. One operation runs at a time, and an error that the
   * language infrastructure logs meanwhile is reported to the caller instead of ending the JVM,
   * which is what the log does by default.
   */
  protected <T> T guarded(final Supplier<T> operation) {
    synchronized (MONTICORE) {
      Log.ensureInitialization();
      final boolean failQuick = Log.isFailQuickEnabled();
      final int findings = Log.getFindings().size();
      Log.enableFailQuick(false);
      try {
        return operation.get();
      }
      finally {
        // the caller got the errors as an exception; left in the log they would end the JVM as
        // soon as the log fails quickly again
        final List<Finding> all = Log.getFindings();
        for (int i = all.size() - 1; i >= Math.min(findings, all.size()); i--) {
          if (!all.get(i).isWarning()) {
            all.remove(i);
          }
        }
        Log.enableFailQuick(failQuick);
      }
    }
  }
  
  /** @return the errors that have been logged since the log had the given number of findings */
  protected List<String> errorsSince(final int findings) {
    final List<Finding> all = Log.getFindings();
    final List<String> errors = new ArrayList<>();
    for (int i = Math.min(findings, all.size()); i < all.size(); i++) {
      if (!all.get(i).isWarning()) {
        errors.add(all.get(i).toString());
      }
    }
    return errors;
  }
  
  protected Instant now() { return clock.instant(); }
  
  /** @return whether anything went wrong during the last operations */
  public boolean hasFindings() {
    return !Log.getFindings().isEmpty();
  }
  
}
