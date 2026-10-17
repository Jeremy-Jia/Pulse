package dev.pulse.monitor;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Bounded native experiments. Custom arguments are passed literally, never interpreted as shell. */
public final class BenchmarkService extends Service {
    public static final String RUN="dev.pulse.monitor.BENCH_RUN", CANCEL="dev.pulse.monitor.BENCH_CANCEL";
    public static volatile boolean running;
    public static volatile String status="准备就绪", lastOutput="";
    public static volatile JSONObject lastResult;
    public static volatile long startedAt;
    public static volatile String currentKind="";
    private volatile java.lang.Process process;
    private volatile long wrapperPid;
    private volatile boolean cancelled;
    private volatile String backend="app";
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(){super.onCreate();NotificationChannel channel=new NotificationChannel("pulse_bench","基准测试",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(channel);}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(CANCEL.equals(intent.getAction())){cancel();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        Notification n=notification("准备测试环境…");
        if(Build.VERSION.SDK_INT>=34)startForeground(42,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(42,n);
        running=true;cancelled=false;wrapperPid=0;lastOutput="";lastResult=null;startedAt=SystemClock.elapsedRealtime();
        currentKind=intent.getStringExtra("kind");if(currentKind==null)currentKind="probe";
        String kind=currentKind;
        int cpu=Math.max(0,Math.min(255,intent.getIntExtra("cpu",0))), seconds=Math.max(10,Math.min(120,intent.getIntExtra("seconds",10)));
        String path=intent.getStringExtra("path"),args=intent.getStringExtra("args"),rawEvents=intent.getStringExtra("raw_events");
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Pulse:Benchmark");
        wakeLock.acquire((2L*seconds+120)*1000);
        worker.execute(()->execute(kind,cpu,seconds,path,args,rawEvents));return START_NOT_STICKY;
    }
    private void execute(String kind,int cpu,int seconds,String customPath,String arguments,String rawEvents){
        ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
        try{
            String dir=getApplicationInfo().nativeLibraryDir;
            String helper=dir+"/libpulsebench.so";
            if(!new File(helper).isFile())throw new IOException("本安装包缺少 ARM64 测试组件");
            try(RootReader access=new RootReader()) {
                access.ensure(); backend=AdbBridge.available()?"adb":access.isRoot()?"root":"app";
            }
            StringBuilder command=new StringBuilder();
            if("custom".equals(kind)){
                File source=new File(customPath==null?"":customPath).getCanonicalFile();
                File base=new File(getFilesDir(),"cases").getCanonicalFile();
                if(!source.getPath().startsWith(base.getPath()+File.separator)||!source.isFile())throw new IOException("请先导入测试 ELF");
                if("app".equals(backend))throw new IOException("自定义 ELF 需要激活 ADB 模式或 Root；普通模式可运行内置测试");
                if("adb".equals(backend)) {
                    File shared=new File(getExternalFilesDir(null),"pulse-custom.elf");
                    try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(shared)) {
                        byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                    }
                    source=shared;
                }
                String staging="/data/local/tmp/pulse-bench-"+android.os.Process.myUid();
                command.append("mkdir -p ").append(q(staging)).append(" && chmod 700 ").append(q(staging)).append(" && cp ").append(q(source.getPath())).append(" ").append(q(staging+"/case.elf")).append(" && chmod 700 ").append(q(staging+"/case.elf")).append(" && ");
                customPath=staging+"/case.elf";
            }
            command.append("exec ").append(q(helper));
            if("probe".equals(kind))command.append(" --probe --cpu ").append(cpu);
            else{
                if(!kind.matches("coremark|dhrystone|whetstone|custom"))throw new IOException("未知测试类型");
                command.append(" --cpu ").append(cpu).append(" --seconds ").append(seconds).append(" --kind ").append(kind);
                String test="custom".equals(kind)?customPath:dir+"/lib"+kind+".so";
                command.append(" --path ").append(q(test));
                if("custom".equals(kind)&&arguments!=null){for(String arg:arguments.split("\\r?\\n"))if(!arg.isEmpty()){if(arg.length()>2048)throw new IOException("参数过长");command.append(" --arg ").append(q(arg));}}
            }
            if(rawEvents!=null&&!rawEvents.trim().isEmpty()){
                String[] events=rawEvents.trim().split("[\\s,;]+");if(events.length>8)throw new IOException("最多添加 8 个原始 PMU 事件");
                for(String event:events){if(!event.matches("0[xX][0-9a-fA-F]{1,16}"))throw new IOException("原始事件需为十六进制，例如 0x08");command.append(" --raw-event ").append(event);}
            }
            status="probe".equals(kind)?"正在探测性能计数器…":"正在运行 "+kind+" · CPU "+cpu;
            notifyStatus();
            process="adb".equals(backend)?AdbBridge.start(command.toString()):
                    new ProcessBuilder("root".equals(backend)?"su":"/system/bin/sh","-c",command.toString()).redirectErrorStream(true).start();
            watchdog.schedule(()->{if(running){status="达到测试时限，正在停止";cancel();}},"probe".equals(kind)?20:2*seconds+90,TimeUnit.SECONDS);
            StringBuilder log=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=reader.readLine())!=null){
                    if(log.length()<2*1024*1024)log.append(line).append('\n');
                    if(line.startsWith("{")){try{JSONObject object=new JSONObject(line);
                        if("started".equals(object.optString("event"))){wrapperPid=object.optLong("pid",0);if(cancelled)signalStop();}
                        else if("phase".equals(object.optString("event"))){String phase=object.optString("name",object.optString("phase","measurement"));status=kind+" · "+("calibration".equals(phase)?"校准迭代数":"crc_validation".equals(phase)?"校验计算结果":"measurement".equals(phase)?"正式测量":phase);notifyStatus();}
                        else lastResult=object;
                    }catch(JSONException ignored){}}
                }
            }
            int exit=process.waitFor();lastOutput=log.toString();
            if(lastResult==null){JSONObject error=new JSONObject();error.put("result","error");error.put("detail",cancelled?"已取消":lastOutput.isEmpty()?"未获取结果，请检查运行模式或重新激活 ADB":lastOutput);error.put("exit_code",exit);lastResult=error;}
            lastResult.put("execution_backend",backend);
            lastResult.put("captured_at",System.currentTimeMillis());lastResult.put("requested_cpu",cpu);lastResult.put("requested_seconds",seconds);lastResult.put("requested_kind",kind);lastResult.put("device",Build.MODEL);lastResult.put("build",Build.DISPLAY);lastResult.put("cancelled",cancelled);
            File reports=new File(getFilesDir(),"benchmarks");reports.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(reports,System.currentTimeMillis()+"-"+kind+".json"))){out.write(lastResult.toString(2).getBytes(StandardCharsets.UTF_8));}
            status=cancelled?"测试已取消":exit==0?"测试完成":"测试结束 · 查看结果说明";
        }catch(Exception e){status="测试未完成："+e.getMessage();lastOutput=status;try{lastResult=new JSONObject().put("result","error").put("detail",status);}catch(JSONException ignored){}}
        finally{releaseWakeLock();watchdog.shutdownNow();running=false;wrapperPid=0;process=null;main.post(()->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}
    }
    private static String q(String value){return "'"+value.replace("'","'\\''")+"'";}
    private void signalStop(){long pid=wrapperPid;if(pid>1){try{if("adb".equals(backend))AdbBridge.signalPid(pid);else new ProcessBuilder("root".equals(backend)?"su":"/system/bin/sh","-c","kill -TERM "+pid).start().waitFor(3,TimeUnit.SECONDS);}catch(Exception ignored){}}else if(process!=null)process.destroy();}
    private void cancel(){cancelled=true;status="正在停止测试…";new Thread(this::signalStop,"pulse-bench-cancel").start();}
    private Notification notification(String line){PendingIntent open=PendingIntent.getActivity(this,10,new Intent(this,BenchActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);PendingIntent stop=PendingIntent.getService(this,11,new Intent(this,BenchmarkService.class).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,"pulse_bench").setSmallIcon(R.drawable.ic_notification).setContentTitle("脉冲 · 性能实验室").setContentText(line).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"停止测试",stop).build()).build();}
    private void notifyStatus(){getSystemService(NotificationManager.class).notify(42,notification(status));}
    private void releaseWakeLock(){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}
    @Override public void onDestroy(){releaseWakeLock();if(running)cancel();worker.shutdown();main.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
