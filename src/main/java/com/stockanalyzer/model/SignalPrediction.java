package com.stockanalyzer.model;

import java.time.LocalDate;

/** A model's view of one candle: what to do, how strongly, over what horizon. */
public record SignalPrediction(String symbol,
                               LocalDate sessionDate,
                               long tsEpoch,
                               Signal signal,
                               double probability,
                               int horizonMinutes,
                               String reason) {

    public enum Signal { ENTRY, EXIT, NEUTRAL }
}
