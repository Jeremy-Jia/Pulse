package dev.pulse.thermal;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

final class ThermalViews {
    static final int BG=Color.rgb(12,16,21), CARD=Color.rgb(22,29,37), LINE=Color.rgb(43,54,66),
        INK=Color.rgb(239,242,245), MUTED=Color.rgb(143,158,174), AMBER=Color.rgb(255,176,135), TEAL=Color.rgb(153,220,207);
    static int dp(Context c,float n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color,int stroke,float radius,Context c) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius));
        if(stroke!=0) d.setStroke(dp(c,1),stroke); return d;
    }
    static TextView text(Context c,String value,float size,int color) {
        TextView t=new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("tnum");
        t.setIncludeFontPadding(false); return t;
    }
    static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static final class Chart extends View {
        final List<float[]> samples=new ArrayList<>(); final Paint p=new Paint(3); long last=0;
        Chart(Context c) { super(c); setContentDescription("最近两分钟的电池与估算机身温度曲线"); }
        void add(Snapshot s) {
            if(s.n("battery_dc")==0 || System.currentTimeMillis()-last<1500) return;
            last=System.currentTimeMillis(); samples.add(new float[]{s.n("battery_dc")/10f,s.n("body_mc")/1000f});
            if(samples.size()>60) samples.remove(0); invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight(),left=0,right=w-27*d,top=12*d,bottom=h-16*d;
            p.setStrokeWidth(d); p.setTextSize(10*d); p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
            for(int v=30;v<=50;v+=10) {
                float y=bottom-(v-25)/30f*(bottom-top);
                p.setColor(LINE); c.drawLine(left,y,right,y,p); p.setColor(MUTED); c.drawText(String.valueOf(v),right+7*d,y+3*d,p);
            }
            for(int line=0;line<2;line++) {
                if(samples.isEmpty()) continue;
                Path path=new Path(); float x=0,y=0;
                for(int i=0;i<samples.size();i++) {
                    x=left+(right-left)*i/59f; y=bottom-(Math.min(55,Math.max(25,samples.get(i)[line]))-25)/30f*(bottom-top);
                    if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
                }
                p.setStyle(Paint.Style.STROKE); p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeWidth(2*d);
                p.setColor(line==0?AMBER:TEAL); c.drawPath(path,p);
                p.setStyle(Paint.Style.FILL); c.drawCircle(x,y,3*d,p);
            }
            if(samples.size()<2) { p.setColor(MUTED); p.setTextSize(12*d); c.drawText("正在收集温度趋势…",8*d,bottom-10*d,p); }
        }
    }
}
