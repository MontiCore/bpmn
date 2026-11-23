/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.parser;

import de.monticore.bpmn.workflow.WorkflowMill;
import de.monticore.bpmn.workflow._parser.WorkflowParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkflowBaselineParserTest {
  
  @BeforeEach
  public void setUp() {
    WorkflowMill.reset();
    WorkflowMill.init();
  }
  
  @Test
  public void testExistingBpmnAfterConditionParses() throws IOException {
    WorkflowParser parser = WorkflowMill.parser();
    var result = parser.parse(
        "src/test/resources/de/monticore/bpmn/workflowwithtimer/parser/TimerWorkflow.wfm");
    
    assertFalse(parser.hasErrors());
    assertTrue(result.isPresent());
  }
  
}
