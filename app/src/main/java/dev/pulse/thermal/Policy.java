package dev.pulse.thermal;

import android.content.Context;
import android.content.SharedPreferences;

public final class Policy {
    public final ThermalProfile profile;
    public Policy() { this(android.os.Build.DEVICE.equals("hongkong")?ThermalProfile.MI18:ThermalProfile.MI15); }
    public Policy(ThermalProfile p) { profile=p; cap0=p.maximum(0); cap6=p.maximum(6); cap3=p.runtimeMount?p.maximum(3):0; curve0=p.curve(0); curve6=p.curve(6); curve3=p.runtimeMount?p.curve(3):new int[0]; }
    public String mode = "fullmax";
    public int boostPercent = 25;
    public int[] curve0 = ConfigCodec.originalCurve(false), curve6 = ConfigCodec.originalCurve(true);
    public int batteryC = 45, bodyC = 43, cpuC = 100, minutes = 15;
    public boolean cpuGuard = false, unlimited = false, resume = false;
    public int cap0 = 3532800, cap6 = 4320000;
    public int cap3; public int[] curve3;
    public int cap(int g) { return g==0?cap0:g==3?cap3:cap6; }
    public void cap(int g,int n) { if(g==0) cap0=n; else if(g==3) cap3=n; else cap6=n; }
    public int[] curve(int g) { return g==0?curve0:g==3?curve3:curve6; }
    public void curve(int g,int[] a) { if(g==0) curve0=a; else if(g==3) curve3=a; else curve6=a; }
    public void validate() {
        if (!mode.equals("fullmax") && !mode.equals("boost") && !mode.equals("custom")) throw new IllegalArgumentException("未知策略");
        if (boostPercent<0 || boostPercent>100) throw new IllegalArgumentException("阶梯增幅应为 0–100%");
        if (batteryC < 35 || batteryC > 50 || bodyC < 35 || bodyC > 50 || cpuC < 70 || cpuC > 110)
            throw new IllegalArgumentException("温度设置超出范围");
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("时间应为 1–1440 分钟");
        if (resume && !unlimited) throw new IllegalArgumentException("开机继续仅适用于持续运行");
        if(resume && profile.runtimeMount) throw new IllegalArgumentException("临时 Root 设备仅支持本次开机持续运行");
    }
    public String title() { return mode.equals("fullmax") ? "全频释放" : mode.equals("custom") ? "逐档自定义" : "阶梯增强 · +"+boostPercent+"%"; }
    public String timeLabel() { return unlimited ? (resume ? "持续运行 · 开机继续" : "持续运行 · 本次开机") : minutes + " 分钟后恢复"; }
    public static Policy load(Context context) {
        Policy p = new Policy();
        SharedPreferences s = context.getSharedPreferences(p.profile.runtimeMount?"thermal_policy_hongkong":"thermal_policy", 0);
        p.mode = s.getString("mode", p.mode); if(p.mode.equals("plus25")) p.mode="boost";
        p.boostPercent=s.getInt("boostPercent",25);
        for(int g:p.profile.groups) p.curve(g,curve(s.getString("curve"+g,""),p.profile,g));
        p.batteryC = s.getInt("battery", p.batteryC);
        p.bodyC = s.getInt("body", p.bodyC); p.cpuC = s.getInt("cpu", p.cpuC);
        p.minutes = s.getInt("minutes", p.minutes); p.cpuGuard = s.getBoolean("cpuGuard", false);
        p.unlimited = s.getBoolean("unlimited", false); p.resume = s.getBoolean("resume", false);
        for(int g:p.profile.groups) { int n=s.getInt("cap"+g,p.cap(g)); if(ConfigCodec.contains(p.profile.table(g),n)) p.cap(g,n); }
        return p;
    }
    public void save(Context c) {
        c.getSharedPreferences(profile.runtimeMount?"thermal_policy_hongkong":"thermal_policy", 0).edit().putString("mode", mode).putInt("battery", batteryC)
            .putInt("boostPercent",boostPercent).putString("curve0",join(curve0)).putString("curve6",join(curve6))
            .putString("curve3",join(curve3)).putInt("cap3",cap3)
            .putInt("body", bodyC).putInt("cpu", cpuC).putInt("minutes", minutes)
            .putBoolean("cpuGuard", cpuGuard).putBoolean("unlimited", unlimited)
            .putBoolean("resume", resume).putInt("cap0", cap0).putInt("cap6", cap6).apply();
    }
    private static int[] curve(String s,ThermalProfile p,int group) {
        try { String[] parts=s.split(","); int[] values=new int[parts.length]; int[] table=p.table(group);
            if(parts.length!=p.curve(group).length) throw new IllegalArgumentException();
            for(int i=0;i<parts.length;i++) { values[i]=Integer.parseInt(parts[i]); if(!ConfigCodec.contains(table,values[i])) throw new IllegalArgumentException(); }
            return values;
        } catch(Exception e) { return p.curve(group); }
    }
    private static String join(int[] a) { StringBuilder b=new StringBuilder(); for(int v:a) { if(b.length()>0) b.append(','); b.append(v); } return b.toString(); }
}
