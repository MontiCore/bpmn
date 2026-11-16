/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn;

import com.google.common.collect.Lists;
import de.monticore.bpmn.cocos.WorkflowCoCos;
import de.monticore.bpmn.conformance.WfConformanceChecker;
import de.monticore.bpmn.engine.runner.WorkflowRunner;
import de.monticore.bpmn.trafos.AddMoreImports;
import de.monticore.bpmn.trafos.AddNameToInlineFlowNodes;
import de.monticore.bpmn.trafos.AddSequenceFlowToFlowNodes;
import de.monticore.bpmn.workflow.WorkflowMill;
import de.monticore.bpmn.workflow._ast.ASTWorkflowCompilationUnit;
import de.monticore.bpmn.workflow._cocos.WorkflowCoCoChecker;
import de.monticore.bpmn.workflow._symboltable.IWorkflowArtifactScope;
import de.monticore.bpmn.workflow._symboltable.WorkflowSTCompleter;
import de.monticore.bpmn.workflow._visitor.WorkflowTraverser;
import de.monticore.symbols.basicsymbols.BasicSymbolsMill;
import de.monticore.symboltable.ImportStatement;
import de.se_rwth.commons.logging.Log;
import org.apache.commons.cli.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class WorkflowTool extends de.monticore.bpmn.workflow.WorkflowTool {
  
  // Add OCL default types, this way we don't need to import them in the models every time
  protected static final ImportStatement OCL_TYPES = new ImportStatement(
      "de.monticore.bpmn._types.ocl.DefaultTypes", true);
  
  /** The result of an execution with {@code -x}: 0 if all steps and expectations succeeded. */
  protected int executionResult = 0;
  
  public static void main(String[] args) {
    WorkflowTool tool = new WorkflowTool();
    tool.run(args);
    if (tool.executionResult != 0) {
      System.exit(tool.executionResult);
    }
  }
  
  public int getExecutionResult() { return executionResult; }
  
  @Override
  public void init() {
    super.init();
    WorkflowMill.globalScope().clear();
    BasicSymbolsMill.initializePrimitives();
    BasicSymbolsMill.initializeString();
  }
  
  @Override
  public void run(String[] args) {
    init();
    Options options = initOptions();
    
    try {
      // create CLI parser and parse input options from command line
      CommandLineParser cliparser = new DefaultParser();
      CommandLine cmd = cliparser.parse(options, args);
      
      // help: when --help
      if (cmd.hasOption("h")) {
        printHelp(options);
        return;
      }
      
      if (cmd.hasOption("v")) {
        printVersion();
        return;
      }
      
      if (!cmd.hasOption("i")) {
        Log.error(Messages.get("0xWFM0007"));
        return;
      }
      
      String[] modelPath = { "." };
      if (cmd.hasOption("path")) {
        modelPath = cmd.getOptionValues("path");
      }
      for (String path : modelPath) {
        WorkflowMill.globalScope().getSymbolPath().addEntry(Paths.get(path));
      }
      
      // load the process model from the specified file
      String modelFile = cmd.getOptionValue("i");
      Optional<ASTWorkflowCompilationUnit> model = loadModel(modelFile);
      if (model.isEmpty()) {
        Log.error(Messages.get("0xWFM0004", modelFile));
        return;
      }
      
      // pretty-printing
      if (cmd.hasOption("pp")) {
        prettyPrint(model.get(), cmd.getOptionValue("pp", ""));
      }
      
      // print out a serialized symbol table
      if (cmd.hasOption("s")) {
        storeSymbols((IWorkflowArtifactScope) model.get().getEnclosingScope(), cmd.getOptionValue(
            "s"));
      }
      
      // conformance checking to a reference model
      if (cmd.hasOption("ref")) {
        
        // load the reference model
        String refFile = cmd.getOptionValue("ref");
        Optional<ASTWorkflowCompilationUnit> reference = loadModel(refFile);
        if (reference.isEmpty()) {
          Log.error(Messages.get("0xWFM0005", refFile));
          return;
        }
        
        // specify the name of the incarnation mapping encoded via stereotypes
        String mapping = "incarnates";
        if (cmd.hasOption("m")) {
          mapping = cmd.getOptionValue("m");
        }
        WfConformanceChecker checker = new WfConformanceChecker();
        checker.checkConformance(model.get(), reference.get(), mapping);
      }
      
      // execution with the workflow engine
      if (cmd.hasOption("x")) {
        final WorkflowRunner runner = new WorkflowRunner(System.out);
        for (String path : modelPath) {
          runner.withSymbolPath(Paths.get(path));
        }
        if (cmd.hasOption("var")) {
          for (String assignment : cmd.getOptionValues("var")) {
            runner.withInitialValue(assignment);
          }
        }
        final String script = cmd.getOptionValue("x");
        if (script == null) {
          executionResult = runner.run(Path.of(modelFile), (java.io.BufferedReader) null);
        }
        else {
          try (
              java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(Path.of(script))
          ) {
            executionResult = runner.run(Path.of(modelFile), reader);
          }
          catch (java.io.IOException e) {
            Log.error("0xWFM0008 The script '" + script + "' could not be read: " + e.getMessage());
          }
        }
      }
      
    }
    catch (ParseException e) {
      Log.error(Messages.get("0xWFM0006", e.getMessage()));
    }
  }
  
  @Override
  public void runDefaultCoCos(ASTWorkflowCompilationUnit ast) {
    WorkflowCoCoChecker checker = WorkflowCoCos.getBasicChecker();
    checker.checkAll(ast);
  }
  
  @Override
  public void prettyPrint(ASTWorkflowCompilationUnit ast, String file) {
    if (!file.isEmpty()) {
      super.prettyPrint(ast, file);
    }
    else {
      System.out.println(WorkflowMill.prettyPrint(ast, true));
    }
  }
  
  /**
   * Initializes the additional options for the BPMNConformance tool.
   *
   * @return The CLI options with arguments.
   */
  @Override
  public Options addAdditionalOptions(Options options) {
    
    // introduce the reference BPMN
    Option reference = Option.builder("ref").longOpt("reference").desc(
        "Parses the file as a reference process model and checks if the the input process model specified by `-i` is conform to it. ")
        .numberOfArgs(1).build();
    options.addOption(reference);
    
    // introduce the reference BPMN
    Option map = Option.builder("m").optionalArg(true).longOpt("map").desc(
        "Specify the names of stereotypes that are used as incarnation mappings in the concrete model. Default : 'incarnates'")
        .numberOfArgs(1).build();
    options.addOption(map);
    
    // execute the process model
    Option execute = Option.builder("x").longOpt("execute").desc(
        "Executes the input process model specified by `-i` with the workflow engine and prints what happens. The optional argument is a script that drives the instance; without it the instance is driven automatically.")
        .optionalArg(true).numberOfArgs(1).build();
    options.addOption(execute);
    
    Option variable = Option.builder("var").longOpt("variable").desc(
        "An initial value of a data object for the execution, e.g., `-var amount=150`. May be given several times.")
        .hasArgs().build();
    options.addOption(variable);
    
    return options;
  }
  
  protected Optional<ASTWorkflowCompilationUnit> loadModel(String file) {
    ASTWorkflowCompilationUnit model = parse(file);
    
    if (model == null) {
      return Optional.empty();
    }
    
    new AddMoreImports(Lists.newArrayList(OCL_TYPES)).transform(model);
    WorkflowMill.scopesGenitorDelegator().createFromAST(model);
    new AddNameToInlineFlowNodes().transform(model);
    new AddSequenceFlowToFlowNodes().transform(model);
    
    WorkflowSTCompleter stCompleter = new WorkflowSTCompleter();
    WorkflowTraverser traverser = WorkflowMill.traverser();
    traverser.add4Workflow(stCompleter);
    model.accept(traverser);
    
    // FullChecker still has some issues
    WorkflowCoCoChecker checker = WorkflowCoCos.getBasicChecker();
    checker.checkAll(model);
    
    return Optional.of(model);
  }
  
}
