package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.store.AlertRepository;

/**
 * Decides whether an alert is actually allowed to fire.
 *
 * <p>Alert fatigue is the failure mode of this feature, so the limits are part
 * of the design rather than something bolted on later: never the same alert
 * twice (which also makes a daemon restart safe), a cooldown per symbol and
 * rule, and hard caps per symbol and per day.
 */
public final class AlertPolicy {

    private final AlertRepository alertRepository;
    private final int cooldownMinutes;
    private final int maxPerSymbolPerDay;
    private final int maxPerDay;

    public AlertPolicy(AlertRepository alertRepository, int cooldownMinutes,
                       int maxPerSymbolPerDay, int maxPerDay) {
        this.alertRepository = alertRepository;
        this.cooldownMinutes = cooldownMinutes;
        this.maxPerSymbolPerDay = maxPerSymbolPerDay;
        this.maxPerDay = maxPerDay;
    }

    public Decision evaluate(Alert alert) {
        if (alertRepository.alreadyFired(alert.idempotencyKey())) {
            return new Decision(false, "already sent");
        }
        if (alertRepository.countFiredToday(alert.sessionDate(), null) >= maxPerDay) {
            return new Decision(false, "daily cap reached");
        }
        if (alert.symbol() != null
                && alertRepository.countFiredToday(alert.sessionDate(), alert.symbol()) >= maxPerSymbolPerDay) {
            return new Decision(false, "per-symbol cap reached");
        }
        boolean inCooldown = alertRepository.firedOn(alert.sessionDate()).stream()
                .filter(fired -> fired.rule().equals(alert.rule()))
                .filter(fired -> java.util.Objects.equals(fired.symbol(), alert.symbol()))
                .anyMatch(fired -> alert.firedAtEpoch() - fired.firedAtEpoch() < cooldownMinutes * 60L);
        if (inCooldown) {
            return new Decision(false, "within cooldown");
        }
        return new Decision(true, null);
    }

    public record Decision(boolean allowed, String reason) {
    }
}
