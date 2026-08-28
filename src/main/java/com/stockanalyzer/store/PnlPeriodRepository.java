package com.stockanalyzer.store;

import com.stockanalyzer.model.PeriodType;
import com.stockanalyzer.model.PnlSummary;

import java.time.LocalDate;
import java.util.Optional;

public interface PnlPeriodRepository {

    void upsert(PnlSummary summary);

    Optional<PnlSummary> find(PeriodType periodType, LocalDate periodStart, String symbolOrNull);
}
