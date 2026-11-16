/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import com.google.common.collect.Lists;
import de.monticore.bpmn.cocos.WorkflowCoCos;
import de.monticore.bpmn.trafos.AddMoreImports;
import de.monticore.bpmn.trafos.AddNameToInlineFlowNodes;
import de.monticore.bpmn.trafos.AddSequenceFlowToFlowNodes;
import de.monticore.bpmn.workflow.WorkflowMill;
import de.monticore.bpmn.workflow._ast.ASTWorkflowCompilationUnit;
import de.monticore.bpmn.workflow._cocos.WorkflowCoCoChecker;
import de.monticore.bpmn.workflow._parser.WorkflowParser;
import de.monticore.bpmn.workflow._symboltable.IWorkflowArtifactScope;
import de.monticore.bpmn.workflow._symboltable.IWorkflowScope;
import de.monticore.bpmn.workflow._symboltable.WorkflowSTCompleter;
import de.monticore.bpmn.workflow._visitor.WorkflowTraverser;
import de.monticore.io.paths.MCPath;
import de.monticore.symbols.basicsymbols.BasicSymbolsMill;
import de.monticore.symboltable.ImportStatement;
import de.se_rwth.commons.logging.Log;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns the source of a process model into an AST that can be interpreted, i.e., it runs the full
 * front end: parser, symbol table, model transformations and CoCos.
 *
 * <p>This is the same pipeline that {@code WorkflowTool} uses. The engine needs it because a
 * process definition is stored as its source and is parsed again whenever the engine is started.
 *
 * <p>Problems of the model are reported to the {@link Log}. A model with errors is returned
 * nevertheless as far as it could be parsed; whoever calls the loader decides what to do with it.
 * The engine refuses to deploy it.
 */
public class ProcessModelLoader {
  
  /** Where symbol files (.cdsym, .wfmsym) of imported models are looked up. */
  protected final List<Path> symbolPath = new ArrayList<>();
  
  /** Imports that are added to every model, e.g., a library of default types. */
  protected final List<ImportStatement> defaultImports = new ArrayList<>();
  
  public ProcessModelLoader withSymbolPath(final Path... paths) {
    symbolPath.addAll(List.of(paths));
    return this;
  }
  
  public ProcessModelLoader withDefaultImport(final String qualifiedName) {
    defaultImports.add(new ImportStatement(qualifiedName, true));
    return this;
  }
  
  /** Initializes the mill and the primitive types. Idempotent, but not thread safe. */
  public void init() {
    WorkflowMill.init();
    BasicSymbolsMill.initializePrimitives();
    BasicSymbolsMill.initializeString();
    if (!symbolPath.isEmpty()) {
      WorkflowMill.globalScope().setSymbolPath(new MCPath(symbolPath));
    }
  }
  
  /**
   * Parses and completes a process model.
   *
   * @param source the source of the model
   * @return the compilation unit, empty if the model could not be parsed
   */
  public Optional<ASTWorkflowCompilationUnit> load(final String source) {
    init();
    final WorkflowParser parser = WorkflowMill.parser();
    final Optional<ASTWorkflowCompilationUnit> ast;
    try {
      ast = parser.parse_String(source);
    }
    catch (IOException e) {
      Log.error("0xWFE0001 The process model could not be read: " + e.getMessage());
      return Optional.empty();
    }
    if (ast.isEmpty() || parser.hasErrors()) {
      Log.error("0xWFE0002 The process model could not be parsed.");
      return Optional.empty();
    }
    complete(ast.get());
    return ast;
  }
  
  public Optional<ASTWorkflowCompilationUnit> loadFile(final Path file) {
    try {
      return load(java.nio.file.Files.readString(file));
    }
    catch (IOException e) {
      Log.error("0xWFE0003 The file '" + file + "' could not be read: " + e.getMessage());
      return Optional.empty();
    }
  }
  
  /** Creates the symbol table, runs the transformations and checks the CoCos. */
  protected void complete(final ASTWorkflowCompilationUnit ast) {
    if (!defaultImports.isEmpty()) {
      new AddMoreImports(Lists.newArrayList(defaultImports)).transform(ast);
    }
    final IWorkflowArtifactScope scope = WorkflowMill.scopesGenitorDelegator().createFromAST(ast);
    forgetOlderVersions(scope);
    new AddNameToInlineFlowNodes().transform(ast);
    new AddSequenceFlowToFlowNodes().transform(ast);
    
    final WorkflowSTCompleter stCompleter = new WorkflowSTCompleter();
    final WorkflowTraverser traverser = WorkflowMill.traverser();
    traverser.add4Workflow(stCompleter);
    ast.accept(traverser);
    
    final WorkflowCoCoChecker checker = WorkflowCoCos.getBasicChecker();
    checker.checkAll(ast);
  }
  
  /**
   * Every model that is loaded adds its symbols to the global scope. A process is loaded many
   * times over the life of an engine: once per deployed version and again after every restart. The
   * global scope would then know the same process several times, so the symbols of an earlier load
   * are removed from it. An AST that has already been loaded stays intact, its scopes still
   * resolve names upwards.
   */
  protected void forgetOlderVersions(final IWorkflowArtifactScope loaded) {
    final List<String> processes = loaded.getLocalWFProcessSymbols().stream().map(symbol -> symbol
        .getName()).toList();
    for (IWorkflowScope scope : List.copyOf(WorkflowMill.globalScope().getSubScopes())) {
      if (scope != loaded && scope instanceof IWorkflowArtifactScope other && other.getPackageName()
          .equals(loaded.getPackageName()) && other.getLocalWFProcessSymbols().stream().anyMatch(
              symbol -> processes.contains(symbol.getName()))) {
        WorkflowMill.globalScope().removeSubScope(scope);
      }
    }
  }
  
}
