package dev.pulse.thermal;

import android.content.Context;
import android.content.SharedPreferences;

public final class Policy {
    public String mode = "fullmax";
    public int boostPercent = 25;
    public int[] curve0 = ConfigCodec.originalCurve(false), curve6 = ConfigCodec.originalCurve(true);
    public int batteryC = 45, bodyC = 43, cpuC = 100, minutes = 15;
    public boolean cpuGuard = false, unlimited = false, resume = false;
    public int cap0 = 3532800, cap6 = 4320000;
    public void validate() {
        if (!mode.equals("fullmax") && !mode.equals("boost") && !mode.equals("custom")) throw new IllegalArgumentException("未知策略");
        if (boostPercent<0 || boostPercent>100) throw new IllegalArgumentException("阶梯增幅应为 0–100%");
        if (batteryC < 35 || batteryC > 50 || bodyC < 35 || bodyC > 50 || cpuC < 70 || cpuC > 110)
            throw new IllegalArgumentException("温度设置超出范围");
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("时间应为 1–1440 分钟");
        if (resume && !unlimited) throw new IllegalArgumentException("开机继续仅适用于持续运行");
    }
    public String title() { return mode.equals("fullmax") ? "全频释放" : mode.equals("custom") ? "逐档自定义" : "阶梯增强 · +"+boostPercent+"%"; }
    public String timeLabel() { return unlimited ? (resume ? "持续运行 · 开机继续" : "持续运行 · 本次开机") : minutes + " 分钟后恢复"; }
    public static Policy load(Context context) {
        SharedPreferences s = context.getSharedPreferences("thermal_policy", 0);
        Policy p = new Policy();
        p.mode = s.getString("mode", p.mode); if(p.mode.equals("plus25")) p.mode="boost";
        p.boostPercent=s.getInt("boostPercent",25); p.curve0=curve(s.getString("curve0",""),false); p.curve6=curve(s.getString("curve6",""),true);
        p.batteryC = s.getInt("battery", p.batteryC);
        p.bodyC = s.getInt("body", p.bodyC); p.cpuC = s.getInt("cpu", p.cpuC);
        p.minutes = s.getInt("minutes", p.minutes); p.cpuGuard = s.getBoolean("cpuGuard", false);
        p.unlimited = s.getBoolean("unlimited", false); p.resume = s.getBoolean("resume", false);
        p.cap0 = s.getInt("cap0", p.cap0); p.cap6 = s.getInt("cap6", p.cap6);
        return p;
    }
    public void save(Context c) {
        c.getSharedPreferences("thermal_policy", 0).edit().putString("mode", mode).putInt("battery", batteryC)
            .putInt("boostPercent",boostPercent).putString("curve0",join(curve0)).putString("curve6",join(curve6))
            .putInt("body", bodyC).putInt("cpu", cpuC).putInt("minutes", minutes)
            .putBoolean("cpuGuard", cpuGuard).putBoolean("unlimited", unlimited)
            .putBoolean("resume", resume).putInt("cap0", cap0).putInt("cap6", cap6).apply();
    }
    private static int[] curve(String s,boolean prime) {
        try { String[] parts=s.split(","); int[] values=new int[parts.length]; int[] table=prime?ConfigCodec.TABLE6:ConfigCodec.TABLE0;
            if(parts.length!=(prime?16:14)) throw new IllegalArgumentException();
            for(int i=0;i<parts.length;i++) { values[i]=Integer.parseInt(parts[i]); if(!ConfigCodec.contains(table,values[i])) throw new IllegalArgumentException(); }
            return values;
        } catch(Exception e) { return ConfigCodec.originalCurve(prime); }
    }
    private static String join(int[] a) { StringBuilder b=new StringBuilder(); for(int v:a) { if(b.length()>0) b.append(','); b.append(v); } return b.toString(); }
}
