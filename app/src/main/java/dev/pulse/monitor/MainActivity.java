package dev.pulse.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Original UI for Pulse. Pure Android views, no ads, analytics, or invented telemetry. */
public final class MainActivity extends Activity {
    private static final int BG=0xFF0B1019, CARD=0xFF141D2B, BORDER=0xFF253247;
    private static final int WHITE=0xFFF1F5FC, MUTED=0xFF91A0B7, DIM=0xFF64758E;
    private static final int MINT=0xFF78E8C6, LILAC=0xFFB7A4FF, AMBER=0xFFFFCC83;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MonitorPrefs prefs;
    private LinearLayout root, content, metricGroups;
    private ScrollView scroll;
    private TextView statusText, actionText, heroValue, heroUnit, heroCaption, rootStatus, selectedCount, permissionStatus,coreLegend;
    private TextView permissionButton, overlayRunButton;
    private Switch overlaySwitch;
    private boolean syncingControls;
    private PulseViews.Icon actionIcon;
    private PulseViews.Trend trend;
    private PulseViews.Cores cores;
    private PulseViews.Preview preview,widgetPreview;
    private final Map<String,List<TextView>> valueViews=new LinkedHashMap<>();
    private int page=0;
    private String metricQuery="", schema="";
    private boolean pendingStart;
    private MetricsSnapshot latest;
    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            latest=MonitorService.latestSnapshot;
            updateLiveValues();
            handler.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=new MonitorPrefs(this);
        page=state==null?0:state.getInt("page",0);
        latest=MonitorService.latestSnapshot;
        trend=new PulseViews.Trend(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        buildScreen(false);
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(ticker);handler.post(ticker);updatePermission();}
    @Override protected void onPause(){handler.removeCallbacks(ticker);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("page",page);}

    private void buildScreen(boolean preserveScroll){
        overlaySwitch=null;
        int previousY=preserveScroll&&scroll!=null?scroll.getScrollY():0;
        valueViews.clear();heroValue=null;heroUnit=null;heroCaption=null;rootStatus=null;permissionStatus=null;permissionButton=null;overlayRunButton=null;selectedCount=null;preview=null;widgetPreview=null;cores=null;statusText=null;actionText=null;actionIcon=null;coreLegend=null;
        root=column();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets edge=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(edge.left,edge.top,edge.right,edge.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.addView(buildHeader());
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content=column();content.setPadding(dp(22),dp(10),dp(22),dp(26));scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(page==0)buildDashboard();else if(page==1)buildOverlaySettings();else buildMetricSettings();
        root.addView(buildNavigation());
        root.requestApplyInsets();
        updateLiveValues();
        if(previousY>0)scroll.post(()->scroll.scrollTo(0,previousY));
    }

    private View buildHeader(){
        LinearLayout header=row();header.setPadding(dp(22),dp(17),dp(22),dp(16));
        FrameLayout mark=new FrameLayout(this);mark.setBackground(shape(0xFF16352E,14,MINT,0));
        PulseViews.Icon icon=new PulseViews.Icon(this,"pulse",MINT);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(27),dp(27),Gravity.CENTER);mark.addView(icon,ip);header.addView(mark,new LinearLayout.LayoutParams(dp(43),dp(43)));
        LinearLayout title=column();LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=dp(12);header.addView(title,tp);
        TextView brand=text("脉冲",21,WHITE,true);title.addView(brand);
        TextView sub=text("P U L S E  /  DEVICE STUDIO",8.5f,MUTED,false);sub.setLetterSpacing(.07f);top(title,sub,3);
        TextView thermal=text("温控",12,AMBER,true);thermal.setPadding(dp(12),dp(10),dp(8),dp(10));thermal.setOnClickListener(v->startActivity(new Intent(this,dev.pulse.thermal.MainActivity.class)));thermal.setContentDescription("打开 CPU 温控策略");header.addView(thermal);
        TextView about=text("说明",12,MUTED,false);about.setPadding(dp(12),dp(10),0,dp(10));about.setOnClickListener(v->showAbout());about.setContentDescription("查看数据说明与权限帮助");header.addView(about);
        return header;
    }

    private void buildDashboard(){
        LinearLayout intro=row();LinearLayout left=column();left.addView(text("设备概览",28,WHITE,true));top(left,text("每一次变化，都清晰可见。",12,MUTED,false),6);intro.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout action=row();action.setGravity(Gravity.CENTER);action.setPadding(dp(15),dp(12),dp(16),dp(12));action.setBackground(shape(MINT,22,0,0));
        actionIcon=new PulseViews.Icon(this,"play",BG);action.addView(actionIcon,new LinearLayout.LayoutParams(dp(17),dp(17)));actionText=text("开始",12,BG,true);LinearLayout.LayoutParams atp=new LinearLayout.LayoutParams(-2,-2);atp.leftMargin=dp(5);action.addView(actionText,atp);action.setOnClickListener(v->toggleMonitor());action.setContentDescription("开始或暂停设备监测");intro.addView(action);content.addView(intro);
        statusText=text("●  监测未启动",11,MUTED,false);top(content,statusText,16);

        LinearLayout hero=column();hero.setPadding(dp(21),dp(20),dp(21),dp(18));GradientDrawable hg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF18302F,0xFF18232F,0xFF1A2133});hg.setCornerRadius(dp(24));hg.setStroke(dp(1),0xFF304640);hero.setBackground(hg);top(content,hero,13);
        LinearLayout label=row();TextView processLabel=text("处理器负载",12,0xFFB2CBC7,false);label.addView(processLabel,new LinearLayout.LayoutParams(0,-2,1));TextView live=text("CPU  /  LIVE",9,MINT,true);live.setLetterSpacing(.1f);label.addView(live);hero.addView(label);
        LinearLayout metric=row();metric.setGravity(Gravity.BOTTOM);heroValue=mono("—",56,WHITE);metric.addView(heroValue);heroUnit=text("%",19,MINT,false);LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-2,-2);up.leftMargin=dp(8);up.bottomMargin=dp(10);metric.addView(heroUnit,up);top(hero,metric,8);
        heroCaption=text("启动监测，查看设备实时表现",11,0xFFACBCBE,false);top(hero,heroCaption,3);
        top(hero,divider(0xFF31423F),17);
        LinearLayout heroStats=row();heroStats.addView(miniMetric("CPU 平均温度","cpu_temp_avg",MINT),new LinearLayout.LayoutParams(0,-2,1));heroStats.addView(miniMetric("屏幕刷新率","refresh_rate",LILAC),new LinearLayout.LayoutParams(0,-2,1));top(hero,heroStats,15);

        LinearLayout two=row();LinearLayout power=smallCard("bolt","电池功率","battery_power",AMBER);LinearLayout memory=smallCard("memory","剩余内存","mem_available",LILAC);LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-1,1);a.rightMargin=dp(6);two.addView(power,a);LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,-1,1);b.leftMargin=dp(6);two.addView(memory,b);top(content,two,13);

        LinearLayout lab=card();lab.setBackground(shape(0xFF211F35,21,0xFF39334F,1));LinearLayout labHead=row();LinearLayout labWords=column();labWords.addView(text("性能实验室",16,WHITE,true));top(labWords,text("COREMARK  ·  DHRYSTONE  ·  WHETSTONE",8.5f,LILAC,false),8);labHead.addView(labWords,new LinearLayout.LayoutParams(0,-2,1));labHead.addView(new PulseViews.Icon(this,"arrow",LILAC),new LinearLayout.LayoutParams(dp(24),dp(24)));lab.addView(labHead);top(lab,text("可重复的基准测试，观察性能与功耗。",10.5f,MUTED,false),12);lab.setOnClickListener(v->{try{startActivity(new Intent().setClassName(getPackageName(),getPackageName()+".BenchActivity"));}catch(Exception e){Toast.makeText(this,"实验室暂时未能打开",Toast.LENGTH_SHORT).show();}});lab.setContentDescription("打开性能实验室，CoreMark、Dhrystone、Whetstone 与 PMU 基准测试");top(content,lab,13);

        LinearLayout thermalCard=card();thermalCard.setBackground(shape(0xFF2B2522,21,0xFF493A30,1));
        thermalCard.addView(sectionHeader("CPU 温控策略","ROOT / XIAOMI 15"));top(thermalCard,text("全频释放、温度停止线、定时恢复与持续运行。",11,AMBER,false),12);
        thermalCard.setOnClickListener(v->startActivity(new Intent(this,dev.pulse.thermal.MainActivity.class)));thermalCard.setContentDescription("打开小米 15 CPU 温控策略");top(content,thermalCard,13);
        LinearLayout coreCard=card();coreCard.addView(sectionHeader("核心频率","GHz · 顶部当前 / 底部最大"));cores=new PulseViews.Cores(this);top(coreCard,cores,13,144);coreLegend=text("核心簇待识别",9.5f,MUTED,false);top(coreCard,coreLegend,11);top(content,coreCard,13);

        LinearLayout trendCard=card();trendCard.addView(sectionHeader("负载轨迹","最近 60 秒"));if(trend.getParent()!=null)((ViewGroup)trend.getParent()).removeView(trend);top(trendCard,trend,13,136);top(content,trendCard,13);

        LinearLayout gpu=card();gpu.addView(sectionHeader("图形与画面","GPU / DISPLAY"));LinearLayout gpuRow=row();gpuRow.addView(miniMetric("GPU 使用率","gpu_usage",LILAC),new LinearLayout.LayoutParams(0,-2,1));gpuRow.addView(miniMetric("GPU 温度","gpu_temp",MINT),new LinearLayout.LayoutParams(0,-2,1));gpuRow.addView(miniMetric("画面帧率","fps",AMBER),new LinearLayout.LayoutParams(0,-2,1));top(gpu,gpuRow,18);top(gpu,divider(BORDER),16);top(gpu,metricLine("图形频率","gpu_freq",11),13);top(content,gpu,13);

        LinearLayout battery=card();battery.addView(sectionHeader("电池状态","ENERGY"));top(battery,metricLine("实时电流","battery_current",13),17);top(battery,metricLine("电池电压","battery_voltage",13),14);top(battery,metricLine("电池温度","battery_temp",13),14);top(battery,metricLine("剩余电量","battery_level",13),14);top(content,battery,13);

        LinearLayout state=column();state.setPadding(dp(3),0,dp(3),0);rootStatus=text("数据来源 · 等待开始监测",11,MUTED,false);state.addView(rootStatus);TextView note=text("未开放的传感器显示「—」。CPU 温度以可识别传感器为准，屏幕刷新率与实际画面帧率分别显示。",10.5f,DIM,false);note.setLineSpacing(dp(3),1);top(state,note,7);top(content,state,20);
    }

    private LinearLayout smallCard(String icon,String label,String id,int accent){
        LinearLayout view=card();LinearLayout title=row();PulseViews.Icon i=new PulseViews.Icon(this,icon,accent);title.addView(i,new LinearLayout.LayoutParams(dp(17),dp(17)));TextView t=text(label,11,MUTED,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=dp(6);title.addView(t,lp);view.addView(title);
        TextView value=mono("—",24,WHITE);value.setMaxLines(1);value.setEllipsize(TextUtils.TruncateAt.END);bind(id,value);top(view,value,16);
        TextView caption=text("battery_power".equals(id)?"电池端实时净功率":"系统可用内存",9.5f,DIM,false);top(view,caption,7);return view;
    }
    private LinearLayout miniMetric(String title,String id,int color){
        LinearLayout view=column();view.addView(text(title,10,MUTED,false));TextView value=mono("—",17,color);value.setSingleLine(true);value.setEllipsize(TextUtils.TruncateAt.END);bind(id,value);top(view,value,6);return view;
    }
    private LinearLayout metricLine(String title,String id,float size){
        LinearLayout view=row();view.addView(text(title,size,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));TextView value=mono("—",size+1,WHITE);bind(id,value);view.addView(value);return view;
    }

    private void buildOverlaySettings(){
        content.addView(text("悬浮窗工作室",28,WHITE,true));top(content,text("为你的屏幕，调出恰好的信息密度。",12,MUTED,false),7);
        overlayRunButton=button("启动并显示悬浮窗",MINT,BG);overlayRunButton.setOnClickListener(v->{
            if(MonitorService.isRunning&&prefs.getBoolean("overlay_enabled",true)){prefs.putBoolean("overlay_enabled",false);notifySettings();}
            else {prefs.putBoolean("overlay_enabled",true);if(!MonitorService.isRunning)toggleMonitor();else notifySettings();if(!Settings.canDrawOverlays(this))openOverlayPermission();}
            updateLiveValues();
        });top(content,overlayRunButton,16);
        preview=new PulseViews.Preview(this,prefs);top(content,preview,22,225);
        LinearLayout previewCaption=row();previewCaption.addView(text("实时样式预览",10,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));previewCaption.addView(text("最多预览 5 项",9,DIM,false));top(content,previewCaption,8);

        LinearLayout permission=card();permissionStatus=text("悬浮显示权限",14,WHITE,true);permission.addView(permissionStatus);TextView permissionHelp=text("允许后可覆盖在游戏和其他应用上。未授权时仍可在应用内与通知中监测。",11,MUTED,false);permissionHelp.setLineSpacing(dp(3),1);top(permission,permissionHelp,7);permissionButton=button("授予悬浮权限",MINT,BG);permissionButton.setOnClickListener(v->openOverlayPermission());top(permission,permissionButton,13);top(content,permission,16);

        LinearLayout display=card();display.addView(sectionHeader("显示与交互","DISPLAY"));top(display,toggle("启用悬浮窗","开启后立即启动监测并显示悬浮窗","overlay_enabled",true),13);top(display,divider(BORDER),12);
        top(display,slider("字号","overlay_size",10,22,12," sp"),18);
        top(display,toggle("粗体显示","同时应用于悬浮窗与桌面小部件","overlay_bold",false),17);
        top(display,toggle("无边框","隐藏悬浮窗描边，背景仍按不透明度显示","overlay_borderless",false),17);
        top(display,slider("背景不透明度","overlay_alpha",20,100,88,"%"),17);top(display,slider("窗口宽度","overlay_width",100,320,210," dp"),17);
        top(display,text("布局密度",12,WHITE,false),17);top(display,booleanSegments("compact",new String[]{"紧凑","舒展"},true),10);
        top(display,divider(BORDER),17);top(display,toggle("固定悬浮位置","关闭时拖动指标移动；开启后可滚动长列表","position_locked",false),14);top(display,toggle("触摸穿透","点击交给下方应用；在此关闭可恢复拖动","touch_through",false),17);top(content,display,13);

        LinearLayout overlayColors=card();overlayColors.addView(sectionHeader("悬浮窗配色","COLOR"));
        TextView overlayColorsHelp=text("名称、数值与背景颜色独立于桌面小部件；未自选数值颜色时继续跟随主题。背景不透明度仍在「显示与交互」中调整。",11,MUTED,false);
        overlayColorsHelp.setLineSpacing(dp(3),1);top(overlayColors,overlayColorsHelp,10);
        OverlayStyle overlayStyle=new OverlayStyle(prefs);
        top(overlayColors,colorControl("名称颜色","overlay_label_color",overlayStyle.labelColor,"悬浮窗"),16);
        top(overlayColors,colorControl("数值颜色","overlay_value_color",overlayStyle.valueColor,"悬浮窗"),13);
        top(overlayColors,colorControl("背景颜色","overlay_background_color",overlayStyle.backgroundColor,"悬浮窗"),13);
        TextView resetOverlay=button("恢复悬浮窗默认配色",0xFF192538,MUTED);
        resetOverlay.setOnClickListener(v->{prefs.remove(OverlayStyle.KEYS);notifySettings();buildScreen(true);});top(overlayColors,resetOverlay,14);
        top(content,overlayColors,13);

        LinearLayout widget=card();widget.addView(sectionHeader("桌面小部件","WIDGET"));
        TextView widgetHelp=text("同步指标、字号、粗体与密度，配色和边框独立设置。以下外观应用于所有 Pulse 小部件。可调整大小、滑动查看更多，点按指标刷新；开启监测后约每 2 秒更新。无需悬浮窗权限。",11,MUTED,false);
        widgetHelp.setLineSpacing(dp(3),1);top(widget,widgetHelp,10);
        widgetPreview=new PulseViews.Preview(this,prefs,true);top(widget,widgetPreview,15,165);
        top(widget,text("小部件样式预览 · 最多 5 项",9,DIM,false),7);
        WidgetStyle widgetStyle=new WidgetStyle(prefs);
        top(widget,colorControl("名称颜色","widget_label_color",widgetStyle.labelColor),16);
        top(widget,colorControl("数值颜色","widget_value_color",widgetStyle.valueColor),13);
        top(widget,colorControl("背景颜色","widget_background_color",widgetStyle.backgroundColor),13);
        top(widget,slider("小部件背景不透明度","widget_alpha",0,100,88,"%"),17);
        top(widget,toggle("小部件无边框","隐藏桌面小部件描边；0% 背景配合此项可仅显示文字","widget_borderless",false),17);
        TextView resetWidget=button("恢复小部件配色与边框",0xFF192538,MUTED);
        resetWidget.setOnClickListener(v->{prefs.remove(WidgetStyle.KEYS);notifySettings();buildScreen(true);});top(widget,resetWidget,14);
        TextView addWidget=button("添加桌面小部件",MINT,BG);addWidget.setOnClickListener(v->pinWidget());top(widget,addWidget,13);top(content,widget,13);

        LinearLayout theme=card();theme.addView(sectionHeader("色彩主题","COLOR"));LinearLayout chips=row();String[] keys={"mint","ice","amber","mono"},names={"薄荷","冰川","琥珀","月白"};int[] colors={MINT,0xFF8BCBFF,AMBER,0xFFE8EEF9};
        for(int i=0;i<keys.length;i++){final String key=keys[i];boolean chosen=key.equals(prefs.getString("theme","mint"));LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(dp(6),dp(12),dp(6),dp(11));item.setBackground(shape(chosen?0xFF24302F:0xFF101824,13,chosen?colors[i]:BORDER,1));TextView dot=text("●",21,colors[i],false);item.addView(dot);top(item,text(names[i],11,chosen?WHITE:MUTED,chosen),5);item.setOnClickListener(v->{prefs.putString("theme",key);notifySettings();buildScreen(true);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(7);chips.addView(item,lp);}top(theme,chips,16);top(content,theme,13);

        LinearLayout collection=card();collection.addView(sectionHeader("采集与常驻","MONITOR"));top(collection,text("刷新间隔",12,WHITE,false),18);top(collection,refreshSegments(),10);top(collection,text("间隔越短，CPU 唤醒与电量开销越高。",10,DIM,false),9);top(collection,divider(BORDER),17);top(collection,toggle("通知显示实时数值","前台监测期间会保留基础常驻通知","notification_enabled",true),14);top(collection,toggle("开机自动监测","解锁手机后恢复；需允许系统自启动","boot_enabled",false),18);top(content,collection,13);
        LinearLayout rootHelp=card();rootHelp.addView(text("非 Root 与 Root 访问模式",14,WHITE,true));TextView rootDescription=text("普通模式可读取公开接口并运行内置测试。非 Root 手机可用电脑执行随附的 tools/activate-adb.py 激活 ADB 模式，开放系统允许的 PMU 与传感器；重启后需重新激活。Root 手机仍可在 KernelSU 中授权。传感器保留系统真实名称。",11,MUTED,false);rootDescription.setLineSpacing(dp(4),1);top(rootHelp,rootDescription,8);top(content,rootHelp,13);
        updatePermission();
    }

    private void buildMetricSettings(){
        content.addView(text("你的指标面板",28,WHITE,true));top(content,text("选择关心的细节，留住真正重要的变化。",12,MUTED,false),7);
        selectedCount=text("",11,MINT,false);top(content,selectedCount,16);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextColor(WHITE);search.setHintTextColor(DIM);search.setTextSize(13);search.setPadding(dp(15),dp(12),dp(15),dp(12));search.setHint("搜索指标、传感器或数据来源");search.setBackground(shape(CARD,14,BORDER,1));search.setText(metricQuery);search.setSelectAllOnFocus(false);top(content,search,16);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){metricQuery=s.toString();populateMetricGroups();}public void afterTextChanged(Editable e){}});
        LinearLayout presets=row();String[] titles={"日常精简","性能全览","全传感器","全部关闭"};for(int i=0;i<titles.length;i++){final int preset=i;TextView button=button(titles[i],0xFF192538,MUTED);button.setTextSize(10);button.setPadding(dp(5),dp(11),dp(5),dp(11));button.setOnClickListener(v->applyPreset(preset));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(6);presets.addView(button,lp);}top(content,presets,12);
        TextView explanation=text("勾选即显示在悬浮窗与桌面小部件中。不可用项保留「—」，不将屏幕刷新率冒充 FPS，也不推测每个逻辑核心的温度。",10.5f,DIM,false);explanation.setLineSpacing(dp(3),1);top(content,explanation,14);
        metricGroups=column();top(content,metricGroups,7);populateMetricGroups();
    }

    private void populateMetricGroups(){
        if(metricGroups==null||page!=2)return;
        metricGroups.removeAllViews();valueViews.clear();
        Map<String,List<MetricsSnapshot.Metric>> grouped=new LinkedHashMap<>();String[] ordered={"处理器","图形处理器","内存","电池与功耗","屏幕与帧率","温度传感器","系统状态"};for(String name:ordered)grouped.put(name,new ArrayList<>());
        String query=metricQuery.trim().toLowerCase(Locale.ROOT);
        List<MetricsSnapshot.Metric> all=allMetrics();schema=signature(all);
        for(MetricsSnapshot.Metric m:all){String searchable=(m.label+" "+m.id+" "+m.source).toLowerCase(Locale.ROOT);if(!query.isEmpty()&&!searchable.contains(query))continue;String group=groupName(m.id);grouped.get(group).add(m);}
        int shown=0;
        for(Map.Entry<String,List<MetricsSnapshot.Metric>> entry:grouped.entrySet()){
            if(entry.getValue().isEmpty())continue;shown+=entry.getValue().size();LinearLayout block=card();block.addView(sectionHeader(entry.getKey(),String.valueOf(entry.getValue().size())+" 项"));
            int i=0;for(MetricsSnapshot.Metric metric:entry.getValue()){
                if(i++>0)top(block,divider(0xFF202C3D),11);
                LinearLayout metricRow=row();metricRow.setPadding(0,dp(10),0,0);
                CheckBox check=new CheckBox(this);check.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{MINT,DIM}));check.setChecked(prefs.isMetricEnabled(metric.id));check.setPadding(0,0,0,0);check.setMinWidth(0);check.setMinimumWidth(0);check.setContentDescription("显示"+metric.label);metricRow.addView(check,new LinearLayout.LayoutParams(dp(35),dp(42)));
                LinearLayout desc=column();TextView label=text(metric.label,12,WHITE,false);desc.addView(label);String source=metric.source==null?"":metric.source;TextView origin=text(source.isEmpty()?"等待采集来源":source,9,DIM,false);origin.setMaxLines(2);origin.setEllipsize(TextUtils.TruncateAt.END);top(desc,origin,4);LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(0,-2,1);dl.rightMargin=dp(8);metricRow.addView(desc,dl);
                TextView value=mono(format(metric),11,metric.available?MINT:DIM);value.setMaxWidth(dp(100));value.setMaxLines(2);value.setGravity(Gravity.END);bind(metric.id,value);metricRow.addView(value);
                check.setOnCheckedChangeListener((button,checked)->{prefs.setMetricEnabled(metric.id,checked);notifySettings();updateSelectedCount();});desc.setOnClickListener(v->check.setChecked(!check.isChecked()));metricRow.setOnClickListener(v->check.setChecked(!check.isChecked()));block.addView(metricRow);
            }
            top(metricGroups,block,13);
        }
        if(shown==0){TextView empty=text("没有匹配的指标\n试试 CPU、GPU、电池或传感器名称。",13,MUTED,false);empty.setGravity(Gravity.CENTER);empty.setLineSpacing(dp(8),1);empty.setPadding(0,dp(35),0,dp(35));metricGroups.addView(empty);}
        updateSelectedCount();
    }

    private String groupName(String id){
        if(id.startsWith("cpu_"))return "处理器";if(id.startsWith("gpu_"))return "图形处理器";if(id.startsWith("mem_"))return "内存";if(id.startsWith("battery_"))return "电池与功耗";if(id.equals("fps")||id.equals("refresh_rate"))return "屏幕与帧率";if(id.startsWith("thermal_"))return "温度传感器";return "系统状态";
    }
    private void applyPreset(int preset){
        List<String> minimal=Arrays.asList("cpu_usage","cpu_temp_avg","mem_available","battery_power","battery_temp","fps");
        LinkedHashSet<String> enabledIds=new LinkedHashSet<>();
        for(MetricsSnapshot.Metric m:allMetrics()){boolean enabled=preset==0?minimal.contains(m.id):preset==1&&!m.id.startsWith("thermal_")&&!m.id.startsWith("cpu_temp_")&&!m.id.equals("root_status");if(preset==1&&m.id.equals("cpu_temp_avg"))enabled=true;if(preset==2)enabled=!m.id.equals("root_status");if(enabled)enabledIds.add(m.id);}
        prefs.setEnabledMetrics(enabledIds);notifySettings();populateMetricGroups();Toast.makeText(this,preset==3?"已关闭全部悬浮指标":"已应用指标组合",Toast.LENGTH_SHORT).show();
    }
    private void updateSelectedCount(){
        if(selectedCount==null)return;int count=0,available=0;for(MetricsSnapshot.Metric m:allMetrics()){if(prefs.isMetricEnabled(m.id))count++;if(m.available)available++;}selectedCount.setText("已选择 "+count+" 项  ·  当前可用 "+available+" / "+allMetrics().size());
    }
    private List<MetricsSnapshot.Metric> allMetrics(){return latest!=null&&latest.metrics!=null&&!latest.metrics.isEmpty()?latest.metrics:MetricsCollector.catalog();}
    private String signature(List<MetricsSnapshot.Metric> metrics){StringBuilder sb=new StringBuilder();for(MetricsSnapshot.Metric m:metrics)sb.append(m.id).append('|');return sb.toString();}

    private LinearLayout toggle(String title,String description,String key,boolean defaultValue){
        LinearLayout row=row();LinearLayout labels=column();labels.addView(text(title,12,WHITE,false));TextView caption=text(description,10,DIM,false);caption.setLineSpacing(dp(2),1);top(labels,caption,5);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(14);row.addView(labels,lp);
        Switch toggle=new Switch(this);if(key.equals("overlay_enabled"))overlaySwitch=toggle;toggle.setShowText(false);toggle.setChecked(prefs.getBoolean(key,defaultValue)&&(!key.equals("overlay_enabled")||MonitorService.isRunning));toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{MINT,0xFF77869B}));toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xFF325B50,0xFF283346}));toggle.setContentDescription(title);toggle.setOnCheckedChangeListener((view,on)->{if(syncingControls)return;prefs.putBoolean(key,on);if(key.equals("overlay_enabled")&&on){if(!MonitorService.isRunning)toggleMonitor();else notifySettings();if(!Settings.canDrawOverlays(this))openOverlayPermission();}else notifySettings();if(key.equals("boot_enabled")&&on)Toast.makeText(this,"请在系统应用设置中允许脉冲自启动",Toast.LENGTH_LONG).show();});row.addView(toggle);labels.setOnClickListener(v->toggle.setChecked(!toggle.isChecked()));return row;
    }
    private LinearLayout slider(String title,String key,int min,int max,int defaultValue,String suffix){
        LinearLayout view=column();LinearLayout labels=row();labels.addView(text(title,12,WHITE,false),new LinearLayout.LayoutParams(0,-2,1));TextView amount=mono(prefs.getInt(key,defaultValue)+suffix,11,MINT);labels.addView(amount);view.addView(labels);
        SeekBar slider=new SeekBar(this);slider.setMin(min);slider.setMax(max);slider.setProgress(prefs.getInt(key,defaultValue));slider.setProgressTintList(ColorStateList.valueOf(MINT));slider.setThumbTintList(ColorStateList.valueOf(MINT));slider.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));slider.setPadding(dp(2),0,dp(2),0);slider.setContentDescription(title);top(view,slider,6,33);slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int n,boolean user){amount.setText(n+suffix);if(user){prefs.putInt(key,n);invalidatePreviews();}}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){notifySettings();}});return view;
    }
    private LinearLayout colorControl(String title,String key,int initial){return colorControl(title,key,initial,"");}
    private LinearLayout colorControl(String title,String key,int initial,String group){
        LinearLayout control=row();control.setPadding(0,dp(5),0,dp(5));
        LinearLayout labels=column();labels.addView(text(title,12,WHITE,false));
        TextView value=mono(WidgetStyle.hex(initial),10,MUTED);top(labels,value,4);
        control.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        View swatch=new View(this);swatch.setBackground(shape(initial,8,BORDER,1));
        LinearLayout.LayoutParams swatchParams=new LinearLayout.LayoutParams(dp(34),dp(34));swatchParams.rightMargin=dp(10);control.addView(swatch,swatchParams);
        control.addView(text("更改 ›",11,MINT,false));control.setContentDescription((group.isEmpty()?"":group)+title+"，点按选择颜色");control.setFocusable(true);
        control.setOnClickListener(v->{
            Integer saved=WidgetStyle.parseHex(prefs.getString(key,""));
            ColorPicker.show(this,title,saved==null?initial:saved,color->{
                prefs.putString(key,WidgetStyle.hex(color));value.setText(WidgetStyle.hex(color));swatch.setBackground(shape(color,8,BORDER,1));notifySettings();
            });
        });return control;
    }
    private LinearLayout booleanSegments(String key,String[] labels,boolean defaultValue){
        LinearLayout row=row();boolean current=prefs.getBoolean(key,defaultValue);
        for(int i=0;i<labels.length;i++){final boolean value=i==0;TextView segment=button(labels[i],current==value?0xFF24493E:0xFF101824,current==value?MINT:MUTED);segment.setTextSize(11);segment.setPadding(dp(8),dp(11),dp(8),dp(11));segment.setOnClickListener(v->{prefs.putBoolean(key,value);notifySettings();buildScreen(true);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(8);row.addView(segment,lp);}return row;
    }
    private LinearLayout refreshSegments(){
        LinearLayout row=row();int[] values={500,1000,2000,3000};String[] labels={"0.5 秒","1 秒","2 秒","3 秒"};int current=prefs.getInt("refresh_ms",1000);
        for(int i=0;i<values.length;i++){final int value=values[i];TextView segment=button(labels[i],current==value?0xFF24493E:0xFF101824,current==value?MINT:MUTED);segment.setTextSize(11);segment.setPadding(dp(4),dp(11),dp(4),dp(11));segment.setOnClickListener(v->{prefs.putInt("refresh_ms",value);notifySettings();buildScreen(true);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(6);row.addView(segment,lp);}return row;
    }

    private View buildNavigation(){
        LinearLayout shell=column();shell.setBackgroundColor(BG);shell.addView(divider(0xFF1C2737));
        LinearLayout nav=row();nav.setPadding(dp(28),dp(5),dp(28),dp(3));
        String[] icons={"pulse","window","sliders"},labels={"概览","悬浮窗","指标"};
        for(int i=0;i<3;i++){
            final int target=i;boolean selected=page==i;
            LinearLayout tab=column();tab.setGravity(Gravity.CENTER);tab.setMinimumHeight(dp(54));
            tab.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x2278E8C6),null,shape(Color.WHITE,14,0,0)));
            FrameLayout capsule=new FrameLayout(this);capsule.setBackground(shape(selected?0xFF1C3732:Color.TRANSPARENT,13,0,0));
            PulseViews.Icon icon=new PulseViews.Icon(this,icons[i],selected?MINT:DIM);
            capsule.addView(icon,new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));
            tab.addView(capsule,new LinearLayout.LayoutParams(dp(49),dp(28)));
            TextView label=text(labels[i],10.5f,selected?MINT:MUTED,selected);label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,dp(17));labelParams.topMargin=dp(3);tab.addView(label,labelParams);
            tab.setContentDescription(labels[i]+(selected?"，当前页":""));tab.setSelected(selected);tab.setFocusable(true);
            tab.setOnClickListener(v->{if(page!=target){page=target;metricGroups=null;buildScreen(false);}});
            nav.addView(tab,new LinearLayout.LayoutParams(0,dp(54),1));
        }
        shell.addView(nav);return shell;
    }

    private void updateLiveValues(){
        boolean running=MonitorService.isRunning;
        if(overlaySwitch!=null){syncingControls=true;overlaySwitch.setChecked(running&&prefs.getBoolean("overlay_enabled",true));syncingControls=false;}
        if(overlayRunButton!=null)overlayRunButton.setText(running&&prefs.getBoolean("overlay_enabled",true)?"隐藏悬浮窗 · 继续监测":running?"显示悬浮窗":"启动并显示悬浮窗");
        if(statusText!=null) {boolean old=latest!=null&&System.currentTimeMillis()-latest.timestampMillis>Math.max(7000,prefs.getInt("refresh_ms",1000)*4L);statusText.setText(running?(old?"●  正在等待新的采样":"●  实时监测中  ·  "+intervalText()+(Settings.canDrawOverlays(this)&&prefs.getBoolean("overlay_enabled",true)?"  ·  悬浮显示":"  ·  应用内与通知")):"○  监测已暂停" );statusText.setTextColor(running?MINT:MUTED);}
        if(actionText!=null)actionText.setText(running?"暂停":"开始");
        if(actionIcon!=null)actionIcon.setKind(running?"pause":"play");
        MetricsSnapshot.Metric cpu=find("cpu_usage");if(heroValue!=null){heroValue.setText(cpu!=null&&cpu.available?cpu.value:"—");heroUnit.setText(cpu!=null&&cpu.unit!=null&&!cpu.unit.isEmpty()?cpu.unit:"%");float usage=cpu==null?Float.NaN:PulseViews.number(cpu.value);heroCaption.setText(!running?"轻触开始，观察设备的每一次变化":cpu==null||!cpu.available?"正在读取系统指标 · 部分数据需要 ADB 模式或 Root":Float.isNaN(usage)?"正在呈现设备的实时运行状态":usage<25?"轻盈运行 · 当前处理器负载较低":usage<65?"平稳运行 · 处理器正在工作":"正在全力工作 · 关注负载与温度变化");}
        for(Map.Entry<String,List<TextView>> entry:valueViews.entrySet()){MetricsSnapshot.Metric m=find(entry.getKey());for(TextView view:entry.getValue())view.setText(format(m));}
        if(cores!=null)cores.setSnapshot(latest);if(coreLegend!=null)coreLegend.setText(clusterSummary());if(preview!=null)preview.setSnapshot(latest);if(widgetPreview!=null)widgetPreview.setSnapshot(latest);if(latest!=null&&cpu!=null&&cpu.available)trend.append(latest.timestampMillis,cpu.value);
        if(rootStatus!=null){MetricsSnapshot.Metric rootMetric=find("root_status");int count=0;for(MetricsSnapshot.Metric m:allMetrics())if(m.available)count++;rootStatus.setText(latest==null?"数据来源 · 等待开始监测":"数据来源 · "+count+" 项可用"+(rootMetric!=null?"  ·  "+rootMetric.value:""));}
        if(page==2&&metricGroups!=null&&!signature(allMetrics()).equals(schema))populateMetricGroups();updateSelectedCount();
    }
    private String intervalText(){int ms=prefs.getInt("refresh_ms",1000);return ms==500?"0.5 秒更新":(ms/1000)+" 秒更新";}
    private String clusterSummary(){
        if(latest==null)return "核心簇待识别";
        LinkedHashMap<String,List<Integer>> groups=new LinkedHashMap<>();for(int i=0;i<8;i++){MetricsSnapshot.Metric cluster=latest.find("cpu_cluster_"+i);if(cluster==null||!cluster.available)continue;List<Integer> ids=groups.get(cluster.value);if(ids==null){ids=new ArrayList<>();groups.put(cluster.value,ids);}ids.add(i);}
        if(groups.isEmpty())return "核心簇待识别";
        StringBuilder label=new StringBuilder();for(Map.Entry<String,List<Integer>> entry:groups.entrySet()){if(label.length()>0)label.append("  /  ");label.append(entry.getKey()).append(" C");List<Integer> ids=entry.getValue();boolean contiguous=ids.get(ids.size()-1)-ids.get(0)+1==ids.size();if(contiguous){label.append(ids.get(0));if(ids.size()>1)label.append("–").append(ids.get(ids.size()-1));}else for(int i=0;i<ids.size();i++){if(i>0)label.append(",");label.append(ids.get(i));}}
        return label.toString();
    }
    private MetricsSnapshot.Metric find(String id){if(latest!=null)return latest.find(id);return null;}
    private String format(MetricsSnapshot.Metric metric){return metric==null||!metric.available?"—":metric.value+(metric.unit==null||metric.unit.isEmpty()?"":" "+metric.unit);}
    private void bind(String id,TextView view){List<TextView> targets=valueViews.get(id);if(targets==null){targets=new ArrayList<>();valueViews.put(id,targets);}targets.add(view);}
    private void invalidatePreviews(){if(preview!=null)preview.invalidate();if(widgetPreview!=null)widgetPreview.invalidate();}
    private void notifySettings(){invalidatePreviews();PulseWidgetProvider.refreshViews(this);if(MonitorService.isRunning)try{startService(new Intent(this,MonitorService.class).setAction(MonitorService.ACTION_REFRESH));}catch(Exception e){Toast.makeText(this,"设置已保存，重新开始监测后应用",Toast.LENGTH_SHORT).show();}}
    private void pinWidget(){
        android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(this);
        if(manager.isRequestPinAppWidgetSupported()) {
            manager.requestPinAppWidget(new android.content.ComponentName(this,PulseWidgetProvider.class),null,null);
        } else Toast.makeText(this,"请长按桌面，在小部件列表中添加「Pulse 实时指标」",Toast.LENGTH_LONG).show();
    }
    private void toggleMonitor(){
        if(MonitorService.isRunning){startService(new Intent(this,MonitorService.class).setAction(MonitorService.ACTION_STOP));handler.postDelayed(this::updateLiveValues,200);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){pendingStart=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},70);}
        else startMonitor();
    }
    private void startMonitor(){
        pendingStart=false;
        try{startForegroundService(new Intent(this,MonitorService.class).setAction(MonitorService.ACTION_START));Toast.makeText(this,Settings.canDrawOverlays(this)?"开始采集 · 按当前访问模式读取":"已开始监测，可到「悬浮窗」授予显示权限",Toast.LENGTH_LONG).show();handler.postDelayed(this::updateLiveValues,250);}
        catch(Exception e){new AlertDialog.Builder(this).setTitle("未能启动监测").setMessage("请保持应用在前台后重试。\n"+e.getClass().getSimpleName()).setPositiveButton("知道了",null).show();}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==70&&pendingStart)startMonitor();}
    private void openOverlayPermission(){try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(Exception e){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}}
    private void updatePermission(){if(permissionStatus!=null){boolean allowed=Settings.canDrawOverlays(this);permissionStatus.setText(allowed?"悬浮显示已允许":"开启屏幕上的实时仪表");permissionStatus.setTextColor(allowed?MINT:WHITE);if(permissionButton!=null)permissionButton.setText(allowed?"管理悬浮权限":"授予悬浮权限");}}
    private void showAbout(){new AlertDialog.Builder(this).setTitle("脉冲 · Pulse").setMessage("属于你设备的实时仪表。\n\nCPU、GPU、内存、电池与温度均来自系统实际读数，无法读取时显示「—」。日常监测只读；1.4 新增的温控策略需 Root 并由你手动启用，仅支持已校验的小米 15 固件。不会写入设备分区。\n\n需要更多硬件指标：非 Root 手机可通过随附的 ADB 激活脚本启用辅助进程；Root 手机可在 KernelSU 中授权。\n\nCPU 传感器不一定对应逻辑核心；电池功率是电池端净功率，不等于处理器功率。FPS 与屏幕刷新率分开显示。\n\n保持常驻：允许悬浮显示、自启动，并在系统电池设置中为本应用选择无限制。监测期间必须保留基础前台通知。").setPositiveButton("了解",null).show();}

    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(18),dp(18),dp(18),dp(18));v.setBackground(shape(CARD,21,BORDER,1));return v;}
    private LinearLayout sectionHeader(String title,String aside){LinearLayout row=row();row.addView(text(title,14,WHITE,true),new LinearLayout.LayoutParams(0,-2,1));TextView note=text(aside,9,DIM,false);row.addView(note);return row;}
    private TextView text(String s,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return v;}
    private TextView mono(String s,float size,int color){TextView v=text(s,size,color,false);v.setTypeface(Typeface.create("monospace",Typeface.NORMAL));return v;}
    private TextView button(String title,int fill,int color){TextView v=text(title,12,color,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(14),dp(13),dp(14),dp(13));v.setBackground(shape(fill,13,0,0));v.setClickable(true);v.setFocusable(true);return v;}
    private View divider(int color){View v=new View(this);v.setBackgroundColor(color);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return v;}
    private GradientDrawable shape(int color,int radius,int border,int stroke){GradientDrawable v=new GradientDrawable();v.setColor(color);v.setCornerRadius(dp(radius));if(stroke>0)v.setStroke(dp(stroke),border);return v;}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void top(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);if(child.getLayoutParams()!=null&&child.getLayoutParams().height==dp(1))lp.height=dp(1);parent.addView(child,lp);}
    private void top(LinearLayout parent,View child,int margin,int height){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(height));lp.topMargin=dp(margin);parent.addView(child,lp);}
}
