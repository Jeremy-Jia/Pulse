package dev.pulse.monitor;

/** Overlay colors are independent of the widget; absent settings retain the existing appearance. */
final class OverlayStyle {
    static final String[] KEYS={"overlay_label_color","overlay_value_color","overlay_background_color"};
    static final int DEFAULT_LABEL=0xffa7b7c9, DEFAULT_BACKGROUND=0xff0d1621, UNAVAILABLE=0xff66778b;
    final int labelColor,valueColor,backgroundColor;
    final boolean customValue;
    OverlayStyle(MonitorPrefs prefs) {
        labelColor=color(prefs,"overlay_label_color",DEFAULT_LABEL);
        valueColor=color(prefs,"overlay_value_color",WidgetStyle.accent(prefs));
        backgroundColor=color(prefs,"overlay_background_color",DEFAULT_BACKGROUND);
        customValue=WidgetStyle.parseHex(prefs.getString("overlay_value_color",""))!=null;
    }
    int valueColor(boolean available){return available||customValue?valueColor:UNAVAILABLE;}
    private static int color(MonitorPrefs prefs,String key,int fallback) {
        Integer parsed=WidgetStyle.parseHex(prefs.getString(key,""));return parsed==null?fallback:parsed;
    }
}
