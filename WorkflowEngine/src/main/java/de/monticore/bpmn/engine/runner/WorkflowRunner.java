/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.runner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.ToNumberPolicy;
import de.monticore.bpmn.engine.ProcessModelLoader;
import de.monticore.bpmn.engine.WorkflowEngine;
import de.monticore.bpmn.engine.definition.InMemoryProcessDefinitionRepository;
import de.monticore.bpmn.engine.definition.ProcessDefinition;
import de.monticore.bpmn.engine.history.HistoryEvent;
import de.monticore.bpmn.engine.history.InMemoryHistoryLog;
import de.monticore.bpmn.engine.instance.InMemoryProcessInstanceRepository;
import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.job.InMemoryJobRepository;
import de.monticore.bpmn.engine.job.Job;
import de.monticore.bpmn.engine.job.TimerCalculator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Executes a process model from the command line and prints what happens.
 *
 * <p>The runner deploys one model into an engine that keeps everything in memory, starts one
 * instance and drives it: either automatically or with the commands of a script. A script is a text
 * with one command per line:
 *
 * <pre>
 * set amount = 150 give a data object a value (before 'start': an initial value)
 * start start the instance (implicit before the first other command)
 * complete Check complete an activity; values may follow: complete Check ok=true
 * trigger PaymentReceived trigger an event; values may follow
 * wait P1D let time pass (ISO 8601) and execute the timers that become due
 * auto continue automatically until the instance ends
 * expect status COMPLETED check the status of the instance
 * expect open Approve, Timeout check what may be signalled next
 * expect variable amount = 150 check the value of a data object
 * show print the state of the instance
 * history print the audit trail
 * </pre>
 *
 * <p>Lines that are empty or start with {@code #} are ignored. A value is {@code true},
 * {@code false}, a number, a text in double quotes or a JSON object or array; a JSON object stands
 * for an object of a model type, its members are the attributes.
 *
 * <p>The automatic mode completes the first open activity; if there is none it lets the next timer
 * elapse; if there is none either it triggers the first open event.
 */
public class WorkflowRunner {
  
  protected static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(
      ToNumberPolicy.LONG_OR_DOUBLE).create();
  
  /** A clock that only moves when the runner lets time pass, so that runs are reproducible. */
  protected static class SteppingClock extends Clock {
    
    protected Instant now;
    
    protected SteppingClock(final Instant start) { this.now = start; }
    
    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }
    
    @Override
    public Clock withZone(final ZoneId zone) { return this; }
    
    @Override
    public Instant instant() { return now; }
    
  }
  
  protected final PrintStream out;
  
  protected final SteppingClock clock = new SteppingClock(Instant.parse("2026-01-01T08:00:00Z"));
  
  protected final List<Path> symbolPath = new ArrayList<>();
  
  protected final Map<String, Object> initialValues = new LinkedHashMap<>();
  
  protected int stepLimit = 100;
  
  protected WorkflowEngine engine;
  
  protected ProcessDefinition definition;
  
  protected String instanceId;
  
  /** How many entries of the audit trail have been printed so far. */
  protected int printedHistory;
  
  protected int steps;
  
  protected boolean failed;
  
  public WorkflowRunner(final PrintStream out) { this.out = out; }
  
  public static void main(final String[] args) {
    System.exit(new WorkflowRunner(System.out).run(args));
  }
  
  /* ======================================================================
   * Command line
   * ====================================================================== */
  
  /**
   * Runs a model as the command line describes.
   *
   * @param args the model file followed by options, cf. {@link #printUsage()}
   * @return 0 if the run succeeded, 1 if a step or an expectation failed, 2 for wrong arguments
   */
  public int run(final String[] args) {
    Path model = null;
    Path script = null;
    boolean interactive = false;
    try {
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "-path" -> symbolPath.add(Path.of(args[++i]));
          case "-var" -> withInitialValue(args[++i]);
          case "-script" -> {
            interactive = args[++i].equals("-");
            script = interactive ? null : Path.of(args[i]);
          }
          case "-steps" -> stepLimit = Integer.parseInt(args[++i]);
          case "-at" -> clock.now = Instant.parse(args[++i]);
          case "-h", "--help" -> {
            printUsage();
            return 0;
          }
          default -> {
            if (model != null || args[i].startsWith("-")) {
              throw new IllegalArgumentException("Unknown argument '" + args[i] + "'.");
            }
            model = Path.of(args[i]);
          }
        }
      }
      if (model == null) {
        throw new IllegalArgumentException("No model file given.");
      }
    }
    catch (IllegalArgumentException | ArrayIndexOutOfBoundsException | DateTimeParseException e) {
      out.println("Error: " + (e instanceof ArrayIndexOutOfBoundsException
          ? "an option lacks its value." : e.getMessage()));
      printUsage();
      return 2;
    }
    
    if (interactive) {
      return run(model, new BufferedReader(new InputStreamReader(System.in,
          StandardCharsets.UTF_8)));
    }
    if (script != null) {
      try (
          BufferedReader reader = Files.newBufferedReader(script)
      ) {
        return run(model, reader);
      }
      catch (IOException e) {
        out.println("Error: the script '" + script + "' could not be read: " + e.getMessage());
        return 2;
      }
    }
    return run(model, (BufferedReader) null);
  }
  
  public void printUsage() {
    out.println("Usage: WorkflowRunner <model.wfm> [options]");
    out.println("  -path <dir>        where symbol files of imported models are looked up;"
        + " may be repeated");
    out.println("  -var <name=value>  an initial value of a data object; may be repeated");
    out.println("  -script <file>     the commands to execute, '-' reads them from the"
        + " standard input;");
    out.println("                     without a script the instance is driven automatically");
    out.println("  -steps <n>         the step limit of the automatic mode (default 100)");
    out.println("  -at <instant>      the time at which the run starts"
        + " (default 2026-01-01T08:00:00Z)");
  }
  
  public WorkflowRunner withSymbolPath(final Path path) {
    symbolPath.add(path);
    return this;
  }
  
  /** @param assignment an initial value in the form {@code name=value} */
  public WorkflowRunner withInitialValue(final String assignment) {
    final int separator = assignment.indexOf('=');
    if (separator < 1) {
      throw new IllegalArgumentException("Expected name=value but got '" + assignment + "'.");
    }
    initialValues.put(assignment.substring(0, separator).trim(), parseValue(assignment.substring(
        separator + 1)));
    return this;
  }
  
  /**
   * Deploys a model and executes a script on one instance of it.
   *
   * @param model the file of the process model
   * @param script the commands, {@code null} to drive the instance automatically
   * @return 0 if the run succeeded, 1 if a step or an expectation failed
   */
  public int run(final Path model, final BufferedReader script) {
    engine = new WorkflowEngine(new ProcessModelLoader(), new InMemoryProcessDefinitionRepository(),
        new InMemoryProcessInstanceRepository(), new InMemoryJobRepository(),
        new InMemoryHistoryLog(), new TimerCalculator(ZoneOffset.UTC)).withClock(clock);
    engine.withSymbolPath(symbolPath.toArray(new Path[0]));
    try {
      definition = engine.deployFile(model);
    }
    catch (IllegalArgumentException e) {
      out.println("The model '" + model + "' could not be deployed:");
      out.println("  " + e.getMessage());
      return 1;
    }
    out.println("Deployed process '" + definition.getKey() + "' (version " + definition.getVersion()
        + ") from " + model.getFileName());
    definition.getLimitations().forEach(limitation -> out.println("  note: " + limitation));
    
    try {
      if (script == null) {
        start();
        auto();
      }
      else {
        String line = script.readLine();
        while (line != null) {
          execute(line.trim());
          line = script.readLine();
        }
        start(); // a script without any command still starts the instance
      }
    }
    catch (IOException e) {
      out.println("Error: the script could not be read: " + e.getMessage());
      failed = true;
    }
    
    if (instanceId != null) {
      final ProcessInstance instance = instance();
      out.println("Result: " + instance.getStatus() + " after " + steps + " step(s)" + instance
          .getFailure().map(failure -> ": " + failure).orElse(""));
    }
    return failed ? 1 : 0;
  }
  
  /* ======================================================================
   * Commands
   * ====================================================================== */
  
  /** Executes one line of a script. */
  protected void execute(final String line) {
    if (line.isEmpty() || line.startsWith("#")) {
      return;
    }
    final String[] parts = line.split("\\s+", 2);
    final String command = parts[0];
    final String argument = parts.length > 1 ? parts[1].trim() : "";
    out.println("> " + line);
    try {
      switch (command) {
        case "set" -> set(argument);
        case "start" -> start();
        case "complete", "trigger", "signal" -> signal(command, argument);
        case "wait" -> letTimePass(argument);
        case "auto" -> {
          start();
          auto();
        }
        case "expect" -> expect(argument);
        case "show" -> {
          start();
          printState();
        }
        case "history" -> history();
        default -> fail("Unknown command '" + command + "'.");
      }
    }
    catch (IllegalArgumentException | IllegalStateException e) {
      fail(e.getMessage());
    }
  }
  
  protected void history() {
    start();
    if (instanceId != null) {
      engine.getHistory().findByInstanceId(instanceId).forEach(event -> out.println("  " + describe(
          event)));
    }
  }
  
  protected void start() {
    if (instanceId != null) {
      return;
    }
    try {
      instanceId = engine.start(definition.getKey(), initialValues).getId();
    }
    catch (IllegalArgumentException | IllegalStateException e) {
      fail("The instance could not be started: " + e.getMessage());
      return;
    }
    out.println("Started an instance" + (initialValues.isEmpty() ? "" : " with " + initialValues));
    printProgress();
  }
  
  protected void set(final String argument) {
    final int separator = argument.indexOf('=');
    if (separator < 1) {
      throw new IllegalArgumentException("Expected 'set name = value'.");
    }
    final String name = argument.substring(0, separator).trim();
    final Object value = parseValue(argument.substring(separator + 1));
    if (instanceId == null) {
      initialValues.put(name, value);
    }
    else {
      engine.setVariables(instanceId, Map.of(name, value));
    }
  }
  
  /** Completes an activity or triggers an event; values may follow the name. */
  protected void signal(final String command, final String argument) {
    start();
    if (instanceId == null) {
      return;
    }
    final String[] parts = argument.split("\\s+", 2);
    final Map<String, Object> values = new LinkedHashMap<>();
    if (parts.length > 1) {
      for (String assignment : splitAssignments(parts[1])) {
        final int separator = assignment.indexOf('=');
        if (separator < 1) {
          throw new IllegalArgumentException("Expected name=value but got '" + assignment + "'.");
        }
        values.put(assignment.substring(0, separator).trim(), parseValue(assignment.substring(
            separator + 1)));
      }
    }
    switch (command) {
      case "complete" -> engine.completeTask(instanceId, parts[0], values);
      case "trigger" -> engine.triggerEvent(instanceId, parts[0], values);
      default -> engine.signal(instanceId, parts[0], values);
    }
    steps++;
    printProgress();
  }
  
  /** Lets time pass and executes the timers that become due, one after the other. */
  protected void letTimePass(final String argument) {
    start();
    final Instant target = plus(clock.now, argument);
    Optional<Job> next = nextJob(target);
    while (next.isPresent()) {
      clock.now = next.get().getDueAt();
      final List<Job> executed = engine.executeDueJobs(clock.now);
      if (executed.isEmpty()) {
        final Job job = engine.getJobs().findById(next.get().getId()).orElse(next.get());
        fail("The timer '" + job.getEventName() + "' could not occur: " + job.getFailure().orElse(
            "unknown reason"));
        break;
      }
      steps += executed.size();
      executed.forEach(job -> out.println("  " + clock.now + " timer " + job.getEventName()
          + " elapsed"));
      printProgress();
      next = nextJob(target);
    }
    clock.now = target;
  }
  
  /** @return the executable job of this instance that is due first, if it is due until then */
  protected Optional<Job> nextJob(final Instant until) {
    if (instanceId == null) {
      return Optional.empty();
    }
    return engine.getJobs().findByInstanceId(instanceId).stream().filter(job -> job.isExecutable()
        && !job.getDueAt().isAfter(until)).min(Comparator.comparing(Job::getDueAt));
  }
  
  /** Drives the instance until it ends, nothing can be done anymore or the step limit is hit. */
  protected void auto() {
    final int limit = steps + stepLimit;
    while (instanceId != null && instance().isActive() && !failed) {
      if (steps >= limit) {
        fail("The instance did not end within " + stepLimit + " steps.");
        return;
      }
      final List<String> activities = engine.getOpenActivities(instanceId);
      final Optional<Job> timer = nextJob(Instant.MAX);
      final Set<String> timerEvents = new LinkedHashSet<>();
      engine.getJobs().findByInstanceId(instanceId).forEach(job -> timerEvents.add(job
          .getEventName()));
      final Optional<String> event = engine.getOpenEvents(instanceId).stream().filter(
          name -> !timerEvents.contains(name)).findFirst();
      if (!activities.isEmpty()) {
        execute("complete " + activities.get(0));
      }
      else if (timer.isPresent()) {
        final Duration delay = Duration.between(clock.now, timer.get().getDueAt());
        execute("wait " + (delay.isNegative() ? Duration.ZERO : delay));
      }
      else if (event.isPresent()) {
        execute("trigger " + event.get());
      }
      else {
        fail("Nothing can be signalled although the instance is active.");
        return;
      }
    }
  }
  
  protected void expect(final String argument) {
    start();
    if (instanceId == null) {
      return;
    }
    final String[] parts = argument.split("\\s+", 2);
    final String rest = parts.length > 1 ? parts[1].trim() : "";
    switch (parts[0]) {
      case "status" -> check("status", rest, instance().getStatus().name());
      case "open" -> {
        final Set<String> expected = new TreeSet<>();
        for (String name : rest.split(",")) {
          if (!name.isBlank()) {
            expected.add(name.trim());
          }
        }
        check("open", expected.toString(), new TreeSet<>(engine.getEnabledSignals(instanceId))
            .toString());
      }
      case "variable" -> {
        final int separator = rest.indexOf('=');
        if (separator < 1) {
          throw new IllegalArgumentException("Expected 'expect variable name = value'.");
        }
        final String name = rest.substring(0, separator).trim();
        check("variable " + name, String.valueOf(parseValue(rest.substring(separator + 1))), String
            .valueOf(engine.getVariables(instanceId).get(name)));
      }
      default -> throw new IllegalArgumentException("Unknown expectation '" + parts[0]
          + "'; use status, open or variable.");
    }
  }
  
  protected void check(final String what, final String expected, final String actual) {
    if (expected.equals(actual)) {
      out.println("  ok: " + what + " is " + actual);
    }
    else {
      fail("Expected " + what + " to be " + expected + " but it is " + actual + ".");
    }
  }
  
  protected void fail(final String message) {
    failed = true;
    out.println("  FAILED: " + message);
  }
  
  /* ======================================================================
   * Output
   * ====================================================================== */
  
  protected ProcessInstance instance() {
    return engine.findInstance(instanceId).orElseThrow();
  }
  
  /** Prints what happened since the last call and what the instance waits for now. */
  protected void printProgress() {
    final List<HistoryEvent> events = engine.getHistory().findByInstanceId(instanceId);
    for (int i = printedHistory; i < events.size(); i++) {
      out.println("  " + describe(events.get(i)));
    }
    printedHistory = events.size();
    if (instance().isActive()) {
      printState();
    }
  }
  
  protected void printState() {
    final ProcessInstance instance = instance();
    if (!instance.isActive()) {
      out.println("  status: " + instance.getStatus());
      return;
    }
    out.println("  open: " + String.join(", ", engine.getEnabledSignals(instanceId)));
    final List<Job> jobs = engine.getJobs().findByInstanceId(instanceId);
    if (!jobs.isEmpty()) {
      final List<String> timers = new ArrayList<>();
      jobs.forEach(job -> timers.add(job.getEventName() + " due " + job.getDueAt()));
      out.println("  timers: " + String.join(", ", timers));
    }
  }
  
  protected String describe(final HistoryEvent event) {
    final String element = event.getElementName();
    return switch (event.getType()) {
      case INSTANCE_STARTED -> "instance started";
      case ACTIVITY_STARTED -> "activity " + element + " started";
      case ACTIVITY_ENDED -> "activity " + element + " ended";
      case ACTIVITY_CANCELLED -> "activity " + element + " cancelled";
      case EVENT_THROWN -> "event " + element + " occurred";
      case INSTANCE_COMPLETED -> "instance completed";
      case INSTANCE_TERMINATED -> "instance terminated";
      case INSTANCE_FAILED -> "instance failed: " + event.getDetail();
      case STEP_FAILED -> "step " + element + " failed and changed nothing: " + event.getDetail();
    };
  }
  
  /* ======================================================================
   * Values and time
   * ====================================================================== */
  
  /** Reads a value of a script: a boolean, a number, a quoted text or JSON. */
  public static Object parseValue(final String text) {
    final String value = text.trim();
    if (value.equals("true") || value.equals("false")) {
      return Boolean.parseBoolean(value);
    }
    if (value.matches("-?\\d+")) {
      return Integer.parseInt(value);
    }
    if (value.matches("-?\\d+\\.\\d+")) {
      return Double.parseDouble(value);
    }
    if (value.startsWith("{") || value.startsWith("[") || value.startsWith("\"")) {
      try {
        return GSON.fromJson(value, Object.class);
      }
      catch (JsonSyntaxException e) {
        throw new IllegalArgumentException("The value '" + value + "' is not valid JSON: " + e
            .getMessage());
      }
    }
    return value;
  }
  
  /** Splits {@code a=1 b={"x": 2}} into assignments; a value in JSON may contain blanks. */
  protected static List<String> splitAssignments(final String text) {
    final List<String> result = new ArrayList<>();
    final StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean quoted = false;
    for (char character : text.toCharArray()) {
      if (character == '"') {
        quoted = !quoted;
      }
      else if (!quoted && (character == '{' || character == '[')) {
        depth++;
      }
      else if (!quoted && (character == '}' || character == ']')) {
        depth--;
      }
      if (Character.isWhitespace(character) && depth == 0 && !quoted) {
        if (current.length() > 0) {
          result.add(current.toString());
          current.setLength(0);
        }
      }
      else {
        current.append(character);
      }
    }
    if (current.length() > 0) {
      result.add(current.toString());
    }
    return result;
  }
  
  /** Adds an ISO 8601 duration such as {@code PT2H}, {@code P14D} or {@code P1M} to an instant. */
  protected static Instant plus(final Instant instant, final String duration) {
    final String text = duration.trim();
    try {
      final int timePart = text.indexOf('T');
      final ZonedDateTime start = instant.atZone(ZoneOffset.UTC);
      if (timePart < 0) {
        return start.plus(Period.parse(text)).toInstant();
      }
      final ZonedDateTime afterDays = timePart == 1 ? start : start.plus(Period.parse(text
          .substring(0, timePart)));
      return afterDays.plus(Duration.parse("P" + text.substring(timePart))).toInstant();
    }
    catch (DateTimeParseException e) {
      throw new IllegalArgumentException("'" + duration
          + "' is not a duration; use the ISO 8601 form, e.g., PT2H or P14D.");
    }
  }
  
}
