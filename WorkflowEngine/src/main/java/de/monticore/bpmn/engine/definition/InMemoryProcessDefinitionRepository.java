/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.definition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Default implementation, sufficient for tests and for an engine without a database. */
public class InMemoryProcessDefinitionRepository implements ProcessDefinitionRepository {
  
  protected final Map<String, ProcessDefinition> definitions = new LinkedHashMap<>();
  
  @Override
  public void save(final ProcessDefinition definition) {
    definitions.put(definition.getId(), definition);
  }
  
  @Override
  public Optional<ProcessDefinition> findById(final String id) {
    return Optional.ofNullable(definitions.get(id));
  }
  
  @Override
  public Optional<ProcessDefinition> findLatest(final String key) {
    return definitions.values().stream().filter(d -> d.getKey().equals(key)).max(Comparator
        .comparingInt(ProcessDefinition::getVersion));
  }
  
  @Override
  public Optional<ProcessDefinition> find(final String key, final int version) {
    return findById(key + ":" + version);
  }
  
  @Override
  public List<ProcessDefinition> findAll() { return new ArrayList<>(definitions.values()); }
  
  @Override
  public int nextVersion(final String key) {
    return findLatest(key).map(d -> d.getVersion() + 1).orElse(1);
  }
  
}
