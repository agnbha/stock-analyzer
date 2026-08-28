package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.store.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies the policy, publishes what survives, and records it so it never fires twice. */
public final class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final AlertPolicy policy;
    private final AlertSink sink;
    private final AlertRepository alertRepository;

    public AlertEngine(AlertPolicy policy, AlertSink sink, AlertRepository alertRepository) {
        this.policy = policy;
        this.sink = sink;
        this.alertRepository = alertRepository;
    }

    /** Returns true when the alert was actually delivered. */
    public boolean fire(Alert alert) {
        AlertPolicy.Decision decision = policy.evaluate(alert);
        if (!decision.allowed()) {
            log.debug("Suppressed {} for {}: {}", alert.rule(), alert.symbol(), decision.reason());
            return false;
        }
        alertRepository.logFired(alert);
        sink.publish(alert);
        return true;
    }
}
