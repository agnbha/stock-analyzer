package com.stockanalyzer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps the JSON body returned by {@code GET /v1/live-data/quote}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record GrowwQuoteResponse(String status, Payload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(
            @JsonProperty("last_price") Double lastPrice,
            @JsonProperty("day_change") Double dayChange,
            @JsonProperty("day_change_perc") Double dayChangePerc,
            @JsonProperty("volume") Long volume,
            @JsonProperty("market_cap") Double marketCap,
            @JsonProperty("average_price") Double averagePrice,
            @JsonProperty("week_52_high") Double week52High,
            @JsonProperty("week_52_low") Double week52Low,
            @JsonProperty("upper_circuit_limit") Double upperCircuitLimit,
            @JsonProperty("lower_circuit_limit") Double lowerCircuitLimit,
            @JsonProperty("ohlc") Ohlc ohlc) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ohlc(Double open, Double high, Double low, Double close) {
    }
}
