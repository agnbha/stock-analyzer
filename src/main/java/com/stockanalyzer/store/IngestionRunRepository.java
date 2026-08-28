package com.stockanalyzer.store;

import java.time.LocalDate;

public interface IngestionRunRepository {

    long start(LocalDate sessionDate, String mode, int requested);

    void finish(long runId, int succeeded, int failed, String status);

    void recordFailure(long runId, String symbol, LocalDate sessionDate,
                       String errorType, String message, int attempts);
}
