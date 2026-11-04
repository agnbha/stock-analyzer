package com.stockanalyzer.model;

import java.util.List;

/**
 * A note written on a chart, as the analyzer keeps it.
 *
 * @param grafanaId    the annotation's id in Grafana, which is what makes a sync repeatable
 * @param symbol       the instrument it is about: named by one of its tags, or assigned by hand; null when neither
 * @param symbolSource {@code TAG} or {@code MANUAL}; a manual assignment survives later syncs
 * @param sentiment    the first tag that is a generic sentiment (BULLISH, BEARISH, NEUTRAL...) once aliases such as
 *                     UP are applied; null when none of its tags is one
 * @param tags         exactly as written in Grafana
 * @param deletedAt    epoch seconds at which a sync found the note gone from Grafana; null while it is there
 */
public record ChartNote(long grafanaId, String dashboardUid, Integer panelId, String symbol, String symbolSource,
                        long notedAt, Long notedTo, String sentiment, List<String> tags, String note, long syncedAt,
                        Long deletedAt) {
}
