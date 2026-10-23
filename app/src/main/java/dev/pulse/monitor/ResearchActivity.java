package dev.pulse.monitor;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/** Vector, memory and frequency experiments share a visible, auditable result format. */
public final class ResearchActivity extends Activity {
    private static final int BG=0xFF0B1019,CARD=0xFF141D2B,EDGE=0xFF273448,WHITE=0xFFF1F5FC,MUTED=0xFF94A3B8,MINT=0xFF78E8C6,LILAC=0xFFB7A4FF,AMBER=0xFFFFCC83;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String suite="crypto",shown="",exportText="";
    private int cpu=0,rounds=3,signatures=128,threads=1,mib=128;
    private TextView state,timer,run,stop,explanation,rootNote;
    private LinearLayout tabs,options,result;
    private Switch rootAccess;
    private JSONObject displayed;
    private boolean screenAwake;
    private final Runnable refresh=new Runnable(){@Override public void run(){
        boolean busy=ResearchService.running;if(!state.getText().toString().equals(ResearchService.status))state.setText(ResearchService.status);run.setEnabled(!busy&&!BenchmarkService.running);run.setAlpha(run.isEnabled()?1f:.4f);stop.setEnabled(busy);stop.setAlpha(busy?1f:.4f);
        if(busy){long s=(SystemClock.elapsedRealtime()-ResearchService.startedAt)/1000;String clock=String.format(Locale.US,"%02d:%02d",s/60,s%60);if(!timer.getText().toString().equals(clock))timer.setText(clock);}
        if(busy!=screenAwake){screenAwake=busy;if(busy)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
        JSONObject r=ResearchService.report;if(r!=null&&!r.toString().equals(shown)){shown=r.toString();displayed=r;renderResults(r);}
        handler.postDelayed(this,700);
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("research",MODE_PRIVATE);suite=prefs.getString("suite","crypto");cpu=prefs.getInt("cpu",0);rounds=prefs.getInt("rounds",3);signatures=prefs.getInt("signatures",128);threads=prefs.getInt("threads",1);mib=prefs.getInt("mib",128);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(BG);if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        build();if(ResearchService.report==null)loadLatest();
    }
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private void build(){
        LinearLayout outer=column();outer.setBackgroundColor(BG);outer.setOnApplyWindowInsetsListener((v,i)->{if(Build.VERSION.SDK_INT>=30){Insets e=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(e.left,e.top,e.right,e.bottom);}else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});setContentView(outer);
        LinearLayout header=row();header.setPadding(dp(22),dp(16),dp(22),dp(16));TextView back=text("‹",34,WHITE,true);back.setPadding(0,0,dp(20),0);back.setOnClickListener(v->finish());back.setContentDescription("返回性能实验室");header.addView(back);LinearLayout brand=column();brand.addView(text("向量与内存",23,WHITE,true));add(brand,text("PULSE LAB  /  NATIVE AARCH64",9,LILAC,true),5);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));TextView history=text("档案",12,MUTED,true);history.setPadding(dp(12),dp(10),0,dp(10));history.setOnClickListener(v->showHistory());header.addView(history);outer.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout body=column();body.setPadding(dp(20),dp(4),dp(20),dp(30));scroll.addView(body);outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout hero=card();GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF263B37,0xFF192137});gradient.setCornerRadius(dp(22));hero.setBackground(gradient);hero.addView(text("从字节，到每一条指令。",22,WHITE,true));add(hero,text("完整签名 · 指令集对照 · 带宽与能效",11,MUTED,false),10);LinearLayout live=row();state=text(ResearchService.status,11,MINT,false);live.addView(state,new LinearLayout.LayoutParams(0,-2,1));timer=text("00:00",19,WHITE,true);timer.setTypeface(Typeface.MONOSPACE);live.addView(timer);add(hero,live,22);add(body,hero,0);
        tabs=row();add(body,tabs,16);renderTabs();
        LinearLayout config=card();explanation=text("",11,MUTED,false);explanation.setLineSpacing(dp(4),1);config.addView(explanation);options=column();add(config,options,17);add(body,config,13);renderOptions();
        LinearLayout controls=row();run=button("开始实验",MINT,BG);run.setOnClickListener(v->startExperiment());controls.addView(run,new LinearLayout.LayoutParams(0,dp(50),1));stop=button("停止",0xFF263145,MUTED);stop.setOnClickListener(v->startService(new Intent(this,ResearchService.class).setAction(ResearchService.STOP)));LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(dp(92),dp(50));sl.leftMargin=dp(9);controls.addView(stop,sl);add(body,controls,15);
        LinearLayout outputs=card();outputs.addView(text("实验结果",16,WHITE,true));result=column();add(outputs,result,16);renderResults(null);add(body,outputs,15);
        LinearLayout exports=row();TextView json=button("导出 JSON",0xFF1D293A,LILAC),csv=button("导出 CSV",0xFF1D293A,MINT);json.setOnClickListener(v->export(false));csv.setOnClickListener(v->export(true));exports.addView(json,new LinearLayout.LayoutParams(0,dp(45),1));LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(0,dp(45),1);cl.leftMargin=dp(8);exports.addView(csv,cl);add(body,exports,12);
        TextView raw=text("查看完整数据与验证结果",11,MUTED,false);raw.setGravity(Gravity.CENTER);raw.setPadding(0,dp(16),0,dp(8));raw.setOnClickListener(v->showRaw());add(body,raw,4);
    }
    private void renderTabs(){tabs.removeAllViews();String[] keys={"memory","crypto","efficiency"},names={"内存带宽","ML-DSA-87","频率能效"};for(int i=0;i<keys.length;i++){final String key=keys[i];TextView tab=button(names[i],suite.equals(key)?0xFF2A3C48:CARD,suite.equals(key)?MINT:MUTED);tab.setTextSize(11);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1);if(i>0)lp.leftMargin=dp(6);tabs.addView(tab,lp);tab.setOnClickListener(v->{if(ResearchService.running)return;suite=key;prefs.edit().putString("suite",key).apply();renderTabs();renderOptions();});}}
    private void renderOptions(){
        options.removeAllViews();boolean memory="memory".equals(suite),sweep="efficiency".equals(suite);
        explanation.setText(memory?"顺序读取、写入与复制；覆盖 256 KiB、2 MiB、16 MiB 和大工作集。分配与首次触页在计时前完成，写入和复制会在计时后校验。复制 GB/s 按有效载荷计算，另保留双向逻辑流量。":sweep?"自动选择当前频率表中的 5 个上限档位，使用相同 ML-DSA-87 NEON 工作负载比较。每档临时降低上限，结束自动恢复；记录实际频率。能效仅在放电且电池数据可读时给出，表示整机电池端能效。":"比较标量、NEON、SVE、SVE2 的完整 ML-DSA-87 签名。每轮采用同一密钥、1 KiB 消息和随机输入，并轮换测试顺序。6 组 NIST KAT、逐个验签和跨路径字节一致性通过后才显示有效成绩。SVE 向量长度由真机检测。");
        List<String> cpus=cpuLabels();choice("测试核心",cpus,cpu,index->{cpu=index;prefs.edit().putInt("cpu",cpu).apply();});
        choice("重复轮数",Arrays.asList("1 轮 · 快速检查","3 轮 · 中位数对比","5 轮 · 多轮观察"),rounds==1?0:rounds==5?2:1,index->{rounds=new int[]{1,3,5}[index];prefs.edit().putInt("rounds",rounds).apply();});
        if(memory){choice("工作线程",Arrays.asList("1 线程 · 指定核心","4 线程 · 从指定核心起","8 线程 · 全核"),threads==4?1:threads==8?2:0,index->{threads=new int[]{1,4,8}[index];prefs.edit().putInt("threads",threads).apply();});choice("最大工作集",Arrays.asList("128 MiB / 每次读写最多 256 MiB 分配","256 MiB / 每次读写最多 512 MiB 分配"),mib==256?1:0,index->{mib=index==1?256:128;prefs.edit().putInt("mib",mib).apply();});}
        else if(!sweep)choice("每路径每轮签名数",Arrays.asList("128 次 · 常规","512 次 · 长采样"),signatures==512?1:0,index->{signatures=index==1?512:128;prefs.edit().putInt("signatures",signatures).apply();});
        rootAccess=new Switch(this);rootAccess.setText(sweep?"Root 频率扫描（必需）":"Root 计数器与额外传感器（可选）");rootAccess.setTextColor(WHITE);rootAccess.setTextSize(11);rootAccess.setChecked(sweep||prefs.getBoolean("root",false));rootAccess.setEnabled(!sweep);rootAccess.setOnCheckedChangeListener((v,on)->{if(!sweep)prefs.edit().putBoolean("root",on).apply();});add(options,rootAccess,12);
        rootNote=text((sweep?"扫描调整所选核心所属频率组的上限，包含独立恢复守护。每档 8192 次签名；充电或采样不足 2 秒时能效排名留空。":"内存和签名可在普通权限下运行。PMU 或传感器未开放时保留不可用原因。")+" 电池达到 48°C 时停止本次实验。",10,MUTED,false);rootNote.setLineSpacing(dp(3),1);add(options,rootNote,9);
    }
    private List<String> cpuLabels(){
        List<Long> max=new ArrayList<>();TreeSet<Long> levels=new TreeSet<>();int n=Math.min(32,Runtime.getRuntime().availableProcessors());
        // /sys topology is preferred to availableProcessors(), which may reflect an app cpuset.
        for(int i=0;i<32;i++){File path=new File("/sys/devices/system/cpu/cpu"+i+"/cpufreq/cpuinfo_max_freq");if(!path.exists()){if(i>=n)break;max.add(0L);continue;}long f=0;try{f=Long.parseLong(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8).trim());}catch(Exception ignored){}max.add(f);if(f>0)levels.add(f);}
        if(max.isEmpty())max.add(0L);List<String> labels=new ArrayList<>();List<Long> ordered=new ArrayList<>(levels);
        for(int i=0;i<max.size();i++){long f=max.get(i);int rank=ordered.indexOf(f);String tier=levels.size()>1?(rank==ordered.size()-1?"超大核":"性能组 "+(rank+1)):"核心";labels.add("CPU "+i+"  ·  "+tier+(f>0?String.format(Locale.US,"  ·  %.2f GHz",f/1e6):""));}
        cpu=Math.max(0,Math.min(max.size()-1,cpu));return labels;
    }
    private interface Selection {void changed(int i);}
    private void choice(String title,List<String> values,int selected,Selection listener){
        add(options,text(title,10,MUTED,true),12);Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,values){
            private View item(int position,View convert,boolean dropdown){TextView v=convert instanceof TextView?(TextView)convert:text("",12,WHITE,false);v.setText(getItem(position));v.setTextColor(WHITE);v.setTextSize(12);v.setPadding(dp(12),dp(13),dp(8),dp(13));v.setBackgroundColor(dropdown?CARD:0xFF101825);return v;}
            @Override public View getView(int p,View c,android.view.ViewGroup parent){return item(p,c,false);}
            @Override public View getDropDownView(int p,View c,android.view.ViewGroup parent){return item(p,c,true);}
        };spinner.setAdapter(adapter);spinner.setSelection(Math.min(values.size()-1,Math.max(0,selected)));spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){listener.changed(pos);}public void onNothingSelected(android.widget.AdapterView<?> p){}});add(options,spinner,7);
    }
    private void startExperiment(){
        if(ResearchService.running||BenchmarkService.running)return;
        Intent i=new Intent(this,ResearchService.class).setAction(ResearchService.RUN).putExtra("suite",suite).putExtra("cpu",cpu).putExtra("rounds",rounds).putExtra("signatures",signatures).putExtra("threads",threads).putExtra("mib",mib).putExtra("root",rootAccess.isChecked());
        try{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},17);startForegroundService(i);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void renderResults(JSONObject r){
        result.removeAllViews();if(r==null){result.addView(text("运行完成后展示多轮中位数、加速比、实际频率与验证情况。\n所有结果均可导出，无数据时不显示分数。",12,MUTED,false));return;}
        String type=r.optString("suite");JSONObject caps=r.optJSONObject("capabilities");
        if(caps!=null)add(result,text("NEON 128   ·   SVE "+caps.optInt("sve_bits")+"   ·   SME "+caps.optInt("sme_bits")+" bit",10,LILAC,true),0);
        add(result,text(("memory".equals(type)?"内存带宽":"efficiency".equals(type)?"频率上限扫描":"ML-DSA-87 完整签名")+"  ·  CPU "+r.optInt("cpu")+"  ·  "+r.optInt("rounds")+" 轮",12,WHITE,true),13);
        JSONArray summary=r.optJSONArray("summary");try{if(summary==null)summary=ResearchService.summarize(r);}catch(Exception ignored){summary=new JSONArray();}
        if(summary.length()>0){
            add(result,new ResultChart(summary,type),18);
            for(int i=0;i<summary.length();i++){
                JSONObject item=summary.optJSONObject(i);if(item==null)continue;LinearLayout panel=column();panel.setPadding(dp(12),dp(12),dp(12),dp(12));panel.setBackground(shape(0xFF0D1521,12));
                if("memory".equals(type)){
                    panel.addView(text(mode(item.optString("mode"))+"  ·  "+size(item.optLong("working_set_bytes")),12,WHITE,true));
                    add(panel,text(String.format(Locale.US,"%.2f GB/s",item.optDouble("payload_gbps")),22,MINT,true),8);
                    if("copy".equals(item.optString("mode")))add(panel,text(String.format(Locale.US,"读＋写逻辑流量 %.2f GB/s",item.optDouble("logical_traffic_gbps")),10,MUTED,false),6);
                }else{
                    String name="efficiency".equals(type)?String.format(Locale.US,"上限 %.3f GHz",item.optLong("cap_khz")/1e6):item.optString("backend").toUpperCase(Locale.ROOT);
                    panel.addView(text(name+"  ·  "+item.optInt("n")+" 轮有效",12,WHITE,true));
                    add(panel,text(String.format(Locale.US,"%,.1f 次签名 / 秒",item.optDouble("signatures_per_second")),21,MINT,true),9);
                    if(item.has("speedup"))add(panel,text(String.format(Locale.US,"相对标量 %.3f×",item.optDouble("speedup")),12,item.optDouble("speedup")>=1?LILAC:AMBER,true),8);
                    add(panel,text(String.format(Locale.US,"单次中位 %.1f μs  ·  P95 %.1f μs",item.optDouble("median_us"),item.optDouble("p95_us")),10,MUTED,false),8);
                    if(item.has("sampled_mean_mhz"))add(panel,text(String.format(Locale.US,"采样均频 %.0f MHz",item.optDouble("sampled_mean_mhz")),10,MUTED,false),6);
                    if(item.has("signatures_per_second_per_mhz"))add(panel,text(String.format(Locale.US,"动态归一化 %.5f 次 / 秒 / MHz",item.optDouble("signatures_per_second_per_mhz")),10,MUTED,false),6);
                    if(item.has("signatures_per_joule"))add(panel,text(String.format(Locale.US,"电池端整机能效 %.2f 次 / J",item.optDouble("signatures_per_joule")),11,LILAC,true),7);
                    else if("efficiency".equals(type))add(panel,text("能效不可比较：充电中或电池数据不可用",10,AMBER,false),7);
                }add(result,panel,9);
            }
        }else add(result,text("正在积累有效样本…",12,MUTED,false),18);
        JSONArray runs=r.optJSONArray("runs");int valid=0,unsupported=0,bad=0;if(runs!=null)for(int i=0;i<runs.length();i++){JSONObject x=runs.optJSONObject(i);if(x!=null){if(x.optBoolean("valid"))valid++;else if("unsupported".equals(x.optString("result")))unsupported++;else bad++;}}
        add(result,text(valid+" 个有效样本"+(unsupported>0?" · "+unsupported+" 项不支持":"")+(bad>0?" · "+bad+" 项未通过":""),10,bad>0?AMBER:MINT,true),16);
        if(r.has("error"))add(result,text(r.optString("error"),11,AMBER,false),10);
        if("crypto".equals(type)||"efficiency".equals(type))add(result,text("向量路径加速 NTT 和点乘，SHAKE 使用同一参考实现。SME 仅显示检测结果，本次签名未使用矩阵指令。频率会动态变化，请结合逐轮数据判断。",10,MUTED,false),12);
    }
    private void export(boolean csv){
        if(displayed==null){Toast.makeText(this,"请先完成实验",Toast.LENGTH_SHORT).show();return;}
        try{exportText=csv?csv(displayed):displayed.toString(2);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(csv?"text/csv":"application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Pulse-"+displayed.optString("suite")+"-"+displayed.optLong("captured_at")+(csv?".csv":".json"));startActivityForResult(i,31);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    static String csv(JSONObject r)throws JSONException{
        StringBuilder s=new StringBuilder("suite,backend,round,cpu,valid,mode,working_set_bytes,threads,cap_khz,signatures,elapsed_seconds,signatures_per_second,median_us,p95_us,payload_gbps,logical_traffic_gbps,sampled_mean_mhz,effective_mhz,ipc,battery_net_watts,battery_net_joules,energy_comparable,cap_restored,transcript_sha3shake256\n");
        JSONArray a=r.optJSONArray("runs");if(a==null)return s.toString();String[] fields={"backend","round","cpu","valid","mode","working_set_bytes","threads","cap_khz","signatures","elapsed_seconds","signatures_per_second","median_us","p95_us","payload_gbps","logical_traffic_gbps"},telemetry={"sampled_mean_mhz","effective_mhz","ipc","battery_net_watts","battery_net_joules","energy_comparable"};
        for(int j=0;j<a.length();j++){JSONObject row=a.getJSONObject(j);s.append(escape(r.optString("suite")));for(String f:fields)s.append(',').append(escape(row.isNull(f)?"":row.optString(f)));JSONObject t=row.optJSONObject("telemetry");for(String f:telemetry)s.append(',').append(escape(t==null||t.isNull(f)?"":t.optString(f)));s.append(',').append(escape(row.optString("cap_restored"))).append(',').append(escape(row.optString("transcript_sha3shake256"))).append('\n');}return s.toString();
    }
    private static String escape(String s){return "\""+s.replace("\"","\"\"")+"\"";}
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);if(request!=31||code!=RESULT_OK||data==null)return;Uri uri=data.getData();if(uri==null)return;String output=exportText;new Thread(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("无法打开导出文件");out.write(output.getBytes(StandardCharsets.UTF_8));handler.post(()->Toast.makeText(this,"报告已导出",Toast.LENGTH_SHORT).show());}catch(Exception e){handler.post(()->Toast.makeText(this,"导出失败："+e.getMessage(),Toast.LENGTH_LONG).show());}},"lab-export").start();}
    private File[] historyFiles(){File[] f=new File(getFilesDir(),"research").listFiles((d,n)->n.endsWith(".json"));if(f==null)return new File[0];Arrays.sort(f,(a,b)->b.getName().compareTo(a.getName()));return f;}
    private void loadLatest(){new Thread(()->{File[] f=historyFiles();if(f.length>0)try{JSONObject r=new JSONObject(new String(Files.readAllBytes(f[0].toPath()),StandardCharsets.UTF_8));handler.post(()->{if(ResearchService.report==null){displayed=r;renderResults(r);}});}catch(Exception ignored){}},"lab-history").start();}
    private void showHistory(){File[] files=historyFiles();if(files.length==0){Toast.makeText(this,"还没有保存的实验",Toast.LENGTH_SHORT).show();return;}int count=Math.min(30,files.length);String[] names=new String[count];SimpleDateFormat format=new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault());for(int i=0;i<count;i++)names[i]=format.format(new Date(files[i].lastModified()))+" · "+files[i].getName().replaceFirst("^\\d+-","").replace(".json","");new AlertDialog.Builder(this).setTitle("本机实验档案").setItems(names,(d,index)->{try{JSONObject r=new JSONObject(new String(Files.readAllBytes(files[index].toPath()),StandardCharsets.UTF_8));ResearchService.report=r;displayed=r;shown=r.toString();renderResults(r);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}).setNegativeButton("关闭",null).show();}
    private void showRaw(){if(displayed==null)return;try{String raw=displayed.toString(2);ScrollView scroll=new ScrollView(this);TextView t=text(raw,10,WHITE,false);t.setTypeface(Typeface.MONOSPACE);t.setTextIsSelectable(true);t.setPadding(dp(18),dp(16),dp(18),dp(16));t.setBackgroundColor(BG);scroll.addView(t);new AlertDialog.Builder(this).setTitle("原始实验记录").setView(scroll).setPositiveButton("关闭",null).setNeutralButton("复制",(d,w)->getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Pulse lab",raw))).show();}catch(Exception ignored){}}
    private final class ResultChart extends View {
        final JSONArray rows;final String type;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        ResultChart(JSONArray a,String s){super(ResearchActivity.this);rows=a;type=s;setMinimumHeight(dp(40+Math.min(12,a.length())*44));setContentDescription("实验结果对比条形图");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);boolean memory="memory".equals(type);String metric=memory?"payload_gbps":"signatures_per_second";double max=1;for(int i=0;i<rows.length();i++)max=Math.max(max,rows.optJSONObject(i).optDouble(metric,0));p.setTextSize(dp(10));p.setColor(MUTED);c.drawText(memory?"有效载荷 GB/s":"完整签名 次/秒",0,dp(12),p);float left=dp(88),width=Math.max(dp(30),getWidth()-left-dp(50));
            for(int i=0;i<Math.min(12,rows.length());i++){JSONObject r=rows.optJSONObject(i);float y=dp(35+i*44);String name=memory?mode(r.optString("mode"))+" "+size(r.optLong("working_set_bytes")):"efficiency".equals(type)?String.format(Locale.US,"%.2f GHz",r.optLong("cap_khz")/1e6):r.optString("backend").toUpperCase(Locale.ROOT);p.setTextSize(dp(9));p.setColor(MUTED);c.drawText(name,0,y+dp(6),p);p.setColor(0xFF233044);c.drawRoundRect(left,y-dp(7),left+width,y+dp(10),dp(4),dp(4),p);p.setColor(i%2==0?MINT:LILAC);c.drawRoundRect(left,y-dp(7),left+(float)(width*r.optDouble(metric,0)/max),y+dp(10),dp(4),dp(4),p);p.setColor(WHITE);c.drawText(String.format(Locale.US,memory?"%.1f":"%.0f",r.optDouble(metric)),left+width+dp(6),y+dp(6),p);}
        }
    }
    private static String mode(String s){return "read".equals(s)?"读取":"write".equals(s)?"写入":"复制";}
    private static String size(long b){return b<1048576?b/1024+"K":b/1048576+"M";}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(18),dp(18),dp(18),dp(18));GradientDrawable d=shape(CARD,21);d.setStroke(dp(1),EDGE);v.setBackground(d);return v;}
    private TextView text(String s,float sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setIncludeFontPadding(false);v.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return v;}
    private TextView button(String s,int background,int color){TextView v=text(s,12,color,true);v.setBackground(shape(background,12));v.setGravity(Gravity.CENTER);v.setPadding(dp(8),dp(12),dp(8),dp(12));v.setClickable(true);v.setFocusable(true);return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void add(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);parent.addView(child,lp);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
