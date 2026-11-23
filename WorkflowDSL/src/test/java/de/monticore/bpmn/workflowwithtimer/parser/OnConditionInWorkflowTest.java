/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.parser;

import de.se_rwth.commons.logging.Log;
import de.se_rwth.commons.logging.LogStub;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import de.monticore.temporal.timetriggerconditions._ast.ASTOnCondition;
import de.monticore.bpmn.workflowwithtimer.WorkflowWithTimerMill;
import de.monticore.bpmn.workflowwithtimer._ast.ASTTimeTriggerTimerCondition;
import de.monticore.bpmn.workflowwithtimer._parser.WorkflowWithTimerParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OnConditionInWorkflowTest {
  
  @BeforeEach
  public void setUp() {
    WorkflowWithTimerMill.reset();
    WorkflowWithTimerMill.init();
    LogStub.init();
    Log.enableFailQuick(false);
    Log.getFindings().clear();
  }
  
  @Test
  public void testStructuredOnConditionParsesInWorkflow() throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse(
        "src/test/resources/de/monticore/bpmn/workflowwithtimer/parser/OnWorkflow.wfm");
    
    assertFalse(parser.hasErrors());
    assertTrue(result.isPresent());
    
    List<ASTOnCondition> conditions = new ArrayList<>();
    var traverser = WorkflowWithTimerMill.inheritanceTraverser();
    traverser.add4WorkflowWithTimer(
        new de.monticore.bpmn.workflowwithtimer._visitor.WorkflowWithTimerVisitor2() {
          
          @Override
          public void visit(ASTTimeTriggerTimerCondition node) {
            if (node.isPresentOnCondition()) {
              conditions.add(node.getOnCondition());
            }
          }
          
        });
    result.get().accept(traverser);
    
    assertEquals(1, conditions.size());
    assertEquals("2026-07-20T12:00:00Z", conditions.getFirst().getIsoDateTime().toRawString());
  }
  
}
