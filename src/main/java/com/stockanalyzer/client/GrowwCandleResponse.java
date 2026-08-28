package com.stockanalyzer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Maps the JSON body returned by {@code GET /v1/historical/candle/range}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record GrowwCandleResponse(String status, Payload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(
            List<List<Double>> candles,
            @JsonProperty("start_time") String startTime,
            @JsonProperty("end_time") String endTime,
            @JsonProperty("interval_in_minutes") Integer intervalInMinutes) {
    }
}
