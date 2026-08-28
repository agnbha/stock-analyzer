package com.stockanalyzer.signal;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.store.CandleRepository;
import com.stockanalyzer.store.PredictionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

/**
 * Fills in what actually happened after each prediction, once the session's
 * tape is complete.
 *
 * <p>Without this the model's accuracy is never measured against reality and
 * drift is invisible. It runs nightly, after ingestion.
 */
public final class PredictionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PredictionEvaluator.class);

    private final PredictionRepository predictionRepository;
    private final CandleRepository candleRepository;
    private final int intervalMinutes;

    public PredictionEvaluator(PredictionRepository predictionRepository,
                               CandleRepository candleRepository,
                               int intervalMinutes) {
        this.predictionRepository = predictionRepository;
        this.candleRepository = candleRepository;
        this.intervalMinutes = intervalMinutes;
    }

    /** Returns how many predictions were scored. */
    public int evaluate(LocalDate sessionDate) {
        List<PredictionRepository.StoredPrediction> pending = predictionRepository.unscored(sessionDate);
        int scored = 0;
        for (PredictionRepository.StoredPrediction stored : pending) {
            long from = stored.prediction().tsEpoch();
            long to = from + stored.prediction().horizonMinutes() * 60L;
            List<Candle> window = candleRepository.find(stored.instrumentId(), intervalMinutes, from, to);
            if (window.size() < 2) {
                continue;
            }
            double entry = window.getFirst().close();
            if (entry <= 0) {
                continue;
            }
            // The best exit available within the horizon: what acting on the signal could have earned.
            double best = window.stream().skip(1).mapToDouble(Candle::high).max().orElse(entry);
            predictionRepository.setRealizedReturn(stored.id(), (best - entry) / entry * 100.0);
            scored++;
        }
        log.info("Scored {} of {} predictions for {}", scored, pending.size(), sessionDate);
        return scored;
    }
}
