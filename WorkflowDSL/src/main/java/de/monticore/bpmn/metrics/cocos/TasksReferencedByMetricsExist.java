/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.metrics.cocos;

import de.monticore.bpmn.workflowwithmetrics._ast.ASTMetricsBlock;
import de.monticore.bpmn.workflowwithmetrics._cocos.WorkflowWithMetricsASTMetricsBlockCoCo;
import de.se_rwth.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;

public class TasksReferencedByMetricsExist implements WorkflowWithMetricsASTMetricsBlockCoCo {
  
  @Override
  public void check(ASTMetricsBlock node) {
    List<String> missing = new ArrayList<>();
    for (int i = 0; i < node.getReferencedTaskList().size(); i++) {
      if (node.getReferencedTaskDefinition(i).isEmpty()) {
        missing.add(node.getReferencedTask(i));
      }
    }
    
    if (!missing.isEmpty()) {
      Log.error("0x9c199: Can not find referenced task(s) with name(s): " + String.join(", ",
          missing), node.isPresent_SourcePositionStart() ? node.get_SourcePositionStart() : null,
          node.isPresent_SourcePositionEnd() ? node.get_SourcePositionEnd() : null);
    }
  }
  
}
