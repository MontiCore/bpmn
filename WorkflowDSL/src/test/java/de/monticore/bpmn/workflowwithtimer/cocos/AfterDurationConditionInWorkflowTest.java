/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.cocos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import de.monticore.temporal.timetriggerconditions._cocos.AfterConditionHasValidDuration;
import de.monticore.temporal.timetriggerconditions._cocos.AfterConditionHasValidPeriod;
import de.monticore.bpmn.workflowwithtimer._cocos.WorkflowWithTimerCoCoChecker;

import java.io.IOException;

public class AfterDurationConditionInWorkflowTest extends AbstractWorkflowTimerCoCoTest {
  
  @Test
  public void testValidAfterConditionPasses() throws IOException {
    checkValid("after 10s");
  }
  
  @Test
  public void testZeroDurationIsRejected() throws IOException {
    checkInvalid("after 0s", AfterConditionHasValidDuration.NON_POSITIVE_DURATION);
  }
  
  @Test
  public void testNonTimeUnitIsRejected() throws IOException {
    checkInvalid("after 10m", AfterConditionHasValidDuration.UNSUPPORTED_UNIT);
  }
  
  @ParameterizedTest(name = "valid ISO period in a workflow: {0}")
  @ValueSource(strings = { "after P1Y", "after P2M", "after PT10S", "after P2D", "after PT1H",
      "after PT5M", "after P1W", "after PT0.5S" })
  public void testValidIsoPeriodsPass(String condition) throws IOException {
    checkValid(condition);
  }
  
  @ParameterizedTest(name = "non-positive ISO period in a workflow: {0}")
  @ValueSource(strings = { "after P", "after PT", "after PT0S", "after PT0.0S", "after P0D",
      "after P0W" })
  public void testZeroIsoPeriodsAreRejected(String condition) throws IOException {
    checkInvalid(condition, AfterConditionHasValidPeriod.NON_POSITIVE_PERIOD);
  }
  
  @Override
  protected WorkflowWithTimerCoCoChecker checker() {
    WorkflowWithTimerCoCoChecker checker = new WorkflowWithTimerCoCoChecker();
    checker.addCoCo(new AfterConditionHasValidDuration());
    checker.addCoCo(new AfterConditionHasValidPeriod());
    return checker;
  }
  
}
