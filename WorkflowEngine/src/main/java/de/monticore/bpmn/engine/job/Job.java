/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.job;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A piece of work the engine has to do at a given point in time, i.e., a row of the job table.
 *
 * <p>Jobs are what makes an engine able to schedule: an instance that waits for a timer event does
 * not occupy a thread, it only leaves a job behind. Something outside the engine (a timer thread, a
 * cron job, a message from a queue) calls
 * {@link de.monticore.bpmn.engine.WorkflowEngine#executeDueJobs} and the instance continues.
 *
 * <p>A job is executed once. A timer that occurs repeatedly gets a new job for every occurrence. A
 * job whose execution fails is kept and tried again until its retries are used up; after that it
 * stays in the table together with the reason, so that the failure can be inspected.
 */
public class Job {
  
  /** How often the engine tries to execute a job before it gives up. */
  public static final int DEFAULT_RETRIES = 3;
  
  protected final String id;
  
  protected final String instanceId;
  
  /** The catching event that is triggered once the job is due. */
  protected final String eventName;
  
  protected final Instant dueAt;
  
  /** The how manyeth occurrence of a repeating timer this job stands for, starting with 1. */
  protected final int occurrence;
  
  /** How often the engine still tries to execute the job. */
  protected final int retries;
  
  /** Why the last execution failed; absent if it did not. */
  protected final String failure;
  
  public Job(final String instanceId, final String eventName, final Instant dueAt) {
    this(instanceId, eventName, dueAt, 1, DEFAULT_RETRIES, null);
  }
  
  public Job(final String instanceId, final String eventName, final Instant dueAt,
      final int occurrence, final int retries, final String failure) {
    this.instanceId = Objects.requireNonNull(instanceId);
    this.eventName = Objects.requireNonNull(eventName);
    this.dueAt = Objects.requireNonNull(dueAt);
    this.occurrence = occurrence;
    this.retries = retries;
    this.failure = failure;
    this.id = instanceId + ":" + eventName;
  }
  
  public String getId() { return id; }
  
  public String getInstanceId() { return instanceId; }
  
  public String getEventName() { return eventName; }
  
  public Instant getDueAt() { return dueAt; }
  
  public int getOccurrence() { return occurrence; }
  
  public int getRetries() { return retries; }
  
  public Optional<String> getFailure() { return Optional.ofNullable(failure); }
  
  public boolean isDue(final Instant now) { return !dueAt.isAfter(now); }
  
  /** @return whether the engine still executes the job */
  public boolean isExecutable() {
    return retries > 0;
  }
  
  /** @return the job after a failed execution: one retry less and the reason of the failure */
  public Job afterFailure(final String reason) {
    return new Job(instanceId, eventName, dueAt, occurrence, retries - 1, reason);
  }
  
  /** @return the job for the next occurrence of a repeating timer */
  public Job nextOccurrence(final Instant nextDueAt) {
    return new Job(instanceId, eventName, nextDueAt, occurrence + 1, DEFAULT_RETRIES, null);
  }
  
  @Override
  public String toString() {
    return "Job[" + id + " due " + dueAt + (failure == null ? "" : ", failed: " + failure) + "]";
  }
  
}
