/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The job table. */
public interface JobRepository {
  
  void save(Job job);
  
  Optional<Job> findById(String id);
  
  /** @return all jobs that are due at the given point in time, earliest first */
  List<Job> findDue(Instant now);
  
  List<Job> findByInstanceId(String instanceId);
  
  List<Job> findAll();
  
  void delete(String id);
  
  void deleteByInstanceId(String instanceId);
  
}
