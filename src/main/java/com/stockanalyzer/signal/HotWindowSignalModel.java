package com.stockanalyzer.signal;

import com.stockanalyzer.features.FeatureVector;
import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.model.SignalPrediction;
import com.stockanalyzer.store.HotWindowRepository;
import com.stockanalyzer.util.MarketClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The baseline model: the time-of-day prior, served through the same interface
 * as anything trained.
 *
 * <p>It is the default, so alerts and the live monitor work end to end before
 * any model exists - and it is what a trained model has to beat on a held-out
 * month before {@code model.enabled} is worth turning on.
 */
public final class HotWindowSignalModel implements IntradaySignalModel {

    private final HotWindowRepository hotWindowRepository;
    private final MarketClock clock;
    private final int bucketMinutes;
    private final int lookbackDays;
    private final int minSessions;
    private final double minLowerBound;

    public HotWindowSignalModel(HotWindowRepository hotWindowRepository, MarketClock clock, int bucketMinutes,
                                int lookbackDays, int minSessions, double minLowerBound) {
        this.hotWindowRepository = hotWindowRepository;
        this.clock = clock;
        this.bucketMinutes = bucketMinutes;
        this.lookbackDays = lookbackDays;
        this.minSessions = minSessions;
        this.minLowerBound = minLowerBound;
    }

    @Override
    public String modelVersion() {
        return "hotwindow-prior/v1";
    }

    @Override
    public List<SignalPrediction> score(SignalRequest request) {
        List<HotWindow> windows = hotWindowRepository.find(request.symbol(), bucketMinutes, lookbackDays).stream()
                .filter(w -> w.sessions() >= minSessions && w.hitRateLcb() >= minLowerBound)
                .toList();
        if (windows.isEmpty()) {
            return List.of();
        }

        List<SignalPrediction> predictions = new ArrayList<>();
        for (FeatureVector feature : request.features()) {
            int minute = clock.minutesSinceOpen(feature.tsEpoch());
            windows.stream()
                    .filter(w -> minute >= w.bucketStartMinute() && minute < w.bucketStartMinute() + w.bucketMinutes())
                    .max((a, b) -> Double.compare(a.hitRateLcb(), b.hitRateLcb()))
                    .ifPresent(window -> predictions.add(new SignalPrediction(
                            request.symbol(), request.sessionDate(), feature.tsEpoch(),
                            SignalPrediction.Signal.ENTRY, window.hitRateLcb(), request.horizonMinutes(),
                            String.format(Locale.ROOT, "top-3 entry on %d of %d sessions, median %+.2f%%",
                                    window.hits(), window.sessions(), window.medianGainPct()))));
        }
        return predictions;
    }
}
