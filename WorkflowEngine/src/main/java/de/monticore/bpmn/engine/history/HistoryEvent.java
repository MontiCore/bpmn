/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.history;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry of the audit trail of a process instance.
 *
 * <p>The interpreter itself keeps no history: it only knows the current state. Visibility and audit
 * data are therefore produced by the engine, which compares the state before and after every step.
 */
public class HistoryEvent {
  
  public enum Type {
    INSTANCE_STARTED, ACTIVITY_STARTED, ACTIVITY_ENDED, EVENT_THROWN, INSTANCE_COMPLETED,
    INSTANCE_TERMINATED
  }
  
  protected final String instanceId;
  
  protected final Type type;
  
  /** The flow element this entry is about, empty for entries about the instance itself. */
  protected final String elementName;
  
  protected final Instant occurredAt;
  
  public HistoryEvent(final String instanceId, final Type type, final String elementName,
      final Instant occurredAt) {
    this.instanceId = Objects.requireNonNull(instanceId);
    this.type = Objects.requireNonNull(type);
    this.elementName = elementName == null ? "" : elementName;
    this.occurredAt = Objects.requireNonNull(occurredAt);
  }
  
  public String getInstanceId() { return instanceId; }
  
  public Type getType() { return type; }
  
  public String getElementName() { return elementName; }
  
  public Instant getOccurredAt() { return occurredAt; }
  
  @Override
  public String toString() {
    return occurredAt + " " + instanceId + " " + type + (elementName.isEmpty() ? "" : " "
        + elementName);
  }
  
}
