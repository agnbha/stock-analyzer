package com.stockanalyzer.note;

import java.util.List;

/** Where the notes written on the charts can be read from. */
public interface GrafanaAnnotationSource {

    /** Every note a person wrote on a chart; the annotations Grafana's alerting makes are not included. */
    List<GrafanaAnnotation> fetch();

    /** The source cannot be read: Grafana's database is not where it was said to be. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
