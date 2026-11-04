/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import de.monticore.bpmn.types3.WorkflowTypeCheck3;
import de.monticore.bpmn.workflow.WorkflowMill;
import de.monticore.bpmn.workflow._ast.ASTWFProcess;
import de.monticore.bpmn.workflow._visitor.WorkflowTraverser;
import de.monticore.expressions.assignmentexpressions.interpreter.AssignmentExpressionsInterpreter;
import de.monticore.expressions.commonexpressions.interpreter.CommonExpressionsInterpreter;
import de.monticore.expressions.expressionsbasis.interpreter.ExpressionCalculationLogVisitor;
import de.monticore.expressions.expressionsbasis.interpreter.ExpressionsBasisInterpreter;
import de.monticore.expressions.lambdaexpressions.interpreter.LambdaExpressionsInterpreter;
import de.monticore.interpreter.util.InterpreterDataForBasicSymbols;
import de.monticore.literals.mccommonliterals.interpreter.MCCommonLiteralsInterpreter;

/**
 * Assembles a {@link BPMNInterpreter} for a process.
 *
 * <p>An interpreter of a MontiCore language is a set of handlers that share one
 * {@link InterpreterDataForBasicSymbols}. Every language a grammar extends contributes the handler
 * for its own part of the AST. The Workflow grammar embeds expressions and literals, so the
 * corresponding handlers of MontiCore are used; the control flow of the process itself is not
 * traversed but executed by {@link BPMNInterpreter} directly, because a process is a graph and not
 * a tree.
 *
 * <p>The AST must have a symbol table and should have passed the CoCos before it is interpreted.
 */
public class BPMNInterpreterBuilder {
  
  protected boolean withExpressionLog = false;
  
  /** Logs every expression calculation. Slow, only use it while developing. */
  public BPMNInterpreterBuilder withExpressionLog(final boolean withExpressionLog) {
    this.withExpressionLog = withExpressionLog;
    return this;
  }
  
  public BPMNInterpreter build(final ASTWFProcess process) {
    WorkflowTypeCheck3.init();
    
    final InterpreterDataForBasicSymbols iData = new InterpreterDataForBasicSymbols();
    final WorkflowTraverser traverser = WorkflowMill.inheritanceTraverser();
    
    // expressions, i.e., flow conditions, gateway guards and loop conditions
    traverser.setExpressionsBasisHandler(new ExpressionsBasisInterpreter(iData));
    traverser.setCommonExpressionsHandler(new CommonExpressionsInterpreter(iData));
    traverser.setAssignmentExpressionsHandler(new AssignmentExpressionsInterpreter(iData));
    traverser.setLambdaExpressionsHandler(new LambdaExpressionsInterpreter(iData));
    traverser.setMCCommonLiteralsHandler(new MCCommonLiteralsInterpreter(iData));
    
    if (withExpressionLog) {
      traverser.add4ExpressionsBasis(new ExpressionCalculationLogVisitor(iData));
    }
    
    return new BPMNInterpreter(process, traverser, iData);
  }
  
  /** Convenience for the default setup. */
  public static BPMNInterpreter buildDefault(final ASTWFProcess process) {
    return new BPMNInterpreterBuilder().build(process);
  }
  
}
