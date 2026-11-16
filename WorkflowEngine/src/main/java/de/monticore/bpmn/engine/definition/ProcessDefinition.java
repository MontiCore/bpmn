/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.definition;

import de.monticore.bpmn.workflow._ast.ASTWFProcess;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One deployed version of a process model, i.e., a row of the process definition table.
 *
 * <p>The durable part is the {@link #source}: the engine parses it again after a restart. The
 * parsed {@link #process} is only a cache and is not part of the persisted state.
 */
public class ProcessDefinition {
  
  /** Technical identity of this version, e.g., "Order:2". */
  protected final String id;
  
  /** Business identity, i.e., the name of the process; stable across versions. */
  protected final String key;
  
  protected final int version;
  
  protected final String source;
  
  protected final Instant deployedAt;
  
  /** Not persisted; rebuilt from {@link #source} when the definition is loaded. */
  protected transient ASTWFProcess process;
  
  /** Not persisted; the constructs of the model that the engine executes in a simplified way. */
  protected transient List<String> limitations = List.of();
  
  public ProcessDefinition(final String key, final int version, final String source,
      final Instant deployedAt, final ASTWFProcess process) {
    this.key = Objects.requireNonNull(key);
    this.version = version;
    this.source = Objects.requireNonNull(source);
    this.deployedAt = Objects.requireNonNull(deployedAt);
    this.process = process;
    this.id = key + ":" + version;
  }
  
  public String getId() { return id; }
  
  public String getKey() { return key; }
  
  public int getVersion() { return version; }
  
  public String getSource() { return source; }
  
  public Instant getDeployedAt() { return deployedAt; }
  
  public boolean isPresentProcess() { return process != null; }
  
  public ASTWFProcess getProcess() {
    if (process == null) {
      throw new IllegalStateException("The model of '" + id + "' has not been parsed yet.");
    }
    return process;
  }
  
  public void setProcess(final ASTWFProcess process) { this.process = process; }
  
  /**
   * @return the constructs of the model that the engine does not execute as BPMN defines them,
   * one message per construct; empty if the process is executed as modelled
   */
  public List<String> getLimitations() { return limitations; }
  
  public void setLimitations(final List<String> limitations) {
    this.limitations = List.copyOf(limitations);
  }
  
  @Override
  public String toString() { return "ProcessDefinition[" + id + "]"; }
  
}
