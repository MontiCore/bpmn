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

/** Default implementation, sufficient for tests and for an engine without a database. */
public class InMemoryJobRepository implements JobRepository {
  
  protected final Map<String, Job> jobs = new LinkedHashMap<>();
  
  @Override
  public void save(final Job job) { jobs.put(job.getId(), job); }
  
  @Override
  public Optional<Job> findById(final String id) { return Optional.ofNullable(jobs.get(id)); }
  
  @Override
  public List<Job> findDue(final Instant now) {
    return jobs.values().stream().filter(job -> job.isDue(now)).sorted(Comparator.comparing(
        Job::getDueAt)).collect(Collectors.toList());
  }
  
  @Override
  public List<Job> findByInstanceId(final String instanceId) {
    return jobs.values().stream().filter(job -> job.getInstanceId().equals(instanceId)).collect(
        Collectors.toList());
  }
  
  @Override
  public List<Job> findAll() { return new ArrayList<>(jobs.values()); }
  
  @Override
  public void delete(final String id) { jobs.remove(id); }
  
  @Override
  public void deleteByInstanceId(final String instanceId) {
    jobs.values().removeIf(job -> job.getInstanceId().equals(instanceId));
  }
  
}
