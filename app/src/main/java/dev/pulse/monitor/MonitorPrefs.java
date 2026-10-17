package dev.pulse.monitor;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MonitorPrefs {
    private final SharedPreferences prefs;
    private static final Set<String> DEFAULTS = new LinkedHashSet<>(Arrays.asList(
        "cpu_usage", "cpu_temp_avg", "cpu_freq_0", "cpu_freq_6", "gpu_usage", "gpu_freq",
        "mem_available", "battery_power", "battery_current", "battery_voltage", "battery_temp", "fps"));
    public MonitorPrefs(Context context) { prefs = context.getSharedPreferences("pulse_config", Context.MODE_PRIVATE); }
    public boolean getBoolean(String key, boolean value) { return prefs.getBoolean(key, value); }
    public int getInt(String key, int value) { return prefs.getInt(key, value); }
    public String getString(String key, String value) { return prefs.getString(key, value); }
    public boolean contains(String key) { return prefs.contains(key); }
    public void remove(String... keys) {
        SharedPreferences.Editor editor=prefs.edit();
        for(String key:keys)editor.remove(key);
        editor.apply();
    }
    public void putBoolean(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    public void putInt(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    public void putString(String key, String value) { prefs.edit().putString(key, value).apply(); }
    public Set<String> enabledMetricIds() { return new LinkedHashSet<>(prefs.getStringSet("enabled_metrics", DEFAULTS)); }
    public boolean isMetricEnabled(String id) { return enabledMetricIds().contains(id); }
    public void setMetricEnabled(String id, boolean enabled) {
        Set<String> values = enabledMetricIds();
        if (enabled) values.add(id); else values.remove(id);
        prefs.edit().putStringSet("enabled_metrics", values).apply();
    }
    public void setEnabledMetrics(Set<String> values) { prefs.edit().putStringSet("enabled_metrics", new LinkedHashSet<>(values)).apply(); }
}
