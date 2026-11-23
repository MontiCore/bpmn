/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.history;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation, sufficient for tests and for an engine without a database. Its methods
 * are synchronized, so that it can be read while another thread uses the engine.
 */
public class InMemoryHistoryLog implements HistoryLog {
  
  protected final List<HistoryEvent> events = new ArrayList<>();
  
  @Override
  public synchronized void append(final HistoryEvent event) { events.add(event); }
  
  @Override
  public synchronized List<HistoryEvent> findByInstanceId(final String instanceId) {
    return events.stream().filter(e -> e.getInstanceId().equals(instanceId)).collect(Collectors
        .toList());
  }
  
  @Override
  public synchronized List<HistoryEvent> findAll() { return new ArrayList<>(events); }
  
}
