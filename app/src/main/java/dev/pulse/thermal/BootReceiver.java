package dev.pulse.thermal;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        if(i==null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        Policy p=Policy.load(c);
        if(p.unlimited && p.resume) {
            try { c.startForegroundService(new Intent(c,ThermalService.class)); } catch(Exception ignored) { /* Root boot recovery is independent of Android's service lifecycle. */ }
        }
    }
}
