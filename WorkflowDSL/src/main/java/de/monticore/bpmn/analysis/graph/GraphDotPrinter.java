/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.analysis.graph;

import com.google.common.graph.EndpointPair;
import de.monticore.bpmn.workflow._ast.ASTFlowElement;
import java.io.IOException;
import java.io.StringWriter;
import org.jgrapht.Graph;
import org.jgrapht.nio.ExportException;
import org.jgrapht.nio.GraphExporter;
import org.jgrapht.nio.dot.DOTExporter;

/** Prints the control flow graph into the dot file format. */
public class GraphDotPrinter {
  
  public static String print(final Graph<ASTFlowElement, EndpointPair<ASTFlowElement>> graph)
      throws IOException {
    final GraphExporter<ASTFlowElement, EndpointPair<ASTFlowElement>> exporter = new DOTExporter<>(
        ASTFlowElement::getName);
    // write dot file
    final StringWriter writer = new StringWriter();
    try {
      exporter.exportGraph(graph, writer);
    }
    catch (ExportException e) {
      throw new IOException(e);
    }
    
    return writer.toString();
  }
  
}
