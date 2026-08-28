package com.stockanalyzer.signal;

import com.stockanalyzer.model.SignalPrediction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Uses the primary model, and falls back to the baseline whenever it is
 * unavailable. The monitor keeps producing signals through an ML service
 * outage; it just produces the ones the prior would have made.
 */
public final class FallbackSignalModel implements IntradaySignalModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackSignalModel.class);

    private final IntradaySignalModel primary;
    private final IntradaySignalModel fallback;

    public FallbackSignalModel(IntradaySignalModel primary, IntradaySignalModel fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public List<SignalPrediction> score(SignalRequest request) {
        try {
            return primary.score(request);
        } catch (MlServiceUnavailableException e) {
            log.warn("Model service unavailable ({}); falling back to {}", e.getMessage(), fallback.modelVersion());
            return fallback.score(request);
        }
    }

    @Override
    public String modelVersion() {
        return primary.modelVersion();
    }
}
