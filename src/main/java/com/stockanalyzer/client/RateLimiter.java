package com.stockanalyzer.client;

/** Blocks the caller until another request may be made. */
public interface RateLimiter {

    void acquire();

    /**
     * Holds every caller back for a while, after the server has said to slow
     * down.
     *
     * <p>This matters with several worker threads: without it, one thread gets
     * a 429 and backs off politely while the others carry on hammering, which
     * is how a soft throttle turns into a hard one.
     */
    default void penalise(long millis) {
    }
}
