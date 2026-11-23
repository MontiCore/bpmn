/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.state;

import de.monticore.bpmn.interpreter.BPMNInterpreter;
import de.monticore.bpmn.interpreter.BPMNState;
import de.monticore.bpmn.workflow._ast.ASTFlowElement;
import de.monticore.bpmn.workflow._ast.ASTWFActivity;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.SequenceFlow;
import de.monticore.interpreter.util.NativeStorageSelector;
import de.monticore.symbols.basicsymbols._symboltable.VariableSymbol;
import de.monticore.types.check.SymTypeExpression;
import de.monticore.types3.SymTypeRelations;
import de.monticore.values.MCValue;
import de.monticore.values.MCValueBoolean;
import de.monticore.values.MCValueDouble;
import de.monticore.values.MCValueInt;
import de.monticore.values.MCValueObject;
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
 * <p>An element of the process is stored under its name, an element inside a sub-process under its
 * qualified name, e.g., {@code Shipping.Pack}. When resolving, the plain name is accepted as well
 * as long as it is unique in the process.
 *
 * <p>One codec belongs to one interpreter, i.e., to one parsed process definition.
 */
public class ProcessStateCodec {
  
  protected final BPMNInterpreter interpreter;
  
  /** Looks up the objects that the application keeps itself; absent if there are none. */
  protected final DataObjectResolver resolver;
  
  protected final Map<String, SequenceFlow> flowsByKey = new LinkedHashMap<>();
  
  protected final Map<String, ASTWFActivity> activitiesByName = new LinkedHashMap<>();
  
  protected final Map<String, ASTWFEvent> eventsByName = new LinkedHashMap<>();
  
  protected final Map<String, VariableSymbol> variablesByName = new LinkedHashMap<>();
  
  public ProcessStateCodec(final BPMNInterpreter interpreter) {
    this(interpreter, null);
  }
  
  public ProcessStateCodec(final BPMNInterpreter interpreter, final DataObjectResolver resolver) {
    this.interpreter = interpreter;
    this.resolver = resolver;
    index();
  }
  
  protected void index() {
    // an element inside a sub-process is also found by its plain name if no other element has it
    final Map<String, Integer> plainNames = new LinkedHashMap<>();
    for (ASTFlowElement node : interpreter.getNodes()) {
      if (node instanceof ASTWFActivity || node instanceof ASTWFEvent) {
        plainNames.merge(node.getName(), 1, Integer::sum);
      }
    }
    for (ASTFlowElement node : interpreter.getNodes()) {
      final boolean unique = plainNames.getOrDefault(node.getName(), 0) == 1;
      if (node instanceof ASTWFActivity activity) {
        activitiesByName.put(nameOf(activity), activity);
        if (unique) {
          activitiesByName.putIfAbsent(activity.getName(), activity);
        }
      }
      else if (node instanceof ASTWFEvent event) {
        eventsByName.put(nameOf(event), event);
        if (unique) {
          eventsByName.putIfAbsent(event.getName(), event);
        }
      }
      for (SequenceFlow outgoing : node.getOutgoingsList()) {
        flowsByKey.put(flowKeyOf(outgoing), outgoing);
      }
    }
    for (VariableSymbol variable : interpreter.getVariableSymbols()) {
      variablesByName.put(variable.getName(), variable);
    }
  }
  
  /** @return the name under which an element is stored */
  public String nameOf(final ASTFlowElement element) {
    return interpreter.getQualifiedName(element);
  }
  
  /**
   * The stable identity of a sequence flow of this process. A flow has no name in the model, so it
   * is identified by its end points; the ordinal disambiguates several flows between the same two
   * elements.
   *
   * @param flow the flow to identify
   * @return a key such as {@code Decision->Approve}
   */
  public String flowKeyOf(final SequenceFlow flow) {
    return withOrdinal(flow, nameOf(flow.getSource()) + "->" + nameOf(flow.getTarget()));
  }
  
  /**
   * The identity of a sequence flow by the plain names of its end points, cf.
   * {@link #flowKeyOf(SequenceFlow)}.
   *
   * @param flow the flow to identify
   * @return a key such as {@code Decision->Approve}
   */
  public static String flowKey(final SequenceFlow flow) {
    return withOrdinal(flow, flow.getSourceName() + "->" + flow.getTargetName());
  }
  
  protected static String withOrdinal(final SequenceFlow flow, final String base) {
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
    state.getTokens().forEach(flow -> persisted.getTokens().add(flowKeyOf(flow)));
    state.getRunning().forEach(activity -> persisted.getRunning().add(nameOf(activity)));
    state.getWaiting().forEach(event -> persisted.getWaiting().add(nameOf(event)));
    state.getThrown().forEach(event -> persisted.getThrown().add(nameOf(event)));
    for (ASTWFActivity activity : state.getRunning()) {
      final int iterations = state.getIterations(activity);
      if (iterations > 0) {
        persisted.getIterations().put(nameOf(activity), iterations);
      }
    }
    persisted.setTerminated(state.isTerminated());
    persisted.setFailure(state.getFailure().orElse(null));
    
    for (Map.Entry<VariableSymbol, MCValue> variable : interpreter.snapshotVariables().entrySet()) {
      final String name = variable.getKey().getName();
      toPersistentValue(name, toJavaObject(variable.getValue())).ifPresent(value -> persisted
          .getVariables().put(name, value));
    }
    return persisted;
  }
  
  /** @return the value of the interpreter as a plain Java object */
  public static Object toJavaObject(final MCValue value) {
    if (value == null || value.isVoid()) {
      return null;
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isInt()) {
      return value.asInt();
    }
    if (value.isDouble()) {
      return value.asDouble();
    }
    return value.asNativeObject();
  }
  
  /**
   * @return the durable form of a value. An object the application keeps is stored as a reference;
   * an object that is neither kept by the application nor plain data cannot be stored, which
   * is reported as an error.
   */
  protected Optional<PersistentValue> toPersistentValue(final String name, final Object value) {
    if (value != null && resolver != null) {
      final Optional<String> reference = resolver.toReference(name, value);
      if (reference.isPresent()) {
        return Optional.of(PersistentValue.ofReference(reference.get()));
      }
    }
    final Optional<PersistentValue> persistent = PersistentValue.of(value);
    if (persistent.isEmpty()) {
      Log.error("0xWFE0101 The value of the data object '" + name + "' is an object of the class '"
          + value.getClass().getName() + "' and cannot be stored. Pass it as a map of its"
          + " attributes or register a DataObjectResolver that provides a reference for it.");
    }
    return persistent;
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
    final BPMNState state = restoreControlFlow(persisted);
    restoreVariables(persisted);
    return state;
  }
  
  /** Rebuilds the runtime state without the values of the data objects. */
  public BPMNState restoreControlFlow(final PersistentProcessState persisted) {
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
    if (persisted.getFailure() != null) {
      state.fail(persisted.getFailure());
    }
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
      final PersistentValue value = entry.getValue();
      if (value.getKind() == PersistentValue.Kind.NULL) {
        continue;
      }
      if (value.getKind() == PersistentValue.Kind.REFERENCE) {
        if (resolver == null) {
          Log.error("0xWFE0104 The data object '" + entry.getKey() + "' refers to the object '"
              + value.getValue() + "' of the application, but no DataObjectResolver is"
              + " registered that could look it up.");
          continue;
        }
        setVariable(entry.getKey(), resolver.resolve(entry.getKey(), value.getValue()));
        continue;
      }
      setVariable(entry.getKey(), value.toJavaObject());
    }
  }
  
  /* ======================================================================
   * Values of data objects
   * ====================================================================== */
  
  /**
   * Gives a data object a value.
   *
   * @param name the name of the data object
   * @param value a plain Java value that fits the type of the data object
   * @throws IllegalArgumentException if the process has no such data object or the value does not
   * fit its type
   */
  public void setVariable(final String name, final Object value) {
    final VariableSymbol symbol = variablesByName.get(name);
    if (symbol == null) {
      throw new IllegalArgumentException("The process '" + interpreter.getProcess().getName()
          + "' has no data object '" + name + "'; it has " + variablesByName.keySet() + ".");
    }
    interpreter.setVariable(symbol, toValue(symbol, value));
  }
  
  /** Converts a plain Java value into the form in which the interpreter stores the variable. */
  protected MCValue toValue(final VariableSymbol variable, final Object value) {
    final SymTypeExpression type = SymTypeRelations.normalize(variable.getType());
    if (NativeStorageSelector.isStoredAsBoolean(type)) {
      if (value instanceof Boolean bool) {
        return new MCValueBoolean(bool);
      }
    }
    else if (NativeStorageSelector.isStoredAsInt(type)) {
      if (value instanceof Character character) {
        return new MCValueInt(character);
      }
      if (value instanceof Number number && number.doubleValue() == Math.rint(number
          .doubleValue())) {
        return new MCValueInt(number.intValue());
      }
    }
    else if (NativeStorageSelector.isStoredAsDouble(type)) {
      if (value instanceof Number number) {
        return new MCValueDouble(number.doubleValue());
      }
    }
    else if (value != null) {
      return new MCValueObject(value);
    }
    throw new IllegalArgumentException("The value '" + value + "' does not fit the data object '"
        + variable.getName() + "' of the type '" + type.print() + "'.");
  }
  
  /* ======================================================================
   * Name resolution
   * ====================================================================== */
  
  public Optional<SequenceFlow> resolveFlow(final String key) {
    return warnIfAbsent(Optional.ofNullable(flowsByKey.get(key)), "sequence flow", key);
  }
  
  public Optional<ASTWFActivity> resolveActivity(final String name) {
    return warnIfAbsent(findActivity(name), "activity", name);
  }
  
  public Optional<ASTWFEvent> resolveEvent(final String name) {
    return warnIfAbsent(findEvent(name), "event", name);
  }
  
  public Optional<VariableSymbol> resolveVariable(final String name) {
    return warnIfAbsent(Optional.ofNullable(variablesByName.get(name)), "data object", name);
  }
  
  /** @return the activity of that name, without a warning if there is none */
  public Optional<ASTWFActivity> findActivity(final String name) {
    return Optional.ofNullable(activitiesByName.get(name));
  }
  
  /** @return the event of that name, without a warning if there is none */
  public Optional<ASTWFEvent> findEvent(final String name) {
    return Optional.ofNullable(eventsByName.get(name));
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
