package com.stockanalyzer.client;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a requested window into provider-legal chunks. Intraday history
 * endpoints cap how much a single request may span, so a multi-month backfill
 * has to be asked for a few days at a time.
 */
public final class CandleRangeChunker {

    private final int maxDaysPerRequest;

    public CandleRangeChunker(int maxDaysPerRequest) {
        if (maxDaysPerRequest < 1) {
            throw new IllegalArgumentException("maxDaysPerRequest must be at least 1");
        }
        this.maxDaysPerRequest = maxDaysPerRequest;
    }

    public List<Range> chunk(LocalDateTime start, LocalDateTime end) {
        if (end.isBefore(start)) {
            return List.of();
        }
        List<Range> ranges = new ArrayList<>();
        LocalDateTime chunkStart = start;
        while (chunkStart.isBefore(end)) {
            LocalDateTime chunkEnd = chunkStart.plusDays(maxDaysPerRequest);
            if (chunkEnd.isAfter(end)) {
                chunkEnd = end;
            }
            ranges.add(new Range(chunkStart, chunkEnd));
            chunkStart = chunkEnd;
        }
        if (ranges.isEmpty()) {
            ranges.add(new Range(start, end));
        }
        return ranges;
    }

    public record Range(LocalDateTime from, LocalDateTime to) {
    }
}
