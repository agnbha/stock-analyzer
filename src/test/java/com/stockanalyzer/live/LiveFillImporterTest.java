package com.stockanalyzer.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveFillImporterTest {

    @Test
    @DisplayName("a failing job is absorbed, so the schedule is not cancelled")
    void failureDoesNotEscape() {
        LiveFillImporter importer = new LiveFillImporter(() -> {
            throw new IllegalStateException("Groww rejected the credential");
        }, 5);

        assertDoesNotThrow(importer::runOnce);
        assertDoesNotThrow(importer::runOnce);
        importer.close();
    }

    @Test
    @DisplayName("the job keeps being retried after a failure and counts again once it recovers")
    void retriesAfterFailure() {
        AtomicInteger calls = new AtomicInteger();
        LiveFillImporter importer = new LiveFillImporter(() -> {
            if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("temporary");
            }
        }, 5);

        importer.runOnce();
        importer.runOnce();
        importer.runOnce();

        assertEquals(3, calls.get());
        importer.close();
    }

    @Test
    @DisplayName("an interval of zero leaves it off")
    void zeroIntervalNeverStarts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LiveFillImporter importer = new LiveFillImporter(calls::incrementAndGet, 0);

        importer.start();
        Thread.sleep(50);

        assertEquals(0, calls.get());
        importer.close();
    }
}
