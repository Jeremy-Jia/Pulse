package dev.pulse.thermal;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Snapshot {
    private final Map<String,String> values = new HashMap<>();
    public Snapshot(String raw) { for (String line: raw.split("\n")) { int n=line.indexOf('='); if(n>0) values.put(line.substring(0,n), line.substring(n+1).trim()); } }
    public String s(String k) { return values.getOrDefault(k, ""); }
    public long n(String k) { try { return Long.parseLong(s(k)); } catch(Exception ignored) { return 0; } }
    /** Session ownership includes a paused scene; it is not a stopped session. */
    public boolean active() { return s("state").equals("active") || s("state").equals("starting") || paused(); }
    public boolean paused() { return s("state").equals("paused"); }
    public boolean recoveryFailed() { return s("state").equals("recovery_failed"); }
    public String temperature(String key, int divisor) { return n(key)>0 ? String.format(Locale.ROOT,"%.1f°", n(key)/(double)divisor) : "—"; }
    public String frequency(String key) { return n(key)>0 ? String.format(Locale.ROOT,"%.2f",n(key)/1000000d) : "—"; }
    public String countdown() {
        if (!active()) return "未运行";
        if (paused()) return "等待场景恢复";
        if (n("remaining")<0) return s("resume").equals("1") ? "持续 · 开机继续" : "持续运行";
        long seconds=Math.max(0,n("remaining"));
        return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
    }
    public String reason() {
        return switch(s("reason")) {
            case "MANUAL" -> "手动恢复原厂";
            case "TIMER", "DEADLINE_FALLBACK" -> "倒计时结束，已恢复原厂";
            case "BATTERY_LIMIT" -> "电池达到停止温度，已恢复原厂";
            case "BODY_LIMIT" -> "估算机身达到停止温度，已恢复原厂";
            case "CPU_LIMIT" -> "CPU 达到停止温度，已恢复原厂";
            case "REBOOT_RECOVERY" -> "重启后恢复原厂";
            case "FIRMWARE_CHANGED" -> "系统已更新，已退出旧版本温控策略";
            case "SCENE_CHANGED" -> "系统切换温控场景，已恢复原厂";
            case "SCENE_PAUSED" -> "场景暂不兼容，已暂停；回到普通场景后继续";
            case "CONFIG_CHANGED" -> "检测到其他策略变化，已恢复原厂";
            case "WATCHDOG_LOST", "WATCHDOG_EXIT", "ACTIVATION_TIMEOUT", "ACTIVATION_FAILED" -> "守护退出，已恢复原厂";
            case "APP_REMOVED" -> "应用卸载，已恢复原厂";
            case "THERMAL_SERVICE_STOPPED" -> "系统温控服务异常，已停止";
            case "SENSOR_ERROR" -> "温度读取异常，已停止";
            case "RECOVERY_FAILED" -> "恢复未确认，请点击恢复原厂重试";
            default -> "尚无运行记录";
        };
    }
}
