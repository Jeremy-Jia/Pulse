package dev.pulse.thermal;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;
import java.util.concurrent.Callable;

/** Explicit, bounded hardware-writing integration suite. No benchmark workload. */
public final class ThermalChecks {
    public static JSONObject runGpu18(Instrumentation i) throws Exception {
        Context c=i.getTargetContext(); Policy saved=Policy.load(c);
        MainActivity activity=(MainActivity)i.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        ThermalHub hub=ThermalHub.get(c);
        waitFor(() -> hub.connected&&!hub.busy,45000,"connect GPU test: "+hub.message);
        check(hub.engine.profile==ThermalProfile.MI18&&!hub.snapshot.active(),"requires idle hongkong");
        JSONObject report=new JSONObject();
        try {
            Policy p=new Policy(); p.gpuEnabled=true; p.unlimited=true; p.batteryC=50; p.bodyC=50; p.cpuGuard=true; p.cpuC=105;
            hub.engine.start(p);
            waitFor(() -> hub.engine.status().n("gpu_max_hz")==1450000000,15000,"GPU actuator settling");
            Snapshot s=hub.engine.status(); check(s.active()&&s.n("gpu_enabled")==1,"GPU enabled session");
            check(s.n("gpu_max_hz")==1450000000,"GPU upper vote not released: "+s.n("gpu_max_hz"));
            report.put("gpu_max_hz",s.n("gpu_max_hz"));
            waitFor(() -> hub.engine.export().contains("gpu_hz,gpu_max_hz,gpu_thermal_level,gpu_enabled"),5000,"GPU CSV fields");
            report.put("csv_export","pass");
            hub.engine.stop(); factoryMi18(); report.put("manual_restore","pass"); note(i,"PASS GPU upper vote and exact factory restoration");
            p.unlimited=false; p.minutes=1; hub.engine.start(p);
            waitFor(() -> hub.engine.status().s("state").equals("restored"),85000,"GPU timer");
            check(hub.engine.status().s("reason").equals("TIMER"),"GPU timer reason"); factoryMi18(); report.put("timer_restore","pass"); note(i,"PASS GPU one-minute timer restores original configuration");
            p.unlimited=true; hub.engine.start(p);
            SystemClock.sleep(2000);
            RootEngine.root("id=$(cat /data/adb/pulse-thermal/current); kill -KILL $(cat /data/adb/pulse-thermal/sessions/$id/watch.pid)");
            waitFor(() -> hub.engine.status().s("state").equals("restored"),30000,"GPU sentry"); factoryMi18();
            check(hub.engine.status().s("reason").equals("WATCHDOG_LOST"),"GPU sentry reason"); report.put("independent_sentry_restore","pass");
            report.put("result","pass");
        } finally { try { hub.engine.stop(); } finally { saved.save(c); } }
        return report;
    }
    public static JSONObject runMi18(Instrumentation i) throws Exception {
        Context c=i.getTargetContext(); Policy saved=Policy.load(c); ThermalHub hub=ThermalHub.get(c);
        ThermalHub.Listener listener=() -> {};
        hub.listeners.add(listener); hub.connect(); note(i,"Connecting from the actual Pulse process");
        waitFor(() -> hub.connected && !hub.busy,45000,"Root connect: "+hub.message);
        check(hub.engine.profile==ThermalProfile.MI18,"requires hongkong");
        check(!hub.snapshot.active(),"existing user session");
        JSONObject report=new JSONObject();
        try {
            Policy p=new Policy(); p.unlimited=true; p.batteryC=45; p.bodyC=43; p.cpuGuard=true; p.cpuC=95;
            hub.apply(p);
            waitFor(() -> hub.snapshot.active() && !hub.busy,20000,"apply: "+hub.message);
            String id=hub.snapshot.s("session");
            check(RootEngine.root("cat /proc/1/mountinfo | grep /data/vendor/thermal/config").contains("/data/vendor/thermal/config"),"global runtime mount");
            check(hub.snapshot.n("max3")>0,"third CPU group missing");
            note(i,"PASS Root from Pulse UID, three-domain curve loaded, global runtime-only mount");
            SystemClock.sleep(6000);
            check(hub.engine.status().active(),"guardian lost session");
            hub.sync();
            waitFor(() -> hub.statusKnown && hub.snapshot.active(),8000,"foreground rehydration");
            check(hub.snapshot.s("session").equals(id),"return must retain original session");
            report.put("guardian_and_resync","pass"); note(i,"PASS guardian and fresh status retain same session");
            hub.engine.stop(); factoryMi18();
            p.unlimited=false; p.minutes=1;
            hub.engine.start(p);
            waitFor(() -> hub.engine.status().s("state").equals("restored"),80000,"one-minute timer");
            check(hub.engine.status().s("reason").equals("TIMER"),"timer reason"); factoryMi18();
            report.put("timer_and_unmount","pass"); note(i,"PASS one-minute timer, exact original hash and runtime unmount");
            p.unlimited=true; hub.engine.start(p);
            RootEngine.root("id=$(cat /data/adb/pulse-thermal/current); kill -KILL $(cat /data/adb/pulse-thermal/sessions/$id/watch.pid)");
            waitFor(() -> hub.engine.status().s("state").equals("restored"),25000,"sentry fallback"); factoryMi18();
            report.put("watchdog_fallback","pass"); report.put("result","pass");
        } finally { try { hub.engine.stop(); } finally { saved.save(c); hub.listeners.remove(listener); } }
        return report;
    }
    static void factoryMi18() throws Exception {
        check(RootEngine.root("sha256sum /data/vendor/thermal/decrypt.txt").startsWith(ThermalProfile.MI18.plainSha),"exact factory plaintext");
        check(RootEngine.root("awk '$5==\"/data/vendor/thermal/config\" {print $1}' /proc/1/mountinfo").trim().isEmpty(),"runtime mount removed");
        check(RootEngine.root("ls -A /data/vendor/thermal/config").trim().isEmpty(),"factory directory remains empty");
    }
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static void waitFor(Callable<Boolean> condition,long timeout,String message) throws Exception {
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until) { if(condition.call()) return; SystemClock.sleep(300); }
        throw new AssertionError(message);
    }
    static void note(Instrumentation i,String message) { Bundle b=new Bundle(); b.putString("stream",message+"\n"); i.sendStatus(1,b); }
    static void factory() throws Exception {
        check(RootEngine.root("sha256sum /data/vendor/thermal/decrypt.txt").startsWith(ConfigCodec.ORIGINAL_SHA),"factory plaintext hash restored");
        check(RootEngine.root("if [ -e /data/vendor/thermal/config/thermal-normal.conf ]; then echo PRESENT; else echo ABSENT; fi").trim().equals("ABSENT"),"override removed");
    }
    public static JSONObject run(Instrumentation i,boolean bootOnly) throws Exception {
        Context c=i.getTargetContext(); Policy saved=Policy.load(c);
        MainActivity activity=(MainActivity)i.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        ThermalHub hub=ThermalHub.get(c);
        waitFor(() -> hub.connected && !hub.busy,45000,"Pulse Root connection unavailable: "+hub.message);
        check(!hub.snapshot.active(),"Do not run checks over an existing active user session");
        JSONObject report=new JSONObject();
        try {
            Policy p=new Policy(); p.batteryC=50; p.bodyC=50; p.minutes=15;
            if(!bootOnly) {
            i.runOnMainSync(() -> { activity.policy=p; activity.startPolicy(); });
            waitFor(() -> hub.snapshot.active() && !hub.busy,15000,"UI start failed: "+hub.message);
            check(RootEngine.root("sha256sum /data/vendor/thermal/decrypt.txt").startsWith("70bb36c356d3f8b1fee484c10a522ef9e63a599b1c8417f469b24c0f6fd5ea69"),"fullmax matches exact tested curve");
            waitFor(() -> { for(StatusBarNotification n:c.getSystemService(NotificationManager.class).getActiveNotifications()) if(n.getNotification().getChannelId().equals("pulse_thermal")) return true; return false; },5000,"thermal foreground notification missing");
            for(StatusBarNotification n:c.getSystemService(NotificationManager.class).getActiveNotifications()) if(n.getNotification().getChannelId().equals("pulse_thermal")) n.getNotification().actions[0].actionIntent.send();
            waitFor(() -> !hub.snapshot.active() && !hub.busy,45000,"notification restore failed"); factory();
            long stopped=hub.engine.status().n("elapsed"); SystemClock.sleep(1200); check(hub.engine.status().n("elapsed")==stopped,"stopped elapsed must freeze");
            report.put("ui_start_notification_restore","pass"); note(i,"PASS UI start, exact fullmax bytes, foreground notification, notification restore");

            p.minutes=1; i.runOnMainSync(() -> { activity.policy=p; activity.startPolicy(); });
            waitFor(() -> hub.snapshot.active() && !hub.busy,15000,"timer start failed");
            waitFor(() -> !hub.snapshot.active() && !hub.busy,75000,"one-minute timer failed");
            check(hub.snapshot.s("reason").equals("TIMER"),"timer reason: "+hub.snapshot.s("reason")); factory();
            report.put("real_one_minute_timer","pass"); note(i,"PASS one-minute timer and factory restoration");

            p.unlimited=true; p.resume=false;
            i.runOnMainSync(() -> { activity.policy=p; activity.startPolicy(); });
            waitFor(() -> hub.snapshot.active() && !hub.busy,15000,"persistent start failed");
            RootEngine.root("id=$(cat /data/adb/pulse-thermal/current); pid=$(cat /data/adb/pulse-thermal/sessions/$id/watch.pid); kill -KILL \"$pid\"");
            waitFor(() -> !hub.snapshot.active() && !hub.busy,20000,"independent sentry failed");
            check(hub.snapshot.s("reason").equals("WATCHDOG_LOST"),"sentry reason: "+hub.snapshot.s("reason")); factory();
            report.put("independent_guard_after_sigkill","pass"); note(i,"PASS independent guard after monitor SIGKILL");
            }
            p.unlimited=true;
            p.resume=true; i.runOnMainSync(() -> { activity.policy=p; activity.startPolicy(); });
            waitFor(() -> hub.snapshot.active() && !hub.busy,15000,"boot-resume start failed");
            String id=hub.snapshot.s("session");
            String oldPid=RootEngine.root("cat /data/adb/pulse-thermal/sessions/"+id+"/watch.pid").trim();
            RootEngine.root("/system/bin/sh /data/adb/pulse-thermal/runner.sh boot");
            check(RootEngine.root("cat /data/adb/pulse-thermal/sessions/"+id+"/watch.pid").trim().equals(oldPid),"same-boot hook must be no-op");
            RootEngine.root("echo simulated-previous-boot > /data/adb/pulse-thermal/sessions/"+id+"/boot.id; /system/bin/sh /data/adb/pulse-thermal/runner.sh boot");
            check(!RootEngine.root("cat /data/adb/pulse-thermal/sessions/"+id+"/watch.pid").trim().equals(oldPid),"simulated reboot must restart guardian");
            check(hub.engine.status().active(),"resume must reapply valid configuration");
            hub.engine.stop(); factory(); report.put("boot_dispatch_simulation","pass; actual phone reboot not performed"); note(i,"PASS same-boot no-op and simulated reboot recovery/resume");

            p.resume=false; p.mode="custom"; p.curve0[1]=3532800;
            hub.engine.start(p); check(hub.engine.status().s("mode").equals("custom"),"custom mode apply");
            SystemClock.sleep(1500);
            hub.engine.stop(); factory(); report.put("custom_single_cluster_curve","pass");
            String csv=hub.engine.export(); check(csv.startsWith("uptime_s,") && csv.split("\n").length>1,"export real telemetry");
            report.put("csv_export","pass"); report.put("result","pass");
        } finally {
            try { hub.engine.stop(); } finally { saved.save(c); i.runOnMainSync(() -> { activity.policy=saved; activity.showPage(0); }); }
        }
        return report;
    }
}
