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
import de.monticore.bpmn.engine.state.PersistentProcessState;
import de.monticore.bpmn.engine.state.PersistentValue;
import de.monticore.bpmn.engine.state.ProcessStateCodec;
import de.monticore.bpmn.interpreter.BPMNInterpreter;
import de.monticore.bpmn.interpreter.BPMNInterpreterBuilder;
import de.monticore.bpmn.interpreter.BPMNState;
import de.monticore.bpmn.workflow._ast.ASTWFActivity;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerTimer;
import de.monticore.bpmn.workflow._ast.ASTWFProcess;
import de.monticore.bpmn.workflow._ast.ASTWorkflowCompilationUnit;
import de.se_rwth.commons.logging.Log;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

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
 * <p>Replace the repositories to persist into a database; the in-memory ones are the default.
 */
public class WorkflowEngine {
  
  protected final ProcessModelLoader loader;
  
  protected final ProcessDefinitionRepository definitions;
  
  protected final ProcessInstanceRepository instances;
  
  protected final JobRepository jobs;
  
  protected final HistoryLog history;
  
  protected final TimerCalculator timers;
  
  protected Clock clock = Clock.systemUTC();
  
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
   */
  public ProcessDefinition deploy(final String source) {
    final Optional<ASTWorkflowCompilationUnit> ast = loader.load(source);
    if (ast.isEmpty()) {
      throw new IllegalArgumentException("The process model could not be deployed, see the log.");
    }
    final ASTWFProcess process = ast.get().getWFProcess();
    final String key = process.getName();
    final ProcessDefinition definition = new ProcessDefinition(key, definitions.nextVersion(key),
        source, now(), process);
    definitions.save(definition);
    return definition;
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
    final ProcessDefinition definition = definitions.findById(definitionId).orElseThrow(
        () -> new IllegalArgumentException("Unknown process definition '" + definitionId + "'."));
    if (!definition.isPresentProcess()) {
      // the definition came from a store that only kept the source, e.g., after a restart
      final Optional<ASTWorkflowCompilationUnit> ast = loader.load(definition.getSource());
      if (ast.isEmpty()) {
        throw new IllegalStateException("The stored model of '" + definitionId
            + "' could not be parsed.");
      }
      definition.setProcess(ast.get().getWFProcess());
    }
    return definition;
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
   */
  public ProcessInstance start(final String key, final Map<String, Object> variables) {
    final ProcessDefinition definition = definitions.findLatest(key).orElseThrow(
        () -> new IllegalArgumentException("No process '" + key + "' has been deployed."));
    return startInstance(definition.getId(), variables);
  }
  
  /** Starts an instance of one specific version, cf. {@link #start(String, Map)}. */
  public ProcessInstance startVersion(final String key, final int version,
      final Map<String, Object> variables) {
    final ProcessDefinition definition = definitions.find(key, version).orElseThrow(
        () -> new IllegalArgumentException("Version " + version + " of '" + key
            + "' has not been deployed."));
    return startInstance(definition.getId(), variables);
  }
  
  protected ProcessInstance startInstance(final String definitionId,
      final Map<String, Object> variables) {
    final ProcessDefinition definition = getDefinition(definitionId);
    final BPMNInterpreter interpreter = newInterpreter(definition);
    final ProcessStateCodec codec = new ProcessStateCodec(interpreter);
    
    applyVariables(interpreter, codec, variables);
    final BPMNState state = interpreter.start();
    
    final ProcessInstance instance = new ProcessInstance(UUID.randomUUID().toString(), definitionId,
        codec.capture(state), now());
    log(instance, HistoryEvent.Type.INSTANCE_STARTED, "");
    finishStep(instance, interpreter, codec, new BPMNState(), state);
    return instance;
  }
  
  /**
   * Signals that a user or a system finished an activity of an instance.
   *
   * @param instanceId the instance
   * @param activityName the name of the activity in the model
   * @return the updated instance
   */
  public ProcessInstance completeTask(final String instanceId, final String activityName) {
    return step(instanceId, (interpreter, state) -> {
      final Optional<ASTWFActivity> activity = new ProcessStateCodec(interpreter).resolveActivity(
          activityName);
      if (activity.isEmpty()) {
        throw new IllegalArgumentException("The process has no activity '" + activityName + "'.");
      }
      return interpreter.interpret(state, activity.get());
    });
  }
  
  /**
   * Signals that an event of an instance occurred, e.g., that a message arrived.
   *
   * @param instanceId the instance
   * @param eventName the name of the event in the model
   * @return the updated instance
   */
  public ProcessInstance triggerEvent(final String instanceId, final String eventName) {
    return step(instanceId, (interpreter, state) -> {
      final Optional<ASTWFEvent> event = new ProcessStateCodec(interpreter).resolveEvent(eventName);
      if (event.isEmpty()) {
        throw new IllegalArgumentException("The process has no event '" + eventName + "'.");
      }
      return interpreter.interpret(state, event.get());
    });
  }
  
  public Optional<ProcessInstance> findInstance(final String instanceId) {
    return instances.findById(instanceId);
  }
  
  /** @return the current values of the data objects of an instance */
  public Map<String, Object> getVariables(final String instanceId) {
    final ProcessInstance instance = requireInstance(instanceId);
    final Map<String, Object> values = new LinkedHashMap<>();
    instance.getState().getVariables().forEach((name, value) -> values.put(name, value
        .toJavaObject()));
    return values;
  }
  
  /** @return what the environment may signal next for this instance */
  public List<String> getEnabledSignals(final String instanceId) {
    final PersistentProcessState state = requireInstance(instanceId).getState();
    final List<String> signals = new ArrayList<>(state.getRunning());
    signals.addAll(state.getWaiting());
    return signals;
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
   * @param now the point in time to execute for
   * @return the jobs that were executed
   */
  public List<Job> executeDueJobs(final Instant now) {
    final List<Job> executed = new ArrayList<>();
    for (Job job : jobs.findDue(now)) {
      final Optional<ProcessInstance> instance = instances.findById(job.getInstanceId());
      if (instance.isEmpty() || !instance.get().isActive()) {
        jobs.delete(job.getId()); // the instance is gone, the job is stale
        continue;
      }
      triggerEvent(job.getInstanceId(), job.getEventName());
      executed.add(job);
    }
    return executed;
  }
  
  /**
   * Keeps the job table in sync with the state: a timer event that an instance waits for gets a
   * job, a timer that is not armed anymore loses its job.
   */
  protected void syncJobs(final ProcessInstance instance, final BPMNInterpreter interpreter,
      final BPMNState state) {
    if (!instance.isActive()) {
      jobs.deleteByInstanceId(instance.getId());
      return;
    }
    final Set<String> armed = new LinkedHashSet<>();
    for (ASTWFEvent event : armedEvents(interpreter, state)) {
      if (!(event.isPresentTrigger() && event
          .getTrigger() instanceof ASTWFEventTriggerTimer timer)) {
        continue;
      }
      armed.add(event.getName());
      final String jobId = instance.getId() + ":" + event.getName();
      if (jobs.findById(jobId).isPresent()) {
        continue; // the instance already waits for this timer, do not restart it
      }
      timers.nextDueDate(timer.getCondition(), now()).ifPresent(dueAt -> jobs.save(new Job(instance
          .getId(), event.getName(), dueAt)));
    }
    for (Job job : jobs.findByInstanceId(instance.getId())) {
      if (!armed.contains(job.getEventName())) {
        jobs.delete(job.getId());
      }
    }
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
   * Runs one step of an instance: load, restore, interpret, capture, store.
   *
   * @param instanceId the instance to advance
   * @param action what the interpreter should do
   * @return the updated instance
   */
  protected ProcessInstance step(final String instanceId,
      final BiFunction<BPMNInterpreter, BPMNState, BPMNState> action) {
    final ProcessInstance instance = requireInstance(instanceId);
    if (!instance.isActive()) {
      throw new IllegalStateException("The instance '" + instanceId + "' is " + instance.getStatus()
          + " and cannot be advanced.");
    }
    final ProcessDefinition definition = getDefinition(instance.getDefinitionId());
    final BPMNInterpreter interpreter = newInterpreter(definition);
    final ProcessStateCodec codec = new ProcessStateCodec(interpreter);
    
    final BPMNState before = codec.restore(instance.getState());
    final BPMNState after = action.apply(interpreter, before);
    
    finishStep(instance, interpreter, codec, before, after);
    return instance;
  }
  
  /** Stores the new state, writes the history and updates the jobs. */
  protected void finishStep(final ProcessInstance instance, final BPMNInterpreter interpreter,
      final ProcessStateCodec codec, final BPMNState before, final BPMNState after) {
    instance.setState(codec.capture(after), now());
    instance.setStatus(statusOf(after));
    
    writeHistory(instance, before, after);
    syncJobs(instance, interpreter, after);
    instances.save(instance);
  }
  
  protected ProcessInstanceStatus statusOf(final BPMNState state) {
    if (state.isTerminated()) {
      return ProcessInstanceStatus.TERMINATED;
    }
    return state.isFinished() ? ProcessInstanceStatus.COMPLETED : ProcessInstanceStatus.ACTIVE;
  }
  
  /** Derives the audit trail of a step by comparing the state before and after it. */
  protected void writeHistory(final ProcessInstance instance, final BPMNState before,
      final BPMNState after) {
    difference(before.getRunning(), after.getRunning()).forEach(activity -> log(instance,
        HistoryEvent.Type.ACTIVITY_ENDED, activity.getName()));
    difference(after.getRunning(), before.getRunning()).forEach(activity -> log(instance,
        HistoryEvent.Type.ACTIVITY_STARTED, activity.getName()));
    for (int i = before.getThrown().size(); i < after.getThrown().size(); i++) {
      log(instance, HistoryEvent.Type.EVENT_THROWN, after.getThrown().get(i).getName());
    }
    if (instance.getStatus() == ProcessInstanceStatus.COMPLETED) {
      log(instance, HistoryEvent.Type.INSTANCE_COMPLETED, "");
    }
    else if (instance.getStatus() == ProcessInstanceStatus.TERMINATED) {
      log(instance, HistoryEvent.Type.INSTANCE_TERMINATED, "");
    }
  }
  
  protected <T> List<T> difference(final Collection<T> minuend, final Collection<T> subtrahend) {
    final List<T> result = new ArrayList<>(minuend);
    result.removeAll(subtrahend);
    return result;
  }
  
  protected void log(final ProcessInstance instance, final HistoryEvent.Type type,
      final String elementName) {
    history.append(new HistoryEvent(instance.getId(), type, elementName, now()));
  }
  
  protected BPMNInterpreter newInterpreter(final ProcessDefinition definition) {
    return BPMNInterpreterBuilder.buildDefault(definition.getProcess());
  }
  
  protected void applyVariables(final BPMNInterpreter interpreter, final ProcessStateCodec codec,
      final Map<String, Object> variables) {
    variables.forEach((name, value) -> codec.resolveVariable(name).ifPresent(
        symbol -> PersistentValue.of(value).toMCValue().ifPresent(mcValue -> interpreter
            .setVariable(symbol, mcValue))));
  }
  
  protected ProcessInstance requireInstance(final String instanceId) {
    return instances.findById(instanceId).orElseThrow(() -> new IllegalArgumentException(
        "Unknown process instance '" + instanceId + "'."));
  }
  
  protected Instant now() { return clock.instant(); }
  
  /** @return whether anything went wrong during the last operations */
  public boolean hasFindings() {
    return !Log.getFindings().isEmpty();
  }
  
}
