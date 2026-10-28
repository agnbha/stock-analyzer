package com.stockanalyzer.signal;

import com.stockanalyzer.features.FeatureVector;
import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.model.SignalPrediction;
import com.stockanalyzer.store.HotWindowRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HotWindowSignalModelTest {

    private static final MarketClock CLOCK = MarketClock.nse();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    @Test
    @DisplayName("the opening bucket predicts nothing, so it cannot raise an entry signal")
    void openingBucketIsIgnored() {
        HotWindowRepository repository = new HotWindowRepository() {
            @Override
            public void replaceAll(int bucketMinutes, int lookbackDays, List<HotWindow> windows) {
            }

            @Override
            public List<HotWindow> find(String symbol, int bucketMinutes, int lookbackDays) {
                return List.of(
                        new HotWindow(symbol, 0, 5, 60, 34, 39, 0.87, 0.73, 1.0, 0.9),
                        new HotWindow(symbol, 30, 5, 60, 30, 39, 0.77, 0.62, 1.0, 0.9));
            }

            @Override
            public List<HotWindow> findAll(int bucketMinutes, int lookbackDays) {
                return List.of();
            }
        };
        HotWindowSignalModel model = new HotWindowSignalModel(repository, CLOCK, 5, 60, 30, 0.10, 5);

        List<SignalPrediction> predictions = model.score(new SignalRequest("AAA", DAY, 30, List.of(
                new FeatureVector(CLOCK.epochOf(DAY, LocalTime.of(9, 16)), Map.of()),
                new FeatureVector(CLOCK.epochOf(DAY, LocalTime.of(9, 47)), Map.of()))));

        assertEquals(1, predictions.size());
        assertEquals(CLOCK.epochOf(DAY, LocalTime.of(9, 47)), predictions.getFirst().tsEpoch());
        assertEquals(0.62, predictions.getFirst().probability(), 1e-9);
    }
}
