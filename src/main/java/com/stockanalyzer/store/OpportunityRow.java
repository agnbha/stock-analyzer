package com.stockanalyzer.store;

import com.stockanalyzer.model.GainOpportunity;

import java.time.LocalDate;

/** A stored opportunity together with the instrument and session it belongs to. */
public record OpportunityRow(long id, String symbol, LocalDate sessionDate, GainOpportunity opportunity) {
}
