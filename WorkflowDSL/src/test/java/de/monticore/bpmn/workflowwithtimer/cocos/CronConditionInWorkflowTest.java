/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.cocos;

import org.junit.jupiter.api.Test;
import de.monticore.temporal.timetriggerconditions._cocos.CronExpressionIsValid;
import de.monticore.bpmn.workflowwithtimer._cocos.WorkflowWithTimerCoCoChecker;

import java.io.IOException;

public class CronConditionInWorkflowTest extends AbstractWorkflowTimerCoCoTest {
  
  @Test
  public void testValidCronConditionPasses() throws IOException {
    checkValid("cron [*/15 9 * JAN-MAR MON-FRI]");
  }
  
  @Test
  public void testInvalidCronConditionIsRejected() throws IOException {
    checkInvalid("cron [60 3 * * 1-5]", CronExpressionIsValid.INVALID_FIELD_VALUE);
  }
  
  @Test
  public void testInvalidStepIsRejected() throws IOException {
    checkInvalid("cron [*/0 * * * *]", CronExpressionIsValid.INVALID_STEP);
  }
  
  @Test
  public void testCalendarImpossibleScheduleProducesWarning() throws IOException {
    checkWarning("cron [0 0 31 2 *]", CronExpressionIsValid.IMPOSSIBLE_DATE);
  }
  
  @Override
  protected WorkflowWithTimerCoCoChecker checker() {
    WorkflowWithTimerCoCoChecker checker = new WorkflowWithTimerCoCoChecker();
    checker.addCoCo(new CronExpressionIsValid());
    return checker;
  }
  
}
