package com.stockanalyzer.signal;

import com.stockanalyzer.features.FeatureVector;

import java.time.LocalDate;
import java.util.List;

/** One symbol's session, featurised, ready to score. */
public record SignalRequest(String symbol,
                            LocalDate sessionDate,
                            int horizonMinutes,
                            List<FeatureVector> features) {
}
