/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import java.util.List;

/**
 * A step of a process instance could not be performed.
 *
 * <p>A step is atomic: when it fails, nothing of it is stored and the instance is exactly in the
 * state it was in before. The caller can remove the cause, e.g., provide a missing value, and try
 * again. Typical causes are a condition that cannot be evaluated, a gateway without an enabled
 * outgoing flow, or a signal the instance does not wait for.
 */
public class ProcessExecutionException extends IllegalStateException {
  
  private static final long serialVersionUID = 1L;
  
  protected final List<String> errors;
  
  public ProcessExecutionException(final String message, final List<String> errors) {
    super(errors.isEmpty() ? message : message + " " + String.join(" ", errors));
    this.errors = List.copyOf(errors);
  }
  
  public ProcessExecutionException(final String message, final Throwable cause) {
    super(message + " " + cause, cause);
    this.errors = List.of(String.valueOf(cause));
  }
  
  /** @return what went wrong, one entry per reported error */
  public List<String> getErrors() {
    return errors;
  }
  
}
