package com.stockanalyzer.model;

/** The event vocabulary shared by the detectors, the model features and alerts. */
public enum EventType {
    OPENING_RANGE_BREAKOUT,
    VWAP_RECLAIM,
    VWAP_LOSS,
    VOLUME_SPIKE,
    GAP_AND_GO,
    PRIOR_DAY_HIGH_BREAK,
    REVERSAL_AFTER_DECLINE,
    FIRST_HOUR_HIGH_BREAK
}
