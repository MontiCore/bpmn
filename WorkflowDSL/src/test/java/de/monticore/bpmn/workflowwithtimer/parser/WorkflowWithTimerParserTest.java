/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.parser;

import de.monticore.temporal.isotemporals._ast.ASTFullPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import de.monticore.temporal.timetriggerconditions._ast.ASTAfterCondition;
import de.monticore.temporal.timetriggerconditions._ast.ASTEveryTimeCondition;
import de.monticore.bpmn.workflowwithtimer.WorkflowWithTimerMill;
import de.monticore.bpmn.workflowwithtimer._ast.ASTAfterPeriodCondition;
import de.monticore.bpmn.workflowwithtimer._ast.ASTTimeTriggerTimerCondition;
import de.monticore.bpmn.workflowwithtimer._parser.WorkflowWithTimerParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkflowWithTimerParserTest {
  
  @BeforeEach
  public void setUp() {
    WorkflowWithTimerMill.reset();
    WorkflowWithTimerMill.init();
  }
  
  @Test
  public void testAfterTimerWorkflowParses() throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse(
        "src/test/resources/de/monticore/bpmn/workflowwithtimer/parser/AfterTimerWorkflow.wfm");
    
    assertFalse(parser.hasErrors());
    assertTrue(result.isPresent());
    
    List<ASTAfterCondition> conditions = new ArrayList<>();
    var traverser = WorkflowWithTimerMill.inheritanceTraverser();
    traverser.add4WorkflowWithTimer(
        new de.monticore.bpmn.workflowwithtimer._visitor.WorkflowWithTimerVisitor2() {
          
          @Override
          public void visit(ASTAfterPeriodCondition node) {
            if (node.getCondition().isPresentDuration()) {
              conditions.add(node.getCondition());
            }
          }
          
        });
    result.get().accept(traverser);
    
    assertEquals(1, conditions.size());
  }
  
  @Test
  @Timeout(5)
  public void testIsoTimerWorkflowUsesStructuredPeriod() throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse(
        "src/test/resources/de/monticore/bpmn/workflowwithtimer/parser/TimerWorkflow.wfm");
    
    assertFalse(parser.hasErrors());
    assertTrue(result.isPresent());
    
    List<ASTAfterCondition> conditions = new ArrayList<>();
    var traverser = WorkflowWithTimerMill.inheritanceTraverser();
    traverser.add4WorkflowWithTimer(
        new de.monticore.bpmn.workflowwithtimer._visitor.WorkflowWithTimerVisitor2() {
          
          @Override
          public void visit(ASTAfterPeriodCondition node) {
            if (node.getCondition().isPresentPeriod()) {
              conditions.add(node.getCondition());
            }
          }
          
        });
    result.get().accept(traverser);
    
    assertEquals(1, conditions.size());
    ASTFullPeriod period = assertInstanceOf(ASTFullPeriod.class, conditions.get(0).getPeriod());
    assertTrue(period.isPresentSeconds());
    assertEquals(10, period.getSeconds());
  }
  
  @ParameterizedTest(name = "malformed after in a workflow: {0}")
  @ValueSource(strings = { "after P1W2D", "after -PT1S", "after PX", "after foo" })
  public void testMalformedIsoPeriodsAreRejected(String condition) throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse_StringWorkflowCompilationUnit(workflow(condition));
    
    assertTrue(parser.hasErrors() || result.isEmpty(), condition);
  }
  
  @ParameterizedTest(name = "recurring condition in a workflow: {0}")
  @ValueSource(strings = { "every 10s", "every 5min 3 times", "from 2026-07-20T12:00:00Z every 10s",
      "from 2026-07-20T12:00:00+02:00 every 10s 5 times" })
  public void testEveryTimeConditionParsesInWorkflow(String condition) throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse_StringWorkflowCompilationUnit(workflow(condition));
    
    assertFalse(parser.hasErrors(), condition);
    assertTrue(result.isPresent(), condition);
    
    List<ASTEveryTimeCondition> conditions = new ArrayList<>();
    var traverser = WorkflowWithTimerMill.inheritanceTraverser();
    traverser.add4WorkflowWithTimer(
        new de.monticore.bpmn.workflowwithtimer._visitor.WorkflowWithTimerVisitor2() {
          
          @Override
          public void visit(ASTTimeTriggerTimerCondition node) {
            if (node.isPresentEveryCondition()) {
              conditions.add(node.getEveryCondition());
            }
          }
          
        });
    result.get().accept(traverser);
    
    assertEquals(1, conditions.size(), condition);
  }
  
  protected String workflow(String condition) {
    return """
        process TimerWorkflow {
          start event Start;
          event Timeout catch timer [%s];
          end event Done;
        
          Start -> Timeout -> Done;
        }
        """.formatted(condition);
  }
  
}
