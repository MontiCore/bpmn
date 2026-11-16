/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.history.HistoryEvent;
import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.instance.ProcessInstanceStatus;
import de.se_rwth.commons.logging.Log;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the engine does when something goes wrong: nothing silently. */
public class FailureTest extends AbstractEngineTest {
  
  /** A gateway without a default flow: for amounts up to 100 no outgoing flow is enabled. */
  protected static final String STRICT = """
      process Strict {
        data amount : int;
        start event Start;
        task Check;
        split xor Decision;
        task Approve;
        task Audit;
        end event Done;
        Start -> Check -> Decision;
        Decision -> [amount > 100] Approve -> Done;
        Decision -> [amount > 100000] Audit -> Done;
      }
      """;
  
  @Test
  public void testAStepThatLosesItsTokenFailsAndChangesNothing() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(STRICT);
    final ProcessInstance instance = engine.start("Strict", Map.of("amount", 20));
    final String id = instance.getId();
    
    final ProcessExecutionException e = assertThrows(ProcessExecutionException.class, () -> engine
        .completeTask(id, "Check"));
    assertTrue(e.getMessage().contains("0xWFM9208"));
    
    // the instance is exactly where it was and is still active
    final ProcessInstance unchanged = engine.findInstance(id).orElseThrow();
    assertEquals(ProcessInstanceStatus.ACTIVE, unchanged.getStatus());
    assertEquals(List.of("Check"), engine.getEnabledSignals(id));
    final List<HistoryEvent> history = engine.getHistory().findByInstanceId(id);
    assertEquals(HistoryEvent.Type.STEP_FAILED, history.get(history.size() - 1).getType());
    
    // once the cause is removed, the same step succeeds
    engine.setVariables(id, Map.of("amount", 500));
    assertEquals(List.of("Approve"), engine.getEnabledSignals(engine.completeTask(id, "Check")
        .getId()));
  }
  
  @Test
  public void testReadingADataObjectWithoutAValueFails() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(STRICT);
    final ProcessInstance instance = engine.start("Strict");
    final String id = instance.getId();
    
    final ProcessExecutionException e = assertThrows(ProcessExecutionException.class, () -> engine
        .completeTask(id, "Check"));
    assertTrue(e.getMessage().contains("0xWFM9214"));
    assertTrue(e.getMessage().contains("amount"));
    
    // the value can come with the completion of the task
    final ProcessInstance next = engine.completeTask(id, "Check", Map.of("amount", 150));
    assertEquals(List.of("Approve"), engine.getEnabledSignals(next.getId()));
    assertEquals(150, engine.getVariables(id).get("amount"));
  }
  
  @Test
  public void testAProcessThatCannotStartLeavesNoInstance() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Blocked {
          data ready : boolean;
          start event Start;
          split xor Decision;
          task Go;
          task Never;
          end event Done;
          Start -> Decision;
          Decision -> [ready] Go -> Done;
          Decision -> [ready && !ready] Never -> Done;
        }
        """);
    
    assertThrows(ProcessExecutionException.class, () -> engine.start("Blocked", Map.of("ready",
        false)));
    assertTrue(engine.getInstances().findAll().isEmpty());
  }
  
  @Test
  public void testSignalsTheInstanceDoesNotWaitForAreRejected() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(ORDER_V1);
    final ProcessInstance instance = engine.start("Order", Map.of("amount", 150));
    final String id = instance.getId();
    
    // an activity that is not running, an unknown name, a value that does not fit
    assertThrows(ProcessExecutionException.class, () -> engine.completeTask(id, "Approve"));
    assertThrows(IllegalArgumentException.class, () -> engine.completeTask(id, "Unknown"));
    assertThrows(IllegalArgumentException.class, () -> engine.completeTask(id, "Check", Map.of(
        "amount", "a lot")));
    assertThrows(IllegalArgumentException.class, () -> engine.completeTask(id, "Check", Map.of(
        "unknown", 1)));
    assertEquals(List.of("Check"), engine.getEnabledSignals(id));
    
    // an instance that has ended cannot be advanced
    engine.completeTask(id, "Check");
    engine.completeTask(id, "Approve");
    assertThrows(IllegalStateException.class, () -> engine.completeTask(id, "Check"));
  }
  
  @Test
  public void testADeadlockFailsTheInstance() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Stuck {
          data fast : boolean;
          start event Start;
          split xor Choose;
          task A;
          task B;
          merge and Join;
          end event Done;
          Start -> Choose;
          Choose -> [fast] A -> Join;
          Choose -> [_] B -> Join;
          Join -> Done;
        }
        """);
    
    ProcessInstance instance = engine.start("Stuck", Map.of("fast", true));
    instance = engine.completeTask(instance.getId(), "A");
    
    // the parallel merge waits for a branch that was never activated
    assertEquals(ProcessInstanceStatus.FAILED, instance.getStatus());
    assertTrue(instance.getFailure().orElse("").contains("A->Join"));
    assertFalse(instance.isActive());
  }
  
  @Test
  public void testAModelWithErrorsIsNotDeployed() {
    final WorkflowEngine engine = new WorkflowEngine();
    
    // a flow to an element that does not exist
    final ProcessDeploymentException e = assertThrows(ProcessDeploymentException.class, () -> engine
        .deploy("""
            process Broken {
              start event Start;
              task A;
              Start -> A -> Nowhere;
            }
            """));
    assertFalse(e.getErrors().isEmpty());
    assertTrue(engine.getDefinitions().findLatest("Broken").isEmpty());
    
    assertThrows(ProcessDeploymentException.class, () -> engine.deploy("this is no model"));
  }
  
  @Test
  public void testAnErrorDoesNotEndTheJvm() {
    // by default the log of MontiCore ends the JVM on the first error
    Log.enableFailQuick(true);
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(STRICT);
    final ProcessInstance instance = engine.start("Strict", Map.of("amount", 20));
    
    assertThrows(ProcessExecutionException.class, () -> engine.completeTask(instance.getId(),
        "Check"));
    // the setting of the application is as it was
    assertTrue(Log.isFailQuickEnabled());
    Log.enableFailQuick(false);
  }
  
  @Test
  public void testConstructsThatAreSimplifiedAreReported() {
    final WorkflowEngine engine = new WorkflowEngine();
    final var definition = engine.deploy("""
        process Limited {
          data n : int;
          start event Start;
          task Work;
          event Enough catch when [n > 3];
          end event Done;
          Start -> Work -> Enough -> Done;
        }
        """);
    
    assertEquals(1, definition.getLimitations().size());
    assertTrue(definition.getLimitations().get(0).startsWith("0xWFM9227"));
    assertTrue(engine.deploy(ORDER_V1).getLimitations().isEmpty());
  }
  
}
