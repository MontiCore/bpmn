/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.instance;

import java.util.List;
import java.util.Optional;

/**
 * The process instance table. Implement this to keep instances in a database; the engine itself
 * holds no instance state, so it stays stateless between two calls.
 */
public interface ProcessInstanceRepository {
  
  void save(ProcessInstance instance);
  
  Optional<ProcessInstance> findById(String id);
  
  List<ProcessInstance> findByDefinitionId(String definitionId);
  
  List<ProcessInstance> findActive();
  
  List<ProcessInstance> findAll();
  
}
