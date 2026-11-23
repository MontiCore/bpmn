/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.instance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Default implementation, sufficient for tests and for an engine without a database. Its methods
 * are synchronized, so that it can be read while another thread uses the engine.
 */
public class InMemoryProcessInstanceRepository implements ProcessInstanceRepository {
  
  protected final Map<String, ProcessInstance> instances = new LinkedHashMap<>();
  
  @Override
  public synchronized void save(final ProcessInstance instance) {
    instances.put(instance.getId(), instance);
  }
  
  @Override
  public synchronized Optional<ProcessInstance> findById(final String id) {
    return Optional.ofNullable(instances.get(id));
  }
  
  @Override
  public synchronized List<ProcessInstance> findByDefinitionId(final String definitionId) {
    return instances.values().stream().filter(i -> i.getDefinitionId().equals(definitionId))
        .collect(Collectors.toList());
  }
  
  @Override
  public synchronized List<ProcessInstance> findActive() {
    return instances.values().stream().filter(ProcessInstance::isActive).collect(Collectors
        .toList());
  }
  
  @Override
  public synchronized List<ProcessInstance> findAll() {
    return new ArrayList<>(instances.values());
  }
  
}
