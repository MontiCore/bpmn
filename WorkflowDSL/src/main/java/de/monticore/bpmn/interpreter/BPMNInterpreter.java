/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import static com.google.common.base.Preconditions.checkNotNull;

import de.monticore.bpmn.collectors.WorkflowCollectors;
import de.monticore.bpmn.types3.WorkflowTypeCheck3;
import de.monticore.bpmn.workflow._ast.ASTConstantsWorkflow;
import de.monticore.bpmn.workflow._ast.ASTFlowCondition;
import de.monticore.bpmn.workflow._ast.ASTFlowElement;
import de.monticore.bpmn.workflow._ast.ASTGatewayType;
import de.monticore.bpmn.workflow._ast.ASTWFActivity;
import de.monticore.bpmn.workflow._ast.ASTWFCallActivity;
import de.monticore.bpmn.workflow._ast.ASTWFDataObject;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerTerminate;
import de.monticore.bpmn.workflow._ast.ASTWFGateway;
import de.monticore.bpmn.workflow._ast.ASTWFInlineGateway;
import de.monticore.bpmn.workflow._ast.ASTWFProcess;
import de.monticore.bpmn.workflow._ast.ASTWFStandardLoop;
import de.monticore.bpmn.workflow._ast.ASTWFSubProcess;
import de.monticore.bpmn.workflow._ast.ASTWFTask;
import de.monticore.bpmn.workflow._ast.SequenceFlow;
import de.monticore.expressions.expressionsbasis._ast.ASTExpression;
import de.monticore.interpreter.calculations.MICalculationVoid;
import de.monticore.interpreter.util.InterpreterDataForBasicSymbols;
import de.monticore.symbols.basicsymbols._symboltable.VariableSymbol;
import de.monticore.symbols.basicsymbols.interpreter.AbstractInterpreterForBasicSymbols;
import de.monticore.values.MCValue;
import de.monticore.visitor.ITraverser;
import de.se_rwth.commons.logging.Log;
import java.util.ArrayList;
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
 * (gateways, throwing events, sequence flows) and stops at every element whose completion is
 * decided outside of the model, i.e., at activities and at catching events. The environment drives
 * the instance forward by signalling these decisions:
 *
 * <pre>
 * BPMNInterpreter interpreter = BPMNInterpreterBuilder.build(process);
 * BPMNState state = interpreter.start(); // token(s) behind the start event(s)
 * state = interpreter.interpret(state, taskA); // taskA has been completed
 * state = interpreter.interpret(state, message); // a message event has been caught
 * boolean done = state.isFinished();
 * </pre>
 *
 * <p>Expressions (flow conditions, gateway guards, loop conditions) are evaluated with the
 * MontiCore expression interpreter: the AST of the expression is turned into an
 * {@link de.monticore.interpreter.calculations.MICalculation} once (and cached) and is then
 * executed on this interpreter's frame. The values of the {@code data} objects therefore live in
 * the frame and are read and written with {@link #addVariable}, {@link #setVariable} and
 * {@link #getVariable} of the super class.
 *
 * <p>The behavior of a flow element (e.g., what a service task actually does) is not part of the
 * Workflow language. It is provided by the embedding application via
 * {@link #setBehavior(ASTFlowElement, MICalculationVoid)}.
 */
public class BPMNInterpreter extends AbstractInterpreterForBasicSymbols {
  
  /** Guards against models that are not sound, e.g., a livelock of gateways. */
  protected static final int MAX_PROPAGATION_STEPS = 10000;
  
  protected final ASTWFProcess process;
  
  /** All flow nodes of the process, in a deterministic order. */
  protected final List<ASTFlowElement> nodes;
  
  /** Maps a boundary event to the activity it is attached to. */
  protected final Map<ASTWFEvent, ASTWFActivity> boundaryOwners = new LinkedHashMap<>();
  
  /** The behavior that is executed when a flow element is executed. */
  protected final Map<ASTFlowElement, MICalculationVoid> behaviors = new LinkedHashMap<>();
  
  /** The data objects of the process, i.e., the variables that expressions may refer to. */
  protected final List<VariableSymbol> variables = new ArrayList<>();
  
  public BPMNInterpreter(final ASTWFProcess process, final ITraverser traverser,
      final InterpreterDataForBasicSymbols iData) {
    super(traverser, iData);
    this.process = checkNotNull(process);
    this.nodes = collectFlowNodes(process);
    
    indexBoundaryEvents();
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
  
  /* ======================================================================
   * Setup
   * ====================================================================== */
  
  /** Collects all flow nodes of the process, including the gateways defined inline in flows. */
  protected List<ASTFlowElement> collectFlowNodes(final ASTWFProcess process) {
    final Set<ASTFlowElement> result = new LinkedHashSet<>(WorkflowCollectors.toFlowNodesLocal(
        process));
    // inline gateways are not visited as ASTFlowElement, they are only reachable via the flows
    for (SequenceFlow flow : WorkflowCollectors.toSequenceFlowLocal(process)) {
      result.add(flow.getSource());
      result.add(flow.getTarget());
    }
    return result.stream().filter(this::isFlowNode).toList();
  }
  
  /** Remembers which activity a boundary event is attached to. */
  protected void indexBoundaryEvents() {
    for (ASTFlowElement node : nodes) {
      if (node instanceof ASTWFTask task) {
        task.getBoundaryEventList().forEach(event -> boundaryOwners.put(event, task));
      }
      else if (node instanceof ASTWFSubProcess subProcess) {
        subProcess.getBoundaryEventList().forEach(event -> boundaryOwners.put(event, subProcess));
      }
      else if (node instanceof ASTWFCallActivity callActivity) {
        callActivity.getBoundaryEventList().forEach(event -> boundaryOwners.put(event,
            callActivity));
      }
    }
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
  
  /** @return the current values of all data objects of the process */
  public Map<VariableSymbol, MCValue> snapshotVariables() {
    final Map<VariableSymbol, MCValue> values = new LinkedHashMap<>();
    for (VariableSymbol symbol : variables) {
      values.put(symbol, getVariable(symbol));
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
  
  /** Evaluates an expression of the model against the current variable values. */
  public MCValue evaluate(final ASTExpression expression) {
    // assure the types are known, the interpreter picks its calculations based on them
    WorkflowTypeCheck3.typeOf(expression);
    return interpretNode(expression);
  }
  
  protected boolean evaluateAsBoolean(final ASTExpression expression) {
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
   * immediately, start events with a trigger wait for
   * {@link #interpret(BPMNState, ASTWFEvent)}. A process without start events starts every flow
   * node that has no incoming flow.
   *
   * @return the state after the process has been instantiated
   */
  public BPMNState start() {
    final BPMNState state = new BPMNState();
    final List<ASTWFEvent> startEvents = WorkflowCollectors.toStartEventsLocal(process);
    if (startEvents.isEmpty()) {
      nodes.stream().filter(ASTFlowElement::isEmptyIncomings).forEach(node -> execute(node, state));
    }
    else {
      for (ASTWFEvent startEvent : startEvents) {
        if (startEvent.isPresentTrigger()) {
          state.addWaiting(startEvent);
        }
        else {
          throwEvent(startEvent, state);
        }
      }
    }
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
      triggerBoundaryEvent(event, next);
    }
    else if (next.isWaiting(event)) {
      next.removeWaiting(event);
      cancelEventBasedAlternatives(event, next);
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
    while (!next.getRunning().isEmpty() && !next.isTerminated()) {
      next = interpret(next, next.getRunning().iterator().next());
    }
    return next;
  }
  
  protected boolean checkStepAllowed(final BPMNState state, final ASTFlowElement element) {
    if (state.isTerminated()) {
      Log.error("0xWFM9206 The process instance has been terminated, '" + element.getName()
          + "' cannot be signalled anymore.");
      return false;
    }
    return true;
  }
  
  /* ======================================================================
   * Token game
   * ====================================================================== */
  
  /** Fires every element that the process can execute on its own until none is enabled anymore. */
  protected void propagate(final BPMNState state) {
    int steps = 0;
    boolean progress = true;
    while (progress && !state.isTerminated()) {
      progress = false;
      for (ASTFlowElement node : nodes) {
        if (isEnabled(node, state)) {
          fire(node, state);
          progress = true;
          break;
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
        return false; // fired by start() resp. by triggering the event
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
        state.addWaiting(event); // waits for its trigger
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
  
  /* ======================================================================
   * Activities
   * ====================================================================== */
  
  protected void startActivity(final ASTWFActivity activity, final BPMNState state) {
    state.resetIterations(activity);
    if (isSkippedByWhileLoop(activity)) {
      produceTokens(activity, state); // the loop body is not executed at all
      return;
    }
    runBehavior(activity);
    state.addRunning(activity);
  }
  
  protected void completeActivity(final ASTWFActivity activity, final BPMNState state) {
    state.incIterations(activity);
    if (repeatsLoop(activity, state)) {
      runBehavior(activity); // stays running, i.e., the next iteration has started
      return;
    }
    state.removeRunning(activity);
    state.resetIterations(activity);
    produceTokens(activity, state);
  }
  
  /** @return whether a {@code while} loop is not entered at all */
  protected boolean isSkippedByWhileLoop(final ASTWFActivity activity) {
    return getStandardLoop(activity).filter(loop -> loop.getLoopForm()
        == ASTConstantsWorkflow.WHILE).map(loop -> !evaluateAsBoolean(loop.getLoopCondition()))
        .orElse(false);
  }
  
  /** @return whether the activity is executed once more */
  protected boolean repeatsLoop(final ASTWFActivity activity, final BPMNState state) {
    final Optional<ASTWFStandardLoop> loop = getStandardLoop(activity);
    if (loop.isEmpty()) {
      return false;
    }
    final ASTWFStandardLoop standardLoop = loop.get();
    if (standardLoop.isPresentLoopMaximum() && state.getIterations(activity) >= standardLoop
        .getLoopMaximum().getValue()) {
      return false;
    }
    final boolean condition = evaluateAsBoolean(standardLoop.getLoopCondition());
    // 'while' repeats as long as the condition holds, 'until' repeats until it holds
    return standardLoop.getLoopForm() == ASTConstantsWorkflow.WHILE ? condition : !condition;
  }
  
  protected Optional<ASTWFStandardLoop> getStandardLoop(final ASTWFActivity activity) {
    if (activity.isPresentWFLoopCharacteristic() && activity
        .getWFLoopCharacteristic() instanceof ASTWFStandardLoop loop) {
      return Optional.of(loop);
    }
    return Optional.empty();
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
  
  /** Executes an event: it is recorded, its behavior runs and it passes its token on. */
  protected void throwEvent(final ASTWFEvent event, final BPMNState state) {
    state.addThrown(event);
    runBehavior(event);
    if (isTerminating(event)) {
      state.terminate();
      return;
    }
    produceTokens(event, state);
  }
  
  protected void triggerBoundaryEvent(final ASTWFEvent event, final BPMNState state) {
    final ASTWFActivity owner = boundaryOwners.get(event);
    if (!state.isRunning(owner)) {
      Log.error("0xWFM9207 Boundary event '" + event.getName() + "' cannot be triggered, '" + owner
          .getName() + "' is not running.");
      return;
    }
    if (!event.isNoninterrupt()) {
      state.removeRunning(owner); // interrupting: the activity is cancelled
      state.resetIterations(owner);
    }
    throwEvent(event, state);
  }
  
  /**
   * Removes the tokens of the branches that lost the race at an exclusive event based gateway.
   *
   * @param event the event that has been triggered
   * @param state the current state
   */
  protected void cancelEventBasedAlternatives(final ASTWFEvent event, final BPMNState state) {
    for (SequenceFlow incoming : event.getIncomingsList()) {
      final ASTFlowElement source = incoming.getSource();
      if (!isGateway(source) || !gatewayType(source).isExclusiveEventBased()) {
        continue;
      }
      for (SequenceFlow alternative : source.getOutgoingsList()) {
        state.removeToken(alternative);
        if (alternative.getTarget() instanceof ASTWFEvent alternativeEvent) {
          state.removeWaiting(alternativeEvent);
        }
      }
    }
  }
  
  /* ======================================================================
   * Gateways
   * ====================================================================== */
  
  protected boolean isGateway(final ASTFlowElement node) {
    return node instanceof ASTWFGateway || node instanceof ASTWFInlineGateway;
  }
  
  protected boolean isFlowNode(final ASTFlowElement node) {
    return node instanceof ASTWFActivity || node instanceof ASTWFEvent || isGateway(node);
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
    // exclusive and inclusive merges pass the token on as soon as a branch arrives
    return hasIncomingToken(node, state);
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
      Log.error("0xWFM9208 No outgoing flow of gateway '" + node.getName()
          + "' is enabled and there is no default flow. The token is lost.");
    }
  }
  
}
