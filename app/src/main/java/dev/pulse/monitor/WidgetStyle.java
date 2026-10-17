package dev.pulse.monitor;

import java.util.Locale;

/** Widget colors are independent of the overlay; absent settings retain the 1.2 appearance. */
final class WidgetStyle {
    static final String[] KEYS={"widget_label_color","widget_value_color","widget_background_color","widget_alpha","widget_borderless"};
    static final int DEFAULT_LABEL=0xffa7b7c9, DEFAULT_BACKGROUND=0xff0d1621, UNAVAILABLE=0xff66778b;
    final int labelColor,valueColor,backgroundColor,alpha;
    final boolean borderless,customValue;
    WidgetStyle(MonitorPrefs prefs) {
        labelColor=color(prefs,"widget_label_color",DEFAULT_LABEL);
        valueColor=color(prefs,"widget_value_color",accent(prefs));
        backgroundColor=color(prefs,"widget_background_color",DEFAULT_BACKGROUND);
        alpha=Math.round(Math.max(0,Math.min(100,prefs.getInt("widget_alpha",88)))*255f/100);
        borderless=prefs.getBoolean("widget_borderless",false);
        customValue=parseHex(prefs.getString("widget_value_color",""))!=null;
    }
    int valueColor(boolean available){return available||customValue?valueColor:UNAVAILABLE;}
    static int accent(MonitorPrefs prefs) {
        String theme=prefs.getString("theme","mint");
        return "ice".equals(theme)?0xff89c9ff:"amber".equals(theme)?0xffffce85:"mono".equals(theme)?0xffebeff7:0xff78e8c6;
    }
    private static int color(MonitorPrefs prefs,String key,int fallback) {
        Integer parsed=parseHex(prefs.getString(key,""));return parsed==null?fallback:parsed;
    }
    static Integer parseHex(String input) {
        if(input==null)return null;
        String hex=input.trim();if(hex.startsWith("#"))hex=hex.substring(1);
        if(!hex.matches("[0-9a-fA-F]{6}"))return null;
        return 0xff000000|Integer.parseInt(hex,16);
    }
    static String hex(int color){return String.format(Locale.US,"#%06X",color&0xffffff);}
}
