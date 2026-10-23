package dev.pulse.monitor;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Sequential, bounded experiments. The native cap owner has an independent restoration process. */
public final class ResearchService extends Service {
    public static final String RUN="dev.pulse.monitor.LAB_RUN", STOP="dev.pulse.monitor.LAB_STOP";
    static volatile boolean running;
    static volatile String status="选择一个实验开始";
    static volatile JSONObject report;
    static volatile long startedAt;
    private volatile java.lang.Process process;
    private volatile long nativePid;
    private volatile boolean cancelled, rootMode;
    private PowerManager.WakeLock wake;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());

    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("pulse_research","向量与内存实验",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(STOP.equals(intent.getAction())){if(!running){stopSelf();return START_NOT_STICKY;}cancelled=true;status="正在停止并恢复频率上限…";new Thread(this::signalStop,"lab-stop").start();return START_NOT_STICKY;}
        if(running||BenchmarkService.running){if(!running)stopSelf();return START_NOT_STICKY;}
        Notification n=notification("正在准备原生实验…");if(Build.VERSION.SDK_INT>=34)startForeground(43,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(43,n);
        running=true;cancelled=false;nativePid=0;startedAt=SystemClock.elapsedRealtime();
        String suite=intent.getStringExtra("suite");int cpu=Math.max(0,Math.min(255,intent.getIntExtra("cpu",0)));
        int rounds=Math.max(1,Math.min(5,intent.getIntExtra("rounds",3))),signatures=intent.getIntExtra("signatures",128);
        int threads=intent.getIntExtra("threads",1),maxMib=intent.getIntExtra("mib",128);
        rootMode=intent.getBooleanExtra("root",false)||"efficiency".equals(suite);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Pulse:Research");wake.acquire(20*60*1000L);
        final String selected=suite==null?"crypto":suite;
        worker.execute(()->runSuite(selected,cpu,rounds,signatures,threads,maxMib));return START_NOT_STICKY;
    }
    private void runSuite(String suite,int cpu,int rounds,int signatures,int threads,int maxMib){
        try{
            if(!Arrays.asList("crypto","memory","efficiency").contains(suite))throw new IOException("未知实验");
            if(signatures!=128&&signatures!=512)throw new IOException("签名次数不在允许范围");
            if(threads!=1&&threads!=4&&threads!=8)throw new IOException("线程数不在允许范围");
            if(maxMib!=128&&maxMib!=256)throw new IOException("工作集大小不在允许范围");
            JSONObject r=new JSONObject().put("schema",1).put("suite",suite).put("device",Build.DEVICE).put("model",Build.MODEL).put("build",Build.DISPLAY).put("cpu",cpu).put("rounds",rounds).put("captured_at",System.currentTimeMillis()).put("execution_backend",rootMode?"root":"app").put("runs",new JSONArray());
            r.put("method","Same ML-DSA-87 reference and public test key; 1024-byte messages; complete signing, verification outside timing; six NIST ACVP KATs per executable; same message/randomness corpus per round; rotated backend order. Memory payload GB/s uses decimal 10^9; copy traffic is separately reported. Battery values are whole-device net energy, not CPU power.");
            report=r;JSONObject caps=invoke("libpulsememory.so",Arrays.asList("--probe"),20);r.put("capabilities",caps);
            JSONArray rows=r.getJSONArray("runs");
            if("crypto".equals(suite)){
                String[] names={"scalar","neon","sve","sve2"};
                for(int round=0;round<rounds&&!cancelled;round++)for(int index=0;index<4&&!cancelled;index++){
                    int slot=(round+(round%2==0?index:3-index))%4;String name=names[slot];
                    progress("第 "+(round+1)+" / "+rounds+" 轮 · "+name.toUpperCase(Locale.ROOT));
                    JSONObject item=invoke("libmldsa_"+name+".so",Arrays.asList("--cpu",""+cpu,"--signatures",""+signatures,"--round",""+round),120);
                    rows.put(item);verifyTranscript(rows,item);report=new JSONObject(r.toString());
                    if(!"unsupported".equals(item.optString("result"))&&!item.optBoolean("valid"))throw new IOException(item.has("affinity_stable")&&!item.optBoolean("affinity_stable")?"系统在测试中改变了绑核范围。请保持前台，或开启 Root 模式重试。":"签名测试未通过："+name+"，查看原始结果");
                    coolDown(1200);
                }
            }else if("memory".equals(suite)){
                for(int round=0;round<rounds&&!cancelled;round++)for(long bytes:new long[]{256*1024L,2*1024*1024L,16*1024*1024L,maxMib*1024*1024L})for(String mode:new String[]{"read","write","copy"}){
                    if(cancelled)break;
                    progress("第 "+(round+1)+" / "+rounds+" 轮 · "+(bytes>=1048576?bytes/1048576+" MiB":"256 KiB")+" · "+mode);
                    JSONObject item=invoke("libpulsememory.so",Arrays.asList("--cpu",""+cpu,"--threads",""+threads,"--bytes",""+bytes,"--seconds","1","--mode",mode),40).put("round",round);
                    rows.put(item);report=new JSONObject(r.toString());
                    if(!item.optBoolean("valid"))throw new IOException("内存测试校验失败或被停止");
                    coolDown(350);
                }
            }else{
                List<Long> capsKhz=frequencyCaps(cpu);r.put("caps_khz",new JSONArray(capsKhz));
                r.put("efficiency_note","Only scaling_max_freq is temporarily lowered within its original bounds. This is a ceiling sweep, not a fixed clock or thermal unlock. The independent native guard restores the original ceiling on exit, cancellation or process death. Energy ranking is unavailable during charging or unreadable battery telemetry.");
                for(int round=0;round<rounds&&!cancelled;round++)for(int k=0;k<capsKhz.size()&&!cancelled;k++){
                    long cap=capsKhz.get(round%2==0?k:capsKhz.size()-1-k);
                    progress("第 "+(round+1)+" / "+rounds+" 轮 · 上限 "+String.format(Locale.US,"%.2f",cap/1e6)+" GHz");
                    JSONObject item=invoke("libmldsa_neon.so",Arrays.asList("--cpu",""+cpu,"--signatures","8192","--round",""+round,"--cap",""+cap),120).put("cap_khz",cap);
                    rows.put(item);verifyTranscript(rows,item);report=new JSONObject(r.toString());
                    if("unsupported".equals(item.optString("result")))continue;
                    if(!item.optBoolean("valid")||!item.optBoolean("cap_restored"))throw new IOException("频率扫描未完成，查看恢复状态和原始结果");
                    coolDown(2000);
                }
            }
            r.put("cancelled",cancelled).put("finished_at",System.currentTimeMillis());
            r.put("summary",summarize(r));report=r;save(r);
            progress(cancelled?"已取消 · 已完成项目保留":"实验完成 · 结果已保存在本机");
        }catch(Exception e){
            progress(cancelled?"已取消 · 已执行原生收尾":"实验停止："+e.getMessage());
            try{JSONObject r=report==null?new JSONObject():report;r.put("error",e.getMessage()).put("cancelled",cancelled).put("summary",summarize(r));report=r;save(r);}catch(Exception ignored){}
        }finally{
            running=false;nativePid=0;process=null;if(wake!=null&&wake.isHeld())wake.release();
            handler.post(()->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});
        }
    }
    private JSONObject invoke(String binary,List<String> args,int timeoutSeconds)throws Exception{
        if(cancelled)throw new InterruptedException("用户取消");
        File file=new File(getApplicationInfo().nativeLibraryDir,binary);if(!file.isFile())throw new IOException("安装包缺少 "+binary);
        List<String> command=new ArrayList<>();command.add(file.getAbsolutePath());command.addAll(args);
        ProcessBuilder builder;
        if(rootMode){StringBuilder shell=new StringBuilder("exec ");for(String s:command)shell.append(q(s)).append(' ');builder=Build.DEVICE.equals("hongkong")?new ProcessBuilder("su","-M","-c",shell.toString()):new ProcessBuilder("su","-c",shell.toString());}
        else builder=new ProcessBuilder(command);
        nativePid=0;process=builder.redirectErrorStream(true).start();process.getOutputStream().close();final java.lang.Process child=process;
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        final boolean[] timedOut={false};timer.schedule(()->{timedOut[0]=true;signalStop();child.destroy();},timeoutSeconds,TimeUnit.SECONDS);
        JSONObject result=null;StringBuilder errors=new StringBuilder();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))){String line;
            while((line=in.readLine())!=null){
                if(line.startsWith("{")){try{JSONObject item=new JSONObject(line);if("started".equals(item.optString("event"))){nativePid=item.optLong("pid");if(cancelled)signalStop();}else if("result".equals(item.optString("event")))result=item;}catch(JSONException e){if(errors.length()<16000)errors.append(line).append('\n');}}
                else if(errors.length()<16000)errors.append(line).append('\n');
            }
        }finally{timer.shutdownNow();}
        int exit=child.waitFor();process=null;nativePid=0;
        if(result==null)throw new IOException(timedOut[0]?"原生测试超时":cancelled?"已取消":errors.length()==0?"原生测试退出码 "+exit:errors.toString().trim());
        result.put("exit_code",exit);return result;
    }
    private List<Long> frequencyCaps(int cpu)throws Exception{
        String base="/sys/devices/system/cpu/cpu"+cpu+"/cpufreq/";
        String table=new String(Files.readAllBytes(new File(base+"scaling_available_frequencies").toPath()),StandardCharsets.UTF_8);
        File boost=new File(base+"scaling_boost_frequencies");if(boost.isFile())table+=" "+new String(Files.readAllBytes(boost.toPath()),StandardCharsets.UTF_8);
        long original=Long.parseLong(new String(Files.readAllBytes(new File(base+"scaling_max_freq").toPath()),StandardCharsets.UTF_8).trim());
        long minimum=Long.parseLong(new String(Files.readAllBytes(new File(base+"scaling_min_freq").toPath()),StandardCharsets.UTF_8).trim());
        TreeSet<Long> all=new TreeSet<>();for(String s:table.trim().split("\\s+")){long f=Long.parseLong(s);if(f>=minimum&&f<=original)all.add(f);}
        if(all.isEmpty())throw new IOException("没有可用的频率表");
        TreeSet<Long> chosen=new TreeSet<>();for(double fraction:new double[]{.4,.55,.7,.85,1.0}){Long value=all.floor((long)(all.last()*fraction));chosen.add(value==null?all.first():value);}
        return new ArrayList<>(chosen);
    }
    private void verifyTranscript(JSONArray rows,JSONObject item)throws Exception{
        if(!item.optBoolean("valid"))return;
        for(int i=0;i<rows.length()-1;i++){
            JSONObject prior=rows.getJSONObject(i);if(!prior.optBoolean("valid")||prior.optInt("round")!=item.optInt("round"))continue;
            if(!prior.optString("transcript_sha3shake256").equals(item.optString("transcript_sha3shake256"))){item.put("valid",false).put("cross_backend_equal",false);throw new IOException("同轮签名字节不一致，已停止比较");}
        }item.put("cross_backend_equal",true);
    }
    static JSONArray summarize(JSONObject r)throws JSONException{
        JSONArray runs=r.optJSONArray("runs"),summary=new JSONArray();if(runs==null)return summary;
        LinkedHashMap<String,List<JSONObject>> groups=new LinkedHashMap<>();String suite=r.optString("suite");
        for(int i=0;i<runs.length();i++){JSONObject item=runs.getJSONObject(i);if(!item.optBoolean("valid"))continue;
            String key="memory".equals(suite)?item.optString("mode")+":"+item.optLong("working_set_bytes"):"efficiency".equals(suite)?""+item.optLong("cap_khz"):item.optString("backend");
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(item);
        }
        double baseline=Double.NaN;
        if(groups.containsKey("scalar"))baseline=median(groups.get("scalar"),"signatures_per_second");
        for(Map.Entry<String,List<JSONObject>> e:groups.entrySet()){
            List<JSONObject> items=e.getValue();JSONObject first=items.get(0),s=new JSONObject().put("key",e.getKey()).put("n",items.size());
            if("memory".equals(suite)){s.put("mode",first.getString("mode")).put("working_set_bytes",first.getLong("working_set_bytes")).put("payload_gbps",median(items,"payload_gbps")).put("logical_traffic_gbps",median(items,"logical_traffic_gbps"));}
            else{
                double ops=median(items,"signatures_per_second");s.put("backend",first.optString("backend")).put("signatures_per_second",ops).put("median_us",median(items,"median_us")).put("p95_us",median(items,"p95_us"));
                if(Double.isFinite(baseline)&&baseline>0)s.put("speedup",ops/baseline);
                if(first.has("cap_khz"))s.put("cap_khz",first.getLong("cap_khz"));
                double hz=medianTelemetry(items,"sampled_mean_mhz");if(Double.isFinite(hz))s.put("sampled_mean_mhz",hz);
                List<Double> efficiency=new ArrayList<>(),perMhz=new ArrayList<>();
                for(JSONObject item:items){JSONObject t=item.optJSONObject("telemetry");if(t==null)continue;double effective=t.optDouble("effective_mhz"),sampled=t.optDouble("sampled_mean_mhz");double f=Double.isFinite(effective)&&effective>0?effective:sampled;if(Double.isFinite(f)&&f>0)perMhz.add(item.getDouble("signatures_per_second")/f);double joules=t.optDouble("battery_net_joules");if(t.optBoolean("energy_comparable")&&joules>0)efficiency.add(item.getDouble("signatures")/joules);}
                if(!perMhz.isEmpty())s.put("signatures_per_second_per_mhz",medianNumbers(perMhz));
                if(efficiency.size()==items.size())s.put("signatures_per_joule",medianNumbers(efficiency));
            }summary.put(s);
        }return summary;
    }
    private static double median(List<JSONObject> a,String k){List<Double> n=new ArrayList<>();for(JSONObject o:a){double v=o.optDouble(k);if(Double.isFinite(v))n.add(v);}return n.isEmpty()?Double.NaN:medianNumbers(n);}
    private static double medianTelemetry(List<JSONObject> a,String k){List<JSONObject> t=new ArrayList<>();for(JSONObject o:a)if(o.optJSONObject("telemetry")!=null)t.add(o.optJSONObject("telemetry"));return median(t,k);}
    private static double medianNumbers(List<Double> n){Collections.sort(n);int k=n.size()/2;return n.size()%2==0?(n.get(k-1)+n.get(k))/2:n.get(k);}
    private void coolDown(long ms)throws InterruptedException{long end=SystemClock.elapsedRealtime()+ms;while(!cancelled&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);}
    private void save(JSONObject r)throws Exception{File dir=new File(getFilesDir(),"research");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("不能创建报告目录");File target=new File(dir,System.currentTimeMillis()+"-"+r.optString("suite","lab")+".json");try(FileOutputStream out=new FileOutputStream(target)){out.write(r.toString(2).getBytes(StandardCharsets.UTF_8));}}
    private void signalStop(){
        long pid=nativePid;try{if(pid>1){java.lang.Process p=rootMode?new ProcessBuilder("su","-c","kill -TERM "+pid).start():new ProcessBuilder("/system/bin/kill","-TERM",""+pid).start();p.waitFor(3,TimeUnit.SECONDS);}else if(process!=null)process.destroy();}catch(Exception ignored){}
    }
    private void progress(String text){status=text;getSystemService(NotificationManager.class).notify(43,notification(text));}
    private Notification notification(String text){PendingIntent open=PendingIntent.getActivity(this,43,new Intent(this,ResearchActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE),stop=PendingIntent.getService(this,44,new Intent(this,ResearchService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,"pulse_research").setSmallIcon(R.drawable.ic_notification).setContentTitle("Pulse · 向量与内存实验").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"停止实验",stop).build()).build();}
    private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
    @Override public void onDestroy(){if(running){cancelled=true;new Thread(this::signalStop,"lab-destroy").start();}if(wake!=null&&wake.isHeld())wake.release();worker.shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
