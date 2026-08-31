package com.stockanalyzer.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Locale;

/**
 * Time-based one-time passwords, RFC 6238.
 *
 * <p>The seed is the Base32 string the broker shows when two-factor
 * authentication is enabled - the same one an authenticator app scans. Codes
 * are derived from the wall clock, so a machine whose time has drifted more
 * than a step or two will be rejected; on a server, keep NTP running.
 *
 * <p>No third-party dependency: HMAC and Base32 are both short enough to be
 * clearer written out than pulled in.
 */
public final class TotpGenerator implements TotpCodeSource {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final byte[] secret;
    private final int digits;
    private final int periodSeconds;
    private final String algorithm;

    public TotpGenerator(String base32Secret) {
        this(base32Secret, 6, 30, "HmacSHA1");
    }

    public TotpGenerator(String base32Secret, int digits, int periodSeconds, String algorithm) {
        if (base32Secret == null || base32Secret.isBlank()) {
            throw new GrowwAuthException("TOTP secret is empty. Set groww.totp.secret (or GROWW_TOTP_SECRET) "
                    + "to the Base32 seed shown when you enabled two-factor authentication.");
        }
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("TOTP digits must be between 6 and 8");
        }
        if (periodSeconds < 1) {
            throw new IllegalArgumentException("TOTP period must be positive");
        }
        this.secret = decodeBase32(base32Secret);
        this.digits = digits;
        this.periodSeconds = periodSeconds;
        this.algorithm = algorithm;
    }

    @Override
    public String currentCode() {
        return generate(Instant.now());
    }

    public String generate(Instant at) {
        return generateForCounter(Math.floorDiv(at.getEpochSecond(), periodSeconds));
    }

    @Override
    public long secondsUntilNextCode(Instant at) {
        return periodSeconds - Math.floorMod(at.getEpochSecond(), periodSeconds);
    }

    private String generateForCounter(long counter) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret, algorithm));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        } catch (Exception e) {
            throw new GrowwAuthException("Could not generate a TOTP code with " + algorithm, e);
        }

        // Dynamic truncation: the low nibble of the last byte picks the offset.
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        int modulus = (int) Math.pow(10, digits);
        return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
    }

    /** RFC 4648 Base32, tolerant of lowercase, spaces and padding - seeds get copied by hand. */
    static byte[] decodeBase32(String encoded) {
        String cleaned = encoded.replaceAll("[\\s-]", "").replace("=", "").toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            throw new GrowwAuthException("TOTP secret contains no Base32 characters");
        }

        byte[] decoded = new byte[cleaned.length() * 5 / 8];
        int buffer = 0;
        int bitsInBuffer = 0;
        int written = 0;

        for (char character : cleaned.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(character);
            if (value < 0) {
                throw new GrowwAuthException("TOTP secret is not valid Base32: unexpected character '"
                        + character + "'");
            }
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8;
                decoded[written++] = (byte) (buffer >> bitsInBuffer);
            }
        }
        return decoded;
    }
}
