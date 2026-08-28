package com.stockanalyzer.store;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.ScheduledAlert;

import java.time.LocalDate;
import java.util.List;

public interface AlertRepository {

    /** Replaces the day plan for {@code sessionDate}, so it can be inspected before it fires. */
    void replaceSchedule(LocalDate sessionDate, List<ScheduledAlert> alerts);

    List<ScheduledAlert> pendingSchedule(LocalDate sessionDate);

    void markScheduleStatus(long id, ScheduledAlert.Status status);

    /** True when this exact alert has already been sent - the restart guard. */
    boolean alreadyFired(String idempotencyKey);

    void logFired(Alert alert);

    List<Alert> firedOn(LocalDate sessionDate);

    int countFiredToday(LocalDate sessionDate, String symbolOrNull);
}
