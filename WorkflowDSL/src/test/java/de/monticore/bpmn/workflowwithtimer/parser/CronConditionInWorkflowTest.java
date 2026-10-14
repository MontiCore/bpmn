/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import de.monticore.temporal.timetriggerconditions._ast.ASTCronCondition;
import de.monticore.temporal.timetriggerconditions._ast.ASTCronName;
import de.monticore.temporal.timetriggerconditions._ast.ASTCronNumber;
import de.monticore.temporal.timetriggerconditions._ast.ASTCronRange;
import de.monticore.temporal.timetriggerconditions._ast.ASTCronValue;
import de.monticore.temporal.timetriggerconditions._ast.ASTCronWildcard;
import de.monticore.bpmn.workflowwithtimer.WorkflowWithTimerMill;
import de.monticore.bpmn.workflowwithtimer._ast.ASTTimeTriggerTimerCondition;
import de.monticore.bpmn.workflowwithtimer._parser.WorkflowWithTimerParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CronConditionInWorkflowTest {
  
  protected static final String MODEL =
      "src/test/resources/de/monticore/bpmn/workflowwithtimer/parser/CronTimerWorkflow.wfm";
  
  @BeforeEach
  public void setUp() {
    WorkflowWithTimerMill.reset();
    WorkflowWithTimerMill.init();
  }
  
  @Test
  public void testStructuredCronConditionParsesInWorkflow() throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse(MODEL);
    
    assertFalse(parser.hasErrors());
    assertTrue(result.isPresent());
    
    List<ASTCronCondition> conditions = findCronConditions(result.get());
    assertEquals(1, conditions.size());
    
    var expression = conditions.get(0).getExpression();
    ASTCronValue minute = assertInstanceOf(ASTCronValue.class, expression.getMinute().getElements(
        0));
    ASTCronValue hour = assertInstanceOf(ASTCronValue.class, expression.getHour().getElements(0));
    ASTCronRange dayOfWeek = assertInstanceOf(ASTCronRange.class, expression.getDayOfWeek()
        .getElements(0));
    
    assertInstanceOf(ASTCronWildcard.class, minute.getValue());
    assertEquals(15, minute.getStep().getValue());
    assertEquals(9, assertInstanceOf(ASTCronNumber.class, hour.getValue()).getValue().getValue());
    assertInstanceOf(ASTCronWildcard.class, assertInstanceOf(ASTCronValue.class, expression
        .getDayOfMonth().getElements(0)).getValue());
    assertInstanceOf(ASTCronWildcard.class, assertInstanceOf(ASTCronValue.class, expression
        .getMonth().getElements(0)).getValue());
    assertEquals("MON", assertInstanceOf(ASTCronName.class, dayOfWeek.getStart()).getValue());
    assertEquals("FRI", assertInstanceOf(ASTCronName.class, dayOfWeek.getEnd()).getValue());
  }
  
  @Test
  public void testCrontabGuruSyntaxParsesInWorkflow() throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse_StringWorkflowCompilationUnit(workflowWith(
        "cron [*/15 9 * JAN-MAR MON-FRI]"));
    
    assertFalse(parser.hasErrors());
    assertTrue(result.isPresent());
    
    var expression = findCronConditions(result.get()).get(0).getExpression();
    ASTCronValue minute = assertInstanceOf(ASTCronValue.class, expression.getMinute().getElements(
        0));
    ASTCronRange month = assertInstanceOf(ASTCronRange.class, expression.getMonth().getElements(0));
    
    assertInstanceOf(ASTCronWildcard.class, minute.getValue());
    assertEquals(15, minute.getStep().getValue());
    assertEquals("JAN", assertInstanceOf(ASTCronName.class, month.getStart()).getValue());
    assertEquals("MAR", assertInstanceOf(ASTCronName.class, month.getEnd()).getValue());
  }
  
  @ParameterizedTest(name = "malformed Cron in a workflow: {0}")
  @ValueSource(strings = { "cron [0 12 * *]", "cron [@daily]", "cron [0 12 * * ?]",
      "cron \"0 12 * * *\"" })
  public void testMalformedCronConditionsDoNotParse(String condition) throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse_StringWorkflowCompilationUnit(workflowWith(condition));
    
    assertTrue(parser.hasErrors() || result.isEmpty(), condition);
  }
  
  protected List<ASTCronCondition> findCronConditions(
      de.monticore.bpmn.workflow._ast.ASTWorkflowCompilationUnit workflow) {
    List<ASTCronCondition> conditions = new ArrayList<>();
    var traverser = WorkflowWithTimerMill.inheritanceTraverser();
    traverser.add4WorkflowWithTimer(
        new de.monticore.bpmn.workflowwithtimer._visitor.WorkflowWithTimerVisitor2() {
          
          @Override
          public void visit(ASTTimeTriggerTimerCondition node) {
            if (node.isPresentCronCondition()) {
              conditions.add(node.getCronCondition());
            }
          }
          
        });
    workflow.accept(traverser);
    return conditions;
  }
  
  protected String workflowWith(String condition) {
    return """
        process InvalidCronWorkflow {
          start event Start;
          event Scheduled catch timer [%s];
          end event Done;
        
          Start -> Scheduled -> Done;
        }
        """.formatted(condition);
  }
  
}
