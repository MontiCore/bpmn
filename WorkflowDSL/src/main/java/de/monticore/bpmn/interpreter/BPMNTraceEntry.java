/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import static com.google.common.base.Preconditions.checkNotNull;

import de.monticore.bpmn.workflow._ast.ASTFlowElement;

/**
 * One thing that happened to a flow element during a step of the {@link BPMNInterpreter}.
 *
 * <p>A {@link BPMNState} only describes where the tokens are. Two states cannot tell what happened
 * in between: an activity that ends and starts again within the same step is running before and
 * after. The interpreter therefore records every occurrence in the trace of the resulting state,
 * cf. {@link BPMNState#getTrace()}. An engine derives its audit trail and its timers from it.
 */
public class BPMNTraceEntry {
  
  public enum Kind {
    
    /** An activity has been activated; its boundary events are armed from now on. */
    ACTIVITY_STARTED,
    
    /** An iteration of a looping activity ended and the next one began; it stays active. */
    ACTIVITY_REPEATED,
    
    /** An activity ended regularly and passed its token on. */
    ACTIVITY_COMPLETED,
    
    /** An activity has been interrupted, e.g., by a boundary event or a terminate event. */
    ACTIVITY_CANCELLED,
    
    /** A catching event got a token and waits for its trigger from now on. */
    EVENT_ARMED,
    
    /** A catching event stopped waiting without having occurred. */
    EVENT_DISARMED,
    
    /** An event occurred, i.e., it has been thrown or its trigger has been caught. */
    EVENT_OCCURRED
  }
  
  protected final Kind kind;
  
  protected final ASTFlowElement element;
  
  public BPMNTraceEntry(final Kind kind, final ASTFlowElement element) {
    this.kind = checkNotNull(kind);
    this.element = checkNotNull(element);
  }
  
  public Kind getKind() { return kind; }
  
  public ASTFlowElement getElement() { return element; }
  
  @Override
  public String toString() { return kind + " " + element.getName(); }
  
}
