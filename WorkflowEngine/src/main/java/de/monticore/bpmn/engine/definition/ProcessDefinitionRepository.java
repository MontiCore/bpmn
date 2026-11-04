/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.definition;

import java.util.List;
import java.util.Optional;

/**
 * The process definition table. Implement this to store deployments in a database; the engine only
 * relies on this interface.
 */
public interface ProcessDefinitionRepository {
  
  void save(ProcessDefinition definition);
  
  Optional<ProcessDefinition> findById(String id);
  
  /** @return the highest deployed version of the process */
  Optional<ProcessDefinition> findLatest(String key);
  
  Optional<ProcessDefinition> find(String key, int version);
  
  List<ProcessDefinition> findAll();
  
  /** @return the version a new deployment of this process gets, i.e., the highest one plus one */
  int nextVersion(String key);
  
}
