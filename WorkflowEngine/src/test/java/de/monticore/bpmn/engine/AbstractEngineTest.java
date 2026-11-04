/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import de.monticore.bpmn.workflow.WorkflowMill;
import de.monticore.symbols.basicsymbols.BasicSymbolsMill;
import de.se_rwth.commons.logging.Log;
import org.junit.jupiter.api.BeforeEach;

/** Resets the global state of MontiCore between two tests. */
public abstract class AbstractEngineTest {
  
  protected static final String ORDER_V1 = """
      package de.monticore.bpmn.engine;
      
      process Order {
        data amount : int;
      
        start event Received;
        task Check;
        split xor Decision;
        task Approve;
        task Reject;
        merge xor Join;
        end event Done;
      
        Received -> Check -> Decision;
        Decision -> [amount > 100] Approve -> Join;
        Decision -> [_] Reject -> Join;
        Join -> Done;
      }
      """;
  
  /** Same process, one activity more; deployed as version 2. */
  protected static final String ORDER_V2 = """
      package de.monticore.bpmn.engine;
      
      process Order {
        data amount : int;
      
        start event Received;
        task Check;
        split xor Decision;
        task Approve;
        task Reject;
        merge xor Join;
        task Archive;
        end event Done;
      
        Received -> Check -> Decision;
        Decision -> [amount > 100] Approve -> Join;
        Decision -> [_] Reject -> Join;
        Join -> Archive -> Done;
      }
      """;
  
  @BeforeEach
  public void setUp() {
    Log.init();
    Log.enableFailQuick(false);
    WorkflowMill.init();
    WorkflowMill.globalScope().clear();
    BasicSymbolsMill.initializePrimitives();
    BasicSymbolsMill.initializeString();
    Log.getFindings().clear();
  }
  
}
