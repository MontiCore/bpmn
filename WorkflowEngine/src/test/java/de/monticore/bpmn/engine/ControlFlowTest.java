/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.history.HistoryEvent;
import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.instance.ProcessInstanceStatus;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The constructs of the Workflow language beyond sequences and exclusive gateways. */
public class ControlFlowTest extends AbstractEngineTest {
  
  protected List<String> trail(final WorkflowEngine engine, final String instanceId) {
    return engine.getHistory().findByInstanceId(instanceId).stream().map(e -> e.getType() + " " + e
        .getElementName()).collect(Collectors.toList());
  }
  
  @Test
  public void testAStartEventInALaneStartsTheProcess() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Leave {
          lane Office {
            start event Requested;
            task Approve {
              boundary event Timeout catch timer [after PT2H];
            }
            task Escalate;
            end event Granted;
            end event Escalated;
            Requested -> Approve -> Granted;
            Timeout -> Escalate -> Escalated;
          }
        }
        """);
    
    ProcessInstance instance = engine.start("Leave");
    // only the start event fired; the boundary event does not wait on its own
    assertEquals(List.of("Approve", "Timeout"), engine.getEnabledSignals(instance.getId()));
    assertEquals(List.of("Approve"), instance.getState().getRunning());
    assertTrue(instance.getState().getWaiting().isEmpty());
    
    instance = engine.completeTask(instance.getId(), "Approve");
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
    assertTrue(engine.getJobs().findAll().isEmpty());
  }
  
  @Test
  public void testAStartEventWithATriggerWaitsAndIsAnAlternative() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Intake {
          message letter : String;
          message call : String;
          lane Desk {
            start event ByLetter catch message letter;
            start event ByCall catch message call;
            task Register;
            end event Done;
            ByLetter -> Register;
            ByCall -> Register;
            Register -> Done;
          }
        }
        """);
    
    ProcessInstance instance = engine.start("Intake");
    assertEquals(List.of("ByLetter", "ByCall"), engine.getEnabledSignals(instance.getId()));
    
    // the first start event that occurs starts the instance, the other one is dropped
    instance = engine.triggerEvent(instance.getId(), "ByCall");
    assertEquals(List.of("Register"), engine.getEnabledSignals(instance.getId()));
    
    instance = engine.completeTask(instance.getId(), "Register");
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
  }
  
  protected static final String VALIDATION = """
      process Validation {
        data valid : boolean;
        error invalidInput : String;
      
        start event Submitted;
        subprocess Validate {
          start event Begin;
          task CheckData;
          split xor Result;
          end event Passed;
          end event Invalid throw error invalidInput;
          Begin -> CheckData -> Result;
          Result -> [valid] Passed;
          Result -> [_] Invalid;
          boundary event OnInvalid catch error invalidInput;
        }
        task Persist;
        task Fix;
        end event Accepted;
        end event Rejected;
      
        Submitted -> Validate -> Persist -> Accepted;
        Validate.OnInvalid -> Fix -> Rejected;
      }
      """;
  
  @Test
  public void testASubProcessRunsItsInnerFlowAndCompletesOnItsOwn() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(VALIDATION);
    
    ProcessInstance instance = engine.start("Validation");
    // the inner task is open, the sub-process itself cannot be completed from outside
    assertEquals(List.of("Validate.CheckData"), engine.getOpenActivities(instance.getId()));
    assertEquals(List.of("Validate", "Validate.CheckData"), instance.getState().getRunning());
    final String id = instance.getId();
    assertThrows(ProcessExecutionException.class, () -> engine.completeTask(id, "Validate"));
    
    // a plain name is enough as long as it is unique in the process
    instance = engine.completeTask(id, "CheckData", Map.of("valid", true));
    assertEquals(List.of("Persist"), engine.getEnabledSignals(id));
    assertTrue(trail(engine, id).containsAll(List.of("ACTIVITY_STARTED Validate",
        "EVENT_THROWN Validate.Begin", "ACTIVITY_ENDED Validate.CheckData",
        "EVENT_THROWN Validate.Passed", "ACTIVITY_ENDED Validate")));
    
    instance = engine.completeTask(id, "Persist");
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
  }
  
  @Test
  public void testAnErrorEndEventIsCaughtByTheBoundaryEventOfItsSubProcess() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy(VALIDATION);
    
    ProcessInstance instance = engine.start("Validation");
    instance = engine.completeTask(instance.getId(), "Validate.CheckData", Map.of("valid", false));
    
    // the error interrupted the sub-process and left it via the boundary event
    assertEquals(List.of("Fix"), engine.getEnabledSignals(instance.getId()));
    final List<String> trail = trail(engine, instance.getId());
    assertTrue(trail.contains("EVENT_THROWN Validate.Invalid"));
    assertTrue(trail.contains("ACTIVITY_CANCELLED Validate"));
    assertTrue(trail.contains("EVENT_THROWN Validate.OnInvalid"));
    
    instance = engine.completeTask(instance.getId(), "Fix");
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
  }
  
  @Test
  public void testAnErrorThatNobodyCatchesFailsTheInstance() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Import {
          error broken : String;
          start event Start;
          subprocess Read {
            start event Begin;
            task Parse;
            end event Broken throw error broken;
            Begin -> Parse -> Broken;
          }
          task Store;
          task Watch;
          split and Fork;
          end event Done;
          Start -> Fork;
          Fork -> Read -> Store -> Done;
          Fork -> Watch -> Done;
        }
        """);
    
    ProcessInstance instance = engine.start("Import");
    instance = engine.completeTask(instance.getId(), "Parse");
    
    assertEquals(ProcessInstanceStatus.FAILED, instance.getStatus());
    assertTrue(instance.getFailure().orElse("").contains("broken"));
    assertTrue(engine.getEnabledSignals(instance.getId()).isEmpty());
    final List<HistoryEvent> history = engine.getHistory().findByInstanceId(instance.getId());
    assertEquals(HistoryEvent.Type.INSTANCE_FAILED, history.get(history.size() - 1).getType());
    // everything that was still active has been cancelled
    assertTrue(trail(engine, instance.getId()).contains("ACTIVITY_CANCELLED Watch"));
  }
  
  @Test
  public void testATerminateEventInASubProcessOnlyEndsThatSubProcess() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Search {
          start event Start;
          subprocess Lookup {
            start event Begin;
            split and Both;
            task AskArchive;
            task AskLibrary;
            end event Found terminate;
            Begin -> Both;
            Both -> AskArchive -> Found;
            Both -> AskLibrary -> Found;
          }
          task Report;
          end event Done;
          Start -> Lookup -> Report -> Done;
        }
        """);
    
    ProcessInstance instance = engine.start("Search");
    assertEquals(List.of("Lookup.AskArchive", "Lookup.AskLibrary"), engine.getOpenActivities(
        instance.getId()));
    
    // the first answer ends the sub-process, the other request is cancelled
    instance = engine.completeTask(instance.getId(), "AskLibrary");
    assertEquals(ProcessInstanceStatus.ACTIVE, instance.getStatus());
    assertEquals(List.of("Report"), engine.getEnabledSignals(instance.getId()));
    assertTrue(trail(engine, instance.getId()).contains("ACTIVITY_CANCELLED Lookup.AskArchive"));
  }
  
  @Test
  public void testAMultiInstanceActivityNeedsOneCompletionPerInstance() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Reviewing {
          data reviewers : int;
          start event Start;
          task Review count [reviewers] parallel;
          end event Done;
          Start -> Review -> Done;
        }
        """);
    
    ProcessInstance instance = engine.start("Reviewing", Map.of("reviewers", 3));
    instance = engine.completeTask(instance.getId(), "Review");
    instance = engine.completeTask(instance.getId(), "Review");
    assertEquals(List.of("Review"), engine.getEnabledSignals(instance.getId()));
    
    instance = engine.completeTask(instance.getId(), "Review");
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
    
    // without any instance the activity is skipped
    final ProcessInstance none = engine.start("Reviewing", Map.of("reviewers", 0));
    assertEquals(ProcessInstanceStatus.COMPLETED, none.getStatus());
  }
  
  @Test
  public void testAnInclusiveMergeWaitsForAllBranchesThatWereTaken() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Extras {
          data express : boolean;
          data fragile : boolean;
          start event Start;
          split ior Choose;
          task BookCourier;
          task AddPadding;
          task Standard;
          merge ior Chosen;
          task HandOver;
          end event Done;
          Start -> Choose;
          Choose -> [express] BookCourier -> Chosen;
          Choose -> [fragile] AddPadding -> Chosen;
          Choose -> [_] Standard -> Chosen;
          Chosen -> HandOver -> Done;
        }
        """);
    
    ProcessInstance both = engine.start("Extras", Map.of("express", true, "fragile", true));
    assertEquals(List.of("BookCourier", "AddPadding"), engine.getEnabledSignals(both.getId()));
    both = engine.completeTask(both.getId(), "BookCourier");
    // the merge does not pass the first token on, the other branch is still on its way
    assertEquals(List.of("AddPadding"), engine.getEnabledSignals(both.getId()));
    both = engine.completeTask(both.getId(), "AddPadding");
    assertEquals(List.of("HandOver"), engine.getEnabledSignals(both.getId()));
    both = engine.completeTask(both.getId(), "HandOver");
    assertEquals(ProcessInstanceStatus.COMPLETED, both.getStatus());
    assertEquals(1, trail(engine, both.getId()).stream().filter("ACTIVITY_STARTED HandOver"::equals)
        .count());
    
    // with one branch only there is nothing to wait for
    ProcessInstance one = engine.start("Extras", Map.of("express", false, "fragile", true));
    one = engine.completeTask(one.getId(), "AddPadding");
    assertEquals(List.of("HandOver"), engine.getEnabledSignals(one.getId()));
  }
  
  @Test
  public void testTheFirstReceiveTaskAfterAnEventBasedGatewayCancelsTheOthers() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Answer {
          message call : String;
          operation takeCall(in call);
          start event Asked;
          split receive first Wait;
          receive task GetCall {
            webservice = ##unspecified;
            operation = takeCall;
            message = call;
          }
          event TooLate catch timer [after P1D];
          task Note;
          end event Done;
          Asked -> Wait;
          Wait -> GetCall -> Note;
          Wait -> TooLate -> Note;
          Note -> Done;
        }
        """);
    
    ProcessInstance instance = engine.start("Answer");
    assertEquals(List.of("GetCall", "TooLate"), engine.getEnabledSignals(instance.getId()));
    
    instance = engine.completeTask(instance.getId(), "GetCall");
    // the timer is not awaited anymore and has lost its job
    assertEquals(List.of("Note"), engine.getEnabledSignals(instance.getId()));
    assertTrue(engine.getJobs().findByInstanceId(instance.getId()).isEmpty());
    
    final ProcessInstance other = engine.start("Answer");
    engine.triggerEvent(other.getId(), "TooLate");
    assertEquals(List.of("Note"), engine.getEnabledSignals(other.getId()));
    assertTrue(trail(engine, other.getId()).contains("ACTIVITY_CANCELLED GetCall"));
  }
  
  @Test
  public void testConditionalFlowsOfAnActivityAreEvaluated() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Delivery {
          data pickedUp : boolean;
          start event Start;
          task Pack;
          task HandOver;
          task Ship;
          end event Done;
          Start -> Pack -> { [pickedUp] HandOver; [_] Ship; } -> Done;
        }
        """);
    
    final ProcessInstance shipped = engine.start("Delivery", Map.of("pickedUp", false));
    engine.completeTask(shipped.getId(), "Pack");
    assertEquals(List.of("Ship"), engine.getEnabledSignals(shipped.getId()));
    
    final ProcessInstance collected = engine.start("Delivery", Map.of("pickedUp", true));
    engine.completeTask(collected.getId(), "Pack");
    assertEquals(List.of("HandOver"), engine.getEnabledSignals(collected.getId()));
  }
  
  @Test
  public void testSignalCompletesAnActivityOrTriggersAnEvent() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Mixed {
          message go : String;
          start event Start;
          task Prepare;
          event Go catch message go;
          end event Done;
          Start -> Prepare -> Go -> Done;
        }
        """);
    
    ProcessInstance instance = engine.start("Mixed");
    instance = engine.signal(instance.getId(), "Prepare", Map.of());
    assertEquals(List.of("Go"), engine.getOpenEvents(instance.getId()));
    instance = engine.signal(instance.getId(), "Go", Map.of());
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
  }
  
}
