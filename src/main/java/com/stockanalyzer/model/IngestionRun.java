package com.stockanalyzer.model;

import java.time.LocalDate;

/** Bookkeeping for one ingestion pass, so partial runs are visible after the fact. */
public record IngestionRun(long id,
                           long startedAt,
                           Long finishedAt,
                           LocalDate sessionDate,
                           String mode,
                           int requested,
                           int succeeded,
                           int failed,
                           String status) {
}
