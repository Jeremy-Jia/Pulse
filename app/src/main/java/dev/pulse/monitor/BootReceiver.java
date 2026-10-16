package dev.pulse.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        MonitorPrefs prefs = new MonitorPrefs(context);
        if (!prefs.getBoolean("boot_enabled", false) || !prefs.getBoolean("monitor_wanted", false)) return;
        try { context.startForegroundService(new Intent(context, MonitorService.class).setAction(MonitorService.ACTION_START)); }
        catch (RuntimeException ignored) { /* OEM background policy may require opening the application. */ }
    }
}
