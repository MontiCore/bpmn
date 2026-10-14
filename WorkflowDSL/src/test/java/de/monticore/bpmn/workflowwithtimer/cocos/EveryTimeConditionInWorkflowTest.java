/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.cocos;

import org.junit.jupiter.api.Test;
import de.monticore.temporal.timetriggerconditions._cocos.EveryTimeConditionHasValidRepetitions;
import de.monticore.bpmn.workflowwithtimer._cocos.WorkflowWithTimerCoCoChecker;

import java.io.IOException;

public class EveryTimeConditionInWorkflowTest extends AbstractWorkflowTimerCoCoTest {
  
  @Test
  public void testPositiveRepetitionsPass() throws IOException {
    checkValid("every 10s 3 times");
  }
  
  @Test
  public void testZeroRepetitionsAreRejected() throws IOException {
    checkInvalid("every 10s 0 times",
        EveryTimeConditionHasValidRepetitions.NON_POSITIVE_REPETITIONS);
  }
  
  @Override
  protected WorkflowWithTimerCoCoChecker checker() {
    WorkflowWithTimerCoCoChecker checker = new WorkflowWithTimerCoCoChecker();
    checker.addCoCo(new EveryTimeConditionHasValidRepetitions());
    return checker;
  }
  
}
