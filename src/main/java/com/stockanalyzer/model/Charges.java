package com.stockanalyzer.model;

/** The statutory and broker charges on one trade, itemised. */
public record Charges(double brokerage,
                      double stt,
                      double exchangeTxn,
                      double sebi,
                      double stampDuty,
                      double gst) {

    public static final Charges ZERO = new Charges(0, 0, 0, 0, 0, 0);

    public double total() {
        return brokerage + stt + exchangeTxn + sebi + stampDuty + gst;
    }
}
