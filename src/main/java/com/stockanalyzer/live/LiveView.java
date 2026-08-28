package com.stockanalyzer.live;

import com.stockanalyzer.model.LiveSnapshot;

/** Renders one frame of the monitor. Terminal today, a local page just as easily. */
public interface LiveView {

    void render(LiveSnapshot snapshot);

    default void close() {
    }
}
