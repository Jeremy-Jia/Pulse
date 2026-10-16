package dev.pulse.monitor;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.pulse.monitor.MetricsSnapshot.Metric;

/** Foreground layer telemetry; never substitutes refresh rate for rendered FPS. */
final class FrameRateReader {
    private String foreground="", layer="";
    private long nextDiscovery;
    private static final Pattern APP=Pattern.compile("mFocusedApp=.*? u[0-9]+ ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)");

    void collect(RootReader root, Map<String,Metric> values) {
        long now=SystemClock.elapsedRealtime();
        if(now>=nextDiscovery || layer.isEmpty()) {
            nextDiscovery=now+3000;
            discover(root);
        }
        if(layer.isEmpty()) {
            MetricsCollector.text(values,"fps","—",false,"未找到前台应用的可采样图层");
            return;
        }
        String data=root.read("dumpsys SurfaceFlinger --latency "+quote(layer),2000);
        if(data==null)return;
        List<Long> present=new ArrayList<>(),ready=new ArrayList<>();
        int rows=0;
        for(String line:data.split("\\n")) {
            String[] p=line.trim().split("\\s+");
            if(p.length!=3)continue;
            try {
                long a=Long.parseLong(p[1]),b=Long.parseLong(p[2]);
                if(a>0&&a<Long.MAX_VALUE)present.add(a);
                if(b>0&&b<Long.MAX_VALUE)ready.add(b);
                rows++;
            } catch(NumberFormatException ignored) { }
        }
        long monotonic=System.nanoTime();
        Set<Long> distinct=new HashSet<>(present);
        if(distinct.size()>=2) {
            double rate=recentRate(present,monotonic);
            if(Double.isFinite(rate)) {
                MetricsCollector.put(values,"fps",rate,0);
                source(values,"fps","SurfaceFlinger actual-present · "+layer);
            }
        } else {
            MetricsCollector.text(values,"fps","—",false,
                    rows>1?"系统呈现时间戳重复，无法验证真实 FPS；可选图层更新率":"前台图层暂未提供有效呈现时间");
        }
        double update=recentRate(ready,monotonic);
        if(Double.isFinite(update)) {
            MetricsCollector.put(values,"layer_fps",update,0);
            source(values,"layer_fps","SurfaceFlinger frameReady · "+layer+"；不是实际呈现 FPS");
            Metric presentMetric=values.get("fps");
            if(presentMetric!=null && !presentMetric.available) {
                values.put("fps",new Metric("fps","绘制帧率",presentMetric.category,
                        MetricsCollector.format(update,0),"fps",
                        "SurfaceFlinger frameReady · "+layer+"；呈现时间不可用，当前显示图层绘制完成率，并非实际呈现 FPS",
                        true,update));
            }
        }
    }

    private void discover(RootReader root) {
        String data=root.read("echo '@@focus'; dumpsys window displays | grep 'mFocusedApp='; " +
                "echo '@@layers'; dumpsys SurfaceFlinger --list",2500);
        if(data==null)return;
        Map<String,String> sections=MetricsCollector.parseSections(data);
        String focus=sections.get("focus");
        if(focus==null)return;
        Matcher m=APP.matcher(focus);
        String pkg="";
        String component="";
        while(m.find()) {
            pkg=m.group(1);
            component=m.group(2).startsWith(".")?pkg+m.group(2):m.group(2);
        }
        if(pkg.isEmpty()){layer="";return;}
        foreground=pkg;
        String list=sections.get("layers");
        if(list==null)return;
        int best=-1;
        String candidate="";
        for(String raw:list.split("\\n")) {
            String name=raw.trim();
            if(name.startsWith("RequestedLayerState{")) {
                name=name.substring("RequestedLayerState{".length());
                int end=name.indexOf(" parentId=");
                if(end<0)end=name.indexOf(" z=");
                if(end<0)end=name.lastIndexOf('}');
                if(end>=0)name=name.substring(0,end);
            }
            if(!name.contains(pkg)||name.contains("InputSink")||name.contains("ActivityRecord")||name.contains("Mirror"))continue;
            int score=0;
            if(!component.isEmpty()&&name.contains(component))score+=100;
            if(name.contains("VRI-"))score+=20;
            if(name.contains("SurfaceView"))score+=30;
            if(name.contains("BLAST")||name.contains("BBQ"))score+=15;
            if(name.contains("animation-leash"))score-=100;
            if(score>best) { best=score;candidate=name; }
        }
        layer=candidate;
    }

    static double recentRate(List<Long> times,long now) {
        if(times.isEmpty())return Double.NaN;
        Set<Long> unique=new HashSet<>(times);
        long newest=0;
        long oldestRecent=Long.MAX_VALUE;
        int count=0;
        for(long t:unique) {
            newest=Math.max(newest,t);
            if(t>=now-1_000_000_000L && t<=now+100_000_000L) {
                count++;
                oldestRecent=Math.min(oldestRecent,t);
            }
        }
        if(newest>now+100_000_000L)return Double.NaN;
        // FrameTracker's ring can fill in under one second on high-Hz panels.
        // Only normalize a full ring; a sparse/static window must remain near 0.
        if(count>=120 && oldestRecent>now-900_000_000L && newest>oldestRecent)
            return (count-1)*1e9/(newest-oldestRecent);
        return count;
    }

    static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    private static void source(Map<String,Metric> values,String id,String source) {
        Metric old=values.get(id);
        if(old!=null)values.put(id,new Metric(old.id,old.label,old.category,old.value,old.unit,source,old.available,old.numericValue));
    }
    void close(RootReader root) { }
}
