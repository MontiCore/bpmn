/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.cocos;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import de.monticore.temporal.timetriggerconditions._cocos.OnConditionHasValidValue;
import de.monticore.bpmn.workflowwithtimer._cocos.WorkflowWithTimerCoCoChecker;

import java.io.IOException;

public class OnConditionInWorkflowTest extends AbstractWorkflowTimerCoCoTest {
  
  @ParameterizedTest(name = "valid on condition in a workflow: {0}")
  @ValueSource(strings = { "on 2024-02-29T12:00:00Z", "on 2026-07-20T12:00:00+02:00",
      "on 2026-07-20T12:00:00+00:00" })
  public void testValidDateTimesPass(String condition) throws IOException {
    checkValid(condition);
  }
  
  @ParameterizedTest(name = "invalid on condition in a workflow: {0}")
  @ValueSource(strings = { "on 2025-02-29T12:00:00Z", "on 2026-07-20T12:00:00" })
  public void testInvalidDateTimesAreRejected(String condition) throws IOException {
    checkInvalid(condition, OnConditionHasValidValue.INVALID_DATE_TIME);
  }
  
  @Override
  protected WorkflowWithTimerCoCoChecker checker() {
    WorkflowWithTimerCoCoChecker checker = new WorkflowWithTimerCoCoChecker();
    checker.addCoCo(new OnConditionHasValidValue());
    return checker;
  }
  
}
