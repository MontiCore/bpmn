/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.definition.InMemoryProcessDefinitionRepository;
import de.monticore.bpmn.engine.history.InMemoryHistoryLog;
import de.monticore.bpmn.engine.instance.InMemoryProcessInstanceRepository;
import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.instance.ProcessInstanceStatus;
import de.monticore.bpmn.engine.job.InMemoryJobRepository;
import de.monticore.bpmn.engine.job.Job;
import de.monticore.bpmn.engine.job.TimerCalculator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TimerJobTest extends AbstractEngineTest {
  
  protected static final Instant START = Instant.parse("2026-01-01T08:00:00Z");
  
  protected static final String REMINDER = """
      package de.monticore.bpmn.engine;
      
      process Reminder {
        start event Requested;
        task SendOffer;
        event GracePeriod catch timer [after P1D];
        task CloseOffer;
        end event Closed;
      
        Requested -> SendOffer -> GracePeriod -> CloseOffer -> Closed;
      }
      """;
  
  protected WorkflowEngine engineAt(final Instant now) {
    return new WorkflowEngine().withClock(Clock.fixed(now, ZoneOffset.UTC));
  }
  
  @Test
  public void testWaitingForATimerCreatesAJob() {
    final WorkflowEngine engine = engineAt(START);
    engine.deploy(REMINDER);
    
    ProcessInstance instance = engine.start("Reminder");
    assertTrue(engine.getJobs().findAll().isEmpty()); // nothing waits for a timer yet
    
    instance = engine.completeTask(instance.getId(), "SendOffer");
    
    // the instance waits for the timer and does not occupy anything but a row in the job table
    assertEquals(List.of("GracePeriod"), engine.getEnabledSignals(instance.getId()));
    final List<Job> jobs = engine.getJobs().findAll();
    assertEquals(1, jobs.size());
    assertEquals("GracePeriod", jobs.get(0).getEventName());
    assertEquals(START.plus(Duration.ofDays(1)), jobs.get(0).getDueAt());
  }
  
  @Test
  public void testADueJobContinuesTheInstance() {
    final WorkflowEngine engine = engineAt(START);
    engine.deploy(REMINDER);
    
    final ProcessInstance instance = engine.start("Reminder");
    engine.completeTask(instance.getId(), "SendOffer");
    
    // nothing is due one hour later
    assertTrue(engine.executeDueJobs(START.plus(Duration.ofHours(1))).isEmpty());
    assertEquals(List.of("GracePeriod"), engine.getEnabledSignals(instance.getId()));
    
    // one day later the timer elapsed
    final List<Job> executed = engine.executeDueJobs(START.plus(Duration.ofDays(1)));
    assertEquals(1, executed.size());
    assertEquals(List.of("CloseOffer"), engine.getEnabledSignals(instance.getId()));
    
    // the job is done and has been removed
    assertTrue(engine.getJobs().findAll().isEmpty());
  }
  
  @Test
  public void testJobsAreRemovedWhenTheInstanceEnds() {
    final WorkflowEngine engine = engineAt(START);
    engine.deploy(REMINDER);
    
    final ProcessInstance instance = engine.start("Reminder");
    engine.completeTask(instance.getId(), "SendOffer");
    engine.triggerEvent(instance.getId(), "GracePeriod"); // triggered by hand instead
    final ProcessInstance finished = engine.completeTask(instance.getId(), "CloseOffer");
    
    assertEquals(ProcessInstanceStatus.COMPLETED, finished.getStatus());
    assertTrue(engine.getJobs().findByInstanceId(instance.getId()).isEmpty());
  }
  
  @Test
  public void testABoundaryTimerCancelsTheActivity() {
    final String withTimeout = """
        package de.monticore.bpmn.engine;
        
        process Approval {
          start event Requested;
          user task Approve {
            webservice = ##unspecified;
            boundary event Timeout catch timer [after PT2H];
          }
          task Escalate;
          end event Granted;
          end event Escalated;
        
          Requested -> Approve -> Granted;
          Timeout -> Escalate -> Escalated;
        }
        """;
    final WorkflowEngine engine = engineAt(START);
    engine.deploy(withTimeout);
    
    final ProcessInstance instance = engine.start("Approval");
    // the task can be completed and its boundary timer can occur
    assertEquals(List.of("Approve", "Timeout"), engine.getEnabledSignals(instance.getId()));
    assertEquals(List.of("Approve"), engine.getOpenActivities(instance.getId()));
    
    // the boundary timer is armed while the activity runs
    final List<Job> jobs = engine.getJobs().findAll();
    assertEquals(1, jobs.size());
    assertEquals(START.plus(Duration.ofHours(2)), jobs.get(0).getDueAt());
    
    engine.executeDueJobs(START.plus(Duration.ofHours(2)));
    
    // the interrupting boundary event cancelled the activity and took the other path
    assertEquals(List.of("Escalate"), engine.getEnabledSignals(instance.getId()));
  }
  
  protected static final String REMINDING = """
      package de.monticore.bpmn.engine;
      
      process Reminding {
        start event Requested;
        task Approve {
          boundary event Remind catch noninterrupt timer [after PT1H];
        }
        task SendReminder;
        end event Granted;
        end event Reminded;
      
        Requested -> Approve -> Granted;
        Remind -> SendReminder -> Reminded;
      }
      """;
  
  @Test
  public void testANonInterruptingBoundaryTimerOccursOnce() {
    final WorkflowEngine engine = engineAt(START);
    engine.deploy(REMINDING);
    final ProcessInstance instance = engine.start("Reminding");
    
    assertEquals(1, engine.executeDueJobs(START.plus(Duration.ofHours(1))).size());
    assertEquals(List.of("Approve", "SendReminder"), engine.getOpenActivities(instance.getId()));
    
    // the activity still runs, but the timer has occurred and does not occur again
    assertTrue(engine.getJobs().findAll().isEmpty());
    assertTrue(engine.executeDueJobs(START.plus(Duration.ofHours(2))).isEmpty());
    assertTrue(engine.executeDueJobs(START.plus(Duration.ofDays(9))).isEmpty());
    assertEquals(List.of("Approve", "SendReminder"), engine.getOpenActivities(instance.getId()));
    assertEquals(1, engine.getHistory().findByInstanceId(instance.getId()).stream().filter(e -> e
        .getElementName().equals("Remind")).count());
  }
  
  @Test
  public void testARepeatingTimerGetsAJobForEveryOccurrence() {
    final WorkflowEngine engine = engineAt(START);
    engine.deploy(REMINDING.replace("after PT1H", "2 times every PT1H"));
    final ProcessInstance instance = engine.start("Reminding");
    
    assertEquals(START.plus(Duration.ofHours(1)), engine.getJobs().findAll().get(0).getDueAt());
    assertEquals(1, engine.executeDueJobs(START.plus(Duration.ofHours(1))).size());
    
    // the second occurrence is scheduled one period after the first one
    assertEquals(START.plus(Duration.ofHours(2)), engine.getJobs().findAll().get(0).getDueAt());
    engine.completeTask(instance.getId(), "SendReminder");
    assertEquals(1, engine.executeDueJobs(START.plus(Duration.ofHours(2))).size());
    
    // the timer was limited to two occurrences
    assertTrue(engine.getJobs().findAll().isEmpty());
    assertTrue(engine.executeDueJobs(START.plus(Duration.ofHours(3))).isEmpty());
  }
  
  @Test
  public void testATimerRunsAnewWhenItsActivityStartsAgain() {
    final WorkflowEngine engine = new WorkflowEngine();
    engine.deploy("""
        process Rework {
          data accepted : boolean;
          start event Start;
          task Work {
            boundary event Late catch noninterrupt timer [after P1D];
          }
          task Remind;
          split xor Accepted;
          end event Done;
          end event Reminded;
          Start -> Work -> Accepted;
          Accepted -> [accepted] Done;
          Accepted -> [_] Work;
          Late -> Remind -> Reminded;
        }
        """);
    final MutableClock clock = new MutableClock(START);
    engine.withClock(clock);
    final ProcessInstance instance = engine.start("Rework");
    assertEquals(START.plus(Duration.ofDays(1)), engine.getJobs().findAll().get(0).getDueAt());
    
    // the activity ends and starts again in one step, half a day later
    clock.now = START.plus(Duration.ofHours(12));
    engine.completeTask(instance.getId(), "Work", Map.of("accepted", false));
    
    assertEquals(List.of("Work"), engine.getOpenActivities(instance.getId()));
    assertEquals(START.plus(Duration.ofHours(36)), engine.getJobs().findAll().get(0).getDueAt());
  }
  
  @Test
  public void testAJobThatFailsIsRetriedAndThenKept() {
    final WorkflowEngine engine = engineAt(START);
    engine.deploy("""
        process Fragile {
          data ready : boolean;
          start event Start;
          event Later catch timer [after PT1H];
          split xor Ready;
          task Go;
          task Never;
          end event Done;
          Start -> Later -> Ready;
          Ready -> [ready] Go -> Done;
          Ready -> [ready && !ready] Never -> Done;
        }
        """);
    final ProcessInstance instance = engine.start("Fragile", Map.of("ready", false));
    final Instant later = START.plus(Duration.ofHours(1));
    
    // the timer occurs, but the gateway behind it has no enabled flow: nothing is executed
    for (int attempt = 1; attempt <= Job.DEFAULT_RETRIES; attempt++) {
      assertTrue(engine.executeDueJobs(later).isEmpty());
      assertEquals(Job.DEFAULT_RETRIES - attempt, engine.getJobs().findAll().get(0).getRetries());
    }
    final Job failed = engine.getJobs().findAll().get(0);
    assertTrue(failed.getFailure().orElse("").contains("0xWFM9208"));
    assertEquals(List.of("Later"), engine.getEnabledSignals(instance.getId()));
    
    // the job is not tried again on its own, but the event can still be triggered
    assertTrue(engine.executeDueJobs(later).isEmpty());
    engine.setVariables(instance.getId(), Map.of("ready", true));
    engine.triggerEvent(instance.getId(), "Later");
    assertEquals(List.of("Go"), engine.getEnabledSignals(instance.getId()));
    assertTrue(engine.getJobs().findAll().isEmpty());
  }
  
  @Test
  public void testTimersWorkInEveryTimeZone() {
    for (ZoneId zone : List.of(ZoneOffset.UTC, ZoneOffset.ofHours(2), ZoneId.of("UTC"), ZoneId.of(
        "Europe/Berlin"))) {
      final WorkflowEngine engine = new WorkflowEngine(new ProcessModelLoader(),
          new InMemoryProcessDefinitionRepository(), new InMemoryProcessInstanceRepository(),
          new InMemoryJobRepository(), new InMemoryHistoryLog(), new TimerCalculator(zone))
          .withClock(Clock.fixed(START, ZoneOffset.UTC));
      engine.deploy(REMINDER);
      final ProcessInstance instance = engine.start("Reminder");
      engine.completeTask(instance.getId(), "SendOffer");
      assertEquals(START.plus(Duration.ofDays(1)), engine.getJobs().findAll().get(0).getDueAt());
    }
  }
  
  /** A clock that the test moves. */
  protected static class MutableClock extends Clock {
    
    protected Instant now;
    
    protected MutableClock(final Instant now) { this.now = now; }
    
    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }
    
    @Override
    public Clock withZone(final ZoneId zone) { return this; }
    
    @Override
    public Instant instant() { return now; }
    
  }
  
}
