package dev.pulse.monitor;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.*;

public final class MonitorService extends Service {
    public static final String ACTION_START="dev.pulse.monitor.START", ACTION_STOP="dev.pulse.monitor.STOP", ACTION_REFRESH="dev.pulse.monitor.REFRESH", ACTION_TOGGLE_OVERLAY="dev.pulse.monitor.TOGGLE";
    public static volatile MetricsSnapshot latestSnapshot;
    public static volatile boolean isRunning;
    private static final int NOTIFICATION_ID=41;
    private static final String CHANNEL="pulse_live";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private MetricsCollector collector;
    private MonitorPrefs prefs;
    private OverlayPanel overlay;
    private volatile boolean destroyed;
    private long lastNotification;
    @Override public void onCreate() {
        super.onCreate(); prefs=new MonitorPrefs(this); collector=new MetricsCollector(this);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"实时性能监测",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("持续监测状态与实时指标；可在通知内隐藏悬浮窗或停止监测");
        channel.setShowBadge(false); getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?ACTION_START:intent.getAction();
        if(ACTION_STOP.equals(action)) { prefs.putBoolean("monitor_wanted",false); stopSelf(); return START_NOT_STICKY; }
        if(ACTION_TOGGLE_OVERLAY.equals(action)) prefs.putBoolean("overlay_enabled",!prefs.getBoolean("overlay_enabled",true));
        Notification initial=notification();
        if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION_ID,initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID,initial);
        prefs.putBoolean("monitor_wanted",true);
        refreshOverlay();
        if(!isRunning) { isRunning=true; worker.execute(this::sample); }
        else getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification());
        return START_STICKY;
    }
    private void sample() {
        if(destroyed) return;
        long started=SystemClock.elapsedRealtime();
        try {
            MetricsSnapshot next=collector.collect();
            if(!destroyed)PulseWidgetProvider.publish(this,next,false);
            if(!destroyed) { latestSnapshot=next; main.post(()-> {
                if(destroyed) return;
                if(overlay!=null) overlay.update(next);
                if(SystemClock.elapsedRealtime()-lastNotification>=2500) {
                    lastNotification=SystemClock.elapsedRealtime();
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification());
                }
            }); }
        } catch(Exception e) { Log.w("PulseMonitor","采集失败",e); }
        if(!destroyed) {
            int interval=Math.max(500,Math.min(5000,prefs.getInt("refresh_ms",1000)));
            PowerManager power=getSystemService(PowerManager.class);
            if(!power.isInteractive()) interval=Math.max(interval,5000);
            long delay=Math.max(150,interval-(SystemClock.elapsedRealtime()-started));
            try { worker.schedule(this::sample,delay,TimeUnit.MILLISECONDS); } catch(RejectedExecutionException ignored) {}
        }
    }
    private void refreshOverlay() {
        if(overlay!=null) { overlay.remove(); overlay=null; }
        if(prefs.getBoolean("overlay_enabled",true) && Settings.canDrawOverlays(this)) {
            try { overlay=new OverlayPanel(this,prefs); overlay.show(); if(latestSnapshot!=null) overlay.update(latestSnapshot); }
            catch(RuntimeException e) { Log.w("PulseMonitor","悬浮窗权限或窗口不可用",e); overlay=null; }
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config); refreshOverlay();
    }
    private PendingIntent serviceAction(String action,int request) {
        return PendingIntent.getService(this,request,new Intent(this,MonitorService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private String metric(String id) {
        MetricsSnapshot.Metric m=latestSnapshot==null?null:latestSnapshot.find(id);
        return m!=null && m.available?m.value+(m.unit.isEmpty()?"":" "+m.unit):"—";
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String summary=prefs.getBoolean("notification_enabled",true)?"CPU "+metric("cpu_usage")+"   ·   "+metric("cpu_temp_avg")+"   ·   "+metric("battery_power"):"性能监测正在运行";
        String expanded=prefs.getBoolean("notification_enabled",true)?summary+"\n可用内存 "+metric("mem_available")+"   ·   GPU "+metric("gpu_usage")+"\n电池 "+metric("battery_temp")+"   ·   "+metric("battery_current")+"   ·   "+metric("battery_voltage"):"持续读取本机性能指标；点按打开面板。";
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("脉冲 · 实时监测").setContentText(summary).setStyle(new Notification.BigTextStyle().bigText(expanded))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setColor(0xff78e8c6)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null,prefs.getBoolean("overlay_enabled",true)?"隐藏悬浮窗":"显示悬浮窗",serviceAction(ACTION_TOGGLE_OVERLAY,1)).build())
            .addAction(new Notification.Action.Builder(null,"停止监测",serviceAction(ACTION_STOP,2)).build()).build();
    }
    @Override public void onDestroy() {
        destroyed=true; isRunning=false; main.removeCallbacksAndMessages(null);
        if(overlay!=null) overlay.remove();
        worker.shutdownNow();
        // Close asynchronously so a blocked privileged read cannot freeze the main thread.
        new Thread(()->collector.close(),"pulse-cleanup").start();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
