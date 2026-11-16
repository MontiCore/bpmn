/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.instance;

/** The life cycle of a process instance. */
public enum ProcessInstanceStatus {
  
  /** The instance carries at least one token and waits for the environment. */
  ACTIVE,
  
  /** All tokens reached the end of their path. */
  COMPLETED,
  
  /** A terminate event ended the instance before it was completed. */
  TERMINATED,
  
  /**
   * The instance ended abnormally and cannot continue: an error was thrown that nobody caught, or
   * the instance is stuck in a deadlock. {@link ProcessInstance#getFailure()} tells why.
   */
  FAILED
  
}
