/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn;

import de.se_rwth.commons.logging.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class WorkflowToolTest {
  
  protected static final String MODEL_DIR = "src/test/resources/de/monticore/bpmn/";
  protected static final String TARGET_DIR = "target/test/de/monticore/bpmn/";
  
  /** The examples of the workflow engine, which the README executes with this tool. */
  protected static final String EXAMPLES_DIR = "../WorkflowEngine/examples/";
  
  /** Where the symbol file of the default types lies that the tool adds to every model. */
  protected static final String DEFAULT_TYPES_DIR = "../WorkflowDSL/src/test/resources/";
  
  /** The byte order mark that Windows tools put in front of a text in UTF-8. */
  protected static final byte[] BYTE_ORDER_MARK = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
  
  @BeforeEach
  public void setup() {
    Log.init();
    Log.getFindings().clear();
    Log.enableFailQuick(false);
  }
  
  /**
   * Runs the tool without leaving the JVM, which {@code main} does if an execution fails.
   *
   * @return what the tool printed
   */
  protected String run(final WorkflowTool tool, final String... args) {
    final PrintStream console = System.out;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
    try {
      tool.run(args);
    }
    finally {
      System.setOut(console);
    }
    return output.toString(StandardCharsets.UTF_8);
  }
  
  @Test
  public void testConformance() {
    String[] conformance = new String[] { "-i", MODEL_DIR + "conformance/Thesis.wfm", "-ref",
        MODEL_DIR + "conformance/PaperAuthoring.wfm", "-m", "ref" };
    WorkflowTool.main(conformance);
    Assertions.assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testNonConformance() {
    String[] conformance = new String[] { "-i", MODEL_DIR + "conformance/AntiPatternMerge.wfm",
        "-ref", MODEL_DIR + "conformance/PaperAuthoring.wfm", "-m", "ref" };
    WorkflowTool.main(conformance);
    Assertions.assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testPrettyPrint() {
    String[] conformance = new String[] { "-i", MODEL_DIR + "examples/OrderToDeliveryWorkflow.wfm",
        "-path", "src/test/resources/", "-pp", TARGET_DIR
            + "examples/OrderToDeliveryWorkflow.wfm" };
    WorkflowTool.main(conformance);
    Assertions.assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testPrettyPrintToStdOut() {
    String[] conformance = new String[] { "-i", MODEL_DIR + "examples/OrderToDeliveryWorkflow.wfm",
        "-path", "src/test/resources/", "-pp" };
    WorkflowTool.main(conformance);
    Assertions.assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testStoreSymbolTable() {
    String[] conformance = new String[] { "-i", MODEL_DIR + "examples/OrderToDeliveryWorkflow.wfm",
        "-path", "src/test/resources/", "-s", TARGET_DIR
            + "examples/OrderToDeliveryWorkflow.wfsym" };
    WorkflowTool.main(conformance);
    Assertions.assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testExecuteAutomatically() {
    final WorkflowTool tool = new WorkflowTool();
    
    final String output = run(tool, "-i", EXAMPLES_DIR + "Order.wfm", "-x", "-var", "amount=150");
    
    Assertions.assertEquals(0, tool.getExecutionResult(), output);
    Assertions.assertTrue(output.contains("Started an instance with {amount=150}"), output);
    Assertions.assertTrue(output.contains("> complete Approve"), output);
    Assertions.assertTrue(output.contains("Result: COMPLETED after 2 step(s)"), output);
    Assertions.assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testExecuteScript() {
    final WorkflowTool tool = new WorkflowTool();
    
    final String output = run(tool, "-i", EXAMPLES_DIR + "Approval.wfm", "-x", EXAMPLES_DIR
        + "Approval.script");
    
    Assertions.assertEquals(0, tool.getExecutionResult(), output);
    Assertions.assertTrue(output.contains("timer Timeout elapsed"), output);
    Assertions.assertTrue(output.contains("ok: status is COMPLETED"), output);
    Assertions.assertTrue(Log.getFindings().isEmpty());
  }
  
  @Test
  public void testExecuteModelWithTypesOfAClassDiagram() {
    final WorkflowTool tool = new WorkflowTool();
    
    // the symbol file of the class diagram is found via the option -path
    final String output = run(tool, "-i", EXAMPLES_DIR + "OrderToDeliveryWorkflow.wfm", "-path",
        EXAMPLES_DIR, "-x", EXAMPLES_DIR + "OrderToDeliveryWorkflow.script");
    
    Assertions.assertEquals(0, tool.getExecutionResult(), output);
    Assertions.assertTrue(output.contains("ok: status is COMPLETED"), output);
    Assertions.assertEquals(0, Log.getErrorCount());
  }
  
  @Test
  public void testExecuteModelWithDefaultTypes() {
    final WorkflowTool tool = new WorkflowTool();
    
    // the model uses the type Date without importing it; the tool adds the import, and the
    // execution has to see the model the same way
    final String output = run(tool, "-i", MODEL_DIR + "examples/Deadline.wfm", "-path",
        DEFAULT_TYPES_DIR, "-x", "-var", "urgent=true");
    
    Assertions.assertEquals(0, tool.getExecutionResult(), output);
    Assertions.assertTrue(output.contains("> complete Expedite"), output);
    Assertions.assertTrue(output.contains("Result: COMPLETED"), output);
    Assertions.assertEquals(0, Log.getErrorCount());
  }
  
  @Test
  public void testExecuteReportsAFailedExpectation() throws IOException {
    final Path script = Files.createTempFile("wrong", ".script");
    Files.writeString(script, "set amount = 150\ncomplete Check\nexpect open Reject\n");
    final WorkflowTool tool = new WorkflowTool();
    
    final String output = run(tool, "-i", EXAMPLES_DIR + "Order.wfm", "-x", script.toString());
    
    Assertions.assertEquals(1, tool.getExecutionResult(), output);
    Assertions.assertTrue(output.contains("FAILED: Expected open"), output);
    Files.delete(script);
  }
  
  @Test
  public void testExecuteCommandsFromStandardInput() {
    final InputStream keyboard = System.in;
    System.setIn(new ByteArrayInputStream(
        "help\nset amount = 20\ncomplete Check\nexit\ncomplete Reject\n".getBytes(
            StandardCharsets.UTF_8)));
    final WorkflowTool tool = new WorkflowTool();
    final String output;
    try {
      output = run(tool, "-i", EXAMPLES_DIR + "Order.wfm", "-x", "-");
    }
    finally {
      System.setIn(keyboard);
    }
    
    Assertions.assertEquals(0, tool.getExecutionResult(), output);
    Assertions.assertTrue(output.contains("'help' lists the commands"), output);
    Assertions.assertTrue(output.contains("complete <activity>"), output);
    Assertions.assertTrue(output.contains("open: Reject"), output);
    // nothing is read after the command exit
    Assertions.assertFalse(output.contains("> complete Reject"), output);
    Assertions.assertTrue(output.contains("Result: ACTIVE after 1 step(s)"), output);
  }
  
  /** @return the text in UTF-8 with a byte order mark in front, lines end as on Windows */
  protected static byte[] withByteOrderMark(final String... lines) {
    final byte[] content = (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    final byte[] result = new byte[BYTE_ORDER_MARK.length + content.length];
    System.arraycopy(BYTE_ORDER_MARK, 0, result, 0, BYTE_ORDER_MARK.length);
    System.arraycopy(content, 0, result, BYTE_ORDER_MARK.length, content.length);
    return result;
  }
  
  @Test
  public void testExecuteScriptThatStartsWithAByteOrderMark() throws IOException {
    // e.g., a script that was written with the redirection operator of PowerShell
    final Path script = Files.createTempFile("marked", ".script");
    Files.write(script, withByteOrderMark("set amount = 150", "complete Check",
        "expect open Approve"));
    final WorkflowTool tool = new WorkflowTool();
    
    final String output = run(tool, "-i", EXAMPLES_DIR + "Order.wfm", "-x", script.toString());
    
    Assertions.assertEquals(0, tool.getExecutionResult(), output);
    Assertions.assertTrue(output.contains("> set amount = 150"), output);
    Assertions.assertTrue(output.contains("ok: open is [Approve]"), output);
    Files.delete(script);
  }
  
  @Test
  public void testExecuteCommandsFromStandardInputWithAByteOrderMark() {
    // e.g., commands that PowerShell pipes into the tool
    final InputStream keyboard = System.in;
    System.setIn(new ByteArrayInputStream(withByteOrderMark("set amount = 20", "complete Check",
        "exit")));
    final WorkflowTool tool = new WorkflowTool();
    final String output;
    try {
      output = run(tool, "-i", EXAMPLES_DIR + "Order.wfm", "-x", "-");
    }
    finally {
      System.setIn(keyboard);
    }
    
    Assertions.assertEquals(0, tool.getExecutionResult(), output);
    Assertions.assertTrue(output.contains("Started an instance with {amount=20}"), output);
    Assertions.assertTrue(output.contains("open: Reject"), output);
  }
  
}
