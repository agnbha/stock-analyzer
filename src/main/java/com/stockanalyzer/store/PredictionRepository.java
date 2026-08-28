package com.stockanalyzer.store;

import com.stockanalyzer.model.SignalPrediction;

import java.time.LocalDate;
import java.util.List;

public interface PredictionRepository {

    long findOrCreateModelVersion(String name);

    void saveAll(long modelVersionId, long instrumentId, List<SignalPrediction> predictions);

    /** Predictions for a session whose realized return has not been filled in yet. */
    List<StoredPrediction> unscored(LocalDate sessionDate);

    void setRealizedReturn(long predictionId, double realizedReturnPct);

    record StoredPrediction(long id, long instrumentId, String symbol, SignalPrediction prediction) {
    }
}
