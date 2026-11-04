/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.AbstractTest;
import de.monticore.bpmn.collectors.WorkflowCollectors;
import de.monticore.bpmn.workflow._ast.ASTFlowElement;
import de.monticore.bpmn.workflow._ast.ASTWFActivity;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.ASTWFProcess;
import de.monticore.symbols.basicsymbols._symboltable.VariableSymbol;
import de.monticore.values.MCValueInt;
import de.se_rwth.commons.logging.Log;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class BPMNInterpreterTest extends AbstractTest {
  
  protected static final String MODEL_PACKAGE = "de.monticore.bpmn.interpreter.";
  
  protected ASTWFProcess load(final String modelName) {
    return loadModel(MODEL_PACKAGE + modelName).getWFProcess();
  }
  
  protected ASTWFActivity activity(final ASTWFProcess process, final String name) {
    return WorkflowCollectors.toActivitiesLocal(process).stream().filter(a -> a.getName().equals(
        name)).findFirst().orElseThrow();
  }
  
  protected ASTWFEvent event(final ASTWFProcess process, final String name) {
    return WorkflowCollectors.toEventsLocal(process).stream().filter(e -> e.getName().equals(name))
        .findFirst().orElseThrow();
  }
  
  protected List<String> names(final java.util.Collection<? extends ASTFlowElement> elements) {
    return elements.stream().map(ASTFlowElement::getName).collect(Collectors.toList());
  }
  
  @Test
  public void testSequence() {
    final ASTWFProcess process = load("SimpleProcess");
    final BPMNInterpreter interpreter = BPMNInterpreterBuilder.buildDefault(process);
    
    // the start event fires and the token stops at the first task
    BPMNState state = interpreter.start();
    assertEquals(List.of("Prepare"), names(state.getRunning()));
    assertFalse(state.isFinished());
    
    state = interpreter.interpret(state, activity(process, "Prepare"));
    assertEquals(List.of("Ship"), names(state.getRunning()));
    
    state = interpreter.interpret(state, activity(process, "Ship"));
    assertTrue(state.isFinished());
    assertEquals(List.of("Start", "Done"), names(state.getThrown()));
    assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testExclusiveGatewayTakesConditionalBranch() {
    final ASTWFProcess process = load("GatewayProcess");
    final BPMNInterpreter interpreter = BPMNInterpreterBuilder.buildDefault(process);
    
    final VariableSymbol amount = process.getSpannedScope().resolveVariable("amount").orElseThrow();
    interpreter.setVariable(amount, new MCValueInt(150));
    
    BPMNState state = interpreter.start();
    state = interpreter.interpret(state, activity(process, "Check"));
    
    // amount > 100, so the conditional branch is taken and not the default one
    assertEquals(List.of("Approve"), names(state.getRunning()));
    
    state = interpreter.interpret(state, activity(process, "Approve"));
    assertTrue(state.isFinished());
    assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testExclusiveGatewayTakesDefaultBranch() {
    final ASTWFProcess process = load("GatewayProcess");
    final BPMNInterpreter interpreter = BPMNInterpreterBuilder.buildDefault(process);
    
    final VariableSymbol amount = process.getSpannedScope().resolveVariable("amount").orElseThrow();
    interpreter.setVariable(amount, new MCValueInt(20));
    
    BPMNState state = interpreter.start();
    state = interpreter.interpret(state, activity(process, "Check"));
    
    assertEquals(List.of("Reject"), names(state.getRunning()));
    assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testParallelGatewaySplitsAndSynchronizes() {
    final ASTWFProcess process = load("ParallelProcess");
    final BPMNInterpreter interpreter = BPMNInterpreterBuilder.buildDefault(process);
    
    BPMNState state = interpreter.start();
    assertEquals(List.of("A", "B"), names(state.getRunning()));
    
    // the merge waits for both branches
    state = interpreter.interpret(state, activity(process, "A"));
    assertEquals(List.of("B"), names(state.getRunning()));
    assertFalse(state.isFinished());
    
    state = interpreter.interpret(state, activity(process, "B"));
    assertTrue(state.isFinished());
    assertTrue(state.getThrown().stream().anyMatch(e -> e.getName().equals("Done")));
    assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testCatchingEventWaitsForItsTrigger() {
    final ASTWFProcess process = load("EventProcess");
    final BPMNInterpreter interpreter = BPMNInterpreterBuilder.buildDefault(process);
    
    BPMNState state = interpreter.start();
    state = interpreter.interpret(state, activity(process, "RequestOffer"));
    
    // the token rests in the catching event until the message arrives
    final ASTWFEvent offerReceived = event(process, "OfferReceived");
    assertEquals(List.of("OfferReceived"), names(state.getWaiting()));
    assertFalse(state.isFinished());
    
    state = interpreter.interpret(state, offerReceived);
    assertTrue(state.isFinished());
    assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testStateIsNotModifiedByAStep() {
    final ASTWFProcess process = load("SimpleProcess");
    final BPMNInterpreter interpreter = BPMNInterpreterBuilder.buildDefault(process);
    
    final BPMNState first = interpreter.start();
    final BPMNState second = interpreter.interpret(first, activity(process, "Prepare"));
    
    assertEquals(List.of("Prepare"), names(first.getRunning()));
    assertEquals(List.of("Ship"), names(second.getRunning()));
  }
  
}
