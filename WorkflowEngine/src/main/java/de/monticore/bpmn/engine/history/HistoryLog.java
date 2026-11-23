/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.history;

import java.util.List;

/** The audit trail. Implement this to write the history into a database or a log. */
public interface HistoryLog {
  
  void append(HistoryEvent event);
  
  List<HistoryEvent> findByInstanceId(String instanceId);
  
  List<HistoryEvent> findAll();
  
}
