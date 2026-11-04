/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.job;

import java.time.Instant;
import java.util.Objects;

/**
 * A piece of work the engine has to do at a given point in time, i.e., a row of the job table.
 *
 * <p>Jobs are what makes an engine able to schedule: an instance that waits for a timer event does
 * not occupy a thread, it only leaves a job behind. Something outside the engine (a timer thread, a
 * cron job, a message from a queue) calls
 * {@link de.monticore.bpmn.engine.WorkflowEngine#executeDueJobs} and the instance continues.
 */
public class Job {
  
  protected final String id;
  
  protected final String instanceId;
  
  /** The catching event that is triggered once the job is due. */
  protected final String eventName;
  
  protected final Instant dueAt;
  
  public Job(final String instanceId, final String eventName, final Instant dueAt) {
    this.instanceId = Objects.requireNonNull(instanceId);
    this.eventName = Objects.requireNonNull(eventName);
    this.dueAt = Objects.requireNonNull(dueAt);
    this.id = instanceId + ":" + eventName;
  }
  
  public String getId() { return id; }
  
  public String getInstanceId() { return instanceId; }
  
  public String getEventName() { return eventName; }
  
  public Instant getDueAt() { return dueAt; }
  
  public boolean isDue(final Instant now) { return !dueAt.isAfter(now); }
  
  @Override
  public String toString() { return "Job[" + id + " due " + dueAt + "]"; }
  
}
