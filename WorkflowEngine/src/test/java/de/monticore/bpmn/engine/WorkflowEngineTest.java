/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.definition.ProcessDefinition;
import de.monticore.bpmn.engine.history.HistoryEvent;
import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.instance.ProcessInstanceStatus;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class WorkflowEngineTest extends AbstractEngineTest {
  
  @Test
  public void testRunsAnInstanceToCompletion() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(ORDER_V1);
    
    ProcessInstance instance = engine.start("Order", Map.of("amount", 150));
    assertEquals(List.of("Check"), engine.getEnabledSignals(instance.getId()));
    assertEquals(ProcessInstanceStatus.ACTIVE, instance.getStatus());
    
    instance = engine.completeTask(instance.getId(), "Check");
    // amount > 100, so the process took the conditional branch
    assertEquals(List.of("Approve"), engine.getEnabledSignals(instance.getId()));
    
    instance = engine.completeTask(instance.getId(), "Approve");
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
    assertTrue(engine.getEnabledSignals(instance.getId()).isEmpty());
  }
  
  @Test
  public void testTakesTheDefaultBranchForOtherVariableValues() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(ORDER_V1);
    
    ProcessInstance instance = engine.start("Order", Map.of("amount", 20));
    instance = engine.completeTask(instance.getId(), "Check");
    
    assertEquals(List.of("Reject"), engine.getEnabledSignals(instance.getId()));
    assertEquals(20, engine.getVariables(instance.getId()).get("amount"));
  }
  
  @Test
  public void testDeploymentIncrementsTheVersion() {
    final WorkflowEngine engine = new WorkflowEngine();
    
    final ProcessDefinition first = engine.deploy(ORDER_V1);
    final ProcessDefinition second = engine.deploy(ORDER_V2);
    
    assertEquals(1, first.getVersion());
    assertEquals(2, second.getVersion());
    assertEquals("Order:1", first.getId());
    assertEquals(second.getId(), engine.getDefinitions().findLatest("Order").orElseThrow().getId());
  }
  
  @Test
  public void testRunningInstancesKeepTheirVersion() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(ORDER_V1);
    
    ProcessInstance instance = engine.start("Order", Map.of("amount", 150));
    engine.deploy(ORDER_V2); // a new version is deployed while the instance runs
    
    instance = engine.completeTask(instance.getId(), "Check");
    instance = engine.completeTask(instance.getId(), "Approve");
    
    // version 1 has no Archive task, so the instance is done
    assertEquals("Order:1", instance.getDefinitionId());
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
    
    // a new instance uses the new version and stops at Archive
    final ProcessInstance newer = engine.start("Order", Map.of("amount", 150));
    assertEquals("Order:2", newer.getDefinitionId());
    engine.completeTask(newer.getId(), "Check");
    engine.completeTask(newer.getId(), "Approve");
    assertEquals(List.of("Archive"), engine.getEnabledSignals(newer.getId()));
  }
  
  @Test
  public void testWritesAnAuditTrail() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(ORDER_V1);
    
    final ProcessInstance instance = engine.start("Order", Map.of("amount", 150));
    engine.completeTask(instance.getId(), "Check");
    engine.completeTask(instance.getId(), "Approve");
    
    final List<String> trail = engine.getHistory().findByInstanceId(instance.getId()).stream().map(
        event -> event.getType() + " " + event.getElementName()).collect(Collectors.toList());
    
    assertTrue(trail.contains(HistoryEvent.Type.INSTANCE_STARTED + " "));
    assertTrue(trail.contains(HistoryEvent.Type.EVENT_THROWN + " Received"));
    assertTrue(trail.contains(HistoryEvent.Type.ACTIVITY_STARTED + " Check"));
    assertTrue(trail.contains(HistoryEvent.Type.ACTIVITY_ENDED + " Check"));
    assertTrue(trail.contains(HistoryEvent.Type.ACTIVITY_STARTED + " Approve"));
    assertTrue(trail.contains(HistoryEvent.Type.EVENT_THROWN + " Done"));
    assertTrue(trail.contains(HistoryEvent.Type.INSTANCE_COMPLETED + " "));
  }
  
  @Test
  public void testInstancesAreIndependent() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(ORDER_V1);
    
    final ProcessInstance approved = engine.start("Order", Map.of("amount", 150));
    final ProcessInstance rejected = engine.start("Order", Map.of("amount", 5));
    
    engine.completeTask(approved.getId(), "Check");
    engine.completeTask(rejected.getId(), "Check");
    
    assertEquals(List.of("Approve"), engine.getEnabledSignals(approved.getId()));
    assertEquals(List.of("Reject"), engine.getEnabledSignals(rejected.getId()));
    assertEquals(150, engine.getVariables(approved.getId()).get("amount"));
    assertEquals(5, engine.getVariables(rejected.getId()).get("amount"));
  }
  
}
