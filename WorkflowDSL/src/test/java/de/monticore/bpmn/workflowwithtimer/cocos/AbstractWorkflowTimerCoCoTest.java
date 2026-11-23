/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer.cocos;

import de.monticore.bpmn.workflow._ast.ASTWorkflowCompilationUnit;
import de.se_rwth.commons.logging.Finding;
import de.se_rwth.commons.logging.Log;
import de.se_rwth.commons.logging.LogStub;
import org.junit.jupiter.api.BeforeEach;
import de.monticore.bpmn.workflowwithtimer.WorkflowWithTimerMill;
import de.monticore.bpmn.workflowwithtimer._cocos.WorkflowWithTimerCoCoChecker;
import de.monticore.bpmn.workflowwithtimer._parser.WorkflowWithTimerParser;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a WorkflowDSL CoCo checker on a parsed workflow. Subclasses either check a single
 * condition in the standard intermediate catch event, or supply a complete model when the
 * event shape itself is under test.
 */
public abstract class AbstractWorkflowTimerCoCoTest {
  
  @BeforeEach
  public void setUp() {
    WorkflowWithTimerMill.reset();
    WorkflowWithTimerMill.init();
    LogStub.init();
    Log.enableFailQuick(false);
    Log.getFindings().clear();
  }
  
  /** Asserts that a condition passes its CoCo without any finding. */
  protected void checkValid(String condition) throws IOException {
    checkModelValid(workflowWith(condition));
  }
  
  /** Asserts that a condition reports the code as an error with a source position. */
  protected void checkInvalid(String condition, String expectedErrorCode) throws IOException {
    checkModelInvalid(workflowWith(condition), expectedErrorCode);
  }
  
  /** Asserts that a condition reports the code as a warning with a source position. */
  protected void checkWarning(String condition, String expectedWarningCode) throws IOException {
    Finding finding = check(workflowWith(condition), expectedWarningCode);
    
    assertTrue(finding.isWarning(), condition);
    assertTrue(finding.getSourcePosition().isPresent(), condition);
  }
  
  /** Asserts that a complete workflow passes the checker without any finding. */
  protected void checkModelValid(String model) throws IOException {
    checker().checkAll(parse(model));
    
    assertTrue(Log.getFindings().isEmpty(), model + " produced " + Log.getFindings());
  }
  
  /** Asserts that a complete workflow reports the code as an error with a source position. */
  protected void checkModelInvalid(String model, String expectedErrorCode) throws IOException {
    Finding finding = check(model, expectedErrorCode);
    
    assertTrue(finding.isError(), model);
    assertTrue(finding.getSourcePosition().isPresent(), model);
  }
  
  /** Runs the checker and returns the first finding carrying the expected code. */
  protected Finding check(String model, String expectedCode) throws IOException {
    checker().checkAll(parse(model));
    
    return Log.getFindings().stream().filter(candidate -> candidate.getMsg().contains(expectedCode))
        .findFirst().orElseThrow(() -> new AssertionError(model + " produced " + Log
            .getFindings()));
  }
  
  /** Parses a complete workflow and clears the parser findings. */
  protected ASTWorkflowCompilationUnit parse(String model) throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse_StringWorkflowCompilationUnit(model);
    
    assertFalse(parser.hasErrors(), Log.getFindings().toString());
    assertTrue(result.isPresent(), model);
    Log.getFindings().clear();
    return result.orElseThrow();
  }
  
  /** Places one condition on a plain intermediate catch event. */
  protected String workflowWith(String condition) {
    return """
        process TimerCoCoTest {
          start event Start;
          event Timeout catch timer [%s];
          end event Done;
        
          Start -> Timeout -> Done;
        }
        """.formatted(condition);
  }
  
  protected abstract WorkflowWithTimerCoCoChecker checker();
  
}
