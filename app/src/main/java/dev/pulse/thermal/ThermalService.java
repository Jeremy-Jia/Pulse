package dev.pulse.thermal;
import dev.pulse.monitor.R;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;

public final class ThermalService extends Service implements ThermalHub.Listener {
    static final String START="dev.pulse.thermal.START", STOP="dev.pulse.thermal.STOP";
    static final int ID=71, ALERT_ID=72;
    ThermalHub hub;
    PowerManager.WakeLock wake;
    boolean requested=false;
    long bootWaitUntil=0;
    @Override public void onCreate() {
        super.onCreate(); hub=ThermalHub.get(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("pulse_thermal","温控策略运行状态",NotificationManager.IMPORTANCE_LOW));
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("pulse_thermal_alerts","温度保护与自动恢复",NotificationManager.IMPORTANCE_DEFAULT));
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
            .addAction(new Notification.Action.Builder(null,hub.snapshot.thermalPaused()?"停止自动恢复":"恢复原厂",stop).build()).build();
    }
    @Override public void update() {
        Snapshot s=hub.snapshot;
        if(hub.statusKnown) postTransition(s);
        if(s.active()) {
            bootWaitUntil=0;
            getSystemService(NotificationManager.class).notify(ID,notification((s.thermalPaused()?"降温等待 · ":s.paused()?"等待普通场景 · ":s.cooling()?"温度调节 "+s.n("cooling_percent")+"% · ":"")+"电池 "+s.temperature("battery_dc",10)+"  ·  机身 "+s.temperature("body_mc",1000)+"  ·  "+s.countdown()));
        }
        else if(requested && hub.statusKnown && !hub.busy && (s.s("reason").equals("MANUAL") || SystemClock.elapsedRealtime()>=bootWaitUntil)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    }
    void postTransition(Snapshot s) {
        NotificationManager manager=getSystemService(NotificationManager.class);
        String kind=s.s("notice_kind");
        if(!s.active() && (s.s("reason").equals("MANUAL") || (s.s("state").equals("restored") && kind.equals("PAUSED")))) { manager.cancel(ALERT_ID); return; }
        if(s.n("notice_seq")==0 || kind.isEmpty()) return;
        if(kind.equals("PAUSED") && !s.thermalPaused()) return;
        if(kind.equals("RESUMED") && !s.s("state").equals("active")) return;
        String key=s.s("session")+":"+s.n("notice_seq");
        android.content.SharedPreferences prefs=getSharedPreferences("thermal_alerts",0);
        if(key.equals(prefs.getString("last",""))) return;
        String title=kind.equals("PAUSED")?"温度保护 · 已暂停性能策略":kind.equals("RESUMED")?"降温完成 · 已恢复性能策略":"温度保护 · 已停止性能策略";
        String detail=kind.equals("PAUSED")?s.reason()+"。\n"+s.resumeRule():kind.equals("RESUMED")?"已自动恢复原先的 CPU/GPU 设置；机身低于 "+s.n("body_limit")/1000+"°C 全释放。":s.reason()+"，需要手动重新开启。";
        detail+="\n当前 CPU 热点 "+s.temperature("cpu_mc",1000)+" · 电池 "+s.temperature("battery_dc",10)+" · 机身 "+s.temperature("body_mc",1000);
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ThermalService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,"pulse_thermal_alerts").setSmallIcon(R.drawable.ic_thermal_status)
            .setContentTitle("Pulse · "+title).setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open).setAutoCancel(true);
        if(s.thermalPaused()) b.addAction(new Notification.Action.Builder(null,"停止自动恢复",stop).build());
        manager.notify(ALERT_ID,b.build()); prefs.edit().putString("last",key).apply();
    }
    @Override public void onDestroy() { hub.listeners.remove(this); if(wake!=null && wake.isHeld()) wake.release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
