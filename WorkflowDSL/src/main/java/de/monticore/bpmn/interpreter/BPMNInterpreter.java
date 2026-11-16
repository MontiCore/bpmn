/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import static com.google.common.base.Preconditions.checkNotNull;

import de.monticore.bpmn.collectors.WorkflowCollectors;
import de.monticore.bpmn.types3.WorkflowTypeCheck3;
import de.monticore.bpmn.workflow.WorkflowMill;
import de.monticore.bpmn.workflow._ast.ASTConstantsWorkflow;
import de.monticore.bpmn.workflow._ast.ASTFlowCondition;
import de.monticore.bpmn.workflow._ast.ASTFlowElement;
import de.monticore.bpmn.workflow._ast.ASTGatewayType;
import de.monticore.bpmn.workflow._ast.ASTWFActivity;
import de.monticore.bpmn.workflow._ast.ASTWFCallActivity;
import de.monticore.bpmn.workflow._ast.ASTWFDataObject;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerCancel;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerCompensate;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerConditional;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerMultiple;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerNotification;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerTerminate;
import de.monticore.bpmn.workflow._ast.ASTWFGateway;
import de.monticore.bpmn.workflow._ast.ASTWFInlineGateway;
import de.monticore.bpmn.workflow._ast.ASTWFLane;
import de.monticore.bpmn.workflow._ast.ASTWFLoopCardinality;
import de.monticore.bpmn.workflow._ast.ASTWFMILoop;
import de.monticore.bpmn.workflow._ast.ASTWFProcess;
import de.monticore.bpmn.workflow._ast.ASTWFStandardLoop;
import de.monticore.bpmn.workflow._ast.ASTWFSubProcess;
import de.monticore.bpmn.workflow._ast.ASTWFTask;
import de.monticore.bpmn.workflow._ast.SequenceFlow;
import de.monticore.bpmn.workflow._visitor.WorkflowTraverser;
import de.monticore.expressions.assignmentexpressions._ast.ASTAssignmentExpression;
import de.monticore.expressions.assignmentexpressions._visitor.AssignmentExpressionsVisitor2;
import de.monticore.expressions.expressionsbasis._ast.ASTExpression;
import de.monticore.expressions.expressionsbasis._ast.ASTNameExpression;
import de.monticore.expressions.expressionsbasis._visitor.ExpressionsBasisVisitor2;
import de.monticore.interpreter.calculations.MICalculationVoid;
import de.monticore.interpreter.util.InterpreterDataForBasicSymbols;
import de.monticore.symbols.basicsymbols._symboltable.VariableSymbol;
import de.monticore.symbols.basicsymbols.interpreter.AbstractInterpreterForBasicSymbols;
import de.monticore.values.MCValue;
import de.monticore.values.MCValueError;
import de.monticore.visitor.ITraverser;
import de.se_rwth.commons.logging.Log;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Interprets a {@link ASTWFProcess}, i.e., executes the BPMN token game of one process instance.
 *
 * <p>The interpreter is reactive: it executes everything that the process can do on its own
 * (gateways, throwing events, sequence flows, sub-processes) and stops at every element whose
 * completion is decided outside of the model, i.e., at tasks and at catching events. The
 * environment drives the instance forward by signalling these decisions:
 *
 * <pre>
 * BPMNInterpreter interpreter = BPMNInterpreterBuilder.build(process);
 * BPMNState state = interpreter.start(); // token(s) behind the start event(s)
 * state = interpreter.interpret(state, taskA); // taskA has been completed
 * state = interpreter.interpret(state, message); // a message event has been caught
 * boolean done = state.isFinished();
 * </pre>
 *
 * <p>Expressions (flow conditions, loop conditions, loop cardinalities) are evaluated with the
 * MontiCore expression interpreter: the AST of the expression is turned into an
 * {@link de.monticore.interpreter.calculations.MICalculation} once (and cached) and is then
 * executed on this interpreter's frame. The values of the {@code data} objects therefore live in
 * the frame and are read and written with {@link #addVariable}, {@link #setVariable} and
 * {@link #getVariable} of the super class. A data object whose type is defined by a model, e.g.,
 * by a class diagram, holds any Java object; its attributes are accessed by name, cf.
 * {@link ModelObjectAccess}.
 *
 * <p>The behavior of a flow element (e.g., what a service task actually does) is not part of the
 * Workflow language. It is provided by the embedding application via
 * {@link #setBehavior(ASTFlowElement, MICalculationVoid)}.
 *
 * <p>Embedded sub-processes are executed: starting one instantiates its inner flow, and it
 * completes on its own once nothing is left inside. Call activities and sub-processes without a
 * body are opaque, the environment completes them like a task. {@link #getLimitations()} lists the
 * constructs of the process that are executed in a simplified way.
 */
public class BPMNInterpreter extends AbstractInterpreterForBasicSymbols {
  
  /** Guards against models that are not sound, e.g., a livelock of gateways. */
  protected static final int MAX_PROPAGATION_STEPS = 10000;
  
  protected final ASTWFProcess process;
  
  /** All flow nodes of the process including those of its sub-processes, in model order. */
  protected final List<ASTFlowElement> nodes = new ArrayList<>();
  
  /** Maps a node inside a sub-process to that sub-process; nodes of the process are absent. */
  protected final Map<ASTFlowElement, ASTWFSubProcess> scopes = new IdentityHashMap<>();
  
  /** Maps a boundary event to the activity it is attached to. */
  protected final Map<ASTWFEvent, ASTWFActivity> boundaryOwners = new LinkedHashMap<>();
  
  /** The names that identify the nodes within the process, e.g., {@code Shipping.Pack}. */
  protected final Map<ASTFlowElement, String> qualifiedNames = new IdentityHashMap<>();
  
  /** The behavior that is executed when a flow element is executed. */
  protected final Map<ASTFlowElement, MICalculationVoid> behaviors = new LinkedHashMap<>();
  
  /** The data objects of the process, i.e., the variables that expressions may refer to. */
  protected final List<VariableSymbol> variables = new ArrayList<>();
  
  /** The data objects that have a value. */
  protected final Set<VariableSymbol> assigned = new LinkedHashSet<>();
  
  /** The data objects an expression reads, cf. {@link #readVariables(ASTExpression)}. */
  protected final Map<ASTExpression, List<VariableSymbol>> readVariables = new IdentityHashMap<>();
  
  public BPMNInterpreter(final ASTWFProcess process, final ITraverser traverser,
      final InterpreterDataForBasicSymbols iData) {
    super(traverser, iData);
    this.process = checkNotNull(process);
    
    collectFlowNodes(process.getFlowElementList(), null);
    collectInlineGateways();
    declareDataObjects();
  }
  
  public ASTWFProcess getProcess() { return process; }
  
  /** @return all flow nodes of the process, in the order the interpreter considers them */
  public List<ASTFlowElement> getNodes() {
    return nodes;
  }
  
  /** @return the data objects of the process that are available as interpreter variables */
  public List<VariableSymbol> getVariableSymbols() {
    return variables;
  }
  
  /** @return the boundary events of the process and the activity each one is attached to */
  public Map<ASTWFEvent, ASTWFActivity> getBoundaryOwners() {
    return boundaryOwners;
  }
  
  /** @return the sub-process that directly contains the node, empty for nodes of the process */
  public Optional<ASTWFSubProcess> getScope(final ASTFlowElement node) {
    return Optional.ofNullable(scopes.get(node));
  }
  
  /**
   * The name that identifies a node within the process. A node of the process itself is identified
   * by its name, a node inside a sub-process is prefixed with the names of the sub-processes around
   * it, as it is in the model: {@code Shipping.Pack}.
   *
   * @param node a node of this process
   * @return its qualified name
   */
  public String getQualifiedName(final ASTFlowElement node) {
    return qualifiedNames.getOrDefault(node, node.getName());
  }
  
  /* ======================================================================
   * Setup
   * ====================================================================== */
  
  /**
   * Collects the flow nodes of one level and, recursively, of the sub-processes on it. Lanes only
   * group nodes, they do not form a level of their own.
   *
   * @param elements the elements of the level
   * @param scope the sub-process that forms the level, {@code null} for the process itself
   */
  protected void collectFlowNodes(final List<ASTFlowElement> elements,
      final ASTWFSubProcess scope) {
    for (ASTFlowElement element : elements) {
      if (element instanceof ASTWFLane lane) {
        collectFlowNodes(lane.getFlowElementList(), scope);
      }
      else if (element instanceof ASTWFActivity activity) {
        final ASTWFSubProcess inner = activity instanceof ASTWFSubProcess
            ? (ASTWFSubProcess) activity : null;
        addNode(activity, scope, scope);
        for (ASTWFEvent boundaryEvent : boundaryEventsOf(activity)) {
          // the boundary event of a sub-process is declared in it but belongs to the level around
          addNode(boundaryEvent, scope, inner != null ? inner : scope);
          boundaryOwners.put(boundaryEvent, activity);
        }
        if (inner != null) {
          collectFlowNodes(inner.getFlowElementList(), inner);
        }
      }
      else if (element instanceof ASTWFEvent || element instanceof ASTWFGateway) {
        addNode(element, scope, scope);
      }
    }
  }
  
  /** Inline gateways are no elements of a level, they are only reachable via the flows. */
  protected void collectInlineGateways() {
    for (int i = 0; i < nodes.size(); i++) {
      final ASTFlowElement node = nodes.get(i);
      final List<SequenceFlow> flows = new ArrayList<>(node.getOutgoingsList());
      flows.addAll(node.getIncomingsList());
      for (SequenceFlow flow : flows) {
        for (ASTFlowElement neighbor : List.of(flow.getSource(), flow.getTarget())) {
          if (neighbor instanceof ASTWFInlineGateway && !qualifiedNames.containsKey(neighbor)) {
            addNode(neighbor, scopes.get(node), scopes.get(node));
          }
        }
      }
    }
  }
  
  protected void addNode(final ASTFlowElement node, final ASTWFSubProcess scope,
      final ASTWFSubProcess namespace) {
    nodes.add(node);
    if (scope != null) {
      scopes.put(node, scope);
    }
    qualifiedNames.put(node, namespace == null ? node.getName() : getQualifiedName(namespace) + "."
        + node.getName());
  }
  
  protected List<ASTWFEvent> boundaryEventsOf(final ASTWFActivity activity) {
    if (activity instanceof ASTWFTask task) {
      return task.getBoundaryEventList();
    }
    if (activity instanceof ASTWFSubProcess subProcess) {
      return subProcess.getBoundaryEventList();
    }
    if (activity instanceof ASTWFCallActivity callActivity) {
      return callActivity.getBoundaryEventList();
    }
    return List.of();
  }
  
  /** Declares one interpreter variable per data object of the process. */
  protected void declareDataObjects() {
    for (ASTWFDataObject dataObject : WorkflowCollectors.toDataObjectsLocal(process)) {
      if (!dataObject.isPresentSymbol()) {
        continue;
      }
      final VariableSymbol symbol = dataObject.getSymbol();
      if (symbol.getType() == null) {
        Log.warn("0xWFM9200 No type for data object '" + symbol.getName()
            + "'; it is not available in the interpreter. Has the symbol table been completed?");
        continue;
      }
      addVariable(symbol);
      variables.add(symbol);
    }
  }
  
  /* ======================================================================
   * Data
   * ====================================================================== */
  
  @Override
  public void setVariable(final VariableSymbol varSym, final MCValue value) {
    super.setVariable(varSym, value);
    assigned.add(varSym);
  }
  
  /** @return whether the data object has a value */
  public boolean isAssigned(final VariableSymbol variable) {
    return assigned.contains(variable);
  }
  
  /** @return the current values of all data objects of the process that have a value */
  public Map<VariableSymbol, MCValue> snapshotVariables() {
    final Map<VariableSymbol, MCValue> values = new LinkedHashMap<>();
    for (VariableSymbol symbol : variables) {
      if (isAssigned(symbol)) {
        values.put(symbol, getVariable(symbol));
      }
    }
    return values;
  }
  
  /* ======================================================================
   * Behavior
   * ====================================================================== */
  
  /**
   * Attaches behavior to a flow element. It is executed when the element is executed, i.e., when an
   * activity is started and when an event is thrown.
   *
   * @param element the element to attach the behavior to
   * @param behavior the behavior, e.g., {@code frame -> setVariable(x, new MCValueInt(1))}
   */
  public void setBehavior(final ASTFlowElement element, final MICalculationVoid behavior) {
    behaviors.put(checkNotNull(element), checkNotNull(behavior));
  }
  
  /** @return the behavior of the element, {@link MICalculationVoid#NOOP_CALC} if there is none */
  protected MICalculationVoid getBehavior(final ASTFlowElement element) {
    return behaviors.getOrDefault(element, MICalculationVoid.NOOP_CALC);
  }
  
  protected void runBehavior(final ASTFlowElement element) {
    getBehavior(element).calculate(getTopMostFrame());
  }
  
  /* ======================================================================
   * Expressions
   * ====================================================================== */
  
  /**
   * Evaluates an expression of the model against the current variable values.
   *
   * @param expression the expression
   * @return its value; an error value if the expression cannot be evaluated, e.g., because it reads
   * a data object that has no value
   */
  public MCValue evaluate(final ASTExpression expression) {
    for (VariableSymbol variable : readVariables(expression)) {
      if (!isAssigned(variable)) {
        return new MCValueError("The data object '" + variable.getName() + "' has no value.");
      }
    }
    try {
      // assure the types are known, the interpreter picks its calculations based on them
      WorkflowTypeCheck3.typeOf(expression);
      final MCValue value = interpretNode(expression);
      return value == null ? new MCValueError("The expression has no value.") : value;
    }
    catch (RuntimeException | LinkageError e) {
      // raised while the calculation is built, e.g., for a member that cannot be accessed
      return new MCValueError(e);
    }
  }
  
  /**
   * Finds the data objects an expression needs a value of. A data object the expression assigns to
   * gets its value from the expression and counts as assigned from now on.
   */
  protected List<VariableSymbol> readVariables(final ASTExpression expression) {
    if (!readVariables.containsKey(expression)) {
      final Set<String> names = new LinkedHashSet<>();
      final Set<String> targets = new LinkedHashSet<>();
      final WorkflowTraverser traverser = WorkflowMill.traverser();
      traverser.add4ExpressionsBasis(new ExpressionsBasisVisitor2() {
        
        @Override
        public void visit(final ASTNameExpression node) {
          names.add(node.getName());
        }
        
      });
      traverser.add4AssignmentExpressions(new AssignmentExpressionsVisitor2() {
        
        @Override
        public void visit(final ASTAssignmentExpression node) {
          if (node.getLeft() instanceof ASTNameExpression target) {
            targets.add(target.getName());
          }
        }
        
      });
      expression.accept(traverser);
      
      final List<VariableSymbol> read = new ArrayList<>();
      for (VariableSymbol variable : variables) {
        if (targets.contains(variable.getName())) {
          assigned.add(variable);
        }
        else if (names.contains(variable.getName())) {
          read.add(variable);
        }
      }
      readVariables.put(expression, read);
    }
    return readVariables.get(expression);
  }
  
  /** Reports the data objects that an expression reads although they have no value. */
  protected boolean isReadable(final ASTExpression expression) {
    final List<String> missing = readVariables(expression).stream().filter(variable -> !isAssigned(
        variable)).map(VariableSymbol::getName).toList();
    if (!missing.isEmpty()) {
      Log.error("0xWFM9214 An expression reads the data object(s) " + missing
          + ", which have no value.", expression.get_SourcePositionStart());
    }
    return missing.isEmpty();
  }
  
  protected boolean evaluateAsBoolean(final ASTExpression expression) {
    if (!isReadable(expression)) {
      return false;
    }
    final MCValue value = evaluate(expression);
    if (value.isError()) {
      Log.error("0xWFM9201 Evaluation of an expression failed: " + value.asError(), expression
          .get_SourcePositionStart());
      return false;
    }
    if (!value.isBoolean()) {
      Log.error("0xWFM9202 Expected a boolean value but got '" + value.printType() + "'.",
          expression.get_SourcePositionStart());
      return false;
    }
    return value.asBoolean();
  }
  
  protected int evaluateAsInt(final ASTExpression expression) {
    if (!isReadable(expression)) {
      return 0;
    }
    final MCValue value = evaluate(expression);
    if (value.isError()) {
      Log.error("0xWFM9201 Evaluation of an expression failed: " + value.asError(), expression
          .get_SourcePositionStart());
      return 0;
    }
    if (!value.isInt()) {
      Log.error("0xWFM9209 Expected an integer value but got '" + value.printType() + "'.",
          expression.get_SourcePositionStart());
      return 0;
    }
    return value.asInt();
  }
  
  /** @return whether all conditions of the flow hold; a flow without conditions is always taken */
  protected boolean isFlowEnabled(final SequenceFlow flow) {
    for (ASTFlowCondition condition : flow.getConditions()) {
      if (condition.isPresentExpression() && !evaluateAsBoolean(condition.getExpression())) {
        return false;
      }
    }
    return true;
  }
  
  /* ======================================================================
   * Stepping API
   * ====================================================================== */
  
  /**
   * Creates the initial state of a process instance. Start events without a trigger fire
   * immediately. If all start events have a trigger, they wait for
   * {@link #interpret(BPMNState, ASTWFEvent)} and the first one that occurs starts the flow. A
   * process without start events starts every flow node that has no incoming flow.
   *
   * @return the state after the process has been instantiated
   */
  public BPMNState start() {
    final BPMNState state = new BPMNState();
    instantiate(null, state);
    propagate(state);
    return state;
  }
  
  /**
   * Signals that a running activity has been completed.
   *
   * @param state the current state
   * @param finished the activity that has been completed
   * @return the resulting state
   */
  public BPMNState interpret(final BPMNState state, final ASTWFActivity finished) {
    final BPMNState next = state.copy();
    if (!checkStepAllowed(next, finished)) {
      return next;
    }
    if (!next.isRunning(finished)) {
      Log.error("0xWFM9204 Activity '" + finished.getName() + "' is not running.");
      return next;
    }
    if (isExecutedInside(finished)) {
      Log.error("0xWFM9211 Sub-process '" + finished.getName()
          + "' completes on its own as soon as its inner flow has ended, it cannot be completed"
          + " from outside.");
      return next;
    }
    cancelEventBasedAlternatives(finished, next);
    completeActivity(finished, next);
    propagate(next);
    return next;
  }
  
  /**
   * Signals that an event has been triggered, i.e., that a message arrived, a timer elapsed, an
   * error occurred etc. This is also used to instantiate a process via a start event with a
   * trigger.
   *
   * @param state the current state
   * @param event the event that has been triggered
   * @return the resulting state
   */
  public BPMNState interpret(final BPMNState state, final ASTWFEvent event) {
    final BPMNState next = state.copy();
    if (!checkStepAllowed(next, event)) {
      return next;
    }
    if (boundaryOwners.containsKey(event)) {
      if (!triggerBoundaryEvent(event, next)) {
        return next;
      }
    }
    else if (next.isWaiting(event)) {
      next.removeWaiting(event);
      cancelEventBasedAlternatives(event, next);
      cancelAlternativeStartEvents(event, next);
      throwEvent(event, next);
    }
    else {
      Log.error("0xWFM9205 Event '" + event.getName() + "' is not enabled in state " + next + ".");
      return next;
    }
    propagate(next);
    return next;
  }
  
  /**
   * Completes every currently running activity. Convenience for tests and for simulating processes
   * whose activities have no externally observable duration.
   *
   * @param state the current state
   * @return the state in which no activity is running anymore
   */
  public BPMNState completeAllRunning(final BPMNState state) {
    BPMNState next = state;
    Optional<ASTWFActivity> open = firstOpenActivity(next);
    while (open.isPresent() && !next.isFinished()) {
      final int findings = Log.getFindings().size();
      next = interpret(next, open.get());
      if (Log.getFindings().size() > findings) {
        break; // the step failed, do not try again forever
      }
      open = firstOpenActivity(next);
    }
    return next;
  }
  
  protected Optional<ASTWFActivity> firstOpenActivity(final BPMNState state) {
    return state.getRunning().stream().filter(activity -> !isExecutedInside(activity)).findFirst();
  }
  
  /**
   * What the environment may signal in a state: the running activities it has to complete, the
   * catching events that wait for their trigger and the boundary events of running activities.
   *
   * @param state the current state
   * @return the activities and events that may be passed to {@code interpret}
   */
  public Set<ASTFlowElement> getEnabledSignals(final BPMNState state) {
    final Set<ASTFlowElement> result = new LinkedHashSet<>();
    state.getRunning().stream().filter(activity -> !isExecutedInside(activity)).forEach(
        result::add);
    result.addAll(state.getWaiting());
    boundaryOwners.forEach((event, owner) -> {
      if (state.isRunning(owner) && !isCompensation(event)) {
        result.add(event);
      }
    });
    return result;
  }
  
  /**
   * @param state the current state
   * @return whether the instance is stuck: it has not ended, but nothing can be signalled anymore,
   * e.g., because a parallel merge waits for a branch that never arrives
   */
  public boolean isDeadlocked(final BPMNState state) {
    return !state.isFinished() && getEnabledSignals(state).isEmpty();
  }
  
  protected boolean checkStepAllowed(final BPMNState state, final ASTFlowElement element) {
    if (state.isTerminated() || state.isFailed()) {
      Log.error("0xWFM9206 The process instance has been terminated, '" + element.getName()
          + "' cannot be signalled anymore.");
      return false;
    }
    return true;
  }
  
  /* ======================================================================
   * Token game
   * ====================================================================== */
  
  /**
   * Starts the flow of one level: of the process or of a sub-process.
   *
   * @param scope the sub-process to start the inner flow of, {@code null} for the process
   * @param state the current state
   */
  protected void instantiate(final ASTWFSubProcess scope, final BPMNState state) {
    final List<ASTFlowElement> level = nodes.stream().filter(node -> scopes.get(node) == scope)
        .toList();
    final List<ASTWFEvent> startEvents = level.stream().filter(
        node -> node instanceof ASTWFEvent event && event.isStart()).map(node -> (ASTWFEvent) node)
        .toList();
    if (startEvents.isEmpty()) {
      level.stream().filter(this::isImplicitStart).forEach(node -> execute(node, state));
      return;
    }
    final List<ASTWFEvent> untriggered = startEvents.stream().filter(event -> !event
        .isPresentTrigger()).toList();
    if (untriggered.isEmpty()) {
      // every start event waits for its trigger; the first one that occurs starts the flow
      startEvents.forEach(event -> arm(event, state));
    }
    else {
      untriggered.forEach(event -> throwEvent(event, state));
    }
  }
  
  /** @return whether a node starts on its own in a level that has no start event */
  protected boolean isImplicitStart(final ASTFlowElement node) {
    return node.isEmptyIncomings() && !boundaryOwners.containsKey(node) && !isCompensationHandler(
        node);
  }
  
  /** Fires every element that the process can execute on its own until none is enabled anymore. */
  protected void propagate(final BPMNState state) {
    final long errors = Log.getErrorCount();
    int steps = 0;
    boolean progress = true;
    // an error leaves the step incomplete; going on would only produce consequential errors
    while (progress && !state.isTerminated() && !state.isFailed() && Log.getErrorCount()
        == errors) {
      progress = false;
      for (ASTFlowElement node : nodes) {
        if (isEnabled(node, state)) {
          fire(node, state);
          progress = true;
          break;
        }
      }
      if (!progress) {
        // nothing moves anymore: a sub-process without anything left inside has ended
        final Optional<ASTWFActivity> ended = state.getRunning().stream().filter(
            activity -> isExecutedInside(activity) && !isScopeActive((ASTWFSubProcess) activity,
                state)).findFirst();
        if (ended.isPresent()) {
          completeActivity(ended.get(), state);
          progress = true;
        }
      }
      if (++steps > MAX_PROPAGATION_STEPS) {
        Log.error("0xWFM9203 The process '" + process.getName() + "' did not stabilize within "
            + MAX_PROPAGATION_STEPS + " steps. Is the model sound?");
        return;
      }
    }
  }
  
  /** @return whether the node may fire without any signal from the environment */
  protected boolean isEnabled(final ASTFlowElement node, final BPMNState state) {
    if (node instanceof ASTWFActivity activity) {
      return !state.isRunning(activity) && hasIncomingToken(node, state);
    }
    if (node instanceof ASTWFEvent event) {
      if (event.isStart() || boundaryOwners.containsKey(event)) {
        return false; // fired by instantiate() resp. by triggering the event
      }
      if (isCatching(event)) {
        return !state.isWaiting(event) && hasIncomingToken(node, state);
      }
      return hasIncomingToken(node, state);
    }
    if (isGateway(node)) {
      return isGatewayEnabled(node, state);
    }
    return false;
  }
  
  protected void fire(final ASTFlowElement node, final BPMNState state) {
    consumeEnablingTokens(node, state);
    execute(node, state);
  }
  
  /** Executes the node; its incoming tokens have already been consumed. */
  protected void execute(final ASTFlowElement node, final BPMNState state) {
    if (node instanceof ASTWFActivity activity) {
      startActivity(activity, state);
    }
    else if (node instanceof ASTWFEvent event) {
      if (isCatching(event) && !event.isStart()) {
        arm(event, state); // waits for its trigger
      }
      else {
        throwEvent(event, state);
      }
    }
    else if (isGateway(node)) {
      executeGateway(node, state);
    }
  }
  
  protected boolean hasIncomingToken(final ASTFlowElement node, final BPMNState state) {
    return node.getIncomingsList().stream().anyMatch(state::hasToken);
  }
  
  protected void consumeEnablingTokens(final ASTFlowElement node, final BPMNState state) {
    final List<SequenceFlow> incoming = node.getIncomingsList();
    if (isGateway(node) && isMerging(node) && !gatewayType(node).isExclusive() && !gatewayType(node)
        .isExclusiveEventBased()) {
      // parallel, inclusive and complex merges synchronize all branches that carry a token
      incoming.stream().filter(state::hasToken).toList().forEach(state::removeToken);
      return;
    }
    incoming.stream().filter(state::hasToken).findFirst().ifPresent(state::removeToken);
  }
  
  /** Produces one token on every outgoing flow. */
  protected void produceTokens(final ASTFlowElement node, final BPMNState state) {
    node.getOutgoingsList().forEach(state::addToken);
  }
  
  /**
   * Passes the token of an activity or an event on. Without conditions every outgoing flow gets a
   * token. With conditions the element behaves like an inclusive split: every flow whose condition
   * holds is taken, the default flow if none of them holds.
   */
  protected void leave(final ASTFlowElement node, final BPMNState state) {
    final List<SequenceFlow> outgoing = node.getOutgoingsList();
    if (outgoing.stream().noneMatch(flow -> flow.isConditional() || flow.isDefault())) {
      produceTokens(node, state);
      return;
    }
    boolean conditionTaken = false;
    boolean anyTaken = false;
    for (SequenceFlow flow : outgoing) {
      if (!flow.isDefault() && isFlowEnabled(flow)) {
        state.addToken(flow);
        anyTaken = true;
        conditionTaken = conditionTaken || flow.isConditional();
      }
    }
    if (!conditionTaken && (!anyTaken || outgoing.stream().anyMatch(SequenceFlow::isDefault))) {
      takeDefaultFlow(node, state);
    }
  }
  
  /* ======================================================================
   * Sub-processes
   * ====================================================================== */
  
  /** @return whether the activity is a sub-process whose inner flow the interpreter executes */
  protected boolean isExecutedInside(final ASTWFActivity activity) {
    return activity instanceof ASTWFSubProcess && scopes.containsValue(activity);
  }
  
  /** @return whether the node lies inside the sub-process, directly or in a nested one */
  protected boolean isInside(final ASTFlowElement node, final ASTWFSubProcess subProcess) {
    for (ASTWFSubProcess scope = scopes.get(node); scope != null; scope = scopes.get(scope)) {
      if (scope == subProcess) {
        return true;
      }
    }
    return false;
  }
  
  /** @return whether anything is left inside the sub-process: a token, an activity, an event */
  protected boolean isScopeActive(final ASTWFSubProcess subProcess, final BPMNState state) {
    return state.getTokens().stream().anyMatch(flow -> isInside(flow.getSource(), subProcess))
        || state.getRunning().stream().anyMatch(activity -> isInside(activity, subProcess)) || state
            .getWaiting().stream().anyMatch(event -> isInside(event, subProcess));
  }
  
  /** Removes everything that is left inside a sub-process. */
  protected void cancelScope(final ASTWFSubProcess subProcess, final BPMNState state) {
    state.getTokens().removeIf(flow -> isInside(flow.getSource(), subProcess));
    for (ASTWFActivity activity : List.copyOf(state.getRunning())) {
      if (isInside(activity, subProcess)) {
        state.removeRunning(activity);
        state.resetIterations(activity);
        state.record(BPMNTraceEntry.Kind.ACTIVITY_CANCELLED, activity);
      }
    }
    for (ASTWFEvent event : List.copyOf(state.getWaiting())) {
      if (isInside(event, subProcess)) {
        disarm(event, state);
      }
    }
  }
  
  /** Interrupts a running activity and, for a sub-process, everything inside it. */
  protected void cancelActivity(final ASTWFActivity activity, final BPMNState state) {
    if (activity instanceof ASTWFSubProcess subProcess) {
      cancelScope(subProcess, state);
    }
    state.removeRunning(activity);
    state.resetIterations(activity);
    state.record(BPMNTraceEntry.Kind.ACTIVITY_CANCELLED, activity);
  }
  
  /** Interrupts everything that is active in the instance. */
  protected void cancelAll(final BPMNState state) {
    List.copyOf(state.getRunning()).forEach(activity -> state.record(
        BPMNTraceEntry.Kind.ACTIVITY_CANCELLED, activity));
    List.copyOf(state.getWaiting()).forEach(event -> state.record(
        BPMNTraceEntry.Kind.EVENT_DISARMED, event));
  }
  
  /* ======================================================================
   * Activities
   * ====================================================================== */
  
  protected void startActivity(final ASTWFActivity activity, final BPMNState state) {
    state.resetIterations(activity);
    if (isSkipped(activity)) {
      leave(activity, state); // the activity is not executed at all
      return;
    }
    state.addRunning(activity);
    state.record(BPMNTraceEntry.Kind.ACTIVITY_STARTED, activity);
    beginIteration(activity, state);
  }
  
  protected void beginIteration(final ASTWFActivity activity, final BPMNState state) {
    runBehavior(activity);
    if (isExecutedInside(activity)) {
      instantiate((ASTWFSubProcess) activity, state);
    }
  }
  
  protected void completeActivity(final ASTWFActivity activity, final BPMNState state) {
    state.incIterations(activity);
    if (repeats(activity, state)) {
      state.record(BPMNTraceEntry.Kind.ACTIVITY_REPEATED, activity);
      beginIteration(activity, state); // stays running, i.e., the next iteration has started
      return;
    }
    state.removeRunning(activity);
    state.resetIterations(activity);
    state.record(BPMNTraceEntry.Kind.ACTIVITY_COMPLETED, activity);
    leave(activity, state);
  }
  
  /** @return whether the activity is not entered: a false {@code while} or no instance at all */
  protected boolean isSkipped(final ASTWFActivity activity) {
    final Optional<ASTWFStandardLoop> standardLoop = getStandardLoop(activity);
    if (standardLoop.isPresent()) {
      return standardLoop.get().getLoopForm() == ASTConstantsWorkflow.WHILE && !evaluateAsBoolean(
          standardLoop.get().getLoopCondition());
    }
    final Optional<ASTWFMILoop> multiInstance = getMultiInstanceLoop(activity);
    return multiInstance.isPresent() && cardinality(multiInstance.get()) <= 0;
  }
  
  /** @return whether the activity is executed once more */
  protected boolean repeats(final ASTWFActivity activity, final BPMNState state) {
    final Optional<ASTWFStandardLoop> standardLoop = getStandardLoop(activity);
    if (standardLoop.isPresent()) {
      return repeatsLoop(standardLoop.get(), state.getIterations(activity));
    }
    final Optional<ASTWFMILoop> multiInstance = getMultiInstanceLoop(activity);
    if (multiInstance.isPresent()) {
      return repeatsMultiInstance(multiInstance.get(), state.getIterations(activity));
    }
    return false;
  }
  
  protected boolean repeatsLoop(final ASTWFStandardLoop loop, final int iterations) {
    if (loop.isPresentLoopMaximum() && iterations >= loop.getLoopMaximum().getValue()) {
      return false;
    }
    final boolean condition = evaluateAsBoolean(loop.getLoopCondition());
    // 'while' repeats as long as the condition holds, 'until' repeats until it holds
    return loop.getLoopForm() == ASTConstantsWorkflow.WHILE ? condition : !condition;
  }
  
  /**
   * A multi-instance activity stays active until all its instances have been completed. The
   * instances cannot be told apart from outside, so parallel instances are completed one after the
   * other like sequential ones.
   */
  protected boolean repeatsMultiInstance(final ASTWFMILoop loop, final int completed) {
    if (loop.isPresentCompletionCondition() && evaluateAsBoolean(loop.getCompletionCondition())) {
      return false; // the remaining instances are not needed anymore
    }
    return completed < cardinality(loop);
  }
  
  /** @return the number of instances of a multi-instance activity */
  protected int cardinality(final ASTWFMILoop loop) {
    final ASTWFLoopCardinality cardinality = loop.getWFLoopCardinality();
    if (cardinality.isPresentCount()) {
      return cardinality.getCount().getValue();
    }
    if (cardinality.isPresentExpression()) {
      return evaluateAsInt(cardinality.getExpression());
    }
    // one instance per element of a collection-valued data object
    final String name = cardinality.getCollection();
    final Optional<VariableSymbol> collection = variables.stream().filter(variable -> variable
        .getName().equals(name)).findFirst();
    if (collection.isEmpty() || !isAssigned(collection.get())) {
      Log.error("0xWFM9212 The data object '" + name
          + "' that determines the number of instances has no value.");
      return 0;
    }
    final Object value = getVariable(collection.get()).asNativeObject();
    if (value instanceof Collection<?> elements) {
      return elements.size();
    }
    if (value instanceof Map<?, ?> entries) {
      return entries.size();
    }
    if (value != null && value.getClass().isArray()) {
      return Array.getLength(value);
    }
    Log.error("0xWFM9213 The data object '" + name
        + "' that determines the number of instances is not a collection.");
    return 0;
  }
  
  protected Optional<ASTWFStandardLoop> getStandardLoop(final ASTWFActivity activity) {
    if (activity.isPresentWFLoopCharacteristic() && activity
        .getWFLoopCharacteristic() instanceof ASTWFStandardLoop loop) {
      return Optional.of(loop);
    }
    return Optional.empty();
  }
  
  protected Optional<ASTWFMILoop> getMultiInstanceLoop(final ASTWFActivity activity) {
    if (activity.isPresentWFLoopCharacteristic() && activity
        .getWFLoopCharacteristic() instanceof ASTWFMILoop loop) {
      return Optional.of(loop);
    }
    return Optional.empty();
  }
  
  /** @return whether the activity only exists to compensate another one */
  protected boolean isCompensationHandler(final ASTFlowElement node) {
    if (!(node instanceof ASTWFActivity activity)) {
      return false;
    }
    if (activity.isPresentSymbol() && activity.getSymbol().isCompensating()) {
      return true;
    }
    return boundaryOwners.keySet().stream().anyMatch(event -> event.isPresentCompensationHandler()
        && event.getCompensationHandler().getActivity().equals(activity.getName()));
  }
  
  /* ======================================================================
   * Events
   * ====================================================================== */
  
  protected boolean isCatching(final ASTWFEvent event) {
    return event.isCatch() && !event.isEnd();
  }
  
  protected boolean isTerminating(final ASTWFEvent event) {
    return event.isPresentTrigger() && event.getTrigger() instanceof ASTWFEventTriggerTerminate;
  }
  
  protected boolean isCompensation(final ASTWFEvent event) {
    return event.isPresentTrigger() && event.getTrigger() instanceof ASTWFEventTriggerCompensate;
  }
  
  /** @return the name of the error an end event throws resp. a boundary event catches */
  protected Optional<String> errorOf(final ASTWFEvent event) {
    if (event.isPresentTrigger() && event
        .getTrigger() instanceof ASTWFEventTriggerNotification notification && notification
            .getKind() == ASTConstantsWorkflow.ERROR) {
      return Optional.of(notification.getName());
    }
    return Optional.empty();
  }
  
  protected void arm(final ASTWFEvent event, final BPMNState state) {
    state.addWaiting(event);
    state.record(BPMNTraceEntry.Kind.EVENT_ARMED, event);
  }
  
  protected void disarm(final ASTWFEvent event, final BPMNState state) {
    state.removeWaiting(event);
    state.record(BPMNTraceEntry.Kind.EVENT_DISARMED, event);
  }
  
  /** Executes an event: it is recorded, its behavior runs and it passes its token on. */
  protected void throwEvent(final ASTWFEvent event, final BPMNState state) {
    state.addThrown(event);
    state.record(BPMNTraceEntry.Kind.EVENT_OCCURRED, event);
    runBehavior(event);
    if (isTerminating(event)) {
      terminate(event, state);
      return;
    }
    if (event.isEnd() && errorOf(event).isPresent()) {
      raiseError(event, errorOf(event).get(), state);
      return;
    }
    leave(event, state);
  }
  
  /**
   * A terminate event ends the level it is on: inside a sub-process only that sub-process, which
   * then completes regularly, on the level of the process the whole instance.
   */
  protected void terminate(final ASTWFEvent event, final BPMNState state) {
    final ASTWFSubProcess scope = scopes.get(event);
    if (scope != null) {
      cancelScope(scope, state);
      return;
    }
    cancelAll(state);
    state.terminate();
  }
  
  /**
   * An error end event interrupts the sub-process it is in. The error is caught by the boundary
   * event of that sub-process that refers to the same error; without one it is passed on to the
   * level around. An error that nobody catches ends the instance abnormally.
   */
  protected void raiseError(final ASTWFEvent source, final String error, final BPMNState state) {
    ASTWFSubProcess scope = scopes.get(source);
    while (scope != null) {
      final ASTWFSubProcess interrupted = scope;
      final Optional<ASTWFEvent> handler = boundaryOwners.entrySet().stream().filter(entry -> entry
          .getValue() == interrupted && errorOf(entry.getKey()).filter(error::equals).isPresent())
          .map(Map.Entry::getKey).findFirst();
      cancelActivity(interrupted, state);
      if (handler.isPresent()) {
        throwEvent(handler.get(), state);
        return;
      }
      scope = scopes.get(interrupted);
    }
    cancelAll(state);
    state.fail("The error '" + error + "' thrown by the end event '" + getQualifiedName(source)
        + "' has not been caught.");
  }
  
  /**
   * @param event the boundary event that has been triggered
   * @param state the current state
   * @return whether the event occurred
   */
  protected boolean triggerBoundaryEvent(final ASTWFEvent event, final BPMNState state) {
    final ASTWFActivity owner = boundaryOwners.get(event);
    if (!state.isRunning(owner)) {
      Log.error("0xWFM9207 Boundary event '" + event.getName() + "' cannot be triggered, '" + owner
          .getName() + "' is not running.");
      return false;
    }
    if (isCompensation(event)) {
      Log.error("0xWFM9210 Boundary event '" + event.getName()
          + "' cannot be triggered, compensation is not supported.");
      return false;
    }
    if (!event.isNoninterrupt()) {
      cancelActivity(owner, state); // interrupting: the activity is cancelled
    }
    throwEvent(event, state);
    return true;
  }
  
  /**
   * Cancels the branches that lost the race at an exclusive event based gateway.
   *
   * @param winner the event that has been triggered or the receive task that has been completed
   * @param state the current state
   */
  protected void cancelEventBasedAlternatives(final ASTFlowElement winner, final BPMNState state) {
    for (SequenceFlow incoming : winner.getIncomingsList()) {
      final ASTFlowElement source = incoming.getSource();
      if (!isGateway(source) || !gatewayType(source).isExclusiveEventBased()) {
        continue;
      }
      for (SequenceFlow alternative : source.getOutgoingsList()) {
        state.removeToken(alternative);
        final ASTFlowElement target = alternative.getTarget();
        if (target == winner) {
          continue;
        }
        if (target instanceof ASTWFEvent event && state.isWaiting(event)) {
          disarm(event, state);
        }
        else if (target instanceof ASTWFActivity activity && state.isRunning(activity)) {
          cancelActivity(activity, state);
        }
      }
    }
  }
  
  /** Start events are alternatives: once one occurred, the others do not start this instance. */
  protected void cancelAlternativeStartEvents(final ASTWFEvent event, final BPMNState state) {
    if (!event.isStart()) {
      return;
    }
    for (ASTWFEvent other : List.copyOf(state.getWaiting())) {
      if (other.isStart() && scopes.get(other) == scopes.get(event)) {
        disarm(other, state);
      }
    }
  }
  
  /* ======================================================================
   * Gateways
   * ====================================================================== */
  
  protected boolean isGateway(final ASTFlowElement node) {
    return node instanceof ASTWFGateway || node instanceof ASTWFInlineGateway;
  }
  
  protected ASTGatewayType gatewayType(final ASTFlowElement node) {
    return node instanceof ASTWFGateway gateway ? gateway.getType() : ((ASTWFInlineGateway) node)
        .getType();
  }
  
  protected boolean isMerging(final ASTFlowElement node) {
    final int direction = node instanceof ASTWFGateway gateway ? gateway.getDirection()
        : ((ASTWFInlineGateway) node).getDirection();
    return direction == ASTConstantsWorkflow.MERGE;
  }
  
  /** @return the number of branches a complex merge waits for, if the model states one */
  protected OptionalInt getActivationCount(final ASTFlowElement node) {
    if (node instanceof ASTWFGateway gateway && gateway.isPresentActivationCount()) {
      return OptionalInt.of(gateway.getActivationCount().getValue());
    }
    return OptionalInt.empty();
  }
  
  protected boolean isGatewayEnabled(final ASTFlowElement node, final BPMNState state) {
    final List<SequenceFlow> incoming = node.getIncomingsList();
    if (incoming.isEmpty()) {
      return false; // instantiating event based gateways are not supported yet
    }
    if (!isMerging(node)) {
      return hasIncomingToken(node, state); // one token is enough to split
    }
    final ASTGatewayType type = gatewayType(node);
    if (type.isParallel() || type.isParallelEventBased()) {
      return incoming.stream().allMatch(state::hasToken); // synchronizes all branches
    }
    if (type.isComplex()) {
      final int required = getActivationCount(node).orElse(incoming.size());
      return incoming.stream().filter(state::hasToken).count() >= required;
    }
    if (type.isInclusive()) {
      // synchronizes the branches that were activated: waits as long as another token may arrive
      return hasIncomingToken(node, state) && !expectsFurtherTokens(node, state);
    }
    // exclusive merges pass the token on as soon as a branch arrives
    return hasIncomingToken(node, state);
  }
  
  /**
   * Decides whether an inclusive merge has to wait: it does as long as a token somewhere else in
   * the instance can still reach one of its incoming flows that carry no token yet.
   */
  protected boolean expectsFurtherTokens(final ASTFlowElement merge, final BPMNState state) {
    final Set<SequenceFlow> empty = new LinkedHashSet<>(merge.getIncomingsList());
    empty.removeIf(state::hasToken);
    if (empty.isEmpty()) {
      return false;
    }
    final Set<ASTFlowElement> positions = new LinkedHashSet<>();
    for (SequenceFlow flow : state.getTokens().elementSet()) {
      if (flow.getTarget() != merge) {
        positions.add(flow.getTarget()); // the token is on its way to that node
      }
    }
    positions.addAll(state.getRunning());
    positions.addAll(state.getWaiting());
    return positions.stream().anyMatch(position -> reaches(position, empty, merge));
  }
  
  /** @return whether a token at the node can get onto one of the flows without passing the merge */
  protected boolean reaches(final ASTFlowElement from, final Set<SequenceFlow> flows,
      final ASTFlowElement merge) {
    final Set<ASTFlowElement> visited = new LinkedHashSet<>();
    final Deque<ASTFlowElement> open = new ArrayDeque<>();
    open.add(from);
    while (!open.isEmpty()) {
      final ASTFlowElement node = open.poll();
      if (node == merge || !visited.add(node)) {
        continue;
      }
      final List<SequenceFlow> outgoing = new ArrayList<>(node.getOutgoingsList());
      // an activity may also be left via its boundary events
      boundaryOwners.forEach((event, owner) -> {
        if (owner == node) {
          outgoing.addAll(event.getOutgoingsList());
        }
      });
      for (SequenceFlow flow : outgoing) {
        if (flows.contains(flow)) {
          return true;
        }
        open.add(flow.getTarget());
      }
    }
    return false;
  }
  
  protected void executeGateway(final ASTFlowElement node, final BPMNState state) {
    runBehavior(node);
    if (isMerging(node)) {
      produceTokens(node, state);
      return;
    }
    final ASTGatewayType type = gatewayType(node);
    if (type.isParallel()) {
      produceTokens(node, state);
    }
    else if (type.isExclusive()) {
      splitExclusive(node, state);
    }
    else if (type.isInclusive() || type.isComplex()) {
      splitInclusive(node, state);
    }
    else {
      // event based: all branches are armed, the events decide how the instance continues
      produceTokens(node, state);
    }
  }
  
  /** Takes the first outgoing flow whose condition holds, the default flow otherwise. */
  protected void splitExclusive(final ASTFlowElement node, final BPMNState state) {
    for (SequenceFlow outgoing : node.getOutgoingsList()) {
      if (!outgoing.isDefault() && isFlowEnabled(outgoing)) {
        state.addToken(outgoing);
        return;
      }
    }
    takeDefaultFlow(node, state);
  }
  
  /** Takes every outgoing flow whose condition holds, the default flow if none holds. */
  protected void splitInclusive(final ASTFlowElement node, final BPMNState state) {
    boolean taken = false;
    for (SequenceFlow outgoing : node.getOutgoingsList()) {
      if (!outgoing.isDefault() && isFlowEnabled(outgoing)) {
        state.addToken(outgoing);
        taken = true;
      }
    }
    if (!taken) {
      takeDefaultFlow(node, state);
    }
  }
  
  protected void takeDefaultFlow(final ASTFlowElement node, final BPMNState state) {
    final Optional<SequenceFlow> defaultFlow = node.getOutgoingsList().stream().filter(
        SequenceFlow::isDefault).findFirst();
    if (defaultFlow.isPresent()) {
      state.addToken(defaultFlow.get());
    }
    else {
      Log.error("0xWFM9208 No outgoing flow of '" + node.getName()
          + "' is enabled and there is no default flow. The token is lost.");
    }
  }
  
  /* ======================================================================
   * Limitations
   * ====================================================================== */
  
  /**
   * Lists the constructs of the process that the interpreter does not execute as BPMN defines
   * them. The process can be executed nevertheless; each entry says how the construct behaves.
   *
   * @return one message per affected construct, empty if the process is executed as modelled
   */
  public List<String> getLimitations() {
    final List<String> result = new ArrayList<>();
    for (ASTFlowElement node : nodes) {
      final String name = "'" + getQualifiedName(node) + "'";
      if (node instanceof ASTWFCallActivity) {
        result.add("0xWFM9220 Call activity " + name + " is executed as an opaque activity: the"
            + " called process is not instantiated, the environment completes the activity.");
      }
      if (node instanceof ASTWFSubProcess subProcess && subProcess.isTransaction()) {
        result.add("0xWFM9221 Transaction " + name + " is executed like an embedded sub-process;"
            + " cancellation and compensation are not executed.");
      }
      if (node instanceof ASTWFSubProcess subProcess && subProcess.isAdHoc()) {
        result.add("0xWFM9222 Ad-hoc sub-process " + name + " is executed like an embedded"
            + " sub-process; its completion condition and its ordering are not evaluated.");
      }
      if (node instanceof ASTWFActivity activity && getMultiInstanceLoop(activity).filter(
          ASTWFMILoop::isPresentWFMIImplicitEventBehavior).isPresent()) {
        result.add("0xWFM9223 The events that the multi-instance activity " + name
            + " throws for its instances are not thrown.");
      }
      if (isCompensationHandler(node)) {
        result.add("0xWFM9224 Activity " + name + " compensates another activity; compensation is"
            + " not executed, so it never starts.");
      }
      if (node instanceof ASTWFEvent event && event.isPresentTrigger()) {
        if (event.getTrigger() instanceof ASTWFEventTriggerCompensate) {
          result.add("0xWFM9225 Event " + name + " refers to compensation, which is not executed:"
              + (boundaryOwners.containsKey(event) ? " it never occurs."
                  : " it only passes its token on."));
        }
        if (event.getTrigger() instanceof ASTWFEventTriggerCancel) {
          result.add("0xWFM9226 Event " + name + " cancels a transaction, which is not executed:"
              + " it only passes its token on.");
        }
        if (event.getTrigger() instanceof ASTWFEventTriggerConditional) {
          result.add("0xWFM9227 The condition of event " + name + " is not evaluated; the"
              + " environment has to trigger the event.");
        }
        if (event.getTrigger() instanceof ASTWFEventTriggerMultiple) {
          result.add("0xWFM9228 Event " + name + " has several triggers; it occurs as soon as the"
              + " environment triggers it once.");
        }
      }
      if (isGateway(node) && gatewayType(node).isComplex() && gatewayType(node).isPresentGuard()) {
        result.add("0xWFM9229 The guard of complex gateway " + name + " is not evaluated; it"
            + " splits like an inclusive gateway and merges after its number of activations.");
      }
    }
    return result;
  }
  
}
