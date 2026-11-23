/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer._cocos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.se_rwth.commons.logging.Log;
import de.se_rwth.commons.logging.LogStub;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import de.monticore.bpmn.workflowwithtimer.WorkflowWithTimerMill;
import de.monticore.bpmn.workflowwithtimer._parser.WorkflowWithTimerParser;

/** Covers defensive placement branches that cannot occur in well-formed workflow models. */
public class WorkflowTimerPlacementCoverageTest {
  
  protected WorkflowTimerPlacementIsSupported coCo;
  
  @BeforeEach
  public void setUp() {
    WorkflowWithTimerMill.reset();
    WorkflowWithTimerMill.init();
    LogStub.init();
    Log.enableFailQuick(false);
    Log.getFindings().clear();
    coCo = new WorkflowTimerPlacementIsSupported();
  }
  
  @Test
  public void testEventsWithoutTimeTriggerConditionsAreIgnored() throws IOException {
    coCo.check(parseEvent("event Plain;"));
    coCo.check(parseEvent("event Legacy catch timer [at 12:30];"));
    coCo.check(parseEvent("event LegacyDate catch timer [on 2026-07-20 at 12:30];"));
    assertTrue(coCo.getConditions(parseEvent("event Cancel throw cancel;")).isEmpty());
    
    assertTrue(Log.getFindings().isEmpty(), Log.getFindings().toString());
  }
  
  @Test
  public void testMultipleTimerTriggerIsRejectedAndUnwrapped() throws IOException {
    ASTWFEvent event = parseEvent(
        "event Multiple catch one {timer [after 1s], timer [on 2026-07-20T12:00:00Z]};");
    
    coCo.check(event);
    coCo.checkBoundaryEvent(event);
    coCo.checkIntermediateCatchEvent(event);
    
    assertTrue(Log.getFindings().stream().anyMatch(finding -> finding.getMsg().contains(
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_TIMER_PLACEMENT)), Log.getFindings()
            .toString());
    assertEquals(2, coCo.getConditions(event).size());
  }
  
  @Test
  public void testBoundaryShapeGuardBranchesReturnBeforeConditionCheck() throws IOException {
    coCo.checkBoundaryEvent(parseEvent("event Plain;"));
    coCo.checkBoundaryEvent(parseEvent("start event Start timer [on 12:00:00Z];"));
    coCo.checkBoundaryEvent(parseEvent("end event End timer [on 12:00:00Z];"));
    coCo.checkBoundaryEvent(parseEvent("event Throw throw timer [on 12:00:00Z];"));
    
    assertTrue(Log.getFindings().isEmpty(), Log.getFindings().toString());
  }
  
  @Test
  public void testBoundaryMustExplicitlyCatch() throws IOException {
    ASTWFEvent parsed = parseEvent("event Implicit timer [after 1s];");
    ASTWFEvent event = new ASTWFEvent() {
      
      @Override
      public boolean isCatch() { return false; }
      
    };
    event.setTrigger(parsed.getTrigger());
    coCo.checkBoundaryEvent(event);
    coCo.checkIntermediateCatchEvent(event);
    
    assertTrue(Log.getFindings().stream().anyMatch(finding -> finding.getMsg().contains(
        WorkflowTimerPlacementIsSupported.UNSUPPORTED_TIMER_PLACEMENT)), Log.getFindings()
            .toString());
  }
  
  protected ASTWFEvent parseEvent(String model) throws IOException {
    WorkflowWithTimerParser parser = WorkflowWithTimerMill.parser();
    var result = parser.parse_StringWFEvent(model);
    assertFalse(parser.hasErrors(), Log.getFindings().toString());
    assertTrue(result.isPresent(), Log.getFindings().toString());
    Log.getFindings().clear();
    return result.orElseThrow();
  }
  
}
