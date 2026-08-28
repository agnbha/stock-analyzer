package com.stockanalyzer.features;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.model.TradingSession;
import com.stockanalyzer.util.JsonMapper;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The feature contract, pinned to a file.
 *
 * <p>Features computed here at serving time must match the ones computed
 * wherever the model is trained. That only stays true if something checks it,
 * so this test writes and verifies a shared fixture: inputs, plus the expected
 * feature vector for every candle. A model service in any language validates
 * itself against the same file.
 *
 * <p>Regenerate deliberately, after an intended feature change:
 * {@code mvn test -Dtest=FeatureParityTest -Dregenerate=true}
 */
class FeatureParityTest {

    private static final Path FIXTURE = Path.of("src", "test", "resources", "feature-parity.json");
    private static final double TOLERANCE = 1e-9;
    private static final LocalDate DAY = LocalDate.of(2026, 8, 27);

    private final MarketClock clock = MarketClock.nse();

    @Test
    @DisplayName("features match the shared fixture exactly")
    void featuresMatchTheFixture() throws Exception {
        List<Candle> candles = sampleSession();
        TradingSession session = new TradingSession("RELIANCE", "NSE", "CASH", DAY, 1, candles);
        EventDetector.PriorSessionContext context =
                new EventDetector.PriorSessionContext(99.0, 104.0, 96.0, TimeOfDayVolumeProfile.empty());
        List<MarketEvent> events = new RuleEventDetector(clock).detect(session, context);
        List<FeatureVector> actual = new FeatureExtractor(clock).extract(session, context, events);

        if (Boolean.getBoolean("regenerate") || !Files.exists(FIXTURE)) {
            write(candles, context, actual);
        }

        JsonNode fixture = JsonMapper.INSTANCE.readTree(Files.readString(FIXTURE));
        assertEquals(FeatureExtractor.FEATURE_NAMES,
                JsonMapper.INSTANCE.convertValue(fixture.get("feature_names"), List.class),
                "the feature contract changed; regenerate the fixture and retrain the model");

        ArrayNode expectedRows = (ArrayNode) fixture.get("expected");
        assertEquals(expectedRows.size(), actual.size(), "one feature row per candle");

        for (int row = 0; row < actual.size(); row++) {
            JsonNode expected = expectedRows.get(row).get("values");
            double[] computed = actual.get(row).asArray(FeatureExtractor.FEATURE_NAMES);
            assertEquals(expectedRows.get(row).get("ts_epoch").asLong(), actual.get(row).tsEpoch());
            for (int i = 0; i < computed.length; i++) {
                assertEquals(expected.get(i).asDouble(), computed[i], TOLERANCE,
                        "row " + row + ", feature " + FeatureExtractor.FEATURE_NAMES.get(i));
            }
        }
    }

    @Test
    @DisplayName("the contract has no duplicate or renamed columns")
    void contractIsWellFormed() {
        assertEquals(FeatureExtractor.FEATURE_NAMES.size(),
                FeatureExtractor.FEATURE_NAMES.stream().distinct().count(), "duplicate feature name");
        assertTrue(FeatureExtractor.FEATURE_NAMES.stream()
                        .allMatch(name -> name.matches("[a-z0-9_]+")),
                "feature names are lower snake case so they survive a trip through any language");
    }

    private void write(List<Candle> candles, EventDetector.PriorSessionContext context,
                       List<FeatureVector> vectors) throws Exception {
        ObjectNode root = JsonMapper.INSTANCE.createObjectNode();
        root.put("description", "Shared feature fixture. Any model service must reproduce these "
                + "vectors from these candles, in this column order.");
        root.put("symbol", "RELIANCE");
        root.put("session_date", DAY.toString());
        root.put("interval_minutes", 1);

        ObjectNode prior = root.putObject("prior_session");
        prior.put("close", context.priorClose());
        prior.put("high", context.priorHigh());
        prior.put("low", context.priorLow());

        ArrayNode candleArray = root.putArray("candles");
        for (Candle candle : candles) {
            ArrayNode row = candleArray.addArray();
            row.add(candle.epochSeconds());
            row.add(candle.open());
            row.add(candle.high());
            row.add(candle.low());
            row.add(candle.close());
            row.add(candle.volume());
        }

        ArrayNode names = root.putArray("feature_names");
        FeatureExtractor.FEATURE_NAMES.forEach(names::add);

        ArrayNode expected = root.putArray("expected");
        for (FeatureVector vector : vectors) {
            ObjectNode node = expected.addObject();
            node.put("ts_epoch", vector.tsEpoch());
            ArrayNode values = node.putArray("values");
            for (double value : vector.asArray(FeatureExtractor.FEATURE_NAMES)) {
                values.add(value);
            }
        }

        Files.createDirectories(FIXTURE.getParent());
        Files.writeString(FIXTURE,
                JsonMapper.INSTANCE.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    /** A session with a gap, an opening range, a breakout, a slump and a recovery. */
    private List<Candle> sampleSession() {
        double[] closes = {
                101.0, 101.4, 100.9, 101.8, 102.6, 103.1, 102.7, 103.9, 105.2, 104.6,
                104.1, 103.2, 102.4, 101.9, 102.8, 103.6, 104.4, 105.9, 106.3, 105.7,
                105.1, 104.2, 103.6, 104.0, 104.9, 105.8, 106.7, 107.2, 106.4, 106.9};
        long[] volumes = {
                4200, 3100, 2600, 2900, 3400, 3900, 2800, 5200, 6100, 3300,
                2400, 2100, 2000, 2300, 2700, 3100, 3600, 7400, 4100, 2900,
                2200, 1900, 1800, 2100, 2500, 2900, 3300, 3800, 2600, 3000};

        List<Candle> candles = new ArrayList<>(closes.length);
        long open = ZonedDateTime.of(DAY, LocalTime.of(9, 15), clock.zone()).toEpochSecond();
        for (int i = 0; i < closes.length; i++) {
            double previous = i == 0 ? 100.2 : closes[i - 1];
            double high = Math.max(previous, closes[i]) + 0.35;
            double low = Math.min(previous, closes[i]) - 0.30;
            candles.add(new Candle(open + i * 60L, previous, high, low, closes[i], volumes[i]));
        }
        return candles;
    }
}
