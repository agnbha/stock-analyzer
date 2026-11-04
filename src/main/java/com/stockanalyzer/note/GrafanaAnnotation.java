package com.stockanalyzer.note;

import java.util.List;

/**
 * A note as Grafana stores it.
 *
 * @param epochMs    when it is drawn, in milliseconds
 * @param epochEndMs the end of a range note; equal to {@code epochMs} for a single point
 */
public record GrafanaAnnotation(long id, String dashboardUid, Integer panelId, long epochMs, long epochEndMs,
                                String text, List<String> tags) {
}
