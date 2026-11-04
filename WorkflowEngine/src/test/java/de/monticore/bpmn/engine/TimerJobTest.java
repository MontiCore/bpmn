/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.instance.ProcessInstanceStatus;
import de.monticore.bpmn.engine.job.Job;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
    assertEquals(List.of("Approve"), engine.getEnabledSignals(instance.getId()));
    
    // the boundary timer is armed while the activity runs
    final List<Job> jobs = engine.getJobs().findAll();
    assertEquals(1, jobs.size());
    assertEquals(START.plus(Duration.ofHours(2)), jobs.get(0).getDueAt());
    
    engine.executeDueJobs(START.plus(Duration.ofHours(2)));
    
    // the interrupting boundary event cancelled the activity and took the other path
    assertEquals(List.of("Escalate"), engine.getEnabledSignals(instance.getId()));
  }
  
}
