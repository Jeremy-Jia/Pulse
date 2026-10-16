package dev.pulse.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable sample. Unavailable readings are never replaced by fabricated zeroes. */
public final class MetricsSnapshot {
    public final long timestampMillis;
    public final List<Metric> metrics;
    public final boolean rootAvailable;
    public final int cpuCount;
    public final String message;

    public MetricsSnapshot(long timestampMillis, List<Metric> metrics) {
        this(timestampMillis, metrics, false, Runtime.getRuntime().availableProcessors(), "");
    }

    public MetricsSnapshot(long timestampMillis, List<Metric> metrics, boolean rootAvailable,
                           int cpuCount, String message) {
        this.timestampMillis = timestampMillis;
        this.metrics = Collections.unmodifiableList(new ArrayList<>(metrics));
        this.rootAvailable = rootAvailable;
        this.cpuCount = cpuCount;
        this.message = message;
    }

    public Metric find(String id) {
        for (Metric m : metrics) if (m.id.equals(id)) return m;
        return null;
    }

    public static final class Metric {
        public final String id, label, category, value, valueText, unit, source;
        public final boolean available;
        public final double numericValue;

        public Metric(String id, String label, String category, String value, String unit,
                      String source, boolean available) {
            this(id, label, category, value, unit, source, available, Double.NaN);
        }

        public Metric(String id, String label, String category, String value, String unit,
                      String source, boolean available, double numericValue) {
            this.id = id; this.label = label; this.category = category;
            this.value = value; this.valueText = value; this.unit = unit;
            this.source = source; this.available = available; this.numericValue = numericValue;
        }
    }
}
