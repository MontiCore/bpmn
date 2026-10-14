/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithtimer._cocos;

import de.monticore.bpmn.workflow._cocos.WorkflowASTWFCallActivityCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFEventCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFLaneCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFProcessCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFSubProcessCoCo;
import de.monticore.bpmn.workflow._cocos.WorkflowASTWFTaskCoCo;
import de.monticore.temporal.timetriggerconditions._cocos.TimeTriggerConditionsCoCos;

/** Creates fully configured CoCo checkers for the WorkflowWithTimer grammar. */
public final class WorkflowWithTimerCoCos {
  
  private WorkflowWithTimerCoCos() {
  }
  
  /**
   * Creates a checker containing condition validation and Workflow placement rules.
   *
   * @return a newly configured checker
   */
  public static WorkflowWithTimerCoCoChecker createChecker() {
    WorkflowWithTimerCoCoChecker checker = new WorkflowWithTimerCoCoChecker();
    checker.addChecker(TimeTriggerConditionsCoCos.createChecker());
    
    WorkflowTimerPlacementIsSupported placement = new WorkflowTimerPlacementIsSupported();
    checker.addCoCo((WorkflowASTWFEventCoCo) placement);
    checker.addCoCo((WorkflowASTWFProcessCoCo) placement);
    checker.addCoCo((WorkflowASTWFLaneCoCo) placement);
    checker.addCoCo((WorkflowASTWFTaskCoCo) placement);
    checker.addCoCo((WorkflowASTWFSubProcessCoCo) placement);
    checker.addCoCo((WorkflowASTWFCallActivityCoCo) placement);
    return checker;
  }
  
}
