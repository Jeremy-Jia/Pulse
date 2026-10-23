package dev.pulse.thermal;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONObject;

/** Explicit device check. Uses saved policy, never changes the user's settings. */
public final class GuardChecks {
    public static JSONObject run(Instrumentation i, String action) throws Exception {
        MainActivity a=(MainActivity)i.startActivitySync(new Intent(i.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.runOnMainSync(() -> a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        ThermalHub h=ThermalHub.get(a);
        long end=SystemClock.elapsedRealtime()+60000;
        while((!h.connected||h.busy||!h.statusKnown)&&SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
        check(h.connected&&!h.busy&&h.statusKnown,"connect: "+h.message);
        if("apply".equals(action)&&!h.snapshot.active()) {
            i.runOnMainSync(a::startPolicy);
            end=SystemClock.elapsedRealtime()+30000;
            while((!h.snapshot.active()||h.busy)&&SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
        }
        Snapshot s=h.engine.status();
        if("apply".equals(action)) check(s.active(),"apply: "+h.message);
        if(s.active()) check(s.n("watch_alive")==1&&s.n("sentry_alive")==1,"live independent guards");
        check(!new Snapshot("state=unprotected\nreason=GUARD_UNHEALTHY").active(),"orphan reported active");
        i.runOnMainSync(() -> {
            h.snapshot=s; h.statusKnown=false; a.update();
            check("正在核对状态".contentEquals(a.heroTitle.getText()),"stale hero after Root failure");
            h.statusKnown=true; a.update();
        });
        JSONObject result=new JSONObject().put("result","pass").put("state",s.s("state"))
            .put("session",s.s("session")).put("max6",s.n("max6"))
            .put("watch_alive",s.n("watch_alive")).put("sentry_alive",s.n("sentry_alive"))
            .put("unknown_state_ui","pass").put("service_running",ThermalService.running);
        return result;
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
