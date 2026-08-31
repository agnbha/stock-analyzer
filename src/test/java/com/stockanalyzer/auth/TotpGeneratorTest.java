package com.stockanalyzer.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpGeneratorTest {

    /** The RFC 6238 reference seed, "12345678901234567890", in Base32. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    @DisplayName("matches the RFC 6238 test vectors")
    void matchesRfcTestVectors() {
        TotpGenerator generator = new TotpGenerator(RFC_SECRET, 8, 30, "HmacSHA1");

        assertEquals("94287082", generator.generate(Instant.ofEpochSecond(59L)));
        assertEquals("07081804", generator.generate(Instant.ofEpochSecond(1111111109L)));
        assertEquals("14050471", generator.generate(Instant.ofEpochSecond(1111111111L)));
        assertEquals("89005924", generator.generate(Instant.ofEpochSecond(1234567890L)));
        assertEquals("69279037", generator.generate(Instant.ofEpochSecond(2000000000L)));
        assertEquals("65353130", generator.generate(Instant.ofEpochSecond(20000000000L)));
    }

    @Test
    @DisplayName("six-digit codes are the last six digits of the same computation")
    void sixDigitCodes() {
        TotpGenerator generator = new TotpGenerator(RFC_SECRET);

        assertEquals("287082", generator.generate(Instant.ofEpochSecond(59L)));
        assertEquals("081804", generator.generate(Instant.ofEpochSecond(1111111109L)));
        assertEquals("050471", generator.generate(Instant.ofEpochSecond(1111111111L)));
    }

    @Test
    @DisplayName("the code is stable within a step and changes at the boundary")
    void codeChangesOnceAStep() {
        TotpGenerator generator = new TotpGenerator(RFC_SECRET);

        String atStart = generator.generate(Instant.ofEpochSecond(1111111110L));
        String atEnd = generator.generate(Instant.ofEpochSecond(1111111139L));
        String nextStep = generator.generate(Instant.ofEpochSecond(1111111140L));

        assertEquals(atStart, atEnd, "same 30-second window");
        assertTrue(!atStart.equals(nextStep), "a new window mints a new code");
    }

    @Test
    @DisplayName("reports how long the current code has left")
    void reportsTimeUntilTheNextCode() {
        TotpGenerator generator = new TotpGenerator(RFC_SECRET);

        assertEquals(30, generator.secondsUntilNextCode(Instant.ofEpochSecond(1111111110L)));
        assertEquals(1, generator.secondsUntilNextCode(Instant.ofEpochSecond(1111111139L)));
    }

    @Test
    @DisplayName("seeds copied by hand still decode")
    void base32DecodingIsForgiving() {
        byte[] expected = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertArrayEquals(expected, TotpGenerator.decodeBase32(RFC_SECRET));
        assertArrayEquals(expected, TotpGenerator.decodeBase32(RFC_SECRET.toLowerCase()));
        assertArrayEquals(expected, TotpGenerator.decodeBase32("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ"));
        assertArrayEquals(expected, TotpGenerator.decodeBase32(RFC_SECRET + "======"));
    }

    @Test
    @DisplayName("a bad seed fails at construction with something actionable")
    void rejectsBadSeeds() {
        GrowwAuthException empty = assertThrows(GrowwAuthException.class, () -> new TotpGenerator(""));
        assertTrue(empty.getMessage().contains("groww.totp.secret"), empty.getMessage());

        GrowwAuthException notBase32 = assertThrows(GrowwAuthException.class,
                () -> new TotpGenerator("not-base32!"));
        assertTrue(notBase32.getMessage().contains("Base32"), notBase32.getMessage());
    }
}
