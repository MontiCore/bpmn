/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.cocos.metrics;

import de.monticore.bpmn.metrics.cocos.WorkflowWithMetricsCoCos;
import de.monticore.bpmn.workflow._ast.ASTWorkflowCompilationUnit;
import de.monticore.bpmn.workflowwithmetrics.WorkflowWithMetricsMill;
import de.monticore.bpmn.workflowwithmetrics.WorkflowWithMetricsTool;
import de.monticore.bpmn.workflowwithmetrics._symboltable.IWorkflowWithMetricsArtifactScope;
import de.se_rwth.commons.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TasksReferencedByMetricsExistTest {
  
  private WorkflowWithMetricsTool tool;
  
  @BeforeEach
  public void init() {
    tool = new WorkflowWithMetricsTool();
    tool.init();
    Log.clearFindings();
    Log.enableFailQuick(false);
  }
  
  @Test
  public void testValid() {
    ASTWorkflowCompilationUnit ast = tool.parse(
        "src/test/resources/de/monticore/bpmn/metrics/cocos/AllReferencedTasksExist.wfm");
    IWorkflowWithMetricsArtifactScope as = tool.createSymbolTable(ast);
    WorkflowWithMetricsMill.globalScope().addSubScope(as);
    
    WorkflowWithMetricsCoCos.getFullChecker().checkAll(ast);
    assertFalse(Log.getFindings().stream().anyMatch(f -> f.getMsg().contains("0x9c199")));
  }
  
  @Test
  public void testInvalid() {
    ASTWorkflowCompilationUnit ast = tool.parse(
        "src/test/resources/de/monticore/bpmn/metrics/cocos/ReferencedTaskDoesNotExist.wfm");
    IWorkflowWithMetricsArtifactScope as = tool.createSymbolTable(ast);
    WorkflowWithMetricsMill.globalScope().addSubScope(as);
    
    WorkflowWithMetricsCoCos.getFullChecker().checkAll(ast);
    assertFalse(Log.getFindings().isEmpty());
    
    assertTrue(Log.getFindings().stream().anyMatch(f -> f.getMsg().contains("0x9c199")));
  }
  
}
