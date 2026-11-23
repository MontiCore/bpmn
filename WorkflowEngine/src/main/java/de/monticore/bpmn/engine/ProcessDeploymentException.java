/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import java.util.List;

/**
 * A process model could not be deployed because it cannot be parsed or violates the context
 * conditions of the Workflow language. Nothing has been stored.
 */
public class ProcessDeploymentException extends IllegalArgumentException {
  
  private static final long serialVersionUID = 1L;
  
  protected final List<String> errors;
  
  public ProcessDeploymentException(final String message, final List<String> errors) {
    super(errors.isEmpty() ? message : message + " " + String.join(" ", errors));
    this.errors = List.copyOf(errors);
  }
  
  /** @return the errors of the model, one entry per finding */
  public List<String> getErrors() {
    return errors;
  }
  
}
