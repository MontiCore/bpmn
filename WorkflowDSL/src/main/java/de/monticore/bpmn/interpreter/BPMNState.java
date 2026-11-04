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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The control flow state of one process instance, i.e., the configuration of the BPMN token game.
 *
 * <p>A state is a snapshot: every step of the {@link BPMNInterpreter} returns a new state, the
 * state passed in is left untouched. The data state (values of {@code data} objects) is not part of
 * this class, it lives in the interpreter's frame, cf. {@link BPMNInterpreter#snapshotVariables()}.
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
  
  /** Set by a terminate end event; a terminated instance cannot make any further step. */
  protected boolean terminated;
  
  public BPMNState() {
    this.tokens = LinkedHashMultiset.create();
    this.running = new LinkedHashSet<>();
    this.waiting = new LinkedHashSet<>();
    this.iterations = new LinkedHashMap<>();
    this.thrown = new ArrayList<>();
    this.terminated = false;
  }
  
  public BPMNState(final BPMNState other) {
    this.tokens = LinkedHashMultiset.create(other.tokens);
    this.running = new LinkedHashSet<>(other.running);
    this.waiting = new LinkedHashSet<>(other.waiting);
    this.iterations = new LinkedHashMap<>(other.iterations);
    this.thrown = new ArrayList<>(other.thrown);
    this.terminated = other.terminated;
  }
  
  /** @return a modifiable copy of this state */
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
  
  // --------------------------------------------------------------- lifecycle
  
  public boolean isTerminated() { return terminated; }
  
  /** Kills the instance: all tokens are removed, nothing can be triggered anymore. */
  public void terminate() {
    tokens.clear();
    running.clear();
    waiting.clear();
    terminated = true;
  }
  
  /** @return whether the instance carries no token at all anymore */
  public boolean isFinished() {
    return terminated || (tokens.isEmpty() && running.isEmpty() && waiting.isEmpty());
  }
  
  /** @return everything that the environment may signal next */
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
                .collect(Collectors.joining(", ")) + (terminated ? "; TERMINATED" : "") + "]";
  }
  
}
