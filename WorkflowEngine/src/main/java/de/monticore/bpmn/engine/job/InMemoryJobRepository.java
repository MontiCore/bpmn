/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.job;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Default implementation, sufficient for tests and for an engine without a database. Its methods
 * are synchronized, so that it can be read while another thread uses the engine.
 */
public class InMemoryJobRepository implements JobRepository {
  
  protected final Map<String, Job> jobs = new LinkedHashMap<>();
  
  @Override
  public synchronized void save(final Job job) { jobs.put(job.getId(), job); }
  
  @Override
  public synchronized Optional<Job> findById(final String id) {
    return Optional.ofNullable(jobs.get(id));
  }
  
  @Override
  public synchronized List<Job> findDue(final Instant now) {
    return jobs.values().stream().filter(job -> job.isDue(now)).sorted(Comparator.comparing(
        Job::getDueAt)).collect(Collectors.toList());
  }
  
  @Override
  public synchronized List<Job> findByInstanceId(final String instanceId) {
    return jobs.values().stream().filter(job -> job.getInstanceId().equals(instanceId)).collect(
        Collectors.toList());
  }
  
  @Override
  public synchronized List<Job> findAll() { return new ArrayList<>(jobs.values()); }
  
  @Override
  public synchronized void delete(final String id) { jobs.remove(id); }
  
  @Override
  public synchronized void deleteByInstanceId(final String instanceId) {
    jobs.values().removeIf(job -> job.getInstanceId().equals(instanceId));
  }
  
}
