/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Multiset;
import de.monticore.bpmn.workflow._ast.ASTFlowElement;
import de.monticore.bpmn.workflow._ast.ASTWFActivity;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.SequenceFlow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The control flow state of one process instance, i.e., the configuration of the BPMN token game.
 *
 * <p>A state is a snapshot: every step of the {@link BPMNInterpreter} returns a new state, the
 * state passed in is left untouched. The data state (values of {@code data} objects) is not part of
 * this class, it lives in the interpreter's frame, cf. {@link BPMNInterpreter#snapshotVariables()}.
 *
 * <p>In addition to the configuration, a state knows how it came about: {@link #getTrace()} lists
 * what happened in the step that produced it.
 */
public class BPMNState {
  
  /** Tokens waiting on sequence flows, i.e., produced by the source but not yet consumed. */
  protected final Multiset<SequenceFlow> tokens;
  
  /** Activities that have been started and wait for their completion to be signalled. */
  protected final Set<ASTWFActivity> running;
  
  /** Catching events that hold a token and wait for their trigger to be signalled. */
  protected final Set<ASTWFEvent> waiting;
  
  /** Number of completed iterations per looping activity. */
  protected final Map<ASTWFActivity, Integer> iterations;
  
  /** Log of all events that have been thrown so far, in the order they occurred. */
  protected final List<ASTWFEvent> thrown;
  
  /** What happened in the step that produced this state; not part of the configuration. */
  protected final List<BPMNTraceEntry> trace;
  
  /** Set by a terminate end event; a terminated instance cannot make any further step. */
  protected boolean terminated;
  
  /** Why the instance ended abnormally, e.g., an error that nobody caught; absent otherwise. */
  protected String failure;
  
  public BPMNState() {
    this.tokens = LinkedHashMultiset.create();
    this.running = new LinkedHashSet<>();
    this.waiting = new LinkedHashSet<>();
    this.iterations = new LinkedHashMap<>();
    this.thrown = new ArrayList<>();
    this.trace = new ArrayList<>();
    this.terminated = false;
    this.failure = null;
  }
  
  /** Copies the configuration of another state. The trace starts empty, it belongs to a step. */
  public BPMNState(final BPMNState other) {
    this.tokens = LinkedHashMultiset.create(other.tokens);
    this.running = new LinkedHashSet<>(other.running);
    this.waiting = new LinkedHashSet<>(other.waiting);
    this.iterations = new LinkedHashMap<>(other.iterations);
    this.thrown = new ArrayList<>(other.thrown);
    this.trace = new ArrayList<>();
    this.terminated = other.terminated;
    this.failure = other.failure;
  }
  
  /** @return a modifiable copy of this state for the next step */
  public BPMNState copy() {
    return new BPMNState(this);
  }
  
  // ------------------------------------------------------------------ tokens
  
  public Multiset<SequenceFlow> getTokens() { return tokens; }
  
  public int countTokens(final SequenceFlow flow) { return tokens.count(flow); }
  
  public boolean hasToken(final SequenceFlow flow) { return tokens.contains(flow); }
  
  public void addToken(final SequenceFlow flow) { tokens.add(flow); }
  
  public void removeToken(final SequenceFlow flow) { tokens.remove(flow); }
  
  // -------------------------------------------------------------- activities
  
  public Set<ASTWFActivity> getRunning() { return Collections.unmodifiableSet(running); }
  
  public boolean isRunning(final ASTWFActivity activity) { return running.contains(activity); }
  
  public void addRunning(final ASTWFActivity activity) { running.add(activity); }
  
  public void removeRunning(final ASTWFActivity activity) { running.remove(activity); }
  
  // ------------------------------------------------------------------ events
  
  public Set<ASTWFEvent> getWaiting() { return Collections.unmodifiableSet(waiting); }
  
  public boolean isWaiting(final ASTWFEvent event) { return waiting.contains(event); }
  
  public void addWaiting(final ASTWFEvent event) { waiting.add(event); }
  
  public void removeWaiting(final ASTWFEvent event) { waiting.remove(event); }
  
  public List<ASTWFEvent> getThrown() { return Collections.unmodifiableList(thrown); }
  
  public void addThrown(final ASTWFEvent event) { thrown.add(event); }
  
  // ------------------------------------------------------------------- loops
  
  public int getIterations(final ASTWFActivity activity) {
    return iterations.getOrDefault(activity, 0);
  }
  
  public void incIterations(final ASTWFActivity activity) {
    iterations.merge(activity, 1, Integer::sum);
  }
  
  public void resetIterations(final ASTWFActivity activity) { iterations.remove(activity); }
  
  // ------------------------------------------------------------------- trace
  
  /** @return what happened in the step that produced this state, in the order it happened */
  public List<BPMNTraceEntry> getTrace() {
    return Collections.unmodifiableList(trace);
  }
  
  public void record(final BPMNTraceEntry.Kind kind, final ASTFlowElement element) {
    trace.add(new BPMNTraceEntry(kind, element));
  }
  
  // --------------------------------------------------------------- lifecycle
  
  public boolean isTerminated() { return terminated; }
  
  /** Kills the instance: all tokens are removed, nothing can be triggered anymore. */
  public void terminate() {
    clear();
    terminated = true;
  }
  
  public boolean isFailed() { return failure != null; }
  
  /** @return why the instance ended abnormally, empty if it did not */
  public Optional<String> getFailure() {
    return Optional.ofNullable(failure);
  }
  
  /**
   * Ends the instance abnormally: all tokens are removed, nothing can be triggered anymore.
   *
   * @param reason why the instance cannot continue
   */
  public void fail(final String reason) {
    clear();
    failure = reason == null ? "" : reason;
  }
  
  protected void clear() {
    tokens.clear();
    running.clear();
    waiting.clear();
    iterations.clear();
  }
  
  /** @return whether the instance carries no token at all anymore */
  public boolean isFinished() {
    return terminated || isFailed() || (tokens.isEmpty() && running.isEmpty() && waiting.isEmpty());
  }
  
  /**
   * @return the activities and events this state waits for. Whether the environment may signal
   * them, and which boundary events are armed in addition, depends on the process, cf.
   * {@link BPMNInterpreter#getEnabledSignals(BPMNState)}.
   */
  public Set<ASTFlowElement> getEnabledSignals() {
    final Set<ASTFlowElement> result = new LinkedHashSet<>(running);
    result.addAll(waiting);
    return result;
  }
  
  @Override
  public String toString() {
    return "BPMNState[tokens=" + tokens.stream().map(SequenceFlow::getName).collect(Collectors
        .joining(", ")) + "; running=" + running.stream().map(ASTWFActivity::getName).collect(
            Collectors.joining(", ")) + "; waiting=" + waiting.stream().map(ASTWFEvent::getName)
                .collect(Collectors.joining(", ")) + (terminated ? "; TERMINATED" : "")
        + (isFailed() ? "; FAILED" : "") + "]";
  }
  
}
