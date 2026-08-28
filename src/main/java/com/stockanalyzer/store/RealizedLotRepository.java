package com.stockanalyzer.store;

import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.model.RealizedLot;

import java.time.LocalDate;
import java.util.List;

public interface RealizedLotRepository {

    /** Replaces all matched lots and open positions - the matcher is a pure recompute. */
    void replaceAll(List<RealizedLot> lots, List<OpenPosition> openPositions, String exchange, String segment);

    List<RealizedLot> findClosedBetween(LocalDate from, LocalDate to);

    List<OpenPosition> openPositions();
}
