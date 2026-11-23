/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.history;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry of the audit trail of a process instance.
 *
 * <p>The engine writes the entries from the trace of every step of the interpreter, cf.
 * {@link de.monticore.bpmn.interpreter.BPMNState#getTrace()}. Every execution of an activity
 * therefore has its own start and end, also the iterations of a loop.
 */
public class HistoryEvent {
  
  public enum Type {
    INSTANCE_STARTED,
    
    /** An activity or one iteration of a looping activity started. */
    ACTIVITY_STARTED,
    
    /** An activity or one iteration of a looping activity ended regularly. */
    ACTIVITY_ENDED,
    
    /** An activity was interrupted, e.g., by a boundary event. */
    ACTIVITY_CANCELLED,
    
    /** An event occurred: it was thrown or its trigger was caught. */
    EVENT_THROWN,
    
    INSTANCE_COMPLETED,
    
    INSTANCE_TERMINATED,
    
    /** The instance ended abnormally; the detail tells why. */
    INSTANCE_FAILED,
    
    /** A step could not be performed and left the instance unchanged; the detail tells why. */
    STEP_FAILED
  }
  
  protected final String instanceId;
  
  protected final Type type;
  
  /** The flow element this entry is about, empty for entries about the instance itself. */
  protected final String elementName;
  
  /** Further information, e.g., the reason of a failure; empty for most entries. */
  protected final String detail;
  
  protected final Instant occurredAt;
  
  public HistoryEvent(final String instanceId, final Type type, final String elementName,
      final Instant occurredAt) {
    this(instanceId, type, elementName, "", occurredAt);
  }
  
  public HistoryEvent(final String instanceId, final Type type, final String elementName,
      final String detail, final Instant occurredAt) {
    this.instanceId = Objects.requireNonNull(instanceId);
    this.type = Objects.requireNonNull(type);
    this.elementName = elementName == null ? "" : elementName;
    this.detail = detail == null ? "" : detail;
    this.occurredAt = Objects.requireNonNull(occurredAt);
  }
  
  public String getInstanceId() { return instanceId; }
  
  public Type getType() { return type; }
  
  public String getElementName() { return elementName; }
  
  public String getDetail() { return detail; }
  
  public Instant getOccurredAt() { return occurredAt; }
  
  @Override
  public String toString() {
    return occurredAt + " " + instanceId + " " + type + (elementName.isEmpty() ? "" : " "
        + elementName) + (detail.isEmpty() ? "" : " (" + detail + ")");
  }
  
}
