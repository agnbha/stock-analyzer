package com.stockanalyzer.auth;

import java.time.Instant;

/**
 * Supplies the current one-time code.
 *
 * <p>{@link TotpGenerator} derives it from a stored seed, which is what running
 * unattended needs. Keeping it behind an interface leaves room for the cases
 * where the seed cannot live on the machine - a hardware token, or prompting a
 * person - without touching the authenticator.
 */
public interface TotpCodeSource {

    String currentCode();

    /** How long the current code remains valid; used to avoid sending one about to expire. */
    long secondsUntilNextCode(Instant at);
}
