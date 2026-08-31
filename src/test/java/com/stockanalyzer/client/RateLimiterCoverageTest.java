package com.stockanalyzer.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the one mistake that is invisible until the API starts refusing you:
 * an entry point that builds the raw client itself and so talks to Groww with
 * no limiter, no retries and no chunking.
 *
 * <p>Two entry points had exactly that bug. A structural check is the only kind
 * that catches it, because everything still compiles and every unit test still
 * passes.
 */
class RateLimiterCoverageTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final String FACTORY = "CandleDataClients.java";

    @Test
    @DisplayName("only the factory constructs the raw candle client")
    void rawClientIsOnlyBuiltByTheFactory() throws Exception {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals(FACTORY))
                    .filter(RateLimiterCoverageTest::constructsRawClient)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        if (!offenders.isEmpty()) {
            fail("These build GrowwCandleDataClient directly and so bypass the rate limiter. "
                    + "Use CandleDataClients.rateLimited(...) instead:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), offenders));
        }
    }

    @Test
    @DisplayName("the factory puts the limiter inside the retry, so retries are counted too")
    void limiterSitsInsideTheRetry() throws Exception {
        String factory = Files.readString(SOURCES.resolve("com/stockanalyzer/client/" + FACTORY));

        int retry = factory.indexOf("new RetryingCandleDataClient");
        int limit = factory.indexOf("new RateLimitedCandleDataClient");
        int chunk = factory.indexOf("new ChunkedCandleDataClient");

        assertTrue(chunk >= 0 && retry > chunk, "chunking wraps retrying");
        assertTrue(limit > retry, "retrying wraps rate limiting, so a retried request spends a token");
    }

    private static boolean constructsRawClient(Path path) {
        try {
            return Files.readString(path).contains("new GrowwCandleDataClient(");
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
