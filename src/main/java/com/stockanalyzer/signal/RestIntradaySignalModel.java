package com.stockanalyzer.signal;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.features.FeatureExtractor;
import com.stockanalyzer.features.FeatureVector;
import com.stockanalyzer.model.SignalPrediction;
import com.stockanalyzer.util.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the Python model service. Feature names are sent with every
 * request so a mismatch between the two sides fails loudly instead of scoring
 * against the wrong columns.
 */
public final class RestIntradaySignalModel implements IntradaySignalModel {

    private final HttpClient httpClient;
    private final String endpoint;
    private final Duration timeout;
    private volatile String modelVersion = "rest-model/unknown";

    public RestIntradaySignalModel(HttpClient httpClient, String endpoint, Duration timeout) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public String modelVersion() {
        return modelVersion;
    }

    @Override
    public List<SignalPrediction> score(SignalRequest request) {
        if (request.features().isEmpty()) {
            return List.of();
        }
        String body = buildBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MlServiceUnavailableException("Model service unreachable at " + endpoint, e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new MlServiceUnavailableException(
                    "Model service returned " + response.statusCode() + ": " + response.body());
        }
        return parse(request, response.body());
    }

    private String buildBody(SignalRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", request.symbol());
        payload.put("session_date", request.sessionDate().toString());
        payload.put("horizon_minutes", request.horizonMinutes());
        payload.put("feature_names", FeatureExtractor.FEATURE_NAMES);
        List<Map<String, Object>> rows = new ArrayList<>(request.features().size());
        for (FeatureVector vector : request.features()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts_epoch", vector.tsEpoch());
            row.put("values", vector.asArray(FeatureExtractor.FEATURE_NAMES));
            rows.add(row);
        }
        payload.put("rows", rows);
        try {
            return JsonMapper.INSTANCE.writeValueAsString(payload);
        } catch (Exception e) {
            throw new MlServiceUnavailableException("Failed to serialise scoring request", e);
        }
    }

    private List<SignalPrediction> parse(SignalRequest request, String body) {
        try {
            JsonNode root = JsonMapper.INSTANCE.readTree(body);
            if (root.hasNonNull("model_version")) {
                modelVersion = root.get("model_version").asText();
            }
            List<SignalPrediction> predictions = new ArrayList<>();
            for (JsonNode node : root.withArray("predictions")) {
                predictions.add(new SignalPrediction(
                        request.symbol(),
                        request.sessionDate(),
                        node.get("ts_epoch").asLong(),
                        SignalPrediction.Signal.valueOf(node.get("signal").asText()),
                        node.get("probability").asDouble(),
                        request.horizonMinutes(),
                        node.hasNonNull("reason") ? node.get("reason").asText() : null));
            }
            return predictions;
        } catch (Exception e) {
            throw new MlServiceUnavailableException("Unparseable model service response: " + body, e);
        }
    }
}
