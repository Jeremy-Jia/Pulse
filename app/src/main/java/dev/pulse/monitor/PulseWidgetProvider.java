package dev.pulse.monitor;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Launcher surface sharing the monitor's real samples and access modes. */
public final class PulseWidgetProvider extends AppWidgetProvider {
    static final String REFRESH="dev.pulse.monitor.WIDGET_REFRESH";
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean collecting=new AtomicBoolean();
    private static volatile MetricsSnapshot cached;
    private static long lastPublish,lastSave;

    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids) {
        refreshViews(context);requestSample(context);
    }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options) {
        manager.updateAppWidget(id,views(context,id));
    }
    @Override public void onReceive(Context context,Intent intent) {
        super.onReceive(context,intent);
        if(REFRESH.equals(intent.getAction()))requestSample(context);
    }
    private void requestSample(Context context) {
        if(MonitorService.isRunning&&MonitorService.latestSnapshot!=null&&
                System.currentTimeMillis()-MonitorService.latestSnapshot.timestampMillis<5000) {
            publish(context,MonitorService.latestSnapshot,true);return;
        }
        if(!collecting.compareAndSet(false,true))return;
        PendingResult pending=goAsync();Context app=context.getApplicationContext();
        worker.execute(()->{
            MetricsCollector collector=null;
            try {
                collector=new MetricsCollector(app);
                // Two samples give delta-based load readings; no permanent background service.
                collector.collect();SystemClock.sleep(250);
                MetricsSnapshot next=collector.collect();
                MetricsSnapshot live=MonitorService.latestSnapshot;
                if(MonitorService.isRunning&&live!=null&&live.timestampMillis>next.timestampMillis)next=live;
                publish(app,next,true);
            } catch(RuntimeException e) {
                android.util.Log.w("PulseWidget","Widget sample unavailable",e);
            } finally {
                if(collector!=null)collector.close();
                collecting.set(false);pending.finish();
            }
        });
    }
    static int[] ids(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context,PulseWidgetProvider.class));
    }
    static synchronized void publish(Context context,MetricsSnapshot snapshot,boolean force) {
        if(ids(context).length==0)return;
        long now=SystemClock.elapsedRealtime();
        if(!force&&now-lastPublish<2000)return;
        cached=snapshot;lastPublish=now;
        // Persist a recovery sample without writing storage at every telemetry tick.
        if(force||now-lastSave>=30000) { save(context,snapshot);lastSave=now; }
        refreshViews(context);
    }
    static void refreshViews(Context context) {
        AppWidgetManager manager=AppWidgetManager.getInstance(context);int[] ids=ids(context);
        for(int id:ids)manager.updateAppWidget(id,views(context,id));
        if(Build.VERSION.SDK_INT<31&&ids.length>0)manager.notifyAppWidgetViewDataChanged(ids,R.id.widget_list);
    }
    static MetricsSnapshot sample(Context context) {
        MetricsSnapshot live=MonitorService.latestSnapshot;
        if(live!=null&&(cached==null||live.timestampMillis>=cached.timestampMillis))return live;
        if(cached!=null)return cached;
        try {
            JSONObject json=new JSONObject(context.getSharedPreferences("pulse_widget",Context.MODE_PRIVATE).getString("sample","{}"));
            JSONArray rows=json.getJSONArray("metrics");List<MetricsSnapshot.Metric> metrics=new ArrayList<>();
            for(int i=0;i<rows.length();i++) {
                JSONObject m=rows.getJSONObject(i);
                metrics.add(new MetricsSnapshot.Metric(m.getString("id"),m.getString("label"),"",m.getString("value"),m.getString("unit"),"",m.getBoolean("available")));
            }
            cached=new MetricsSnapshot(json.getLong("time"),metrics);return cached;
        } catch(Exception ignored) { return new MetricsSnapshot(0,MetricsCollector.catalog()); }
    }
    private static void save(Context context,MetricsSnapshot snapshot) {
        try {
            JSONArray rows=new JSONArray();
            for(MetricsSnapshot.Metric m:snapshot.metrics)rows.put(new JSONObject().put("id",m.id).put("label",m.label)
                .put("value",m.value).put("unit",m.unit).put("available",m.available));
            JSONObject json=new JSONObject().put("time",snapshot.timestampMillis).put("metrics",rows);
            context.getSharedPreferences("pulse_widget",Context.MODE_PRIVATE).edit().putString("sample",json.toString()).apply();
        } catch(Exception e) { android.util.Log.w("PulseWidget","Cannot cache widget sample",e); }
    }
    static List<MetricsSnapshot.Metric> selected(Context context,MetricsSnapshot snapshot) {
        Set<String> enabled=new MonitorPrefs(context).enabledMetricIds();List<MetricsSnapshot.Metric> selected=new ArrayList<>();
        for(MetricsSnapshot.Metric m:snapshot.metrics)if(enabled.contains(m.id))selected.add(m);
        return selected;
    }
    static RemoteViews views(Context context,int id) {
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.pulse_widget);
        views.setEmptyView(R.id.widget_list,R.id.widget_empty);
        Intent refresh=new Intent(context,PulseWidgetProvider.class).setAction(REFRESH)
            .setData(Uri.parse("pulse://widget/"+id)).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);
        // Collections need fill-in intents. The mutable template is restricted to our explicit receiver.
        PendingIntent click=PendingIntent.getBroadcast(context,id,refresh,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.widget_list,click);
        views.setOnClickPendingIntent(R.id.widget_empty,PendingIntent.getActivity(context,0,new Intent(context,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        MetricsSnapshot snapshot=sample(context);
        views.setContentDescription(R.id.widget_root,snapshot.timestampMillis==0?"Pulse，等待采样":
            "Pulse，采样时间 "+android.text.format.DateFormat.format("HH:mm:ss",snapshot.timestampMillis)+"，点按指标刷新");
        if(Build.VERSION.SDK_INT>=31) {
            List<MetricsSnapshot.Metric> rows=selected(context,snapshot);
            RemoteViews.RemoteCollectionItems.Builder items=new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1);
            for(MetricsSnapshot.Metric m:rows)items.addItem(m.id.hashCode(),row(context,m));
            views.setRemoteAdapter(R.id.widget_list,items.build());
        } else {
            Intent adapter=new Intent(context,PulseWidgetRowsService.class).setData(Uri.parse("pulse://widget-rows/"+id));
            views.setRemoteAdapter(R.id.widget_list,adapter);
        }
        return views;
    }
    static RemoteViews row(Context context,MetricsSnapshot.Metric metric) {
        MonitorPrefs prefs=new MonitorPrefs(context);boolean bold=prefs.getBoolean("overlay_bold",false);
        int font=Math.max(10,Math.min(22,prefs.getInt("overlay_size",12)));
        String theme=prefs.getString("theme","mint");
        int accent="ice".equals(theme)?0xff89c9ff:"amber".equals(theme)?0xffffce85:"mono".equals(theme)?0xffebeff7:0xff78e8c6;
        RemoteViews row=new RemoteViews(context.getPackageName(),R.layout.pulse_widget_row);
        row.setTextViewText(R.id.widget_label,styled(metric.label,bold));
        row.setTextViewText(R.id.widget_value,styled(metric.available?metric.value+(metric.unit.isEmpty()?"":" "+metric.unit):"—",bold));
        row.setTextViewTextSize(R.id.widget_label,TypedValue.COMPLEX_UNIT_SP,font-1);
        row.setTextViewTextSize(R.id.widget_value,TypedValue.COMPLEX_UNIT_SP,font);
        row.setTextColor(R.id.widget_value,metric.available?accent:0xff66778b);
        int padding=Math.round((prefs.getBoolean("compact",true)?1:4)*context.getResources().getDisplayMetrics().density);
        row.setViewPadding(R.id.widget_metric_row,0,padding,0,padding);
        row.setContentDescription(R.id.widget_metric_row,metric.label+" "+(metric.available?metric.value+metric.unit:"暂不可用"));
        row.setOnClickFillInIntent(R.id.widget_metric_row,new Intent().putExtra("metric",metric.id));
        return row;
    }
    private static CharSequence styled(String text,boolean bold) {
        if(!bold)return text;
        SpannableString styled=new SpannableString(text);
        styled.setSpan(new StyleSpan(Typeface.BOLD),0,styled.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return styled;
    }
}
