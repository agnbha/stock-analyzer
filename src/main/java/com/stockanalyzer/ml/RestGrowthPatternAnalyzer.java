package com.stockanalyzer.ml;

import com.stockanalyzer.model.GrowthAnalysisResult;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.util.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link GrowthPatternAnalyzer} that delegates to an external ML service over
 * HTTP: POSTs a candle series as JSON and expects a JSON prediction back.
 * Point {@code ml.service.url} at whichever model-serving endpoint you stand
 * up (e.g. a Python FastAPI/Flask service wrapping a trained model).
 */
public final class RestGrowthPatternAnalyzer implements GrowthPatternAnalyzer {

    private final HttpClient httpClient;
    private final String serviceUrl;
    private final Duration timeout;

    public RestGrowthPatternAnalyzer(HttpClient httpClient, String serviceUrl, Duration timeout) {
        this.httpClient = httpClient;
        this.serviceUrl = serviceUrl;
        this.timeout = timeout;
    }

    @Override
    public GrowthAnalysisResult analyze(StockCandleSeries series) {
        String requestBody;
        try {
            requestBody = JsonMapper.INSTANCE.writeValueAsString(GrowthAnalysisRequest.from(series));
        } catch (Exception e) {
            throw new MlServiceException("Failed to serialize ML request for " + series.symbol(), e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MlServiceException("Failed to reach ML service for " + series.symbol(), e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new MlServiceException(
                    "ML service request for " + series.symbol() + " failed with status " + response.statusCode()
                            + ": " + response.body());
        }

        GrowthAnalysisResponse parsed;
        try {
            parsed = JsonMapper.INSTANCE.readValue(response.body(), GrowthAnalysisResponse.class);
        } catch (Exception e) {
            throw new MlServiceException("Failed to parse ML response for " + series.symbol() + ": " + response.body(), e);
        }

        return new GrowthAnalysisResult(series.symbol(), parsed.trend(), parsed.growthScore(), parsed.confidence());
    }
}
