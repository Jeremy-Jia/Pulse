package dev.pulse.thermal;

import android.content.Context;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

public final class RootEngine {
    static final String BASE="/data/adb/pulse-thermal", RUNNER=BASE+"/runner.sh";
    private final Context context;
    private ConfigCodec codec;
    private File stage;
    RootEngine(Context c) { context=c.getApplicationContext(); }
    static String q(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    static String root(String command) throws Exception {
        java.lang.Process p=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
        // Every operation is noninteractive. EOF also prevents a procfs race in
        // a shell pipeline from accidentally waiting for input from the app.
        p.getOutputStream().close();
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread reader=new Thread(() -> {
            try(InputStream in=p.getInputStream()) { byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) { if(output.size()<3_000_000) output.write(b,0,n); } } catch(Exception ignored) {}
        },"thermal-root-output");
        reader.setDaemon(true); reader.start();
        if(!p.waitFor(40,TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("Root 请求超时；请检查 KernelSU 授权"); }
        reader.join(2000);
        String text=new String(output.toByteArray(),StandardCharsets.UTF_8);
        if(p.exitValue()!=0) throw new IllegalStateException(text.trim().isEmpty()?"Root 被拒绝；请在 KernelSU 授权 Pulse":friendly(text));
        return text;
    }
    void connect() throws Exception {
        if(!root("id -u").trim().equals("0")) throw new IllegalStateException("需要 Root 权限");
        if(!root("getprop ro.product.device").trim().equals("dada")) throw new IllegalStateException("当前版本仅支持小米 15（dada）");
        verifyTable(0,ConfigCodec.TABLE0); verifyTable(6,ConfigCodec.TABLE6);
        if(root("getprop persist.vendor.aes_whitebox.encrypt").trim().equals("true")) throw new IllegalStateException("此固件配置编码未适配");
        stage=new File(context.getFilesDir(),"stage"); if(!stage.isDirectory() && !stage.mkdirs()) throw new IllegalStateException("无法创建本地准备目录");
        File binary=new File(stage,"firmware.bin"), original=new File(stage,"original.bin"), script=new File(stage,"runner.sh");
        int uid=Process.myUid();
        root("cp /vendor/bin/mi_thermald "+q(binary.getPath())+" && cp /odm/etc/thermal-normal.conf "+q(original.getPath())+
            " && chown "+uid+":"+uid+" "+q(binary.getPath())+" "+q(original.getPath())+" && chmod 600 "+q(binary.getPath())+" "+q(original.getPath()));
        try { codec=new ConfigCodec(Files.readAllBytes(binary.toPath()),Files.readAllBytes(original.toPath())); }
        finally { Files.deleteIfExists(binary.toPath()); }
        try(InputStream in=context.getAssets().open("thermal/runner.sh")) { Files.write(script.toPath(),readAll(in)); }
        String scriptSha=ConfigCodec.sha(Files.readAllBytes(script.toPath()));
        root("mkdir -p "+BASE+"/sessions && chmod 700 "+BASE+" "+BASE+"/sessions; " +
            "if [ -f "+q(BASE+"/current")+" ]; then id=$(cat "+q(BASE+"/current")+"); state=$(cat "+BASE+"/sessions/$id/phase 2>/dev/null); " +
            "case \"$state\" in active|starting|restoring|recovery_failed) [ \"$(sha256sum "+RUNNER+" | cut -d ' ' -f 1)\" = "+q(scriptSha)+" ] || { echo ERROR RESTORE_BEFORE_UPDATE; exit 1; };; esac; fi; " +
            "cp "+q(script.getPath())+" "+RUNNER+".new && chown root:root "+RUNNER+".new && chmod 600 "+RUNNER+".new && mv "+RUNNER+".new "+RUNNER+" && /system/bin/sh -n "+RUNNER);
    }
    Snapshot status() throws Exception { return new Snapshot(root("/system/bin/sh "+RUNNER+" status")); }
    void start(Policy p) throws Exception {
        p.validate(); if(codec==null) connect();
        byte[] plain=codec.make(p.mode,p.cap0,p.cap6,p.boostPercent,p.curve0,p.curve6), encrypted=codec.encode(plain);
        String id=Long.toString(System.currentTimeMillis());
        File trial=new File(stage,"trial.bin"), policy=new File(stage,"policy.env");
        Files.write(trial.toPath(),encrypted);
        String env="battery_limit="+p.batteryC*10+"\nbody_limit="+p.bodyC*1000+"\ncpu_limit="+(p.cpuGuard?p.cpuC*1000:0)+
            "\nduration="+(p.unlimited?0:p.minutes*60)+"\nresume_boot="+(p.unlimited&&p.resume?1:0)+"\ncap0="+p.cap0+"\ncap6="+p.cap6+
            "\nmode="+p.mode+"\nboost_percent="+p.boostPercent+"\nowner_uid="+Process.myUid()+"\ntrial_hash="+ConfigCodec.sha(plain)+"\ntrial_cipher_hash="+ConfigCodec.sha(encrypted)+"\n";
        Files.write(policy.toPath(),env.getBytes(StandardCharsets.UTF_8));
        String d=BASE+"/sessions/"+id;
        root("mkdir "+d+" && cp "+q(trial.getPath())+" "+d+"/trial.bin && cp "+q(new File(stage,"original.bin").getPath())+" "+d+
            "/original.bin && cp "+q(policy.getPath())+" "+d+"/policy.env && chown -R root:root "+d+" && chmod 700 "+d+
            " && chmod 600 "+d+"/* && /system/bin/sh "+RUNNER+" start "+id);
    }
    void stop() throws Exception {
        root("if [ -f "+BASE+"/current ]; then id=$(cat "+BASE+"/current); /system/bin/sh "+RUNNER+" restore \"$id\" MANUAL; fi");
    }
    String export() throws Exception { return root("/system/bin/sh "+RUNNER+" export"); }
    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
        while((n=input.read(buffer))!=-1) out.write(buffer,0,n); return out.toByteArray();
    }
    private static void verifyTable(int policy,int[] expected) throws Exception {
        String[] parts=root("cat /sys/devices/system/cpu/cpufreq/policy"+policy+"/scaling_available_frequencies").trim().split("\\s+");
        int[] actual=new int[parts.length]; for(int i=0;i<parts.length;i++) actual[i]=Integer.parseInt(parts[i]);
        java.util.Arrays.sort(actual);
        if(!java.util.Arrays.equals(actual,expected)) throw new IllegalStateException("CPU 频率表已变化，此内核尚未适配温控修改");
    }
    private static String friendly(String text) {
        String code=text.trim();
        if(code.contains("PREFLIGHT_BODY_LIMIT")) return "机身已达到所选停止温度，请冷却后重试";
        if(code.contains("PREFLIGHT_BATTERY_LIMIT")) return "电池已达到所选停止温度，请冷却后重试";
        if(code.contains("PREFLIGHT_CPU_LIMIT")) return "CPU 已达到所选停止温度，请冷却后重试";
        if(code.contains("EXISTING_OVERRIDE")) return "已有其他温控配置，请先恢复后再应用";
        if(code.contains("ACTIVE_PROFILE_MISMATCH") || code.contains("NORMAL_PROFILE_REQUIRED")) return "当前不是已适配的原厂普通温控场景，请先退出其他调优工具";
        if(code.contains("FIRMWARE_CHANGED")) return "系统版本已变化，拒绝套用旧配置";
        if(code.contains("RESTORE_BEFORE_UPDATE")) return "请先恢复原厂策略，再更新控制程序";
        if(code.contains("SESSION_ALREADY_ACTIVE")) return "已有策略运行，请先恢复原厂再修改";
        if(code.contains("Permission denied") || code.contains("denied")) return "Root 被拒绝；请在 KernelSU 授权 Pulse";
        return code.length()>300?code.substring(0,300):code;
    }
}
