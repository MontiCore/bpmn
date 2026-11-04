/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.definition.ProcessDefinition;
import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.instance.ProcessInstanceStatus;
import de.monticore.bpmn.engine.job.TimerCalculator;
import de.monticore.bpmn.engine.state.PersistentProcessState;
import de.monticore.bpmn.engine.state.ProcessStateJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DurableStateTest extends AbstractEngineTest {
  
  /**
   * Simulates a restart: a new engine is built over the same tables, and the definitions have lost
   * their parsed model, exactly as if they had just been read from a database.
   */
  protected WorkflowEngine restart(final WorkflowEngine engine) {
    engine.getDefinitions().findAll().forEach(definition -> definition.setProcess(null));
    return new WorkflowEngine(new ProcessModelLoader(), engine.getDefinitions(), engine
        .getInstances(), engine.getJobs(), engine.getHistory(), new TimerCalculator());
  }
  
  @Test
  public void testAnInstanceSurvivesARestartOfTheEngine() {
    final WorkflowEngine before = new WorkflowEngine();
    before.deploy(ORDER_V1);
    
    ProcessInstance instance = before.start("Order", Map.of("amount", 150));
    instance = before.completeTask(instance.getId(), "Check");
    final String instanceId = instance.getId();
    assertEquals(List.of("Approve"), before.getEnabledSignals(instanceId));
    
    // the engine goes down and comes up again; only the tables survive
    final WorkflowEngine after = restart(before);
    
    assertEquals(List.of("Approve"), after.getEnabledSignals(instanceId));
    assertEquals(150, after.getVariables(instanceId).get("amount"));
    
    final ProcessInstance continued = after.completeTask(instanceId, "Approve");
    assertEquals(ProcessInstanceStatus.COMPLETED, continued.getStatus());
  }
  
  @Test
  public void testTheStateOfAParallelInstanceSurvivesARestart() {
    final String parallel = """
        package de.monticore.bpmn.engine;
        
        process Shipment {
          start event Ordered;
          split and Fork;
          task Pack;
          task Invoice;
          merge and Join;
          end event Shipped;
        
          Ordered -> Fork;
          Fork -> Pack -> Join;
          Fork -> Invoice -> Join;
          Join -> Shipped;
        }
        """;
    final WorkflowEngine before = new WorkflowEngine();
    before.deploy(parallel);
    
    ProcessInstance instance = before.start("Shipment");
    instance = before.completeTask(instance.getId(), "Pack");
    // one branch is done, its token waits at the merge for the other branch
    assertEquals(List.of("Pack->Join"), instance.getState().getTokens());
    assertEquals(List.of("Invoice"), before.getEnabledSignals(instance.getId()));
    
    final WorkflowEngine after = restart(before);
    
    // the token that waits at the merge is restored, so the merge still synchronizes
    final ProcessInstance continued = after.completeTask(instance.getId(), "Invoice");
    assertEquals(ProcessInstanceStatus.COMPLETED, continued.getStatus());
    assertTrue(continued.getState().getThrown().contains("Shipped"));
  }
  
  @Test
  public void testTheStateIsReadableAsJson() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(ORDER_V1);
    
    ProcessInstance instance = engine.start("Order", Map.of("amount", 150));
    instance = engine.completeTask(instance.getId(), "Check");
    
    final String json = ProcessStateJson.toJson(instance.getState());
    final PersistentProcessState restored = ProcessStateJson.fromJson(json);
    
    // the state only refers to names of the model, never to objects of a JVM
    assertTrue(json.contains("\"Approve\""), json);
    assertEquals(List.of("Approve"), restored.getRunning());
    assertEquals(instance.getState().getTokens(), restored.getTokens());
    assertEquals("150", restored.getVariables().get("amount").getValue());
  }
  
  @Test
  public void testTheDefinitionIsRebuiltFromItsSource() {
    final WorkflowEngine engine = new WorkflowEngine();
    final ProcessDefinition deployed = engine.deploy(ORDER_V1);
    deployed.setProcess(null); // as if it had just been read from the database
    
    final ProcessDefinition reloaded = engine.getDefinition(deployed.getId());
    
    assertTrue(reloaded.isPresentProcess());
    assertEquals("Order", reloaded.getProcess().getName());
  }
  
}
