package dev.pulse.thermal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class ThermalHub {
    interface Listener { void update(); }
    private static ThermalHub instance;
    static synchronized ThermalHub get(Context c) { if(instance==null) instance=new ThermalHub(c); return instance; }
    final CopyOnWriteArrayList<Listener> listeners=new CopyOnWriteArrayList<>();
    final Handler main=new Handler(Looper.getMainLooper());
    final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    final RootEngine engine;
    volatile Snapshot snapshot=new Snapshot("");
    volatile boolean connected=false, busy=false, statusKnown=false;
    volatile String message="连接 Root，核对设备适配";
    private ThermalHub(Context c) {
        engine=new RootEngine(c);
        worker.scheduleWithFixedDelay(() -> { if(connected && !listeners.isEmpty()) refresh(); },1,2,TimeUnit.SECONDS);
    }
    void emit() { main.post(() -> { for(Listener l:listeners) l.update(); }); }
    void connect() {
        if(busy) return; busy=true; message="正在请求 Root 并验证固件…"; emit();
        worker.execute(() -> { try { engine.connect(); connected=true; snapshot=engine.status(); statusKnown=true; message=engine.profile.name+" · 当前固件适配通过"; }
            catch(Exception e) { connected=false; message=e.getMessage(); } finally { busy=false; emit(); } });
    }
    void apply(Policy p) {
        if(busy) return; busy=true; message="正在应用策略并校验实际加载结果…"; emit();
        worker.execute(() -> { try { if(!connected) { engine.connect(); connected=true; } engine.start(p); snapshot=engine.status(); statusKnown=true; message="策略已生效，独立守护正在运行"; }
            catch(Exception e) { message=e.getMessage(); android.util.Log.e("PulseThermal","apply failed",e); try { snapshot=engine.status(); statusKnown=true; } catch(Exception ignored) {} }
            finally { busy=false; emit(); } });
    }
    void stop() {
        if(busy) return; busy=true; emit();
        worker.execute(() -> { try { android.util.Log.i("PulseThermal","restore begin"); engine.stop(); if(!connected) { engine.connect(); connected=true; } snapshot=engine.status(); statusKnown=true; message="已恢复原厂温控"; android.util.Log.i("PulseThermal","restore complete "+snapshot.s("state")); }
            catch(Exception e) { message=e.getMessage(); android.util.Log.e("PulseThermal","restore failed",e); } finally { busy=false; emit(); } });
    }
    void sync() { statusKnown=false; if(!connected) { connect(); return; } worker.execute(this::refresh); }
    void updateBootResume(boolean enabled) {
        worker.execute(() -> { try { boolean updated=engine.updateBootResume(enabled);
                message=updated?(enabled?"开机继续已写入当前会话":"已关闭开机继续"):"开机继续已保存；应用策略后生效"; }
            catch(Exception e) { message=e.getMessage(); android.util.Log.e("PulseThermal","boot resume update failed",e); }
            finally { emit(); } });
    }
    private void refresh() {
        try { boolean wasActive=snapshot.active(); snapshot=engine.status(); statusKnown=true; if(wasActive && !snapshot.active()) message=snapshot.reason(); } catch(Exception e) { statusKnown=false; message="读取状态失败："+e.getMessage(); }
        emit();
    }
}
