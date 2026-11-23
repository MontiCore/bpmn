/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.history.HistoryEvent;
import de.monticore.bpmn.engine.history.InMemoryHistoryLog;
import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.instance.ProcessInstanceStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The audit trail records every execution of an activity. */
public class HistoryTest extends AbstractEngineTest {
  
  protected List<String> trail(final WorkflowEngine engine, final String instanceId) {
    return engine.getHistory().findByInstanceId(instanceId).stream().map(e -> e.getType() + " " + e
        .getElementName()).collect(Collectors.toList());
  }
  
  protected long count(final List<String> trail, final String entry) {
    return trail.stream().filter(entry::equals).count();
  }
  
  @Test
  public void testEveryIterationOfALoopIsRecorded() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Polishing {
          data shiny : boolean;
          start event Start;
          task Polish until [shiny] max 5;
          end event Done;
          Start -> Polish -> Done;
        }
        """);
    
    ProcessInstance instance = engine.start("Polishing", Map.of("shiny", false));
    instance = engine.completeTask(instance.getId(), "Polish");
    instance = engine.completeTask(instance.getId(), "Polish");
    instance = engine.completeTask(instance.getId(), "Polish", Map.of("shiny", true));
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
    
    final List<String> trail = trail(engine, instance.getId());
    assertEquals(3, count(trail, "ACTIVITY_STARTED Polish"));
    assertEquals(3, count(trail, "ACTIVITY_ENDED Polish"));
  }
  
  @Test
  public void testAnActivityThatIsReachedAgainInTheSameStepIsRecordedAgain() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Retry {
          data ok : boolean;
          start event Start;
          task Try;
          split xor Ok;
          end event Done;
          Start -> Try -> Ok;
          Ok -> [ok] Done;
          Ok -> [_] Try;
        }
        """);
    
    ProcessInstance instance = engine.start("Retry");
    instance = engine.completeTask(instance.getId(), "Try", Map.of("ok", false));
    instance = engine.completeTask(instance.getId(), "Try", Map.of("ok", true));
    assertEquals(ProcessInstanceStatus.COMPLETED, instance.getStatus());
    
    assertEquals(List.of("INSTANCE_STARTED ", "EVENT_THROWN Start", "ACTIVITY_STARTED Try",
        "ACTIVITY_ENDED Try", "ACTIVITY_STARTED Try", "ACTIVITY_ENDED Try", "EVENT_THROWN Done",
        "INSTANCE_COMPLETED "), trail(engine, instance.getId()));
  }
  
  @Test
  public void testAnInterruptedActivityIsRecordedAsCancelled() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Stop {
          message stop : String;
          start event Start;
          split and Fork;
          task Work {
            boundary event Stopped catch message stop;
          }
          task Watch;
          end event Done;
          end event Killed terminate;
          Start -> Fork;
          Fork -> Work -> Done;
          Fork -> Watch -> Done;
          Stopped -> Killed;
        }
        """);
    
    ProcessInstance instance = engine.start("Stop");
    instance = engine.triggerEvent(instance.getId(), "Stopped");
    assertEquals(ProcessInstanceStatus.TERMINATED, instance.getStatus());
    
    final List<String> trail = trail(engine, instance.getId());
    assertTrue(trail.contains("ACTIVITY_CANCELLED Work"));
    assertTrue(trail.contains("ACTIVITY_CANCELLED Watch"));
    assertEquals("INSTANCE_TERMINATED ", trail.get(trail.size() - 1));
    assertEquals(0, count(trail, "ACTIVITY_ENDED Work"));
  }
  
  @Test
  public void testTheHistoryCanBeReadWhileItIsWritten() throws InterruptedException {
    final InMemoryHistoryLog history = new InMemoryHistoryLog();
    final int events = 20000;
    final AtomicInteger written = new AtomicInteger();
    final Thread writer = new Thread(() -> {
      for (int i = 0; i < events; i++) {
        history.append(new HistoryEvent("instance" + i % 10, HistoryEvent.Type.ACTIVITY_STARTED,
            "Work", Instant.EPOCH));
        written.incrementAndGet();
      }
    });
    
    writer.start();
    final List<RuntimeException> failures = new ArrayList<>();
    int read = 0;
    while (writer.isAlive() && failures.isEmpty()) {
      // reading again at once would keep the writer waiting for the lock
      if (written.get() < read + 100) {
        Thread.yield();
        continue;
      }
      read = written.get();
      try {
        history.findByInstanceId("instance1");
        history.findAll();
      }
      catch (RuntimeException e) {
        failures.add(e);
      }
    }
    writer.join();
    
    assertTrue(failures.isEmpty(), "Reading the history failed: " + failures);
    assertEquals(events, history.findAll().size());
  }
  
}
