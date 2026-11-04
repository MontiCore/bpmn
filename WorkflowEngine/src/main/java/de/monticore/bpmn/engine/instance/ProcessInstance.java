/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.instance;

import de.monticore.bpmn.engine.state.PersistentProcessState;
import java.time.Instant;
import java.util.Objects;

/**
 * One running or finished execution of a process definition, i.e., a row of the process instance
 * table.
 *
 * <p>An instance is pinned to the definition version it was started with, so a new deployment
 * never changes the behavior of instances that are already running.
 */
public class ProcessInstance {
  
  protected final String id;
  
  /** The definition version this instance runs on, e.g., "Order:2". */
  protected final String definitionId;
  
  protected final Instant createdAt;
  
  protected ProcessInstanceStatus status;
  
  protected PersistentProcessState state;
  
  protected Instant updatedAt;
  
  public ProcessInstance(final String id, final String definitionId,
      final PersistentProcessState state, final Instant createdAt) {
    this.id = Objects.requireNonNull(id);
    this.definitionId = Objects.requireNonNull(definitionId);
    this.state = Objects.requireNonNull(state);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.updatedAt = createdAt;
    this.status = ProcessInstanceStatus.ACTIVE;
  }
  
  public String getId() { return id; }
  
  public String getDefinitionId() { return definitionId; }
  
  public Instant getCreatedAt() { return createdAt; }
  
  public Instant getUpdatedAt() { return updatedAt; }
  
  public ProcessInstanceStatus getStatus() { return status; }
  
  public void setStatus(final ProcessInstanceStatus status) { this.status = status; }
  
  public PersistentProcessState getState() { return state; }
  
  public void setState(final PersistentProcessState state, final Instant updatedAt) {
    this.state = Objects.requireNonNull(state);
    this.updatedAt = updatedAt;
  }
  
  public boolean isActive() { return status == ProcessInstanceStatus.ACTIVE; }
  
  @Override
  public String toString() {
    return "ProcessInstance[" + id + ", " + definitionId + ", " + status + ", " + state + "]";
  }
  
}
