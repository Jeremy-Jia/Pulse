package dev.pulse.monitor;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Explicit read-only device integration check, without an external test runner. */
public final class MonitorInstrumentation extends Instrumentation {
    private Bundle options;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        options=arguments==null?new Bundle():arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        MetricsCollector collector = null;
        int finishCode = Activity.RESULT_CANCELED;
        try {
            check(Looper.myLooper() != Looper.getMainLooper(), "Collector must not block the UI thread");
            // HyperOS freezes otherwise headless instrumentation; keep the target visibly foreground.
            Activity visible=startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            onMain(()->visible.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            if("adb".equals(options.getString("backend"))) {
                long end=SystemClock.elapsedRealtime()+25000;
                while(!AdbBridge.available()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
                check(AdbBridge.available(),"Activate the ADB helper while this instrumentation is waiting");
                check("2000".equals(AdbBridge.read("id -u",2000).trim()),"Bridge must run as shell, not root");
            } else if("app".equals(options.getString("backend"))) {
                check(!AdbBridge.available(),"Stop the bridge before testing standalone mode");
            }
            checkParsingBoundaries();
            collector = new MetricsCollector(getTargetContext());
            MetricsSnapshot first = collector.collect();
            validate(first);
            SystemClock.sleep(1100);
            MetricsSnapshot second = collector.collect();
            validate(second);
            check(second.timestampMillis >= first.timestampMillis, "Snapshot timestamps moved backwards");

            JSONObject report = new JSONObject();
            report.put("result", "pass");
            report.put("parserChecks", "pass");
            report.put("first", json(first));
            report.put("second", json(second));
            String kind=options.getString("benchmark");
            if(kind!=null) {
                android.content.Intent run=new android.content.Intent(getTargetContext(),BenchmarkService.class)
                    .setAction(BenchmarkService.RUN).putExtra("kind",kind).putExtra("cpu",7).putExtra("seconds",10);
                if("custom".equals(kind)) {
                    java.io.File dir=new java.io.File(getTargetContext().getFilesDir(),"cases");dir.mkdirs();
                    java.io.File file=new java.io.File(dir,"test.elf");
                    try(java.io.InputStream in=new java.io.FileInputStream(getTargetContext().getApplicationInfo().nativeLibraryDir+"/libcoremark.so");
                        java.io.OutputStream out=new java.io.FileOutputStream(file)) {
                        byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
                    }
                    run.putExtra("path",file.getPath()).putExtra("args","0\n0\n0x66\n20000");
                }
                getTargetContext().startForegroundService(run);
                long start=SystemClock.elapsedRealtime();
                while(!BenchmarkService.running&&BenchmarkService.lastResult==null&&SystemClock.elapsedRealtime()-start<5000)SystemClock.sleep(50);
                boolean cancel="true".equals(options.getString("cancel"));
                if(cancel) {SystemClock.sleep(1500);getTargetContext().startService(new android.content.Intent(getTargetContext(),BenchmarkService.class).setAction(BenchmarkService.CANCEL));}
                while(BenchmarkService.running&&SystemClock.elapsedRealtime()-start<180000)SystemClock.sleep(100);
                check(!BenchmarkService.running,"Benchmark service failed to finish within limit");
                JSONObject bench=BenchmarkService.lastResult;
                check(bench!=null,"No benchmark result");
                if(cancel)check(bench.optBoolean("cancelled"),"Cancellation did not propagate");
                else if("probe".equals(kind))check("probe".equals(bench.optString("result")),"Probe failed: "+bench);
                else check("completed".equals(bench.optString("result"))&&("custom".equals(kind)||bench.getJSONObject("score").getBoolean("valid")),"Benchmark failed: "+bench);
                if(!cancel&&"adb".equals(options.getString("backend"))) {
                    check("adb".equals(bench.optString("execution_backend")),"Wrong benchmark backend");
                    check(bench.getJSONObject("pmu").getJSONObject("cycles").getBoolean("available"),"ADB cycles unavailable");
                    check(bench.getJSONObject("pmu").getJSONObject("instructions").getBoolean("available"),"ADB instructions unavailable");
                }
                report.put("benchmark",bench);
                if(!"true".equals(options.getString("widget")))startActivitySync(new android.content.Intent(getTargetContext(),BenchActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            if("true".equals(options.getString("overlay")))report.put("overlay",checkOverlay(second));
            if("true".equals(options.getString("widget")))report.put("widget",checkWidget(second,visible));
            result.putString("report_json", report.toString());
            result.putString("stream", "\nPASS: parsing boundaries and two real telemetry samples.\n"
                    + "Root: " + second.rootAvailable + "; available metrics: " + available(second)
                    + "/" + second.metrics.size() + "; CPU cores: " + second.cpuCount + ".\n"
                    + "No preferences or device permissions were changed by the test.\n");
            finishCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + error + "\n");
            result.putString("failure", error.toString());
        } finally {
            if (collector != null) collector.close();
        }
        finish(finishCode, result);
    }

    private JSONObject checkOverlay(MetricsSnapshot snapshot)throws Exception {
        check(android.provider.Settings.canDrawOverlays(getTargetContext()),"Overlay permission is required for the requested UI test");
        MonitorPrefs prefs=new MonitorPrefs(getTargetContext());
        int oldX=prefs.getInt("overlay_x",0),oldY=prefs.getInt("overlay_y",0);
        int oldWidth=prefs.getInt("overlay_width",210),oldSize=prefs.getInt("overlay_size",12);
        boolean locked=prefs.getBoolean("position_locked",false),pass=prefs.getBoolean("touch_through",false);
        boolean bold=prefs.getBoolean("overlay_bold",false),compact=prefs.getBoolean("compact",true);
        Set<String> enabled=prefs.enabledMetricIds();
        int testWidth="100".equals(options.getString("overlay_width"))?100:120;
        prefs.putBoolean("position_locked",false);prefs.putBoolean("touch_through",false);
        prefs.putBoolean("overlay_bold",true);prefs.putBoolean("compact",false);prefs.putInt("overlay_size",testWidth==100?10:12);prefs.putInt("overlay_width",testWidth);
        prefs.setEnabledMetrics(new java.util.LinkedHashSet<>(java.util.Arrays.asList("cpu_freq_0","cpu_usage_0","mem_available")));
        final OverlayPanel[] overlay=new OverlayPanel[1];final android.view.View[] drag=new android.view.View[1],panel=new android.view.View[1];
        JSONObject result=new JSONObject();
        try {
            onMain(()->{overlay[0]=new OverlayPanel(getTargetContext(),prefs);overlay[0].update(snapshot);overlay[0].show();});
            java.lang.reflect.Field f=OverlayPanel.class.getDeclaredField("panel");f.setAccessible(true);panel[0]=(android.view.View)f.get(overlay[0]);
            onMain(()->{
                android.view.ViewGroup root=(android.view.ViewGroup)panel[0];
                check(root.getChildCount()==1&&root.getChildAt(0) instanceof android.widget.ScrollView,"Overlay still contains header, graph or footer");
                drag[0]=root.getChildAt(0);
                android.view.ViewGroup rows=(android.view.ViewGroup)((android.widget.ScrollView)drag[0]).getChildAt(0);
                check(rows.getChildCount()==3,"Overlay contains unselected readings or category headers");
                for(int i=0;i<rows.getChildCount();i++) {
                    android.view.ViewGroup row=(android.view.ViewGroup)rows.getChildAt(i);
                    check(row.getChildCount()==2,"Overlay row structure invalid");
                    for(int j=0;j<2;j++)check(((android.widget.TextView)row.getChildAt(j)).getTypeface().isBold(),"Bold setting ignored");
                }
                check("核0频率".equals(snapshot.find("cpu_freq_0").label),"CPU frequency label too long");
                check("核0负载".equals(snapshot.find("cpu_usage_0").label),"CPU load label too long");
                check(root.getPaddingLeft()<=Math.ceil(3*root.getResources().getDisplayMetrics().density),"Overlay frame padding too wide");
                drag(drag[0],-10000,-10000);
            });
            SystemClock.sleep(400);int[] location=new int[2];onMain(()->panel[0].getLocationOnScreen(location));
            result.put("top_left_x",location[0]);result.put("top_left_y",location[1]);
            check(location[0]==0&&location[1]==0,"Overlay cannot reach physical top-left: "+location[0]+","+location[1]);
            onMain(()->{
                android.view.ViewGroup rows=(android.view.ViewGroup)((android.widget.ScrollView)drag[0]).getChildAt(0);
                android.widget.TextView label=(android.widget.TextView)((android.view.ViewGroup)rows.getChildAt(0)).getChildAt(0);
                check(label.getLayout()!=null&&label.getLayout().getEllipsisCount(0)==0,"Short frequency label still clipped at "+testWidth+" dp");
                saveView(panel[0],"overlay-"+testWidth+"dp.png");
            });
            onMain(()->drag(drag[0],10000,10000));SystemClock.sleep(400);
            final int[] size=new int[2];onMain(()->{panel[0].getLocationOnScreen(location);size[0]=panel[0].getWidth();size[1]=panel[0].getHeight();});
            android.graphics.Rect bounds=((android.view.WindowManager)getTargetContext().getSystemService(android.content.Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();
            result.put("bottom_right_x",location[0]+size[0]);result.put("bottom_right_y",location[1]+size[1]);
            check(location[0]+size[0]==bounds.width()&&location[1]+size[1]==bounds.height(),"Overlay cannot reach bottom-right");
            onMain(()->{
                prefs.putBoolean("position_locked",true);drag(drag[0],-10000,-10000);
            });SystemClock.sleep(200);
            final int[] lockedLocation=new int[2];onMain(()->panel[0].getLocationOnScreen(lockedLocation));
            check(lockedLocation[0]==location[0]&&lockedLocation[1]==location[1],"Locked overlay moved");
            onMain(()->{prefs.setEnabledMetrics(java.util.Collections.emptySet());overlay[0].update(snapshot);});
            check(panel[0].getVisibility()==android.view.View.GONE,"Empty selection still shows overlay chrome");
            result.put("selected_only",true);result.put("bold",true);result.put("width_dp",testWidth);result.put("empty_hidden",true);result.put("locked",true);
            result.put("result","pass");
        } finally {
            onMain(()->{if(overlay[0]!=null)overlay[0].remove();});
            prefs.putInt("overlay_x",oldX);prefs.putInt("overlay_y",oldY);prefs.putBoolean("position_locked",locked);prefs.putBoolean("touch_through",pass);
            prefs.putBoolean("overlay_bold",bold);prefs.putBoolean("compact",compact);prefs.putInt("overlay_width",oldWidth);prefs.putInt("overlay_size",oldSize);prefs.setEnabledMetrics(enabled);
        }
        return result;
    }
    private JSONObject checkWidget(MetricsSnapshot snapshot,Activity activity)throws Exception {
        android.content.Context context=getTargetContext();MonitorPrefs prefs=new MonitorPrefs(context);
        Set<String> enabled=prefs.enabledMetricIds();boolean bold=prefs.getBoolean("overlay_bold",false);
        android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(context);
        android.content.ComponentName component=new android.content.ComponentName(context,PulseWidgetProvider.class);
        android.appwidget.AppWidgetProviderInfo info=null;
        for(android.appwidget.AppWidgetProviderInfo candidate:manager.getInstalledProviders())if(component.equals(candidate.provider))info=candidate;
        check(info!=null,"Launcher widget is not registered");
        final android.appwidget.AppWidgetProviderInfo provider=info;
        android.appwidget.AppWidgetHost host=new android.appwidget.AppWidgetHost(context,1202026);
        host.deleteHost(); // Clean an allocation left by an interrupted test, never a launcher host.
        int id=host.allocateAppWidgetId();final android.appwidget.AppWidgetHostView[] hostView=new android.appwidget.AppWidgetHostView[1];
        final android.view.View[] previous=new android.view.View[1];JSONObject report=new JSONObject();
        try {
            // A temporary test host, never the user's launcher. Delete the allocation in finally.
            getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
            try {check(manager.bindAppWidgetIdIfAllowed(id,component),"Cannot bind temporary widget test host");}
            finally {getUiAutomation().dropShellPermissionIdentity();}
            prefs.setEnabledMetrics(new java.util.LinkedHashSet<>(java.util.Arrays.asList("cpu_freq_0","mem_available","battery_temp")));
            prefs.putBoolean("overlay_bold",true);
            onMain(()->{
                android.view.ViewGroup content=activity.findViewById(android.R.id.content);previous[0]=content.getChildAt(0);
                host.startListening();hostView[0]=host.createView(context,id,provider);
                hostView[0].updateAppWidgetSize(null,150,95,150,95);
                android.widget.FrameLayout frame=new android.widget.FrameLayout(context);frame.setBackgroundColor(0xff0b1019);
                frame.addView(hostView[0],new android.widget.FrameLayout.LayoutParams(dp(150),dp(95),android.view.Gravity.CENTER));activity.setContentView(frame);
            });
            PulseWidgetProvider.publish(context,snapshot,true);
            final int[] count={-1};long deadline=SystemClock.elapsedRealtime()+8000;
            while(SystemClock.elapsedRealtime()<deadline) {
                onMain(()->{
                    hostView[0].measure(android.view.View.MeasureSpec.makeMeasureSpec(dp(150),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(dp(95),android.view.View.MeasureSpec.EXACTLY));
                    hostView[0].layout(0,0,dp(150),dp(95));
                    android.widget.ListView list=hostView[0].findViewById(R.id.widget_list);
                    if(list!=null&&list.getAdapter()!=null&&list.getChildCount()>0)count[0]=list.getAdapter().getCount();
                });
                if(count[0]==3)break;SystemClock.sleep(100);
            }
            check(count[0]==3,"Launcher host did not render selected widget rows: "+count[0]);
            onMain(()->{
                android.widget.ListView list=hostView[0].findViewById(R.id.widget_list);
                check(list.getChildCount()>0,"Widget has no visible rows");
                android.widget.TextView label=list.getChildAt(0).findViewById(R.id.widget_label);
                check("核0频率".contentEquals(label.getText()),"Widget short label was lost");
                check(label.getText() instanceof android.text.Spanned,"Widget bold span lost across RemoteViews");
                android.text.style.StyleSpan[] spans=((android.text.Spanned)label.getText()).getSpans(0,label.length(),android.text.style.StyleSpan.class);
                check(spans.length==1&&spans[0].getStyle()==android.graphics.Typeface.BOLD,"Widget is not bold");
                saveView(hostView[0],"widget-bold.png");
                // All selections remain in the collection at a short height and can scroll.
                prefs.setEnabledMetrics(new java.util.LinkedHashSet<>(java.util.Arrays.asList("cpu_freq_0","mem_available","battery_temp","cpu_temp_avg","gpu_freq","gpu_usage","battery_power","battery_voltage","battery_current","fps")));
                prefs.putBoolean("overlay_bold",false);
            });
            PulseWidgetProvider.refreshViews(context);deadline=SystemClock.elapsedRealtime()+5000;
            final boolean[] regular={false};
            while(SystemClock.elapsedRealtime()<deadline) {
                onMain(()->{
                    hostView[0].measure(android.view.View.MeasureSpec.makeMeasureSpec(dp(150),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(dp(95),android.view.View.MeasureSpec.EXACTLY));
                    hostView[0].layout(0,0,dp(150),dp(95));
                    android.widget.ListView list=hostView[0].findViewById(R.id.widget_list);count[0]=list.getAdapter().getCount();
                    if(list.getChildCount()>0) {
                        android.widget.TextView label=list.getChildAt(0).findViewById(R.id.widget_label);
                        regular[0]=!(label.getText() instanceof android.text.Spanned)||((android.text.Spanned)label.getText()).getSpans(0,label.length(),android.text.style.StyleSpan.class).length==0;
                    }
                });
                if(count[0]==10&&regular[0])break;SystemClock.sleep(100);
            }
            check(count[0]==10,"Widget selection changes did not propagate");
            onMain(()->{
                android.widget.ListView list=hostView[0].findViewById(R.id.widget_list);
                android.widget.TextView label=list.getChildAt(0).findViewById(R.id.widget_label);
                if(label.getText() instanceof android.text.Spanned)check(((android.text.Spanned)label.getText()).getSpans(0,label.length(),android.text.style.StyleSpan.class).length==0,"Widget bold setting did not switch off");
                check(list.canScrollVertically(1),"Short widget cannot scroll to remaining selections");
                saveView(hostView[0],"widget-regular.png");
            });
            android.widget.RemoteViewsService.RemoteViewsFactory fallback=new PulseWidgetRowsService.Factory(context);
            fallback.onCreate();check(fallback.getCount()==10,"Legacy widget factory lost selections");
            check(fallback.getViewAt(-1)==null&&fallback.getViewAt(10)==null,"Legacy factory did not check bounds");
            onMain(()->{
                android.view.View row=fallback.getViewAt(0).apply(context,null);
                check("CPU 温度".contentEquals(((android.widget.TextView)row.findViewById(R.id.widget_label)).getText()),"Legacy widget row failed to render");
            });fallback.onDestroy();
            long before=PulseWidgetProvider.sample(context).timestampMillis;SystemClock.sleep(500);
            onMain(()->{
                android.widget.ListView list=hostView[0].findViewById(R.id.widget_list);
                list.performItemClick(list.getChildAt(0),0,list.getAdapter().getItemId(0));
            });
            deadline=SystemClock.elapsedRealtime()+15000;
            while(PulseWidgetProvider.sample(context).timestampMillis<=before&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            check(PulseWidgetProvider.sample(context).timestampMillis>before,"Clicking widget did not collect a fresh sample");
            report.put("result","pass");report.put("registered",true);report.put("host_rendered",true);report.put("bold_toggle",true);report.put("selection_updates",true);report.put("scrollable",true);report.put("tap_refresh",true);report.put("legacy_factory",true);
        } finally {
            onMain(()->{host.stopListening();if(previous[0]!=null)activity.setContentView(previous[0]);});
            host.deleteAppWidgetId(id);prefs.setEnabledMetrics(enabled);prefs.putBoolean("overlay_bold",bold);PulseWidgetProvider.refreshViews(context);
        }
        return report;
    }
    private int dp(int value){return Math.round(value*getTargetContext().getResources().getDisplayMetrics().density);}
    private void saveView(android.view.View view,String name) {
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        java.io.File dir=new java.io.File(getTargetContext().getFilesDir(),"verification");dir.mkdirs();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        } catch(java.io.IOException e){throw new RuntimeException(e);}finally {bitmap.recycle();}
    }
    private static void drag(android.view.View v,float x,float y) {
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP}) {
            android.view.MotionEvent e=android.view.MotionEvent.obtain(now,now,action,action==0?0:x,action==0?0:y,0);v.dispatchTouchEvent(e);e.recycle();
        }
    }

    private static void validate(MetricsSnapshot snapshot) {
        check(snapshot.cpuCount > 0 && snapshot.cpuCount <= 256, "Invalid CPU core count");
        check(snapshot.timestampMillis > 0, "Invalid sample timestamp");
        Set<String> ids = new HashSet<>();
        for (MetricsSnapshot.Metric metric : snapshot.metrics) {
            check(ids.add(metric.id), "Duplicate metric ID: " + metric.id);
            if (!metric.available) continue;
            // Text states deliberately have no unit or numeric value.
            if (metric.unit.isEmpty()) continue;
            double shown;
            try { shown = Double.parseDouble(metric.value); }
            catch (NumberFormatException e) { throw new AssertionError("Non-numeric available metric: " + metric.id); }
            check(Double.isFinite(shown), "Non-finite display value: " + metric.id);
            check(Double.isFinite(metric.numericValue), "Non-finite numeric value: " + metric.id);
            if (metric.unit.equals("%")) inRange(metric.numericValue, 0, 100, metric.id);
            if (metric.unit.equals("MHz") || metric.unit.equals("GB") || metric.unit.equals("W")
                    || metric.unit.equals("ms") || metric.unit.equals("fps") || metric.unit.equals("Hz"))
                check(metric.numericValue >= 0, "Negative magnitude: " + metric.id);
            if (metric.unit.equals("°C")) inRange(metric.numericValue, -40, 180, metric.id);
        }

        double total = required(snapshot, "mem_total");
        double free = required(snapshot, "mem_available");
        double used = required(snapshot, "mem_used");
        check(total > 0 && free >= 0 && free <= total, "Invalid memory totals");
        check(Math.abs(total - free - used) < .001, "Memory total does not equal available plus used");
        inRange(required(snapshot, "battery_level"), 0, 100, "battery_level");
        MetricsSnapshot.Metric voltage = snapshot.find("battery_voltage");
        if (voltage != null && voltage.available) inRange(voltage.numericValue, 0, 20, "battery_voltage");
        check(snapshot.find("cpu_usage") != null, "CPU metric is missing from catalog");
        check(snapshot.find("gpu_usage") != null, "GPU metric is missing from catalog");
    }

    private static double required(MetricsSnapshot snapshot, String id) {
        MetricsSnapshot.Metric metric = snapshot.find(id);
        check(metric != null && metric.available && Double.isFinite(metric.numericValue),
                "Required base metric unavailable: " + id);
        return metric.numericValue;
    }

    private static void checkParsingBoundaries() {
        check(MetricsCollector.parseSections("").isEmpty(), "Empty section stream must stay empty");
        Map<String,String> sections = MetricsCollector.parseSections(
                "ignored preamble\n@@stat\ncpu 10 0 0 90\n@@empty\n@@voltage\n4435000\n");
        check("cpu 10 0 0 90".equals(sections.get("stat")), "Multiline section parsing failed");
        check("".equals(sections.get("empty")), "Empty section lost");
        check("4435000".equals(sections.get("voltage")), "Final section lost");
        check(MetricsCollector.number(" 123.5 ") == 123.5, "Trimmed numeric input failed");
        check(Double.isNaN(MetricsCollector.number(null)), "Missing input fabricated a numeric value");
        check(Double.isNaN(MetricsCollector.number("permission denied")), "Invalid input fabricated a numeric value");
        Map<String,MetricsSnapshot.Metric> values = new LinkedHashMap<>();
        MetricsSnapshot.Metric initial = new MetricsSnapshot.Metric("test", "Test", "Test", "—", "V", "test", false);
        values.put("test", initial);
        MetricsCollector.put(values, "test", Double.NaN, 2);
        MetricsCollector.put(values, "test", Double.POSITIVE_INFINITY, 2);
        MetricsCollector.put(values, "test", Double.NEGATIVE_INFINITY, 2);
        check(!values.get("test").available, "Non-finite input must remain unavailable");
        MetricsCollector.put(values, "test", 0, 2);
        check(values.get("test").available && values.get("test").numericValue == 0,
                "Real zero must remain distinguishable from unavailable");
        MetricsCollector.put(values, "unknown", 1, 0);
        check(!values.containsKey("unknown"), "Unknown metric ID was silently added");
    }

    private static JSONObject json(MetricsSnapshot snapshot) throws Exception {
        JSONObject out = new JSONObject();
        out.put("timestampMillis", snapshot.timestampMillis);
        out.put("rootAvailable", snapshot.rootAvailable);
        out.put("rootStatus", snapshot.message);
        out.put("cpuCount", snapshot.cpuCount);
        out.put("availableCount", available(snapshot));
        out.put("totalCount", snapshot.metrics.size());
        JSONArray metrics = new JSONArray();
        for (MetricsSnapshot.Metric metric : snapshot.metrics) {
            JSONObject item = new JSONObject();
            item.put("id", metric.id);
            item.put("available", metric.available);
            item.put("value", metric.value);
            item.put("unit", metric.unit);
            item.put("numeric", Double.isFinite(metric.numericValue) ? metric.numericValue : JSONObject.NULL);
            metrics.put(item);
        }
        out.put("metrics", metrics);
        return out;
    }

    private static int available(MetricsSnapshot snapshot) {
        int count = 0;
        for (MetricsSnapshot.Metric metric : snapshot.metrics) if (metric.available) count++;
        return count;
    }

    private static void inRange(double value, double min, double max, String name) {
        check(Double.isFinite(value) && value >= min && value <= max,
                "Out-of-range " + name + ": " + value);
    }

    private void onMain(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        super.runOnMainSync(()->{try {action.run();}catch(Throwable error){failure.set(error);}});
        Throwable error=failure.get();
        if(error instanceof Error)throw (Error)error;
        if(error!=null)throw new RuntimeException(error);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
