/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.workflowwithmetrics._ast;

import de.monticore.bpmn.workflow._ast.SequenceFlow;

import java.util.List;
import java.util.stream.Stream;

public class ASTMetricsBlock extends de.monticore.bpmn.workflowwithmetrics._ast.ASTMetricsBlockTOP {
  
  @Override
  public boolean addIncomings(SequenceFlow element) {
    return false;
  }
  
  @Override
  public boolean addOutgoings(SequenceFlow element) {
    return false;
  }
  
  @Override
  public List<SequenceFlow> getIncomingsList() { return List.of(); }
  
  @Override
  public List<SequenceFlow> getOutgoingsList() { return List.of(); }
  
  @Override
  public boolean isEmptyIncomings() { return false; }
  
  @Override
  public boolean isEmptyOutgoings() { return false; }
  
  @Override
  public int sizeIncomings() {
    return 0;
  }
  
  @Override
  public int sizeOutgoings() {
    return 0;
  }
  
  @Override
  public Stream<SequenceFlow> streamOutgoings() {
    return Stream.empty();
  }
  
  @Override
  public Stream<SequenceFlow> streamIncomings() {
    return Stream.empty();
  }
  
}
