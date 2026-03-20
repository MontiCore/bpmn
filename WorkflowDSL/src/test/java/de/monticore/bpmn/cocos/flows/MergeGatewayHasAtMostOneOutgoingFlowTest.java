/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.cocos.flows;

import com.google.common.collect.Lists;
import de.monticore.bpmn.Messages;
import de.monticore.bpmn.cocos.AbstractCoCoTest;
import de.monticore.bpmn.cocos.WorkflowCoCos;
import de.monticore.bpmn.workflow._cocos.WorkflowCoCoChecker;
import de.se_rwth.commons.logging.Finding;
import java.io.IOException;
import java.util.Collection;

import org.jgrapht.nio.ExportException;
import org.junit.jupiter.api.Test;

class MergeGatewayHasAtMostOneOutgoingFlowTest extends AbstractCoCoTest {
  
  @Override
  protected WorkflowCoCoChecker getChecker() { return WorkflowCoCos.getSequenceFlowChecker(); }
  
  @Test
  void mergeHasMultipleOutgoing() throws IOException, ExportException {
    String modelName = "de.monticore.bpmn.cocos.flows.invalid.MergeGatewayHasAtMostOneOutgoingFlow";
    
    Collection<Finding> expectedErrors = Lists.newArrayList(Finding.error(Messages.get("0xWFM5004",
        "G1")));
    
    testModelForErrors(modelName, expectedErrors);
  }
  
  @Test
  void mergeHasAtMostOneOutgoing() {
    String modelName = "de.monticore.bpmn.cocos.flows.valid.MergeGatewayHasAtMostOneOutgoingFlow";
    
    testModelNoErrors(modelName);
  }
  
}
