/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.state;

import de.monticore.bpmn.interpreter.BPMNInterpreter;
import de.monticore.bpmn.interpreter.BPMNState;
import de.monticore.bpmn.workflow._ast.ASTFlowElement;
import de.monticore.bpmn.workflow._ast.ASTWFActivity;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.SequenceFlow;
import de.monticore.symbols.basicsymbols._symboltable.VariableSymbol;
import de.monticore.values.MCValue;
import de.se_rwth.commons.logging.Log;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Translates between the runtime state of the interpreter and its durable form.
 *
 * <p>This is what makes the state durable: the interpreter works on AST objects, which only exist
 * in the memory of one JVM, while a stored instance must survive a restart. The codec therefore
 * maps every AST object to a stable name of the model and back. Because the names come from the
 * model, a stored state stays readable in the database and can be inspected without the engine.
 *
 * <p>One codec belongs to one interpreter, i.e., to one parsed process definition.
 */
public class ProcessStateCodec {
  
  protected final BPMNInterpreter interpreter;
  
  protected final Map<String, SequenceFlow> flowsByKey = new LinkedHashMap<>();
  
  protected final Map<String, ASTWFActivity> activitiesByName = new LinkedHashMap<>();
  
  protected final Map<String, ASTWFEvent> eventsByName = new LinkedHashMap<>();
  
  protected final Map<String, VariableSymbol> variablesByName = new LinkedHashMap<>();
  
  public ProcessStateCodec(final BPMNInterpreter interpreter) {
    this.interpreter = interpreter;
    index();
  }
  
  protected void index() {
    for (ASTFlowElement node : interpreter.getNodes()) {
      if (node instanceof ASTWFActivity activity) {
        activitiesByName.put(activity.getName(), activity);
      }
      else if (node instanceof ASTWFEvent event) {
        eventsByName.put(event.getName(), event);
      }
      for (SequenceFlow outgoing : node.getOutgoingsList()) {
        flowsByKey.put(flowKey(outgoing), outgoing);
      }
    }
    for (VariableSymbol variable : interpreter.getVariableSymbols()) {
      variablesByName.put(variable.getName(), variable);
    }
  }
  
  /**
   * The stable identity of a sequence flow. A flow has no name in the model, so it is identified by
   * its end points; the ordinal disambiguates several flows between the same two elements.
   *
   * @param flow the flow to identify
   * @return a key such as {@code Decision->Approve}
   */
  public static String flowKey(final SequenceFlow flow) {
    final String base = flow.getSourceName() + "->" + flow.getTargetName();
    int ordinal = 0;
    for (SequenceFlow sibling : flow.getSource().getOutgoingsList()) {
      if (sibling == flow) {
        break;
      }
      if (sibling.getTarget() == flow.getTarget()) {
        ordinal++;
      }
    }
    return ordinal == 0 ? base : base + "#" + ordinal;
  }
  
  /* ======================================================================
   * Runtime state to durable state
   * ====================================================================== */
  
  public PersistentProcessState capture(final BPMNState state) {
    final PersistentProcessState persisted = new PersistentProcessState();
    state.getTokens().forEach(flow -> persisted.getTokens().add(flowKey(flow)));
    state.getRunning().forEach(activity -> persisted.getRunning().add(activity.getName()));
    state.getWaiting().forEach(event -> persisted.getWaiting().add(event.getName()));
    state.getThrown().forEach(event -> persisted.getThrown().add(event.getName()));
    for (ASTWFActivity activity : state.getRunning()) {
      final int iterations = state.getIterations(activity);
      if (iterations > 0) {
        persisted.getIterations().put(activity.getName(), iterations);
      }
    }
    persisted.setTerminated(state.isTerminated());
    
    for (Map.Entry<VariableSymbol, MCValue> variable : interpreter.snapshotVariables().entrySet()) {
      persisted.getVariables().put(variable.getKey().getName(), PersistentValue.of(variable
          .getValue()));
    }
    return persisted;
  }
  
  /* ======================================================================
   * Durable state to runtime state
   * ====================================================================== */
  
  /**
   * Rebuilds the runtime state and writes the stored variable values into the interpreter's frame.
   *
   * @param persisted the stored state
   * @return the state the interpreter can continue with
   */
  public BPMNState restore(final PersistentProcessState persisted) {
    final BPMNState state = new BPMNState();
    for (String token : persisted.getTokens()) {
      resolveFlow(token).ifPresent(state::addToken);
    }
    for (String name : persisted.getRunning()) {
      resolveActivity(name).ifPresent(state::addRunning);
    }
    for (String name : persisted.getWaiting()) {
      resolveEvent(name).ifPresent(state::addWaiting);
    }
    for (String name : persisted.getThrown()) {
      resolveEvent(name).ifPresent(state::addThrown);
    }
    for (Map.Entry<String, Integer> iteration : persisted.getIterations().entrySet()) {
      resolveActivity(iteration.getKey()).ifPresent(activity -> {
        for (int i = 0; i < iteration.getValue(); i++) {
          state.incIterations(activity);
        }
      });
    }
    if (persisted.isTerminated()) {
      state.terminate();
    }
    restoreVariables(persisted);
    return state;
  }
  
  protected void restoreVariables(final PersistentProcessState persisted) {
    for (Map.Entry<String, PersistentValue> entry : persisted.getVariables().entrySet()) {
      final VariableSymbol symbol = variablesByName.get(entry.getKey());
      if (symbol == null) {
        Log.warn("0xWFE0102 The stored state contains the variable '" + entry.getKey()
            + "', which the process does not declare (anymore). It is ignored.");
        continue;
      }
      entry.getValue().toMCValue().ifPresent(value -> interpreter.setVariable(symbol, value));
    }
  }
  
  /* ======================================================================
   * Name resolution
   * ====================================================================== */
  
  public Optional<SequenceFlow> resolveFlow(final String key) {
    return warnIfAbsent(Optional.ofNullable(flowsByKey.get(key)), "sequence flow", key);
  }
  
  public Optional<ASTWFActivity> resolveActivity(final String name) {
    return warnIfAbsent(Optional.ofNullable(activitiesByName.get(name)), "activity", name);
  }
  
  public Optional<ASTWFEvent> resolveEvent(final String name) {
    return warnIfAbsent(Optional.ofNullable(eventsByName.get(name)), "event", name);
  }
  
  public Optional<VariableSymbol> resolveVariable(final String name) {
    return warnIfAbsent(Optional.ofNullable(variablesByName.get(name)), "data object", name);
  }
  
  /**
   * Reports elements of a stored state that the process does not contain. This happens when an
   * instance is continued with a definition it was not started with.
   */
  protected <T> Optional<T> warnIfAbsent(final Optional<T> resolved, final String kind,
      final String name) {
    if (resolved.isEmpty()) {
      Log.warn("0xWFE0103 The stored state refers to the " + kind + " '" + name
          + "', which the process '" + interpreter.getProcess().getName()
          + "' does not contain. It is ignored.");
    }
    return resolved;
  }
  
}
