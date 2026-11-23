/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import de.monticore.expressions.commonexpressions.interpreter.CommonExpressionsInterpreter;
import de.monticore.interpreter.util.InterpreterDataForBasicSymbols;

/**
 * The interpreter for common expressions as the Workflow language uses it: field accesses and
 * method calls also work on objects whose type is defined by a model instead of a Java class, cf.
 * {@link ModelObjectAccessHandler}.
 */
public class WorkflowCommonExpressionsInterpreter extends CommonExpressionsInterpreter {
  
  public WorkflowCommonExpressionsInterpreter(final InterpreterDataForBasicSymbols iData) {
    super(iData);
    this.symbolAccessHandler = new ModelObjectAccessHandler();
  }
  
}
