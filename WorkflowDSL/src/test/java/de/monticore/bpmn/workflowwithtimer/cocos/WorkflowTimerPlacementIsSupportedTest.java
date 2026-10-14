/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.cocos;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import de.monticore.temporal.timetriggerconditions._cocos.AfterConditionHasValidDuration;
import de.monticore.temporal.timetriggerconditions._cocos.CronExpressionIsValid;
import de.monticore.bpmn.workflowwithtimer._cocos.WorkflowTimerPlacementIsSupported;
import de.monticore.bpmn.workflowwithtimer._cocos.WorkflowWithTimerCoCoChecker;
import de.monticore.bpmn.workflowwithtimer._cocos.WorkflowWithTimerCoCos;

public class WorkflowTimerPlacementIsSupportedTest extends AbstractWorkflowTimerCoCoTest {
  
  @Test
  public void testSupportedTimerStartConditionsPass() throws IOException {
    checkModelValid(startWorkflow("on 2026-07-20T12:00:00Z"));
    checkModelValid(startWorkflow("on 12:00:00Z"));
    checkModelValid(startWorkflow("cron [0 9 * * MON-FRI]"));
    checkModelValid(startWorkflow("from 2026-07-20T12:00:00Z every 10s"));
    checkModelValid(startWorkflow("from 2026-07-20T12:00:00Z every 10s 3 times"));
  }
  
  @Test
  public void testRelativeTimerStartConditionsAreRejected() throws IOException {
    checkModelInvalid(startWorkflow("after 10s"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_START_CONDITION);
    checkModelInvalid(startWorkflow("after PT10S"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_START_CONDITION);
    checkModelInvalid(startWorkflow("every 10s"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_START_CONDITION);
    checkModelInvalid(startWorkflow("every 10s 3 times"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_START_CONDITION);
  }
  
  @Test
  public void testInterruptingOneShotBoundaryConditionsPass() throws IOException {
    checkModelValid(taskBoundaryWorkflow("catch", "after 10s"));
    checkModelValid(taskBoundaryWorkflow("catch", "after PT10S"));
    checkModelValid(taskBoundaryWorkflow("catch", "on 2026-07-20T12:00:00Z"));
  }
  
  @Test
  public void testRecurringInterruptingBoundaryConditionsAreRejected() throws IOException {
    checkModelInvalid(taskBoundaryWorkflow("catch", "every 10s"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_BOUNDARY_CONDITION);
    checkModelInvalid(taskBoundaryWorkflow("catch", "cron [0 9 * * MON-FRI]"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_BOUNDARY_CONDITION);
  }
  
  @Test
  public void testAllConditionsPassOnNonInterruptingBoundaries() throws IOException {
    checkModelValid(taskBoundaryWorkflow("catch noninterrupt", "after 10s"));
    checkModelValid(taskBoundaryWorkflow("catch noninterrupt", "on 2026-07-20T12:00:00Z"));
    checkModelValid(taskBoundaryWorkflow("catch noninterrupt", "every 10s"));
    checkModelValid(taskBoundaryWorkflow("catch noninterrupt", "cron [0 9 * * MON-FRI]"));
  }
  
  @Test
  public void testThrowingBoundariesAreRejected() throws IOException {
    checkModelInvalid(taskBoundaryWorkflow("throw", "after 10s"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_TIMER_PLACEMENT);
  }
  
  @Test
  public void testAfterBoundaryIsSupportedOnAllActivityKinds() throws IOException {
    checkModelValid(subprocessBoundaryWorkflow());
    checkModelValid(callActivityBoundaryWorkflow());
  }
  
  @Test
  public void testTimerEndAndThrowEventsAreRejected() throws IOException {
    checkModelInvalid("""
        process InvalidEndTimer {
          start event Start;
          end event Done timer [cron [0 9 * * MON-FRI]];
          Start -> Done;
        }
        """, WorkflowTimerPlacementIsSupported.UNSUPPORTED_TIMER_PLACEMENT);
    checkModelInvalid("""
        process InvalidThrowTimer {
          start event Start;
          event Alarm throw timer [on 2026-07-20T12:00:00Z];
          end event Done;
          Start -> Alarm -> Done;
        }
        """, WorkflowTimerPlacementIsSupported.UNSUPPORTED_TIMER_PLACEMENT);
  }
  
  @Test
  public void testOneShotIntermediateCatchEventsPass() throws IOException {
    checkModelValid("""
        process IntermediateTimers {
          start event Start;
          event A catch timer [after 10s];
          event B catch timer [on 2026-07-20T12:00:00Z];
          end event Done;
          Start -> A -> B -> Done;
        }
        """);
  }
  
  @Test
  public void testRecurringIntermediateCatchEventsAreRejected() throws IOException {
    checkModelInvalid(intermediateWorkflow("every 10s"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_INTERMEDIATE_CONDITION);
    checkModelInvalid(intermediateWorkflow("cron [0 9 * * MON-FRI]"),
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_INTERMEDIATE_CONDITION);
  }
  
  @Test
  public void testRecurringIntermediateCatchInLaneIsRejected() throws IOException {
    checkModelInvalid("""
        process LaneTimer {
          start event Start;
          lane WorkLane {
            event Reminder catch timer [every 10s];
          }
          end event Done;
        }
        """, WorkflowTimerPlacementIsSupported.UNSUPPORTED_INTERMEDIATE_CONDITION);
  }
  
  @Test
  public void testHostFactoryAlsoRunsConditionValidation() throws IOException {
    checkModelInvalid(taskBoundaryWorkflow("catch", "after 0s"),
        AfterConditionHasValidDuration.NON_POSITIVE_DURATION);
    checkModelInvalid(startWorkflow("cron [*/0 * * * *]"), CronExpressionIsValid.INVALID_STEP);
  }
  
  protected String startWorkflow(String condition) {
    return """
        process TimerStart {
          start event Start timer [%s];
          end event Done;
          Start -> Done;
        }
        """.formatted(condition);
  }
  
  protected String taskBoundaryWorkflow(String eventKind, String condition) {
    return """
        process BoundaryTimer {
          start event Start;
          task Work {
            boundary event Timeout %s timer [%s];
          }
          end event Done;
          Start -> Work -> Done;
          Timeout -> Done;
        }
        """.formatted(eventKind, condition);
  }
  
  protected String intermediateWorkflow(String condition) {
    return """
        process IntermediateTimer {
          start event Start;
          event Wait catch timer [%s];
          end event Done;
          Start -> Wait -> Done;
        }
        """.formatted(condition);
  }
  
  protected String subprocessBoundaryWorkflow() {
    return """
        process SubprocessBoundaryTimer {
          start event Start;
          subprocess Work {
            event Inner catch timer [after 1s];
            boundary event Timeout catch timer [after 10s];
          }
          end event Done;
          Start -> Work -> Done;
          Timeout -> Done;
        }
        """;
  }
  
  protected String callActivityBoundaryWorkflow() {
    return """
        process CallActivityBoundaryTimer {
          start event Start;
          call-activity Work calls CallActivityBoundaryTimer {
            boundary event Timeout catch timer [after 10s];
          }
          end event Done;
          Start -> Work -> Done;
          Timeout -> Done;
        }
        """;
  }
  
  /** The placement rules are only meaningful together with the condition CoCos. */
  @Override
  protected WorkflowWithTimerCoCoChecker checker() {
    return WorkflowWithTimerCoCos.createChecker();
  }
  
}
