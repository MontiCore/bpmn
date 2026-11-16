/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.runner.WorkflowRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Executes the examples. Every script in the folder {@code examples} is run on the model it
 * belongs to: {@code Order.script} and {@code Order.default.script} both run {@code Order.wfm}. A
 * script states what it expects, so an example that does not behave as described fails here.
 */
public class ExamplesTest extends AbstractEngineTest {
  
  protected static final Path EXAMPLES = Path.of("examples");
  
  protected static String run(final String... args) {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final int result = new WorkflowRunner(new PrintStream(output, true, StandardCharsets.UTF_8))
        .run(args);
    final String text = output.toString(StandardCharsets.UTF_8);
    assertEquals(0, result, text);
    return text;
  }
  
  @TestFactory
  public Stream<DynamicTest> testEveryExampleBehavesAsItsScriptExpects() throws IOException {
    final List<Path> scripts;
    try (
        Stream<Path> files = Files.list(EXAMPLES)
    ) {
      scripts = files.filter(file -> file.getFileName().toString().endsWith(".script")).sorted()
          .collect(Collectors.toList());
    }
    assertFalse(scripts.isEmpty());
    return scripts.stream().map(script -> DynamicTest.dynamicTest(script.getFileName().toString(),
        () -> {
          setUp(); // the examples share the global state of MontiCore
          final String name = script.getFileName().toString();
          final Path model = EXAMPLES.resolve(name.substring(0, name.indexOf('.')) + ".wfm");
          final String output = run(model.toString(), "-path", EXAMPLES.toString(), "-script",
              script.toString());
          assertTrue(output.contains("ok: status is"), output);
        }));
  }
  
  @Test
  public void testWithoutAScriptTheInstanceIsDrivenAutomatically() {
    final String output = run(EXAMPLES.resolve("Approval.wfm").toString());
    
    // the task is completed first, so no timer ever elapses
    assertTrue(output.contains("> complete Approve"), output);
    assertTrue(output.contains("Result: COMPLETED after 1 step(s)"), output);
  }
  
  @Test
  public void testInitialValuesCanBeGivenAsArguments() {
    final String output = run(EXAMPLES.resolve("Order.wfm").toString(), "-var", "amount=20");
    
    assertTrue(output.contains("> complete Reject"), output);
    assertTrue(output.contains("Result: COMPLETED"), output);
  }
  
  @Test
  public void testAFailedExpectationFailsTheRun() throws IOException {
    final Path script = Files.createTempFile("wrong", ".script");
    Files.writeString(script, "set amount = 150\ncomplete Check\nexpect open Reject\n");
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    
    final int result = new WorkflowRunner(new PrintStream(output, true, StandardCharsets.UTF_8))
        .run(new String[] { EXAMPLES.resolve("Order.wfm").toString(), "-script", script
            .toString() });
    
    assertEquals(1, result);
    assertTrue(output.toString(StandardCharsets.UTF_8).contains("FAILED: Expected open"));
    Files.delete(script);
  }
  
}
