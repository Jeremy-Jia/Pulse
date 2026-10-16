package dev.pulse.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Native, local benchmark control surface. No benchmark result is estimated or fabricated. */
public final class BenchActivity extends Activity {
    private static final int BG=0xFF0B1019,CARD=0xFF141D2B,BORDER=0xFF253247,WHITE=0xFFF1F5FC,MUTED=0xFF91A0B7,DIM=0xFF64758E,MINT=0xFF78E8C6,LILAC=0xFFB7A4FF,AMBER=0xFFFFCC83;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MonitorPrefs prefs;
    private String kind="coremark",customPath="",customName="",resultKey="",exportJson="";
    private int cpu=0,seconds=10;
    private boolean viewingHistory,importing,wasRunning;
    private long observedStart;
    private LinearLayout content,methodChoices,cpuChoices,durationChoices,customBox,resultContent,historyContent,pmuContent;
    private ScrollView mainScroll;
    private View resultCardView;
    private TextView statusValue,elapsedValue,runButton,cancelButton,probeButton,caseLabel,resultBadge;
    private EditText arguments,rawEvents;
    private JSONObject shownResult;
    private final Runnable refresh=new Runnable(){@Override public void run(){updateStatus();handler.postDelayed(this,500);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=new MonitorPrefs(this);kind=prefs.getString("bench_kind","coremark");cpu=Math.max(0,Math.min(7,prefs.getInt("bench_cpu",0)));seconds=prefs.getInt("bench_seconds",10);customPath=prefs.getString("bench_custom_path","");customName=prefs.getString("bench_custom_name","");
        if(state!=null){kind=state.getString("kind",kind);cpu=state.getInt("cpu",cpu);seconds=state.getInt("seconds",seconds);exportJson=state.getString("export_json","");}
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(BG);if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        build();
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("kind",kind);state.putInt("cpu",cpu);state.putInt("seconds",seconds);state.putString("export_json",exportJson);}

    private void build(){
        LinearLayout root=col();root.setBackgroundColor(BG);root.setOnApplyWindowInsetsListener((view,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets edge=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(edge.left,edge.top,edge.right,edge.bottom);}else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});setContentView(root);
        LinearLayout header=row();header.setPadding(dp(20),dp(18),dp(22),dp(16));TextView back=text("‹",35,WHITE,false);back.setPadding(0,0,dp(20),0);back.setContentDescription("返回设备概览");back.setOnClickListener(v->finish());header.addView(back);LinearLayout brand=col();brand.addView(text("性能实验室",22,WHITE,true));add(brand,text("PULSE  /  BENCHMARK LAB",8.5f,LILAC,false),5);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
        mainScroll=new ScrollView(this);mainScroll.setVerticalScrollBarEnabled(false);mainScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);content=col();content.setPadding(dp(22),dp(8),dp(22),dp(28));mainScroll.addView(content);root.addView(mainScroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout hero=card();GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF2B2543,0xFF17232F});gradient.setCornerRadius(dp(23));gradient.setStroke(dp(1),0xFF413654);hero.setBackground(gradient);hero.addView(text("让性能，成为可比较的事实。",21,WHITE,true));TextView heroNote=text("原生测试 · 单核绑核 · 真实硬件计数器",11,0xFFB7B3CB,false);add(hero,heroNote,10);LinearLayout live=row();statusValue=text("准备就绪",12,MINT,false);live.addView(statusValue,new LinearLayout.LayoutParams(0,-2,1));elapsedValue=mono("00:00",18,WHITE);live.addView(elapsedValue);add(hero,live,24);add(content,hero,0);

        LinearLayout methods=card();methods.addView(title("01  选择测试","WORKLOAD"));methodChoices=col();add(methods,methodChoices,14);add(content,methods,13);renderMethods();
        customBox=card();customBox.addView(text("导入 ARM64 测试程序",14,WHITE,true));TextView customInfo=text("自定义程序以 Root 运行，仅导入你信任的测试。支持 ELF64 小端 AArch64，可执行文件最大 128 MB。",11,MUTED,false);customInfo.setLineSpacing(dp(3),1);add(customBox,customInfo,8);caseLabel=text(customName.isEmpty()?"尚未导入 ELF":customName,11,MINT,false);caseLabel.setMaxLines(2);add(customBox,caseLabel,14);TextView importButton=button("选择 ELF 文件",0xFF273C36,MINT);importButton.setOnClickListener(v->importCase());add(customBox,importButton,11);add(customBox,text("运行参数 · 每行一个参数",11,WHITE,false),17);arguments=new EditText(this);arguments.setTextColor(WHITE);arguments.setHintTextColor(DIM);arguments.setTextSize(12);arguments.setTypeface(Typeface.MONOSPACE);arguments.setMinLines(3);arguments.setGravity(Gravity.TOP);arguments.setPadding(dp(12),dp(12),dp(12),dp(12));arguments.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);arguments.setBackground(shape(0xFF0D1521,11,BORDER,1));arguments.setHint("--threads\n1");arguments.setText(prefs.getString("bench_args",""));add(customBox,arguments,8);add(customBox,text("按原样传递参数，空行忽略；不解析 Shell 命令。",9.5f,DIM,false),7);add(content,customBox,13);customBox.setVisibility("custom".equals(kind)?View.VISIBLE:View.GONE);

        LinearLayout core=card();core.addView(title("02  指定 CPU 核心","AFFINITY"));cpuChoices=col();add(core,cpuChoices,14);add(core,text("标签与最大频率来自设备。指定核心用于绑核，不锁定频率，也不修改温控。",10,DIM,false),10);add(content,core,13);renderCpus();
        LinearLayout timing=card();timing.addView(title("03  测试时长","DURATION"));durationChoices=row();add(timing,durationChoices,14);renderDurations();add(timing,text("校准、收尾与测试自身的迭代会增加总耗时。",10,DIM,false),10);add(content,timing,13);

        LinearLayout controls=row();runButton=button("开始基准测试",MINT,BG);cancelButton=button("取消",0xFF263145,MUTED);runButton.setOnClickListener(v->run(kind));cancelButton.setOnClickListener(v->{if(BenchmarkService.running)startService(new Intent(this,BenchmarkService.class).setAction(BenchmarkService.CANCEL));});LinearLayout.LayoutParams startLp=new LinearLayout.LayoutParams(0,dp(48),1);controls.addView(runButton,startLp);LinearLayout.LayoutParams cancelLp=new LinearLayout.LayoutParams(dp(76),dp(48));cancelLp.leftMargin=dp(9);controls.addView(cancelButton,cancelLp);add(content,controls,17);

        LinearLayout resultCard=card();resultCardView=resultCard;LinearLayout resultHead=row();resultHead.addView(text("本次结果",15,WHITE,true),new LinearLayout.LayoutParams(0,-2,1));resultBadge=text("等待测量",10,DIM,false);resultHead.addView(resultBadge);resultCard.addView(resultHead);resultContent=col();add(resultCard,resultContent,17);LinearLayout export=row();TextView raw=button("查看原始 JSON",0xFF1D2B3D,MUTED),save=button("导出 JSON",0xFF283D36,MINT);raw.setTextSize(11);save.setTextSize(11);raw.setOnClickListener(v->showRaw());save.setOnClickListener(v->exportResult());LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,-2,1);ep.rightMargin=dp(4);export.addView(raw,ep);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-2,1);sp.leftMargin=dp(4);export.addView(save,sp);add(resultCard,export,18);add(content,resultCard,17);renderResult(null);

        LinearLayout pmu=card();pmu.addView(title("性能计数器","PMU / PERF"));TextView pmuNote=text("探测周期、指令、缓存与分支事件。内核不开放的事件会显示原因；不会把缺失计数转换成 0。",11,MUTED,false);pmuNote.setLineSpacing(dp(3),1);add(pmu,pmuNote,8);
        TextView advanced=button("高级 · 原始 PMU 事件  ▾",0xFF192438,MUTED);advanced.setTextSize(11);add(pmu,advanced,13);
        LinearLayout rawBox=col();rawBox.setPadding(0,dp(14),0,dp(5));rawBox.addView(text("原始 PMU 事件 · 选填",12,WHITE,true));TextView rawHelp=text("每行一个十六进制事件码，最多 8 个。事件含义与 CPU 型号相关，请按该处理器的文档填写。仅显示代码与实际计数，不推测事件名称。",10.5f,MUTED,false);rawHelp.setLineSpacing(dp(3),1);add(rawBox,rawHelp,7);
        rawEvents=new EditText(this);rawEvents.setTextColor(WHITE);rawEvents.setHintTextColor(DIM);rawEvents.setTextSize(12);rawEvents.setTypeface(Typeface.MONOSPACE);rawEvents.setMinLines(3);rawEvents.setGravity(Gravity.TOP);rawEvents.setPadding(dp(12),dp(12),dp(12),dp(12));rawEvents.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);rawEvents.setBackground(shape(0xFF0D1521,11,BORDER,1));rawEvents.setHint("每行一个十六进制代码，支持 0x 前缀");rawEvents.setText(prefs.getString("bench_raw_events",""));add(rawBox,rawEvents,10);add(rawBox,text("留空只采集通用事件；填写后应用于探测与下一次测试。",9.5f,DIM,false),7);rawBox.setVisibility(rawEvents.getText().length()==0?View.GONE:View.VISIBLE);advanced.setText(rawBox.getVisibility()==View.VISIBLE?"高级 · 原始 PMU 事件  ▴":"高级 · 原始 PMU 事件  ▾");advanced.setOnClickListener(v->{boolean show=rawBox.getVisibility()!=View.VISIBLE;rawBox.setVisibility(show?View.VISIBLE:View.GONE);advanced.setText(show?"高级 · 原始 PMU 事件  ▴":"高级 · 原始 PMU 事件  ▾");});add(pmu,rawBox,0);
        probeButton=button("探测 PMU 支持情况",0xFF29263F,LILAC);probeButton.setOnClickListener(v->run("probe"));add(pmu,probeButton,13);pmuContent=col();add(pmu,pmuContent,12);add(content,pmu,13);

        LinearLayout history=card();history.addView(title("测试档案","LOCAL HISTORY"));historyContent=col();add(history,historyContent,15);add(content,history,13);renderHistory();
        LinearLayout notes=card();notes.addView(text("理解成绩，比追逐成绩重要。",14,WHITE,true));TextView info=text("使用 EEMBC CoreMark、Dhrystone 2.1 与 Netlib Whetstone 1.2 工作负载。CoreMark 与 Dhrystone 测量整数表现，Whetstone 测量浮点表现；不同测试的单位不可直接比较。\n\n频率动态变化、温度、后台任务与编译选项都会影响结果。每 MHz 仅展示原生测试输出的归一化结果，并标注口径。PMU 可能受内核权限或硬件事件支持限制。\n\nCoreMark 验证 CRC，Dhrystone 检查关键最终变量；Whetstone 没有同等级的 CRC 自检。有效性应结合测试说明理解。\n\n原始结果自动保存于应用私有目录。导出的 JSON 保留频率、计数器与验证详情。",11,MUTED,false);info.setLineSpacing(dp(4),1);add(notes,info,10);add(content,notes,13);
        root.requestApplyInsets();
    }

    private void renderMethods(){
        methodChoices.removeAllViews();String[] keys={"coremark","dhrystone","whetstone","custom"},names={"CoreMark","Dhrystone","Whetstone","自定义 ELF"},hints={"整数 · 综合指令路径","整数 · 传统基准","浮点 · 科学运算","你的 ARM64 测试"};
        for(int r=0;r<2;r++){LinearLayout line=row();for(int c=0;c<2;c++){int n=r*2+c;final String key=keys[n];boolean selected=kind.equals(key);LinearLayout item=col();item.setPadding(dp(13),dp(13),dp(13),dp(13));item.setBackground(shape(selected?0xFF29334A:0xFF101824,13,selected?LILAC:BORDER,1));item.addView(text(names[n],13,selected?WHITE:MUTED,true));add(item,text(hints[n],9,selected?LILAC:DIM,false),7);item.setOnClickListener(v->{if(BenchmarkService.running)return;kind=key;prefs.putString("bench_kind",key);renderMethods();customBox.setVisibility("custom".equals(kind)?View.VISIBLE:View.GONE);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(c>0)lp.leftMargin=dp(8);line.addView(item,lp);}add(methodChoices,line,r==0?0:8);}
    }
    private void renderCpus(){
        cpuChoices.removeAllViews();MetricsSnapshot snapshot=MonitorService.latestSnapshot;
        for(int r=0;r<2;r++){LinearLayout line=row();for(int c=0;c<4;c++){final int index=r*4+c;MetricsSnapshot.Metric cluster=snapshot==null?null:snapshot.find("cpu_cluster_"+index),max=snapshot==null?null:snapshot.find("cpu_max_freq_"+index);String label=cluster!=null&&cluster.available?cluster.value:"待识别";boolean prime=label.contains("超大");int accent=prime?LILAC:MINT;LinearLayout choice=col();choice.setGravity(Gravity.CENTER);choice.setPadding(dp(3),dp(12),dp(3),dp(12));choice.setBackground(shape(cpu==index?0xFF243A36:0xFF101824,12,cpu==index?accent:BORDER,1));choice.addView(mono("CPU "+index,11,cpu==index?WHITE:MUTED));TextView clusterText=text(label,8.5f,prime?LILAC:DIM,false);clusterText.setGravity(Gravity.CENTER);add(choice,clusterText,7);TextView frequencyText=mono(max!=null&&max.available?frequencyLabel(max):"— GHz",9,cpu==index?accent:DIM);frequencyText.setGravity(Gravity.CENTER);add(choice,frequencyText,6);choice.setOnClickListener(v->{if(BenchmarkService.running)return;cpu=index;prefs.putInt("bench_cpu",cpu);renderCpus();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(c>0)lp.leftMargin=dp(5);line.addView(choice,lp);}add(cpuChoices,line,r==0?0:6);}
    }
    private String frequencyLabel(MetricsSnapshot.Metric metric){double n=PulseViews.number(metric.value);if(Double.isNaN(n))return metric.value+metric.unit;if("MHz".equalsIgnoreCase(metric.unit))n/=1000;else if("kHz".equalsIgnoreCase(metric.unit))n/=1000000;return String.format(Locale.US,"%.2fG",n);}
    private void renderDurations(){durationChoices.removeAllViews();for(int duration:new int[]{10,30,60,120}){TextView choice=button(duration+" 秒",seconds==duration?0xFF24493E:0xFF101824,seconds==duration?MINT:MUTED);choice.setTextSize(11);choice.setPadding(dp(3),dp(11),dp(3),dp(11));choice.setOnClickListener(v->{if(BenchmarkService.running)return;seconds=duration;prefs.putInt("bench_seconds",seconds);renderDurations();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(duration!=10)lp.leftMargin=dp(6);durationChoices.addView(choice,lp);}}

    private void run(String requestedKind){
        if(BenchmarkService.running||importing)return;
        String raw;
        try{raw=validatedRawEvents();}catch(IllegalArgumentException error){showError("请检查原始 PMU 事件",error.getMessage());return;}
        String args=arguments.getText().toString();if("custom".equals(requestedKind)){if(customPath.isEmpty()||!new File(customPath).isFile()){Toast.makeText(this,"请先导入 ARM64 ELF 文件",Toast.LENGTH_SHORT).show();return;}if(args.length()>32768||args.split("\\r?\\n").length>64){Toast.makeText(this,"参数最多 64 行、总长度不超过 32 KB",Toast.LENGTH_LONG).show();return;}for(String arg:args.split("\\r?\\n"))if(arg.length()>2048){Toast.makeText(this,"单个参数不能超过 2048 字符",Toast.LENGTH_LONG).show();return;}prefs.putString("bench_args",args);}
        viewingHistory=false;resultKey="";shownResult=null;renderResult(null);resultBadge.setText("运行中");
        pmuContent.removeAllViews();pmuContent.addView(text("本次计数将在测试结束后显示。",11,DIM,false));
        Intent intent=new Intent(this,BenchmarkService.class).setAction(BenchmarkService.RUN).putExtra("kind",requestedKind).putExtra("cpu",cpu).putExtra("seconds",seconds).putExtra("path",customPath).putExtra("args",args).putExtra("raw_events",raw);
        try{startForegroundService(intent);handler.postDelayed(this::updateStatus,150);}catch(Exception e){showError("无法启动测试",e.getMessage());}
    }
    private String validatedRawEvents(){
        String input=rawEvents.getText().toString();StringBuilder normalized=new StringBuilder();int count=0,lineNumber=0;java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(String line:input.split("\\r?\\n")){lineNumber++;line=line.trim();if(line.isEmpty())continue;if(++count>8)throw new IllegalArgumentException("最多填写 8 个事件代码。");if(!line.matches("(?i)(?:0x)?[0-9a-f]{1,16}"))throw new IllegalArgumentException("第 "+lineNumber+" 行需要 1–16 位十六进制代码，可带 0x 前缀；不要添加事件名或注释。");String digits=new java.math.BigInteger(line.replaceFirst("(?i)^0x",""),16).toString(16);if(!seen.add(digits))throw new IllegalArgumentException("第 "+lineNumber+" 行的事件代码重复：0x"+digits+"。每种事件只能添加一次。");if(normalized.length()>0)normalized.append('\n');normalized.append("0x").append(digits);}
        prefs.putString("bench_raw_events",input);return normalized.toString();
    }
    private void updateStatus(){
        boolean running=BenchmarkService.running;statusValue.setText(BenchmarkService.status);statusValue.setTextColor(running?MINT:MUTED);
        if(running){long elapsed=Math.max(0,(SystemClock.elapsedRealtime()-BenchmarkService.startedAt)/1000);elapsedValue.setText(String.format(Locale.US,"%02d:%02d",elapsed/60,elapsed%60));if(observedStart!=BenchmarkService.startedAt){observedStart=BenchmarkService.startedAt;viewingHistory=false;resultKey="";}}
        runButton.setEnabled(!running&&!importing);runButton.setAlpha(running||importing?.45f:1f);runButton.setText(running?"测试正在运行":"开始基准测试");cancelButton.setEnabled(running);cancelButton.setAlpha(running?1f:.4f);probeButton.setEnabled(!running);probeButton.setAlpha(running?.4f:1f);
        JSONObject result=BenchmarkService.lastResult;
        if(!viewingHistory&&result!=null&&!"phase".equals(result.optString("event"))&&!"started".equals(result.optString("event"))){String key=result.toString();if(!key.equals(resultKey)){resultKey=key;shownResult=result;renderResult(result);if(!running){renderHistory();renderCpus();}}}
        if(wasRunning&&!running){renderHistory();renderCpus();}wasRunning=running;
        if(!running&&shownResult==null)resultBadge.setText("等待测量");
    }

    private void renderResult(JSONObject result){
        resultContent.removeAllViews();if(result==null){TextView empty=text("从一个核心、一段时间开始。\n有效成绩、频率与计数器会在测试结束后呈现。",12,MUTED,false);empty.setLineSpacing(dp(7),1);resultContent.addView(empty);return;}
        JSONObject score=object(result,"score");if(score==null)score=object(result,"benchmark");
        String value=first(score,"value","score","score_value"),unit=first(score,"unit","score_unit");if(value.isEmpty())value=first(result,"score","score_value","value");if(unit.isEmpty())unit=first(result,"unit","score_unit");
        String valid=first(score,"valid","validated","validation"),detail=first(score,"detail","reason","message");if(valid.isEmpty())valid=first(result,"valid","validated","validation");if(detail.isEmpty())detail=first(result,"detail","reason","message");
        String resultType=first(result,"result","status"),requested=first(result,"kind","requested_kind");boolean probe="probe".equals(requested)||"probe".equals(resultType)||result.has("probe");
        String badge=result.optBoolean("cancelled",false)||"cancelled".equals(resultType)?"已取消":"timeout".equals(resultType)?"达到时限":"error".equals(resultType)||"failed".equals(resultType)?"执行失败":"false".equalsIgnoreCase(valid)?"未通过验证":"true".equalsIgnoreCase(valid)?"结果有效":probe?"探测结果":"原始测量";resultBadge.setText(badge);resultBadge.setTextColor("结果有效".equals(badge)?MINT:AMBER);
        if(!value.isEmpty()&&!"error".equals(resultType)){TextView scoreValue=mono(numeric(value,3),32,WHITE);scoreValue.setMaxLines(2);resultContent.addView(scoreValue);if(!unit.isEmpty())add(resultContent,text(unit,12,LILAC,false),7);}else resultContent.addView(text(probe?"硬件事件支持情况":"本次未生成有效成绩",18,WHITE,true));
        if(!valid.isEmpty())add(resultContent,dataRow("有效性",translatedValid(valid)),16);
        JSONObject freq=object(result,"frequency");if(freq==null)freq=object(result,"frequency_mhz");String average=first(freq,"sampled_mean_mhz","average_mhz","avg_mhz","mean_mhz","average","avg");if(average.isEmpty())average=first(result,"avg_freq_mhz","average_frequency_mhz","frequency_avg_mhz","cpu_frequency_mhz");if(!average.isEmpty())add(resultContent,dataRow("采样平均频率",numeric(average,1)+" MHz"),12);
        String effective=first(freq,"effective_mhz");if(!effective.isEmpty())add(resultContent,dataRow("有效频率",numeric(effective,1)+" MHz"),12);
        String rangeMin=first(freq,"sampled_min_mhz"),rangeMax=first(freq,"sampled_max_mhz");if(!rangeMin.isEmpty()&&!rangeMax.isEmpty())add(resultContent,dataRow("频率采样范围",numeric(rangeMin,0)+"–"+numeric(rangeMax,0)+" MHz"),12);
        String normalized=first(score,"per_mhz","normalized","score_per_mhz");if(normalized.isEmpty())normalized=first(result,"score_per_mhz","normalized_score","per_mhz");if(!normalized.isEmpty())add(resultContent,dataRow("每 MHz 成绩",numeric(normalized,5)),12);
        String normalization=first(freq,"normalization_source");if(!normalization.isEmpty()&&!normalization.equals("unavailable")){String note=normalization.equals("user_cycles/task_clock")?"归一化使用用户态周期 / 任务 CPU 时间。":"100ms cpufreq sampling".equals(normalization)?"归一化使用每 100 ms 采样的 CPU 频率。":"归一化口径："+normalization;TextView n=text(note+" 属于动态频率估计，并非官方提交成绩。",9.5f,DIM,false);n.setLineSpacing(dp(3),1);add(resultContent,n,8);}
        JSONObject pmu=object(result,"pmu");String ipc=first(result,"ipc","instructions_per_cycle");if(ipc.isEmpty())ipc=first(pmu,"ipc","instructions_per_cycle");if(!ipc.isEmpty())add(resultContent,dataRow("IPC",numeric(ipc,4)),12);
        String duration=first(result,"elapsed_seconds","duration_seconds","wall_seconds");if(!duration.isEmpty())add(resultContent,dataRow("实际耗时",numeric(duration,2)+" 秒"),12);
        if(result.has("requested_cpu"))add(resultContent,dataRow("指定核心","CPU "+result.optInt("requested_cpu")),12);
        if(!detail.isEmpty()){TextView details=text(detail,11,MUTED,false);details.setLineSpacing(dp(4),1);add(resultContent,details,16);}
        if(!probe&&!result.has("cancelled")&&value.isEmpty()&&detail.isEmpty())add(resultContent,text("该结果未提供标准分数字段，请查看完整 JSON。",11,DIM,false),14);
        if(pmuContent!=null)renderPmu(result);
    }
    private String translatedValid(String value){return "true".equalsIgnoreCase(value)?"有效，判定口径见说明":"false".equalsIgnoreCase(value)?"未通过，成绩不可作为有效基准":value;}
    private String numeric(String value,int digits){try{double n=Double.parseDouble(value);return Double.isFinite(n)?String.format(Locale.US,"%."+digits+"f",n):"—";}catch(Exception ignored){return value;}}
    private void renderPmu(JSONObject result){
        pmuContent.removeAllViews();JSONObject pmu=object(result,"pmu");Object events=result.opt("events");if(events==null&&pmu!=null)events=pmu.opt("events");if(events==null)events=result.opt("counters");if(events==null&&pmu!=null)events=pmu.opt("counters");
        if(events instanceof JSONArray){JSONArray list=(JSONArray)events;for(int i=0;i<list.length();i++){JSONObject event=list.optJSONObject(i);if(event!=null)addPmuRow(first(event,"name","event","label","id"),event);}}
        else if(events instanceof JSONObject){JSONObject map=(JSONObject)events;Iterator<String> keys=map.keys();while(keys.hasNext()){String key=keys.next();Object event=map.opt(key);if(event instanceof JSONObject)addPmuRow(key,(JSONObject)event);else add(pmuContent,dataRow(key,String.valueOf(event)),9);}}
        else if(pmu!=null){Iterator<String> keys=pmu.keys();while(keys.hasNext()){String key=keys.next();Object entry=pmu.opt(key);if(entry instanceof JSONObject)addPmuRow(key,(JSONObject)entry);else if(!(entry instanceof JSONArray))add(pmuContent,dataRow(key,String.valueOf(entry)),9);}}
        if(pmuContent.getChildCount()==0){String reason=first(pmu,"reason","detail","error");if(reason.isEmpty())reason=first(result,"pmu_reason","pmu_error");add(pmuContent,text(reason.isEmpty()?"该结果尚未提供事件详情，请查看原始 JSON。":reason,11,DIM,false),0);}
        String scope=first(result,"pmu_scope","scope");if(!scope.isEmpty()){TextView note=text("计数范围 · "+scope,9.5f,DIM,false);note.setLineSpacing(dp(3),1);add(pmuContent,note,15);}
        renderEventSources(result);
    }
    private void renderEventSources(JSONObject result){
        JSONArray devices=result.optJSONArray("event_source_devices");String failure=first(result,"event_source_error");
        if(!failure.isEmpty())add(pmuContent,text("事件源目录 · "+failure,10,AMBER,false),16);
        if(devices==null)return;
        add(pmuContent,text("内核公开的事件源  ·  "+devices.length(),12,WHITE,true),20);
        TextView explanation=text("下方名称与编码由内核原样提供。展开查看设备类型、可用事件和格式字段。",9.5f,DIM,false);explanation.setLineSpacing(dp(3),1);add(pmuContent,explanation,7);
        if(devices.length()==0){add(pmuContent,text("本次未读取到事件源。",10,DIM,false),10);return;}
        for(int i=0;i<devices.length();i++){
            JSONObject source=devices.optJSONObject(i);if(source==null)continue;
            LinearLayout cell=col();cell.setPadding(dp(11),dp(11),dp(11),dp(11));cell.setBackground(shape(0xFF101824,11,BORDER,1));
            LinearLayout heading=row();TextView name=text(source.optString("name","未命名事件源"),11,WHITE,true);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("展开 ▾",9,LILAC,false);heading.addView(arrow);cell.addView(heading);
            JSONArray events=source.optJSONArray("events");String type=first(source,"type"),cpus=first(source,"cpus"),mask=first(source,"cpumask");String caption="type "+(type.isEmpty()?"未知":type)+"  ·  "+(events==null?0:events.length())+" 项事件";if(!cpus.isEmpty())caption+="  ·  CPU "+cpus;else if(!mask.isEmpty())caption+="  ·  掩码 "+mask;add(cell,text(caption,9,DIM,false),6);
            LinearLayout details=col();details.setVisibility(View.GONE);add(cell,details,0);
            cell.setOnClickListener(v->{boolean expanded=details.getVisibility()!=View.VISIBLE;if(expanded&&details.getChildCount()==0){String error=first(source,"error","type_error");if(!error.isEmpty())add(details,text(error,9.5f,AMBER,false),12);renderSourceEntries(details,source.optJSONArray("events"),"具名事件");renderSourceEntries(details,source.optJSONArray("formats"),"格式字段");if(source.optBoolean("truncated",false))add(details,text("事件源列表已按读取上限截断；原始 JSON 保留本次读取结果。",9,DIM,false),10);if(details.getChildCount()==0)add(details,text("内核没有为此设备公开具名事件或格式。",10,DIM,false),12);}details.setVisibility(expanded?View.VISIBLE:View.GONE);arrow.setText(expanded?"收起 ▴":"展开 ▾");});
            add(pmuContent,cell,9);
        }
    }
    private void renderSourceEntries(LinearLayout target,JSONArray entries,String label){
        if(entries==null||entries.length()==0)return;add(target,text(label,10,LILAC,true),13);
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;LinearLayout line=col();line.addView(text(entry.optString("name","—"),10,WHITE,false));TextView encoding=mono(entry.optString("encoding","—"),9,DIM);encoding.setTextIsSelectable(true);add(line,encoding,3);add(target,line,9);}
    }
    private void addPmuRow(String name,JSONObject event){
        if(name.isEmpty())name="硬件事件";String available=first(event,"available","supported","enabled","ok"),value=first(event,"scaled","value","count","scaled_value","raw"),reason=first(event,"reason","error","detail","message");boolean failed="false".equals(available)||(!reason.isEmpty()&&value.isEmpty());if(failed&&reason.isEmpty()&&event.optInt("errno",0)!=0)reason="内核返回 errno "+event.optInt("errno");LinearLayout cell=col();LinearLayout head=row();TextView eventTitle=text(pmuName(name),11,WHITE,false);LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(0,-2,1);tl.rightMargin=dp(8);head.addView(eventTitle,tl);TextView number=mono(failed?"不可用":value.isEmpty()?"true".equals(available)?"支持":"—":counterNumber(value),11,failed?AMBER:MINT);number.setMaxWidth(dp(145));number.setGravity(Gravity.END);head.addView(number);cell.addView(head);if(!reason.isEmpty()){TextView detail=text(reason,9.5f,DIM,false);detail.setLineSpacing(dp(2),1);add(cell,detail,5);}String ratio=first(event,"running_ratio");if(!failed&&!ratio.isEmpty())add(cell,text("事件运行比例 "+numeric(ratio,3),9,DIM,false),4);add(pmuContent,cell,12);
    }
    private String counterNumber(String value){try{return new java.math.BigDecimal(value).setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}catch(Exception ignored){return value;}}
    private String pmuName(String name){switch(name){case "cycles":return "CPU 周期";case "instructions":return "退休指令";case "branches":return "分支指令";case "branch_misses":return "分支预测失误";case "cache_references":return "缓存访问";case "cache_misses":return "缓存未命中";case "stalled_frontend":return "前端停顿周期";case "stalled_backend":return "后端停顿周期";case "reference_cycles":return "参考周期";case "bus_cycles":return "总线周期";case "l1d_read_accesses":return "L1 数据缓存读取";case "l1d_read_misses":return "L1 数据读取未命中";case "llc_read_accesses":return "末级缓存读取";case "llc_read_misses":return "末级缓存读取未命中";case "task_clock":return "任务 CPU 时钟 (ns)";case "context_switches":return "上下文切换";case "cpu_migrations":return "CPU 迁移";case "page_faults":return "缺页次数";case "minor_faults":return "次要缺页";case "major_faults":return "主要缺页";default:return name;}}
    private JSONObject object(JSONObject parent,String key){if(parent==null)return null;return parent.optJSONObject(key);}
    private String first(JSONObject object,String...keys){if(object==null)return "";for(String key:keys){Object value=object.opt(key);if(value!=null&&value!=JSONObject.NULL&&!(value instanceof JSONObject)&&!(value instanceof JSONArray)){String s=String.valueOf(value);if(!s.isEmpty()&&!s.equals("null"))return s;}}return "";}

    private void renderHistory(){
        historyContent.removeAllViews();File dir=new File(getFilesDir(),"benchmarks");File[] files=dir.listFiles((parent,name)->name.endsWith(".json"));if(files==null||files.length==0){historyContent.addView(text("每一次完成的测试都会保存在这里。",11,DIM,false));return;}Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));int shown=0;
        for(File file:files){if(shown++>=30)break;try{JSONObject result=new JSONObject(readText(file));long timestamp=result.optLong("captured_at",file.lastModified());String test=first(result,"requested_kind","kind");if(test.isEmpty())test=file.getName();LinearLayout entry=col();entry.setPadding(0,dp(10),0,dp(11));LinearLayout head=row();head.addView(text(prettyKind(test),12,WHITE,true),new LinearLayout.LayoutParams(0,-2,1));head.addView(text(new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(timestamp)),10,DIM,false));entry.addView(head);String meta="CPU "+result.optInt("requested_cpu",0)+"  ·  "+result.optInt("requested_seconds",0)+" 秒"+(result.optBoolean("cancelled",false)?"  ·  已取消":"");add(entry,text(meta,10,MUTED,false),6);entry.setOnClickListener(v->{if(BenchmarkService.running){Toast.makeText(this,"测试运行中，结束后可查看历史",Toast.LENGTH_SHORT).show();return;}viewingHistory=true;shownResult=result;renderResult(result);mainScroll.post(()->mainScroll.smoothScrollTo(0,Math.max(0,resultCardView.getTop()-dp(10))));Toast.makeText(this,"已载入历史结果，可查看或导出 JSON",Toast.LENGTH_SHORT).show();});historyContent.addView(entry);}catch(Exception ignored){}}
        if(files.length>30)add(historyContent,text("显示最近 30 次测试；更早记录仍保存在本地。",9,DIM,false),8);
    }
    private String prettyKind(String value){if("coremark".equals(value))return "CoreMark";if("dhrystone".equals(value))return "Dhrystone";if("whetstone".equals(value))return "Whetstone";if("probe".equals(value))return "PMU 探测";if("custom".equals(value))return "自定义 ELF";return value;}
    private String readText(File file)throws Exception{if(file.length()>8*1024*1024)throw new Exception("结果文件过大");try(InputStream input=new FileInputStream(file);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){byte[] data=new byte[8192];int n;while((n=input.read(data))!=-1)output.write(data,0,n);return output.toString("UTF-8");}}
    private String currentJson(){if(shownResult==null)return "";try{return shownResult.toString(2);}catch(Exception ignored){return shownResult.toString();}}
    private void showRaw(){String json=currentJson();if(json.isEmpty()){Toast.makeText(this,"尚无测试结果",Toast.LENGTH_SHORT).show();return;}ScrollView scroll=new ScrollView(this);TextView raw=mono(json,10,WHITE);raw.setTextIsSelectable(true);raw.setPadding(dp(18),dp(16),dp(18),dp(16));raw.setBackgroundColor(BG);scroll.addView(raw);new AlertDialog.Builder(this).setTitle("原始测试结果").setView(scroll).setPositiveButton("关闭",null).setNeutralButton("复制 JSON",(dialog,which)->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Pulse benchmark",json));Toast.makeText(this,"已复制原始 JSON",Toast.LENGTH_SHORT).show();}).show();}
    private void exportResult(){exportJson=currentJson();if(exportJson.isEmpty()){Toast.makeText(this,"尚无测试结果",Toast.LENGTH_SHORT).show();return;}Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"pulse-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".json");startActivityForResult(create,201);}

    private void importCase(){if(BenchmarkService.running||importing)return;Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");startActivityForResult(picker,200);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==200)importElf(uri);else if(request==201){String json=exportJson;new Thread(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new Exception("无法打开导出位置");out.write(json.getBytes(StandardCharsets.UTF_8));handler.post(()->Toast.makeText(this,"JSON 已导出",Toast.LENGTH_SHORT).show());}catch(Exception e){handler.post(()->showError("导出失败",e.getMessage()));}},"pulse-export").start();}}
    private void importElf(Uri uri){
        importing=true;caseLabel.setText("正在校验并导入…");String displayName="ARM64 测试程序";
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())displayName=cursor.getString(0);}catch(Exception ignored){}
        final String name=displayName;
        new Thread(()->{File temp=null;try{File dir=new File(getFilesDir(),"cases");if(!dir.isDirectory()&&!dir.mkdirs())throw new Exception("无法创建测试目录");temp=File.createTempFile("import-",".tmp",dir);MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] header=new byte[20];int headerCount=0;
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){if(in==null)throw new Exception("无法读取所选文件");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){total+=n;if(total>128L*1024*1024)throw new Exception("文件超过 128 MB 上限");if(headerCount<20){int copy=Math.min(20-headerCount,n);System.arraycopy(buffer,0,header,headerCount,copy);headerCount+=copy;}digest.update(buffer,0,n);out.write(buffer,0,n);}}
                if(headerCount<20||header[0]!=0x7f||header[1]!='E'||header[2]!='L'||header[3]!='F'||header[4]!=2||header[5]!=1||(header[18]&255)!=183||header[19]!=0)throw new Exception("需要 ELF64、小端、AArch64（ARM64）的原生程序");StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(Locale.US,"%02x",b&255));File target=new File(dir,sha+".elf");if(target.exists()){if(!temp.delete())throw new Exception("无法移除临时文件");}else if(!temp.renameTo(target))throw new Exception("无法保存测试程序");final long size=total;customPath=target.getAbsolutePath();customName=name;prefs.putString("bench_custom_path",customPath);prefs.putString("bench_custom_name",customName);handler.post(()->{importing=false;caseLabel.setText(name+"\n"+String.format(Locale.US,"%.2f MB",size/1048576.0)+"  ·  ARM64 ELF 已校验");Toast.makeText(this,"测试程序已导入",Toast.LENGTH_SHORT).show();});
            }catch(Exception e){if(temp!=null)temp.delete();handler.post(()->{importing=false;caseLabel.setText(customName.isEmpty()?"尚未导入 ELF":customName);showError("未导入测试程序",e.getMessage());});}},"pulse-elf-import").start();
    }
    private void showError(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message==null?"请稍后重试":message).setPositiveButton("知道了",null).show();}
    private LinearLayout dataRow(String label,String value){LinearLayout row=row();TextView title=text(label,11,MUTED,false);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));TextView data=mono(value,11,WHITE);data.setGravity(Gravity.END);data.setMaxWidth(dp(185));row.addView(data);return row;}
    private LinearLayout title(String label,String note){LinearLayout row=row();row.addView(text(label,14,WHITE,true),new LinearLayout.LayoutParams(0,-2,1));row.addView(text(note,9,DIM,false));return row;}
    private LinearLayout col(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private LinearLayout card(){LinearLayout v=col();v.setPadding(dp(18),dp(18),dp(18),dp(18));v.setBackground(shape(CARD,21,BORDER,1));return v;}
    private TextView text(String value,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return v;}
    private TextView mono(String value,float size,int color){TextView v=text(value,size,color,false);v.setTypeface(Typeface.MONOSPACE);return v;}
    private TextView button(String value,int fill,int color){TextView v=text(value,12,color,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(12),dp(13),dp(12),dp(13));v.setBackground(shape(fill,12,0,0));v.setClickable(true);v.setFocusable(true);return v;}
    private GradientDrawable shape(int color,int radius,int border,int width){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));if(width>0)bg.setStroke(dp(width),border);return bg;}
    private void add(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);parent.addView(child,lp);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
