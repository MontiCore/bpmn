/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.job;

import com.cronutils.model.time.ExecutionTime;
import de.monticore.bpmn.timerconditions._ast.ASTAfterPeriodCondition;
import de.monticore.bpmn.timerconditions._ast.ASTAtTimeCondition;
import de.monticore.bpmn.timerconditions._ast.ASTCronCondition;
import de.monticore.bpmn.timerconditions._ast.ASTEveryTimeCondition;
import de.monticore.bpmn.timerconditions._ast.ASTOnDateCondition;
import de.monticore.bpmn.timerconditions._ast.ASTTimerCondition;
import de.se_rwth.commons.logging.Log;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/**
 * Computes when a timer event is due.
 *
 * <p>The Workflow language describes the timer with a {@code TimerCondition}; the engine needs a
 * concrete point in time to store a {@link Job} for. The reference time is the moment the instance
 * started to wait for the event, which is what BPMN prescribes for relative timers.
 */
public class TimerCalculator {
  
  protected final ZoneId zone;
  
  public TimerCalculator() { this(ZoneId.systemDefault()); }
  
  public TimerCalculator(final ZoneId zone) { this.zone = zone; }
  
  /**
   * @param condition the timer condition of the event
   * @param reference the moment the instance started to wait for the event
   * @return when the event is due, empty if the condition cannot be scheduled
   */
  public Optional<Instant> nextDueDate(final ASTTimerCondition condition, final Instant reference) {
    if (condition instanceof ASTAfterPeriodCondition after) {
      return Optional.of(plus(reference, after.getPeriod().getPeriod()));
    }
    if (condition instanceof ASTOnDateCondition onDate) {
      return Optional.of(toInstant(onDate.getLocalDateTimeOrDefaultMidnight()));
    }
    if (condition instanceof ASTAtTimeCondition atTime) {
      return Optional.of(nextTimeOfDay(atTime, reference));
    }
    if (condition instanceof ASTEveryTimeCondition every) {
      // the first occurrence; the engine reschedules the job after every occurrence
      final Instant start = every.getStartLocalDateTime().map(this::toInstant).orElse(reference);
      return Optional.of(plus(start, every.getPeriod().getPeriod()));
    }
    if (condition instanceof ASTCronCondition cron) {
      return ExecutionTime.forCron(cron.getCron()).nextExecution(ZonedDateTime.ofInstant(reference,
          zone)).map(ZonedDateTime::toInstant);
    }
    Log.warn("0xWFE0201 The timer condition '" + condition.getClass().getSimpleName()
        + "' cannot be scheduled yet. The event has to be triggered manually.");
    return Optional.empty();
  }
  
  /**
   * @param condition the timer condition of the event
   * @return whether the event occurs more than once, i.e., whether the job is rescheduled
   */
  public boolean isRepeating(final ASTTimerCondition condition) {
    return condition instanceof ASTEveryTimeCondition || condition instanceof ASTCronCondition;
  }
  
  protected Instant plus(final Instant reference, final org.joda.time.Period period) {
    return Instant.ofEpochMilli(new DateTime(reference.toEpochMilli(), zone()).plus(period)
        .getMillis());
  }
  
  protected Instant nextTimeOfDay(final ASTAtTimeCondition atTime, final Instant reference) {
    final DateTime referenceTime = new DateTime(reference.toEpochMilli(), zone());
    DateTime due = referenceTime.withTime(atTime.getHours(), atTime.getMinutes(), atTime
        .getSeconds(), 0);
    if (!due.isAfter(referenceTime)) {
      due = due.plusDays(1); // the time of day has already passed today
    }
    return Instant.ofEpochMilli(due.getMillis());
  }
  
  protected Instant toInstant(final org.joda.time.LocalDateTime localDateTime) {
    return Instant.ofEpochMilli(localDateTime.toDateTime(zone()).getMillis());
  }
  
  protected DateTimeZone zone() { return DateTimeZone.forID(zone.getId()); }
  
}
