package dev.pulse.monitor;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Explicit read-only device integration check, without an external test runner. */
public final class MonitorInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        MetricsCollector collector = null;
        int finishCode = Activity.RESULT_CANCELED;
        try {
            check(Looper.myLooper() != Looper.getMainLooper(), "Collector must not block the UI thread");
            // HyperOS freezes otherwise headless instrumentation; keep the target visibly foreground.
            startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            checkParsingBoundaries();
            collector = new MetricsCollector(getTargetContext());
            MetricsSnapshot first = collector.collect();
            validate(first);
            SystemClock.sleep(1100);
            MetricsSnapshot second = collector.collect();
            validate(second);
            check(second.timestampMillis >= first.timestampMillis, "Snapshot timestamps moved backwards");

            JSONObject report = new JSONObject();
            report.put("result", "pass");
            report.put("parserChecks", "pass");
            report.put("first", json(first));
            report.put("second", json(second));
            result.putString("report_json", report.toString());
            result.putString("stream", "\nPASS: parsing boundaries and two real telemetry samples.\n"
                    + "Root: " + second.rootAvailable + "; available metrics: " + available(second)
                    + "/" + second.metrics.size() + "; CPU cores: " + second.cpuCount + ".\n"
                    + "No preferences or device permissions were changed by the test.\n");
            finishCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + error + "\n");
            result.putString("failure", error.toString());
        } finally {
            if (collector != null) collector.close();
        }
        finish(finishCode, result);
    }

    private static void validate(MetricsSnapshot snapshot) {
        check(snapshot.cpuCount > 0 && snapshot.cpuCount <= 256, "Invalid CPU core count");
        check(snapshot.timestampMillis > 0, "Invalid sample timestamp");
        Set<String> ids = new HashSet<>();
        for (MetricsSnapshot.Metric metric : snapshot.metrics) {
            check(ids.add(metric.id), "Duplicate metric ID: " + metric.id);
            if (!metric.available) continue;
            // Text states deliberately have no unit or numeric value.
            if (metric.unit.isEmpty()) continue;
            double shown;
            try { shown = Double.parseDouble(metric.value); }
            catch (NumberFormatException e) { throw new AssertionError("Non-numeric available metric: " + metric.id); }
            check(Double.isFinite(shown), "Non-finite display value: " + metric.id);
            check(Double.isFinite(metric.numericValue), "Non-finite numeric value: " + metric.id);
            if (metric.unit.equals("%")) inRange(metric.numericValue, 0, 100, metric.id);
            if (metric.unit.equals("MHz") || metric.unit.equals("GB") || metric.unit.equals("W")
                    || metric.unit.equals("ms") || metric.unit.equals("fps") || metric.unit.equals("Hz"))
                check(metric.numericValue >= 0, "Negative magnitude: " + metric.id);
            if (metric.unit.equals("°C")) inRange(metric.numericValue, -40, 180, metric.id);
        }

        double total = required(snapshot, "mem_total");
        double free = required(snapshot, "mem_available");
        double used = required(snapshot, "mem_used");
        check(total > 0 && free >= 0 && free <= total, "Invalid memory totals");
        check(Math.abs(total - free - used) < .001, "Memory total does not equal available plus used");
        inRange(required(snapshot, "battery_level"), 0, 100, "battery_level");
        MetricsSnapshot.Metric voltage = snapshot.find("battery_voltage");
        if (voltage != null && voltage.available) inRange(voltage.numericValue, 0, 20, "battery_voltage");
        check(snapshot.find("cpu_usage") != null, "CPU metric is missing from catalog");
        check(snapshot.find("gpu_usage") != null, "GPU metric is missing from catalog");
    }

    private static double required(MetricsSnapshot snapshot, String id) {
        MetricsSnapshot.Metric metric = snapshot.find(id);
        check(metric != null && metric.available && Double.isFinite(metric.numericValue),
                "Required base metric unavailable: " + id);
        return metric.numericValue;
    }

    private static void checkParsingBoundaries() {
        check(MetricsCollector.parseSections("").isEmpty(), "Empty section stream must stay empty");
        Map<String,String> sections = MetricsCollector.parseSections(
                "ignored preamble\n@@stat\ncpu 10 0 0 90\n@@empty\n@@voltage\n4435000\n");
        check("cpu 10 0 0 90".equals(sections.get("stat")), "Multiline section parsing failed");
        check("".equals(sections.get("empty")), "Empty section lost");
        check("4435000".equals(sections.get("voltage")), "Final section lost");
        check(MetricsCollector.number(" 123.5 ") == 123.5, "Trimmed numeric input failed");
        check(Double.isNaN(MetricsCollector.number(null)), "Missing input fabricated a numeric value");
        check(Double.isNaN(MetricsCollector.number("permission denied")), "Invalid input fabricated a numeric value");
        Map<String,MetricsSnapshot.Metric> values = new LinkedHashMap<>();
        MetricsSnapshot.Metric initial = new MetricsSnapshot.Metric("test", "Test", "Test", "—", "V", "test", false);
        values.put("test", initial);
        MetricsCollector.put(values, "test", Double.NaN, 2);
        MetricsCollector.put(values, "test", Double.POSITIVE_INFINITY, 2);
        MetricsCollector.put(values, "test", Double.NEGATIVE_INFINITY, 2);
        check(!values.get("test").available, "Non-finite input must remain unavailable");
        MetricsCollector.put(values, "test", 0, 2);
        check(values.get("test").available && values.get("test").numericValue == 0,
                "Real zero must remain distinguishable from unavailable");
        MetricsCollector.put(values, "unknown", 1, 0);
        check(!values.containsKey("unknown"), "Unknown metric ID was silently added");
    }

    private static JSONObject json(MetricsSnapshot snapshot) throws Exception {
        JSONObject out = new JSONObject();
        out.put("timestampMillis", snapshot.timestampMillis);
        out.put("rootAvailable", snapshot.rootAvailable);
        out.put("rootStatus", snapshot.message);
        out.put("cpuCount", snapshot.cpuCount);
        out.put("availableCount", available(snapshot));
        out.put("totalCount", snapshot.metrics.size());
        JSONArray metrics = new JSONArray();
        for (MetricsSnapshot.Metric metric : snapshot.metrics) {
            JSONObject item = new JSONObject();
            item.put("id", metric.id);
            item.put("available", metric.available);
            item.put("value", metric.value);
            item.put("unit", metric.unit);
            item.put("numeric", Double.isFinite(metric.numericValue) ? metric.numericValue : JSONObject.NULL);
            metrics.put(item);
        }
        out.put("metrics", metrics);
        return out;
    }

    private static int available(MetricsSnapshot snapshot) {
        int count = 0;
        for (MetricsSnapshot.Metric metric : snapshot.metrics) if (metric.available) count++;
        return count;
    }

    private static void inRange(double value, double min, double max, String name) {
        check(Double.isFinite(value) && value >= min && value <= max,
                "Out-of-range " + name + ": " + value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
