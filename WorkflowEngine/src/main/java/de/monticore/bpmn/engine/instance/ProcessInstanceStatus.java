/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.instance;

/** The life cycle of a process instance. */
public enum ProcessInstanceStatus {
  
  /** The instance carries at least one token and waits for the environment. */
  ACTIVE,
  
  /** All tokens reached an end event. */
  COMPLETED,
  
  /** A terminate event ended the instance before it was completed. */
  TERMINATED
  
}
