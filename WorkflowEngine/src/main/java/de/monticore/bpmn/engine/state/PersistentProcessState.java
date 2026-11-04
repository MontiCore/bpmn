/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The durable form of a {@link de.monticore.bpmn.interpreter.BPMNState}: the content of the
 * "current state" column of the process instance table.
 *
 * <p>It only contains names, never references into an AST, so it survives a restart of the engine
 * and can be written to a database or to JSON.
 */
public class PersistentProcessState {
  
  /** Keys of the sequence flows that carry a token, cf. {@link ProcessStateCodec#flowKey}. */
  protected List<String> tokens = new ArrayList<>();
  
  /** Names of the activities that have been started and are not completed yet. */
  protected List<String> running = new ArrayList<>();
  
  /** Names of the catching events that wait for their trigger. */
  protected List<String> waiting = new ArrayList<>();
  
  /** Completed iterations of the looping activities. */
  protected Map<String, Integer> iterations = new LinkedHashMap<>();
  
  /** Names of the events that were thrown, in the order they occurred. */
  protected List<String> thrown = new ArrayList<>();
  
  protected boolean terminated = false;
  
  /** Values of the data objects by name. */
  protected Map<String, PersistentValue> variables = new LinkedHashMap<>();
  
  public List<String> getTokens() { return tokens; }
  
  public void setTokens(final List<String> tokens) { this.tokens = tokens; }
  
  public List<String> getRunning() { return running; }
  
  public void setRunning(final List<String> running) { this.running = running; }
  
  public List<String> getWaiting() { return waiting; }
  
  public void setWaiting(final List<String> waiting) { this.waiting = waiting; }
  
  public Map<String, Integer> getIterations() { return iterations; }
  
  public void setIterations(final Map<String, Integer> iterations) { this.iterations = iterations; }
  
  public List<String> getThrown() { return thrown; }
  
  public void setThrown(final List<String> thrown) { this.thrown = thrown; }
  
  public boolean isTerminated() { return terminated; }
  
  public void setTerminated(final boolean terminated) { this.terminated = terminated; }
  
  public Map<String, PersistentValue> getVariables() { return variables; }
  
  public void setVariables(final Map<String, PersistentValue> variables) {
    this.variables = variables;
  }
  
  /** @return whether the instance carries no token at all anymore */
  public boolean isFinished() {
    return terminated || (tokens.isEmpty() && running.isEmpty() && waiting.isEmpty());
  }
  
  @Override
  public String toString() {
    return "PersistentProcessState[tokens=" + tokens + ", running=" + running + ", waiting="
        + waiting + (terminated ? ", TERMINATED" : "") + "]";
  }
  
}
