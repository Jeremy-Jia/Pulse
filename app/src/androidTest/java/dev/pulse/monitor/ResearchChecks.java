package dev.pulse.monitor;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;

/** Real installed service, actual packaged executables, KATs, transcript comparison and report export. */
final class ResearchChecks {
    static JSONObject run(Instrumentation test,Bundle options)throws Exception{
        String scope=options.getString("research_scope","crypto");boolean cancel=scope.startsWith("cancel");
        Activity visible=test.startActivitySync(new Intent(test.getTargetContext(),ResearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.runOnMainSync(()->visible.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        String suite="cancel_efficiency".equals(scope)?"efficiency":cancel?"memory":scope;
        Intent intent=new Intent(test.getTargetContext(),ResearchService.class).setAction(ResearchService.RUN).putExtra("suite",suite).putExtra("cpu",6).putExtra("rounds",cancel?5:"crypto".equals(suite)?3:1).putExtra("signatures",512).putExtra("threads",1).putExtra("mib",128).putExtra("root",true);
        ResearchService.report=null;test.getTargetContext().startForegroundService(intent);
        long begin=SystemClock.elapsedRealtime();while(!ResearchService.running&&ResearchService.report==null&&SystemClock.elapsedRealtime()-begin<5000)SystemClock.sleep(30);
        if(cancel){SystemClock.sleep(1700);test.getTargetContext().startService(new Intent(test.getTargetContext(),ResearchService.class).setAction(ResearchService.STOP));}
        while(ResearchService.running&&SystemClock.elapsedRealtime()-begin<240000)SystemClock.sleep(100);
        check(!ResearchService.running,"Research service timed out");JSONObject r=ResearchService.report;check(r!=null,"Missing service report");
        if(cancel){check(r.optBoolean("cancelled"),"Cancellation did not propagate");return new JSONObject().put("result","pass").put("scope",scope).put("report",r);}
        check(!r.has("error"),"Service failure: "+r.optString("error"));check("root".equals(r.optString("execution_backend")),"Root backend not selected");
        JSONArray runs=r.getJSONArray("runs");check(runs.length()==("crypto".equals(scope)?12:"memory".equals(scope)?12:5),"Incomplete matrix: "+runs.length());
        for(int i=0;i<runs.length();i++){JSONObject row=runs.getJSONObject(i);if("efficiency".equals(scope)&&"unsupported".equals(row.optString("result")))continue;check(row.optBoolean("valid"),"Invalid result: "+row);
            if(!"memory".equals(scope)){check(row.optInt("kat_passed")==6,"KAT failure");check(row.optInt("verified")==row.optInt("signatures"),"Signatures not verified");check(row.optBoolean("cross_backend_equal"),"Transcript mismatch");check(row.optBoolean("affinity_stable"),"CPU affinity changed");}
            if("efficiency".equals(scope))check(row.optBoolean("cap_restored"),"Frequency cap not restored");
        }
        String csv=ResearchActivity.csv(r);check(csv.split("\n").length==runs.length()+1,"CSV row count mismatch");check(!csv.contains("NaN")&&!csv.contains("Infinity"),"Nonfinite values in export");
        int summaryCount=r.getJSONArray("summary").length();check("efficiency".equals(scope)?summaryCount>=2:summaryCount==("crypto".equals(scope)?4:12),"Summary groups missing");
        if("crypto".equals(scope)){for(int i=0;i<4;i++){JSONObject row=r.getJSONArray("summary").getJSONObject(i);check(row.optInt("n")==3,"Median does not contain all rounds");}}
        return new JSONObject().put("result","pass").put("scope",scope).put("report",r);
    }
    private static void check(boolean ok,String error){if(!ok)throw new AssertionError(error);}
}
