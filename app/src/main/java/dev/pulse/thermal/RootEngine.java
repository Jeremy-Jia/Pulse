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
    ThermalProfile profile;
    volatile boolean rootGranted=false;
    private File stage;
    RootEngine(Context c) { context=c.getApplicationContext(); }
    static String q(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    static String root(String command) throws Exception {
        java.lang.Process p=(android.os.Build.DEVICE.equals("hongkong")?new ProcessBuilder("su","-M","-c",command):new ProcessBuilder("su","-c",command)).redirectErrorStream(true).start();
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
        rootGranted=true;
        profile=ThermalProfile.forDevice(root("getprop ro.product.device").trim());
        for(int group:profile.groups) {
            verifyTable(group,"scaling_available_frequencies",profile.normalTable(group));
            if(profile.boostTable(group).length>0) verifyTable(group,"scaling_boost_frequencies",profile.boostTable(group));
        }
        if(!profile.runtimeMount && root("getprop persist.vendor.aes_whitebox.encrypt").trim().equals("true")) throw new IllegalStateException("此固件配置编码未适配");
        stage=new File(context.getFilesDir(),"stage"); if(!stage.isDirectory() && !stage.mkdirs()) throw new IllegalStateException("无法创建本地准备目录");
        File binary=new File(stage,"firmware.bin"), original=new File(stage,"original.bin"), script=new File(stage,"runner.sh");
        int uid=Process.myUid();
        root("cp /vendor/bin/mi_thermald "+q(binary.getPath())+" && cp /odm/etc/thermal-normal.conf "+q(original.getPath())+
            " && chown "+uid+":"+uid+" "+q(binary.getPath())+" "+q(original.getPath())+" && chmod 600 "+q(binary.getPath())+" "+q(original.getPath()));
        File key=new File(stage,"private.key"), copy=new File(stage,"vendor-copy.so");
        byte[] recovered=null;
        try {
            byte[] bytes=Files.readAllBytes(binary.toPath()), ciphertext=Files.readAllBytes(original.toPath());
            if(!ConfigCodec.sha(bytes).equals(profile.binarySha)||!ConfigCodec.sha(ciphertext).equals(profile.cipherSha)) throw new IllegalStateException("此系统温控版本尚未验证，拒绝套用旧配置");
            if(profile.runtimeMount) {
                String helper=new File(context.getApplicationInfo().nativeLibraryDir,"libpulsecodec.so").getPath();
                root(q(helper)+" "+q(binary.getPath())+" "+q(copy.getPath())+" "+q(key.getPath())+" && chown "+uid+":"+uid+" "+q(key.getPath()));
                recovered=Files.readAllBytes(key.toPath());
            }
            codec=new ConfigCodec(profile,bytes,ciphertext,recovered);
        } finally {
            if(recovered!=null) java.util.Arrays.fill(recovered,(byte)0);
            root("rm -f "+q(key.getPath())+" "+q(copy.getPath())+" "+q(binary.getPath()));
        }
        try(InputStream in=context.getAssets().open("thermal/runner.sh")) { Files.write(script.toPath(),readAll(in)); }
        String scriptSha=ConfigCodec.sha(Files.readAllBytes(script.toPath()));
        root("mkdir -p "+BASE+"/sessions && chmod 700 "+BASE+" "+BASE+"/sessions; " +
            "if [ -f "+q(BASE+"/current")+" ]; then id=$(cat "+q(BASE+"/current")+"); state=$(cat "+BASE+"/sessions/$id/phase 2>/dev/null); " +
            "case \"$state\" in active|paused|starting|restoring|recovery_failed) [ \"$(sha256sum "+RUNNER+" | cut -d ' ' -f 1)\" = "+q(scriptSha)+" ] || { echo ERROR RESTORE_BEFORE_UPDATE; exit 1; };; esac; fi; " +
            "cp "+q(script.getPath())+" "+RUNNER+".new && chown root:root "+RUNNER+".new && chmod 600 "+RUNNER+".new && mv "+RUNNER+".new "+RUNNER+" && /system/bin/sh -n "+RUNNER);
    }
    Snapshot status() throws Exception { return new Snapshot(root("/system/bin/sh "+RUNNER+" status")); }
    void start(Policy p) throws Exception {
        p.validate(); if(codec==null) connect();
        if(!p.profile.device.equals(profile.device)) throw new IllegalStateException("策略设备与连接设备不一致");
        byte[] plain=codec.make(p.mode,p.cap0,p.cap3,p.cap6,p.boostPercent,p.curve0,p.curve3,p.curve6), encrypted=codec.encode(plain);
        String id=Long.toString(System.currentTimeMillis());
        File trial=new File(stage,"trial.bin"), policy=new File(stage,"policy.env");
        Files.write(trial.toPath(),encrypted);
        String env="battery_limit="+p.batteryC*10+"\nbody_limit="+p.bodyC*1000+"\ncpu_limit="+(p.cpuGuard?p.cpuC*1000:0)+
            "\nduration="+(p.unlimited?0:p.minutes*60)+"\nresume_boot="+(p.unlimited&&p.resume?1:0)+"\ncap0="+p.cap0+"\ncap3="+p.cap3+"\ncap6="+p.cap6+
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
    /** Keep an already-running 15 session's boot hook in sync with the UI toggle. */
    boolean updateBootResume(boolean enabled) throws Exception {
        if(profile==null) throw new IllegalStateException("请先连接 Root");
        if(profile.runtimeMount) return false;
        String id=root("cat "+q(BASE+"/current")+" 2>/dev/null").trim();
        if(id.isEmpty()) return false;
        String dir=BASE+"/sessions/"+id;
        String phase=root("cat "+q(dir+"/phase")+" 2>/dev/null").trim();
        if(!phase.equals("active")&&!phase.equals("paused")) return false;
        root("sed -i 's/^resume_boot=.*/resume_boot="+(enabled?1:0)+"/' "+q(dir+"/policy.env")+
            " && chmod 600 "+q(dir+"/policy.env")+" && chown root:root "+q(dir+"/policy.env"));
        String hook="/data/adb/service.d/70-pulse-thermal.sh";
        if(enabled) root("mkdir -p /data/adb/service.d && printf '#!/system/bin/sh\\n/system/bin/sh /data/adb/pulse-thermal/runner.sh boot >> /data/adb/pulse-thermal/boot.log 2>&1\\n' > "+q(hook)+
            " && chmod 700 "+q(hook)+" && chown root:root "+q(hook));
        else root("rm -f "+q(hook));
        return true;
    }
    String export() throws Exception { return root("/system/bin/sh "+RUNNER+" export"); }
    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int n;
        while((n=input.read(buffer))!=-1) out.write(buffer,0,n); return out.toByteArray();
    }
    private static void verifyTable(int policy,String node,int[] expected) throws Exception {
        String[] parts=root("cat /sys/devices/system/cpu/cpufreq/policy"+policy+"/"+node).trim().split("\\s+");
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
        if(code.contains("BOOST_UNAVAILABLE")) return "所选 Boost 档当前不可用；请启用系统 Boost 或选择普通频率档";
        if(code.contains("RESTORE_BEFORE_UPDATE")) return "请先恢复原厂策略，再更新控制程序";
        if(code.contains("SESSION_ALREADY_ACTIVE")) return "已有策略运行，请先恢复原厂再修改";
        if(code.contains("Permission denied") || code.contains("denied")) return "Root 被拒绝；请在 KernelSU 授权 Pulse";
        return code.length()>300?code.substring(0,300):code;
    }
}
