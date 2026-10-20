package dev.pulse.thermal;
import dev.pulse.monitor.R;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;

public final class ThermalService extends Service implements ThermalHub.Listener {
    static final String START="dev.pulse.thermal.START", STOP="dev.pulse.thermal.STOP";
    static final int ID=71;
    ThermalHub hub;
    PowerManager.WakeLock wake;
    boolean requested=false;
    long bootWaitUntil=0;
    @Override public void onCreate() {
        super.onCreate(); hub=ThermalHub.get(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("pulse_thermal","温控策略运行状态",NotificationManager.IMPORTANCE_LOW));
        hub.listeners.add(this);
        Notification n=notification("正在核对策略状态");
        if(Build.VERSION.SDK_INT>=34) startForeground(ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(ID,n);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && STOP.equals(intent.getAction())) { requested=true; hub.stop(); }
        else if(intent!=null && START.equals(intent.getAction())) {
            requested=true;
            Policy p=Policy.load(this);
            if(!p.unlimited) {
                wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PulseThermal:TimedSession");
                wake.acquire(p.minutes*60000L+60000L);
            }
            hub.apply(p);
        } else {
            requested=true;
            Policy p=Policy.load(this); if(p.unlimited && p.resume) bootWaitUntil=SystemClock.elapsedRealtime()+150000;
            hub.sync();
        }
        return START_STICKY;
    }
    Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ThermalService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"pulse_thermal").setSmallIcon(R.drawable.ic_thermal_status).setContentTitle("Pulse · "+(hub.snapshot.active()?"温控策略运行中":"温控策略"))
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,"恢复原厂",stop).build()).build();
    }
    @Override public void update() {
        Snapshot s=hub.snapshot;
        if(s.active()) {
            bootWaitUntil=0;
            getSystemService(NotificationManager.class).notify(ID,notification((s.paused()?"等待普通场景 · ":"")+"电池 "+s.temperature("battery_dc",10)+"  ·  机身 "+s.temperature("body_mc",1000)+"  ·  "+s.countdown()));
        }
        else if(requested && hub.statusKnown && !hub.busy && (s.s("reason").equals("MANUAL") || SystemClock.elapsedRealtime()>=bootWaitUntil)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    }
    @Override public void onDestroy() { hub.listeners.remove(this); if(wake!=null && wake.isHeld()) wake.release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
