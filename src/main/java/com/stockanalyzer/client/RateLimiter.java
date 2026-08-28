package com.stockanalyzer.client;

/** Blocks the caller until another request may be made. */
public interface RateLimiter {

    void acquire();
}
