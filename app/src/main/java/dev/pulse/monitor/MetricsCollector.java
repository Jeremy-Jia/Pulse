package dev.pulse.monitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.pulse.monitor.MetricsSnapshot.Metric;

/** Read-only device telemetry. collect() must run on a background thread. */
public final class MetricsCollector implements AutoCloseable {
    private final Context context;
    private final RootReader root = new RootReader();
    private final Map<String, String> sensors = new LinkedHashMap<>();
    private final Map<String, long[]> previousCpu = new LinkedHashMap<>();
    private final FrameRateReader frames = new FrameRateReader();
    private final int cpuCount;
    private boolean discovered;
    private boolean frameRateEnabled = true;

    public MetricsCollector(Context context) {
        this.context = context.getApplicationContext();
        cpuCount = Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    public String getRootStatus() { return root.status(); }
    public void setFrameRateEnabled(boolean enabled) { frameRateEnabled = enabled; }

    public static List<Metric> catalog() {
        List<Metric> out = new ArrayList<>();
        def(out,"cpu_usage","CPU 使用率","CPU","%","/proc/stat 两次采样的忙碌时间差");
        def(out,"cpu_temp_avg","CPU 平均温度","CPU","°C","已识别 CPU 温度传感器的算术平均");
        def(out,"cpu_temp_max","CPU 最高温度","CPU","°C","已识别 CPU 温度传感器的最大值");
        for (int i=0;i<8;i++) {
            def(out,"cpu_freq_"+i,"CPU "+i+" 频率","CPU","MHz","cpufreq/scaling_cur_freq；共享策略的核可能同频");
            def(out,"cpu_usage_"+i,"CPU "+i+" 使用率","CPU","%","/proc/stat 核心时间差");
            def(out,"cpu_temp_"+i,"CPU "+i+" 核温","CPU","°C","未验证传感器与逻辑核映射，请使用单独的温度传感器");
            def(out,"cpu_max_freq_"+i,"CPU "+i+" 最高频率","CPU","MHz","cpufreq/cpuinfo_max_freq；驱动报告的硬件上限");
            def(out,"cpu_cluster_"+i,"CPU "+i+" 核心类型","CPU","","cpufreq 共享策略和最高频率；仅已验证机型标注架构角色");
        }
        def(out,"gpu_usage","GPU 使用率","GPU","%","Qualcomm KGSL gpubusy 的忙碌/总时间");
        def(out,"gpu_freq","GPU 频率","GPU","MHz","Qualcomm KGSL gpuclk");
        def(out,"gpu_temp","GPU 温度","GPU","°C","Qualcomm KGSL temp 或具名 GPU 热传感器");
        def(out,"gpu_temp_max","GPU 最高温度","GPU","°C","具名 GPU 温度传感器的最大值");
        def(out,"mem_available","可用内存","内存","GB","MemAvailable；含可回收缓存");
        def(out,"mem_used","已用内存","内存","GB","MemTotal − MemAvailable");
        def(out,"mem_total","物理内存","内存","GB","内核可见 MemTotal");
        def(out,"mem_used_percent","内存使用率","内存","%","(MemTotal − MemAvailable) / MemTotal");
        def(out,"swap_used","已用交换空间","内存","GB","SwapTotal − SwapFree，通常包含 zRAM");
        def(out,"battery_current","电池电流","电池","mA","电池侧 current_now；保留驱动正负号");
        def(out,"battery_voltage","电池电压","电池","V","电池侧 voltage_now");
        def(out,"battery_power","电池净功率","电池","W","电池电压 × 电流的绝对值；非 SoC 功耗或充电器输入功率");
        def(out,"battery_temp","电池温度","电池","°C","电池传感器");
        def(out,"battery_level","剩余电量","电池","%","电池电量百分比");
        def(out,"battery_status","充电状态","电池","","Android 电池状态");
        def(out,"battery_charge_counter","电池剩余容量","电池","mAh","charge_counter；电量计估计，不是设计容量");
        def(out,"battery_cycles","电池循环次数","电池","次","power_supply/battery/cycle_count");
        def(out,"soc_power","SoC 功耗","设备","W","尚未验证 SoC 独立电源轨计量，不能用电池功率代替");
        def(out,"fps","前台帧率","屏幕","fps","前台图层的实际呈现时间；不以屏幕 Hz 代替");
        def(out,"layer_fps","图层更新率","屏幕","fps","前台图层 frameReady 时间；不代表实际呈现或游戏 FPS");
        def(out,"refresh_rate","屏幕刷新率","屏幕","Hz","Display.getRefreshRate；不是应用帧率");
        def(out,"root_status","Root 状态","设备","","KernelSU / su 授权状态");
        def(out,"sample_duration","采样耗时","设备","ms","本次读取耗时");
        def(out,"sample_core_ms","核心读取耗时","设备","ms","CPU、内存、电池和 GPU 原始数据的读取耗时");
        def(out,"sample_thermal_ms","温度读取耗时","设备","ms","具名热传感器的读取和处理耗时");
        def(out,"sample_frame_ms","帧率读取耗时","设备","ms","前台图层发现与帧时间读取耗时");
        def(out,"sample_platform_ms","系统接口耗时","设备","ms","数值转换、Android 电池回退接口和屏幕刷新率查询");
        return out;
    }

    private static void def(List<Metric> out,String id,String label,String group,String unit,String source) {
        out.add(new Metric(id,label,group,"—",unit,source,false));
    }

    public synchronized MetricsSnapshot collect() {
        long start = SystemClock.elapsedRealtime();
        Map<String,Metric> values = new LinkedHashMap<>();
        for (Metric m : catalog()) values.put(m.id,m);
        boolean hasRoot = root.ensure();
        if (hasRoot && !discovered) discover();
        Map<String,String> raw = new LinkedHashMap<>();
        if (hasRoot) {
            String batch = root.read(buildReadCommand(), 4000);
            if (batch != null) raw = parseSections(batch);
        }
        if (raw.isEmpty()) {
            raw.put("stat",readLocal("/proc/stat"));
            raw.put("meminfo",readLocal("/proc/meminfo"));
        }
        put(values,"sample_core_ms",SystemClock.elapsedRealtime()-start,0);
        long platformStart=SystemClock.elapsedRealtime();
        cpu(values,raw);
        memory(values,raw.get("meminfo"));
        battery(values,raw);
        double gpuHz = number(raw.get("gpu_freq"));
        put(values,"gpu_freq",gpuHz/1e6,0);
        String busy = raw.get("gpu_busy");
        if (busy != null) {
            String[] parts=busy.trim().split("\\s+");
            if(parts.length>=2) {
                double a=number(parts[0]), b=number(parts[1]);
                if (a>=0 && b>0 && a<=b) put(values,"gpu_usage",a*100.0/b,1);
            }
        }
        try {
            WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
            put(values,"refresh_rate",wm.getDefaultDisplay().getRefreshRate(),0);
        } catch (Exception ignored) { }
        put(values,"sample_platform_ms",SystemClock.elapsedRealtime()-platformStart,0);
        long thermalStart=SystemClock.elapsedRealtime();
        if (hasRoot && !sensors.isEmpty()) {
            String thermal=root.read(buildThermalCommand(),2000);
            if(thermal!=null)raw.putAll(parseSections(thermal));
        }
        temperatures(values,raw);
        put(values,"sample_thermal_ms",SystemClock.elapsedRealtime()-thermalStart,0);
        long frameStart=SystemClock.elapsedRealtime();
        if (hasRoot && frameRateEnabled) frames.collect(root,values);
        put(values,"sample_frame_ms",SystemClock.elapsedRealtime()-frameStart,0);
        text(values,"root_status",root.status(),true,root.isRoot()?"持久 su 会话，仅执行读取":"基础指标仍可使用");
        put(values,"sample_duration",SystemClock.elapsedRealtime()-start,0);
        return new MetricsSnapshot(System.currentTimeMillis(),new ArrayList<>(values.values()),
                root.isRoot(),cpuCount,root.status());
    }

    private void discover() {
        String command="for d in /sys/class/thermal/thermal_zone*; do " +
                "if [ -r \"$d/type\" ]; then IFS= read -r t < \"$d/type\"; " +
                "print -r -- \"$d|$t\"; fi; done";
        String data=root.read(command,4000);
        if(data==null)return;
        sensors.clear();
        for(String line:data.split("\\n")){
            String[] p=line.trim().split("\\|",2);
            if(p.length==2 && p[0].matches("/sys/class/thermal/thermal_zone[0-9]+") &&
                    p[1].matches("[A-Za-z0-9_.:+-]{1,100}") &&
                    !p[1].matches("(?i).*(?:bcl|ibat|vbat|socd).*")) sensors.put(p[0],p[1]);
        }
        discovered=!sensors.isEmpty();
    }

    private String buildReadCommand() {
        StringBuilder b=new StringBuilder();
        b.append("echo '@@stat'; cat /proc/stat; echo '@@meminfo'; cat /proc/meminfo;\n");
        for(int i=0;i<cpuCount;i++) read(b,"freq_"+i,"/sys/devices/system/cpu/cpu"+i+"/cpufreq/scaling_cur_freq");
        for(int i=0;i<cpuCount;i++) {
            read(b,"max_freq_"+i,"/sys/devices/system/cpu/cpu"+i+"/cpufreq/cpuinfo_max_freq");
            read(b,"related_"+i,"/sys/devices/system/cpu/cpu"+i+"/cpufreq/related_cpus");
        }
        String base="/sys/class/power_supply/battery/";
        for(String field:new String[]{"current_now","voltage_now","temp","capacity","status","charge_counter","cycle_count"})
            read(b,"battery_"+field,base+field);
        read(b,"gpu_freq","/sys/class/kgsl/kgsl-3d0/gpuclk");
        read(b,"gpu_busy","/sys/class/kgsl/kgsl-3d0/gpubusy");
        read(b,"gpu_temp","/sys/class/kgsl/kgsl-3d0/temp");
        return b.toString();
    }

    private String buildThermalCommand() {
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,String> sensor:sensors.entrySet()) read(b,"thermal:"+sensor.getKey(),sensor.getKey()+"/temp");
        return b.toString();
    }

    private static void read(StringBuilder b,String key,String path) {
        b.append("echo '@@").append(key).append("'; ");
        // Android's printf is an external binary. mksh print is a builtin;
        // -r and -- preserve backslashes and leading dashes in arbitrary values.
        b.append("if [ -r '").append(path).append("' ]; then v=''; IFS= read -r v < '").append(path)
                .append("'; print -r -- \"$v\"; fi;\n");
    }

    static Map<String,String> parseSections(String data) {
        Map<String,String> map=new LinkedHashMap<>();
        String key=null;
        StringBuilder value=new StringBuilder();
        for(String line:data.split("\\n")) {
            if(line.startsWith("@@")) {
                if(key!=null)map.put(key,value.toString().trim());
                key=line.substring(2).trim(); value.setLength(0);
            } else value.append(line).append('\n');
        }
        if(key!=null)map.put(key,value.toString().trim());
        return map;
    }

    private void cpu(Map<String,Metric> values,Map<String,String> raw) {
        String stat=raw.get("stat");
        if(stat!=null) for(String line:stat.split("\\n")) {
            if(!line.matches("cpu(?:[0-9]+)?\\s+.*"))continue;
            String[] p=line.trim().split("\\s+");
            if(p.length<5)continue;
            try {
                long total=0;
                // guest / guest_nice are already included in user / nice.
                for(int i=1;i<Math.min(p.length,9);i++)total+=Long.parseLong(p[i]);
                long idle=Long.parseLong(p[4])+(p.length>5?Long.parseLong(p[5]):0);
                long[] prev=previousCpu.put(p[0],new long[]{total,idle});
                if(prev!=null && total>prev[0]) {
                    double usage=100.0*(1.0-(idle-prev[1])/(double)(total-prev[0]));
                    String id=p[0].equals("cpu")?"cpu_usage":"cpu_usage_"+p[0].substring(3);
                    if(!values.containsKey(id))values.put(id,new Metric(id,p[0]+" 使用率","CPU","—","%","/proc/stat",false));
                    put(values,id,Math.max(0,Math.min(100,usage)),1);
                }
            } catch(NumberFormatException ignored) { }
        }
        for(int i=0;i<cpuCount;i++) {
            String id="cpu_freq_"+i;
            if(!values.containsKey(id))values.put(id,new Metric(id,"CPU "+i+" 频率","CPU","—","MHz","cpufreq/scaling_cur_freq",false));
            put(values,id,number(raw.get("freq_"+i))/1000.0,0);
            double max=number(raw.get("max_freq_"+i));
            put(values,"cpu_max_freq_"+i,max/1000.0,0);
            String related=raw.get("related_"+i);
            if(related!=null && !related.isEmpty() && Double.isFinite(max)) {
                String role="频率簇 "+related.trim().split("\\s+")[0];
                // Xiaomi 15's six Oryon performance + two Oryon prime cores are
                // only named when both the device and its observed policy match.
                if(Build.DEVICE.equals("dada") && cpuCount==8) {
                    if(related.trim().equals("0 1 2 3 4 5") && max>3_400_000 && max<3_600_000)role="性能核";
                    if(related.trim().equals("6 7") && max>4_200_000 && max<4_400_000)role="超大核";
                }
                text(values,"cpu_cluster_"+i,role,true,"共享策略核心 "+related+" · 最高 "+format(max/1e6,2)+" GHz");
                label(values,id,"CPU "+i+" · "+role);
                label(values,"cpu_usage_"+i,"CPU "+i+" · "+role+"负载");
            }
        }
    }

    private void memory(Map<String,Metric> values,String data) {
        Map<String,Double> fields=new LinkedHashMap<>();
        if(data!=null)for(String line:data.split("\\n")) {
            String[] p=line.split(":",2);
            if(p.length==2)fields.put(p[0].trim(),number(p[1].trim().split("\\s+")[0])*1024);
        }
        double total=get(fields,"MemTotal"), available=get(fields,"MemAvailable");
        if(!(total>0) || Double.isNaN(available)) {
            try {
                ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();
                ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);
                total=m.totalMem; available=m.availMem;
            } catch(Exception ignored) { }
        }
        if(total>0 && available>=0 && available<=total) {
            put(values,"mem_total",total/1e9,2);
            put(values,"mem_available",available/1e9,2);
            put(values,"mem_used",(total-available)/1e9,2);
            put(values,"mem_used_percent",(total-available)*100.0/total,1);
        }
        double swapTotal=get(fields,"SwapTotal"), swapFree=get(fields,"SwapFree");
        if(swapTotal>=swapFree && swapFree>=0)put(values,"swap_used",(swapTotal-swapFree)/1e9,2);
    }

    private void battery(Map<String,Metric> values,Map<String,String> raw) {
        double current=number(raw.get("battery_current_now"))/1000.0;
        double voltage=number(raw.get("battery_voltage_now"))/1e6;
        double temp=number(raw.get("battery_temp"))/10.0;
        double level=number(raw.get("battery_capacity"));
        String state=raw.get("battery_status");
        if(Double.isNaN(voltage)||Double.isNaN(temp)||Double.isNaN(level)||Double.isNaN(current)||state==null||state.isEmpty()) try {
            Intent info=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(info!=null) {
                if(Double.isNaN(voltage)) { int v=info.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1); if(v>=0)voltage=v/1000.0; }
                if(Double.isNaN(temp)) { int t=info.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE); if(t!=Integer.MIN_VALUE)temp=t/10.0; }
                if(Double.isNaN(level)) { int l=info.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=info.getIntExtra(BatteryManager.EXTRA_SCALE,-1); if(l>=0&&scale>0)level=l*100.0/scale; }
                if(state==null||state.isEmpty()) {
                    int s=info.getIntExtra(BatteryManager.EXTRA_STATUS,1);
                    state=s==2?"Charging":s==3?"Discharging":s==4?"Not charging":s==5?"Full":"Unknown";
                }
            }
            if(Double.isNaN(current)) {
                BatteryManager bm=(BatteryManager)context.getSystemService(Context.BATTERY_SERVICE);
                long v=bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if(v!=Long.MIN_VALUE && v!=Integer.MIN_VALUE) current=v/1000.0;
            }
        } catch(Exception ignored) { }
        put(values,"battery_current",current,0);
        put(values,"battery_voltage",voltage,3);
        if(voltage>=0 && Double.isFinite(current))put(values,"battery_power",Math.abs(current)*voltage/1000.0,2);
        if(temp>=-30&&temp<=100)put(values,"battery_temp",temp,1);
        if(level>=0&&level<=100)put(values,"battery_level",level,0);
        double charge=number(raw.get("battery_charge_counter"));
        if(charge>=0)put(values,"battery_charge_counter",charge/1000.0,0);
        double cycles=number(raw.get("battery_cycle_count"));
        if(cycles>=0)put(values,"battery_cycles",cycles,0);
        if(state!=null) {
            String stateZh=state.equals("Charging")?"充电中":state.equals("Discharging")?"放电中":state.equals("Full")?"已充满":state.equals("Not charging")?"未充电":"未知";
            text(values,"battery_status",stateZh,true,"power_supply/battery/status · "+state);
        }
    }

    private void temperatures(Map<String,Metric> values,Map<String,String> raw) {
        List<Double> cpu=new ArrayList<>(),gpu=new ArrayList<>();
        for(Map.Entry<String,String> sensor:sensors.entrySet()) {
            String name=sensor.getValue();
            double degrees=number(raw.get("thermal:"+sensor.getKey()))/1000.0;
            boolean valid=Double.isFinite(degrees)&&degrees>=-40&&degrees<=180;
            String low=name.toLowerCase(Locale.ROOT);
            boolean cpuSensor=low.matches("cpu-[0-9]+-[0-9]+-[0-9]+") || low.matches("cpu[0-9]+") || low.startsWith("cpu-therm");
            boolean gpuSensor=low.startsWith("gpuss")||low.startsWith("gpu");
            String group=cpuSensor?"CPU 传感器":gpuSensor?"GPU 传感器":"温度传感器";
            String id="thermal_"+name.replaceAll("[^A-Za-z0-9]","_");
            values.put(id,new Metric(id,name,group,valid?format(degrees,1):"—","°C",
                    sensor.getKey()+"/temp · type="+name,valid,valid?degrees:Double.NaN));
            if(valid && cpuSensor)cpu.add(degrees);
            if(valid && gpuSensor)gpu.add(degrees);
        }
        if(!cpu.isEmpty()) {
            double sum=0,max=-100;
            for(double v:cpu){sum+=v;max=Math.max(max,v);}
            put(values,"cpu_temp_avg",sum/cpu.size(),1);
            put(values,"cpu_temp_max",max,1);
        }
        double kgsl=number(raw.get("gpu_temp"))/1000.0;
        if(kgsl>=-40&&kgsl<=180)put(values,"gpu_temp",kgsl,1);
        if(!gpu.isEmpty()) {
            double sum=0,max=-100;
            for(double v:gpu){sum+=v;max=Math.max(max,v);}
            if(Double.isNaN(kgsl))put(values,"gpu_temp",sum/gpu.size(),1);
            put(values,"gpu_temp_max",max,1);
        }
    }

    static void put(Map<String,Metric> values,String id,double value,int decimals) {
        Metric old=values.get(id);
        if(old==null||!Double.isFinite(value))return;
        values.put(id,new Metric(id,old.label,old.category,format(value,decimals),old.unit,old.source,true,value));
    }
    static void text(Map<String,Metric> values,String id,String value,boolean available,String source) {
        Metric old=values.get(id);
        if(old!=null)values.put(id,new Metric(id,old.label,old.category,value,old.unit,source,available));
    }
    private static void label(Map<String,Metric> values,String id,String label) {
        Metric old=values.get(id);
        if(old!=null)values.put(id,new Metric(old.id,label,old.category,old.value,old.unit,old.source,old.available,old.numericValue));
    }
    static String format(double value,int decimals) { return String.format(Locale.US,"%."+decimals+"f",value); }
    static double number(String value) { try { return Double.parseDouble(value.trim()); } catch(Exception e){return Double.NaN;} }
    private static double get(Map<String,Double> map,String key) { Double v=map.get(key);return v==null?Double.NaN:v; }
    private static String readLocal(String path) {
        try(BufferedReader in=new BufferedReader(new FileReader(path))) {
            StringBuilder out=new StringBuilder();String line;
            while((line=in.readLine())!=null && out.length()<65536)out.append(line).append('\n');
            return out.toString();
        } catch(Exception e){return "";}
    }
    @Override public synchronized void close() { frames.close(root); root.close(); }
}
