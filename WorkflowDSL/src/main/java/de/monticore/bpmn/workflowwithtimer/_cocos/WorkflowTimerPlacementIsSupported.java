/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer._cocos;

import de.monticore.bpmn.workflow._ast.ASTWFCallActivity;
import de.monticore.bpmn.workflow._ast.ASTWFEvent;
import de.monticore.bpmn.workflow._ast.ASTWFEventTrigger;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerMultiple;
import de.monticore.bpmn.workflow._ast.ASTWFEventTriggerTimer;
import de.monticore.bpmn.workflow._ast.ASTWFLane;
import de.monticore.bpmn.workflow._ast.ASTWFProcess;
import de.monticore.bpmn.workflow._ast.ASTWFSubProcess;
import de.monticore.bpmn.workflow._ast.ASTWFTask;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFCallActivityCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFEventCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFLaneCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFProcessCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFSubProcessCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFTaskCoCo;
import de.se_rwth.commons.logging.Log;
import java.util.ArrayList;
import java.util.List;
import de.monticore.temporal.timetriggerconditions._ast.ASTAfterCondition;
import de.monticore.temporal.timetriggerconditions._ast.ASTCronCondition;
import de.monticore.temporal.timetriggerconditions._ast.ASTEveryTimeCondition;
import de.monticore.temporal.timetriggerconditions._ast.ASTOnCondition;
import de.monticore.temporal.timetriggerconditions._ast.ASTTimeTriggerCondition;
import de.monticore.bpmn.workflowwithtimer._ast.ASTAfterPeriodCondition;
import de.monticore.bpmn.workflowwithtimer._ast.ASTTimeTriggerTimerCondition;

/**
 * Restricts TimeTrigger conditions to the documented BPMN execution profile.
 *
 * <p>BPMN 2.0.2 defines {@code timeDate}, {@code timeDuration}, and
 * {@code timeCycle} as the mutually exclusive TimerEventDefinition variants
 * (Table 10.122). The standard also distinguishes interrupting and
 * non-interrupting boundary timer events. This CoCo deliberately applies the
 * narrower mapping documented in {@code docs/semantics.md}: a cycle is useful
 * only where the event remains subscribed after its first occurrence.</p>
 *
 * <p>The restriction is a host-language well-formedness rule rather than
 * component syntax. Keeping it here therefore preserves reuse of the timer
 * condition grammar in Statecharts, where all four condition kinds are valid.</p>
 */
public class WorkflowTimerPlacementIsSupported implements WorkflowASTWFEventCoCo,
    WorkflowASTWFProcessCoCo, WorkflowASTWFLaneCoCo, WorkflowASTWFTaskCoCo,
    WorkflowASTWFSubProcessCoCo, WorkflowASTWFCallActivityCoCo {
  
  /** Error code for a condition that is not meaningful on a timer start event. */
  public static final String UNSUPPORTED_START_CONDITION = "0xF0015";
  /** Error code for a condition that is not supported on a boundary timer event. */
  public static final String UNSUPPORTED_BOUNDARY_CONDITION = "0xF0016";
  /** Error code for a timer event form whose lifecycle is not defined. */
  public static final String UNSUPPORTED_TIMER_PLACEMENT = "0xF0017";
  /** Error code for a recurring condition on a one-shot intermediate catch event. */
  public static final String UNSUPPORTED_INTERMEDIATE_CONDITION = "0xF0018";
  
  /** Validates top-level start, end, and throwing timer events. */
  @Override
  public void check(ASTWFEvent event) {
    List<ASTTimeTriggerCondition> conditions = getConditions(event);
    if (conditions.isEmpty()) {
      return;
    }
    
    if (!(event.getTrigger() instanceof ASTWFEventTriggerTimer)) {
      logUnsupportedPlacement(event, "TimeTrigger events must use one direct timer trigger.");
      return;
    }
    
    if (event.isStart()) {
      for (ASTTimeTriggerCondition condition : conditions) {
        if (!isSupportedStartCondition(condition)) {
          Log.error(UNSUPPORTED_START_CONDITION
              + " Timer start events support on, cron, or an anchored every condition.", event
                  .get_SourcePositionStart());
        }
      }
      return;
    }
    
    if (event.isEnd() || event.isThrow()) {
      logUnsupportedPlacement(event,
          "TimeTrigger conditions are catching timers and cannot be used on end or throw events.");
    }
  }
  
  /** Validates intermediate catch events declared directly in a process. */
  @Override
  public void check(ASTWFProcess process) {
    process.getFlowElementList().stream().filter(ASTWFEvent.class::isInstance).map(
        ASTWFEvent.class::cast).forEach(this::checkIntermediateCatchEvent);
  }
  
  /** Validates intermediate catch events declared directly in a lane. */
  @Override
  public void check(ASTWFLane lane) {
    lane.getFlowElementList().stream().filter(ASTWFEvent.class::isInstance).map(
        ASTWFEvent.class::cast).forEach(this::checkIntermediateCatchEvent);
  }
  
  /** Validates timer boundary events attached to a task. */
  @Override
  public void check(ASTWFTask task) {
    task.getBoundaryEventList().forEach(this::checkBoundaryEvent);
  }
  
  /** Validates timer boundary events attached to a subprocess. */
  @Override
  public void check(ASTWFSubProcess subprocess) {
    subprocess.getFlowElementList().stream().filter(ASTWFEvent.class::isInstance).map(
        ASTWFEvent.class::cast).forEach(this::checkIntermediateCatchEvent);
    subprocess.getBoundaryEventList().forEach(this::checkBoundaryEvent);
  }
  
  /** Validates timer boundary events attached to a call activity. */
  @Override
  public void check(ASTWFCallActivity callActivity) {
    callActivity.getBoundaryEventList().forEach(this::checkBoundaryEvent);
  }
  
  /**
   * Validates a direct boundary timer.
   *
   * <p>One-shot {@code after}/{@code on} conditions work for both lifecycle
   * variants. Recurring {@code every}/{@code cron} conditions are limited to
   * non-interrupting boundaries because an interrupting boundary cancels its
   * attached activity on the first occurrence and cannot observe another one.</p>
   */
  protected void checkBoundaryEvent(ASTWFEvent event) {
    List<ASTTimeTriggerCondition> conditions = getConditions(event);
    if (conditions.isEmpty()) {
      return;
    }
    
    // Multiple, start, end, and throw forms are reported by the event-level check.
    if (!(event.getTrigger() instanceof ASTWFEventTriggerTimer) || event.isStart() || event.isEnd()
        || event.isThrow()) {
      return;
    }
    
    if (!event.isCatch()) {
      logUnsupportedPlacement(event,
          "TimeTrigger boundary events must be direct catching timer events.");
      return;
    }
    
    for (ASTTimeTriggerCondition condition : conditions) {
      if (!isSupportedBoundaryCondition(condition, event.isNoninterrupt())) {
        Log.error(UNSUPPORTED_BOUNDARY_CONDITION
            + " Interrupting boundary timer events support only after or on conditions; "
            + "recurring conditions require a non-interrupting boundary.", event
                .get_SourcePositionStart());
      }
    }
  }
  
  /** Validates a normal-flow intermediate catch timer as a one-shot wait. */
  protected void checkIntermediateCatchEvent(ASTWFEvent event) {
    List<ASTTimeTriggerCondition> conditions = getConditions(event);
    if (conditions.isEmpty() || event.isStart() || event.isEnd() || event.isThrow()) {
      return;
    }
    
    // The event-level check owns the diagnostic for multiple or non-timer triggers.
    if (!(event.getTrigger() instanceof ASTWFEventTriggerTimer) || !event.isCatch()) {
      return;
    }
    
    for (ASTTimeTriggerCondition condition : conditions) {
      if (!(condition instanceof ASTAfterCondition || condition instanceof ASTOnCondition)) {
        Log.error(UNSUPPORTED_INTERMEDIATE_CONDITION
            + " Intermediate catch timer events support only after or on conditions; "
            + "the event completes after its first occurrence.", event.get_SourcePositionStart());
      }
    }
  }
  
  /** Returns whether a condition has a defined timer-start lifecycle. */
  protected boolean isSupportedStartCondition(ASTTimeTriggerCondition condition) {
    return condition instanceof ASTOnCondition || condition instanceof ASTCronCondition
        || (condition instanceof ASTEveryTimeCondition every && every.isPresentStart());
  }
  
  /** Returns whether a condition has a useful lifecycle on this boundary kind. */
  protected boolean isSupportedBoundaryCondition(ASTTimeTriggerCondition condition,
      boolean nonInterrupting) {
    if (condition instanceof ASTAfterCondition || condition instanceof ASTOnCondition) {
      return true;
    }
    // The only remaining alternatives of TimeTriggerCondition are every and cron.
    return nonInterrupting;
  }
  
  /** Extracts all TimeTrigger conditions from a direct or multiple trigger. */
  protected List<ASTTimeTriggerCondition> getConditions(ASTWFEvent event) {
    List<ASTTimeTriggerCondition> conditions = new ArrayList<>();
    if (event.isPresentTrigger()) {
      collectConditions(event.getTrigger(), conditions);
    }
    return conditions;
  }
  
  /** Recursively unwraps WorkflowDSL timer-condition adapters. */
  protected void collectConditions(ASTWFEventTrigger trigger,
      List<ASTTimeTriggerCondition> conditions) {
    if (trigger instanceof ASTWFEventTriggerTimer timer) {
      if (timer.getCondition() instanceof ASTAfterPeriodCondition after) {
        conditions.add(after.getCondition());
      }
      else if (timer.getCondition() instanceof ASTTimeTriggerTimerCondition other) {
        if (other.isPresentOnCondition()) {
          conditions.add(other.getOnCondition());
        }
        else if (other.isPresentEveryCondition()) {
          conditions.add(other.getEveryCondition());
        }
        else if (other.isPresentCronCondition()) {
          conditions.add(other.getCronCondition());
        }
      }
    }
    else if (trigger instanceof ASTWFEventTriggerMultiple multiple) {
      multiple.getTriggerList().forEach(nested -> collectConditions(nested, conditions));
    }
  }
  
  /** Logs one placement diagnostic at the event declaration. */
  protected void logUnsupportedPlacement(ASTWFEvent event, String message) {
    Log.error(UNSUPPORTED_TIMER_PLACEMENT + " " + message, event.get_SourcePositionStart());
  }
  
}
