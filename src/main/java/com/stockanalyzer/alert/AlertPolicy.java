package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.store.AlertRepository;

/**
 * Decides whether an alert is actually allowed to fire.
 *
 * <p>The one rule is that the same alert never goes out twice. Every alert carries an idempotency
 * key, so a daemon restart, or the same condition being seen on a later poll, cannot repeat what
 * was already sent. There are no cooldowns or daily caps: an alert is raised for a purchase, once
 * per target, and a purchase is rare enough that rationing them would only hide the one you want.
 */
public final class AlertPolicy {

    private final AlertRepository alertRepository;

    public AlertPolicy(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    public Decision evaluate(Alert alert) {
        if (alertRepository.alreadyFired(alert.idempotencyKey())) {
            return new Decision(false, "already sent");
        }
        return new Decision(true, null);
    }

    public record Decision(boolean allowed, String reason) {
    }
}
