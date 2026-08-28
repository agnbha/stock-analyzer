package com.stockanalyzer.signal;

import com.stockanalyzer.model.SignalPrediction;

import java.util.List;

/**
 * Scores candles for entry and exit. Two implementations matter: the
 * statistical time-of-day prior, which needs no service and is the benchmark,
 * and a trained model served over HTTP.
 */
public interface IntradaySignalModel {

    List<SignalPrediction> score(SignalRequest request);

    String modelVersion();
}
