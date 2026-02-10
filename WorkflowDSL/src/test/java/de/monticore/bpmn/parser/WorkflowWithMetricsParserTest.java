/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.parser;

import de.monticore.bpmn.metrictags.MetricTagsMill;
import de.monticore.bpmn.metrictags._ast.ASTMetricTag;
import de.monticore.bpmn.workflow.WorkflowTool;
import de.monticore.bpmn.workflow._ast.ASTWFTask;
import de.monticore.bpmn.workflow._ast.ASTWorkflowCompilationUnit;
import de.monticore.bpmn.workflow._symboltable.IWorkflowArtifactScope;
import de.monticore.bpmn.workflow._visitor.WorkflowVisitor2;
import de.monticore.bpmn.workflowwithmetrics.WorkflowWithMetricsMill;
import de.monticore.siunit.siunits.SIUnitsMill;
import de.monticore.tagging.SimpleSymbolTagger;
import de.monticore.tagging.TagRepository;
import de.monticore.tagging.tags._ast.ASTComplexTag;
import de.monticore.tagging.tags._ast.ASTTag;
import de.monticore.tagging.tags._ast.ASTValuedTag;
import de.se_rwth.commons.logging.Log;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkflowWithMetricsParserTest {
  
  @Test
  public void testValidModels() throws IOException {
    WorkflowWithMetricsMill.init();
    Optional<ASTWorkflowCompilationUnit> wfmOpt = WorkflowWithMetricsMill.parser().parse(
        "src/test/resources/de/monticore/bpmn/metrics/ProcessWithMetrics.wfm");
    
    assertTrue(wfmOpt.isPresent());
  }
  
  @Test
  public void testParseMetricDef() throws IOException {
    WorkflowWithMetricsMill.init();
    WorkflowWithMetricsMill.parser().parse_StringMetricDef("someName: s");
  }
  
  @Test
  public void testParseMetricBlock() throws IOException {
    WorkflowWithMetricsMill.init();
    WorkflowWithMetricsMill.parser().parse_StringMetricsBlock(
        "metrics { someName: s; } for group g1 { t1; }");
  }
  
  @Test
  public void testParseGenericTags() throws IOException {
    TagRepository.clearTags();
    Log.getFindings().clear();
    
    var metricsTags = TagRepository.loadTagModel(new File(
        "src/test/resources/de/monticore/bpmn/metrics/MetricsGeneric.tag"));
    assertTrue(Log.getFindings().isEmpty());
    
    WorkflowWithMetricsMill.init();
    Optional<ASTWorkflowCompilationUnit> wfmOpt = WorkflowWithMetricsMill.parser().parse(
        "src/test/resources/de/monticore/bpmn/metrics/ProcessWithoutMetrics.wfm");
    assertTrue(wfmOpt.isPresent());
    
    var wfm = wfmOpt.get();
    IWorkflowArtifactScope artifactScope = new WorkflowTool().createSymbolTable(wfm);
    WorkflowWithMetricsMill.globalScope().addSubScope(artifactScope);
    
    var tagger = new SimpleSymbolTagger(TagRepository::getLoadedTagUnits);
    
    var t = WorkflowWithMetricsMill.inheritanceTraverser();
    t.add4Workflow(new WorkflowVisitor2() {
      
      @Override
      public void visit(ASTWFTask node) {
        List<ASTTag> tags = tagger.getTags(node.getSymbol());
        assertEquals(1, tags.size());
        ASTTag tag = tags.get(0);
        
        assertTrue(tag instanceof ASTComplexTag);
        ASTComplexTag complexTag = (ASTComplexTag) tag;
        
        assertEquals("Metric", complexTag.getName());
        assertEquals(2, complexTag.sizeTags());
        
        var nameTag = (ASTValuedTag) complexTag.streamTags().filter(t -> t instanceof ASTValuedTag
            && ((ASTValuedTag) t).getName().equals("name")).findAny().get();
        var unit = (ASTValuedTag) complexTag.streamTags().filter(t -> t instanceof ASTValuedTag
            && ((ASTValuedTag) t).getName().equals("unit")).findAny().get();
        
        assertEquals("duration", nameTag.getValue());
        assertEquals("s", unit.getValue());
      }
      
    });
    wfm.accept(t);
  }
  
  @Test
  public void testParseSpecificLanguageTags() throws IOException {
    TagRepository.clearTags();
    Log.getFindings().clear();
    
    MetricTagsMill.init();
    var metricsTags = TagRepository.loadTagModel(new File(
        "src/test/resources/de/monticore/bpmn/metrics/Metrics.tag"));
    assertTrue(Log.getFindings().isEmpty());
    
    WorkflowWithMetricsMill.init();
    Optional<ASTWorkflowCompilationUnit> wfmOpt = WorkflowWithMetricsMill.parser().parse(
        "src/test/resources/de/monticore/bpmn/metrics/ProcessWithoutMetrics.wfm");
    assertTrue(wfmOpt.isPresent());
    
    var wfm = wfmOpt.get();
    IWorkflowArtifactScope artifactScope = new WorkflowTool().createSymbolTable(wfm);
    WorkflowWithMetricsMill.globalScope().addSubScope(artifactScope);
    
    var tagger = new SimpleSymbolTagger(TagRepository::getLoadedTagUnits);
    
    var t = WorkflowWithMetricsMill.inheritanceTraverser();
    t.add4Workflow(new WorkflowVisitor2() {
      
      @Override
      public void visit(ASTWFTask node) {
        List<ASTTag> tags = tagger.getTags(node.getSymbol());
        assertEquals(1, tags.size());
        ASTTag tag = tags.get(0);
        
        assertTrue(tag instanceof ASTMetricTag);
        var metricTag = (ASTMetricTag) tag;
        
        assertEquals("Metric", metricTag.getName());
        
        assertEquals("duration", metricTag.getMetricName());
        assertEquals("s", SIUnitsMill.prettyPrint(metricTag.getType(), true));
      }
      
    });
    wfm.accept(t);
  }
  
}
