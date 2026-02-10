/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.metrics.cocos;

import de.monticore.bpmn.cocos.WorkflowCoCos;
import de.monticore.bpmn.workflowwithmetrics._cocos.WorkflowWithMetricsCoCoChecker;

public class WorkflowWithMetricsCoCos {
  
  private WorkflowWithMetricsCoCos() {}
  
  /**
   * Returns the full CoCo checker.
   *
   * <p>Checks basic CoCos, then structural CoCos, then behavioral CoCos
   *
   * @return the CoCo checker
   */
  public static WorkflowWithMetricsCoCoChecker getFullChecker() {
    WorkflowWithMetricsCoCoChecker res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(getStructuralChecker());
    res.addChecker(getBehavioralChecker());
    
    return res;
  }
  
  /**
   * Returns the basic CoCo checker
   *
   * <p>Checks basic (syntax-based) CoCos
   *
   * @return the CoCo checker
   */
  public static WorkflowWithMetricsCoCoChecker getBasicChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getBasicChecker());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getStructuralChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getStructuralChecker());
    res.addCoCo(new TasksReferencedByMetricsExist());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getBehavioralChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getBehavioralChecker());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getSequenceFlowChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getSequenceFlowChecker());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getGatewayChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getGatewayChecker());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getActivityChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getActivityChecker());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getEventChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getEventChecker());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getEventTriggerChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getEventTriggerChecker());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getTimeExpressionsChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getTimeExpressionsChecker());
    return res;
  }
  
  public static WorkflowWithMetricsCoCoChecker getTypesChecker() {
    var res = new WorkflowWithMetricsCoCoChecker();
    res.addChecker(WorkflowCoCos.getTypesChecker());
    return res;
  }
  
}
