package dev.pulse.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Small, dependency-free visual components. Every plotted value comes from a snapshot. */
final class PulseViews {
    static final int MINT = Color.rgb(120, 232, 198);
    static final int MUTED = Color.rgb(136, 151, 173);
    static final int LILAC = Color.rgb(183, 164, 255);
    static float number(String value) {
        if (value == null) return Float.NaN;
        try {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(value);
            return match.find() ? Float.parseFloat(match.group()) : Float.NaN;
        } catch (Exception ignored) { return Float.NaN; }
    }
    static float dp(Context context, float n) { return n * context.getResources().getDisplayMetrics().density; }

    static final class Icon extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        String kind;
        int color;
        Icon(Context context, String kind, int color) { super(context); this.kind = kind; this.color = color; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setKind(String value) { if(!kind.equals(value)){kind=value;invalidate();} }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            float size = Math.min(getWidth(), getHeight());
            canvas.translate((getWidth()-size)/2, (getHeight()-size)/2);
            canvas.scale(size/24f, size/24f);
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.65f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            if ("pulse".equals(kind)) {
                Path path = new Path(); path.moveTo(2,12); path.lineTo(7,12); path.lineTo(10,5); path.lineTo(14,19); path.lineTo(17,12); path.lineTo(22,12); canvas.drawPath(path,p);
            } else if ("window".equals(kind)) {
                canvas.drawRoundRect(3,4,21,20,4,4,p); canvas.drawLine(3,9,21,9,p); canvas.drawLine(7,6.5f,7.2f,6.5f,p); canvas.drawLine(10,6.5f,10.2f,6.5f,p);
            } else if ("sliders".equals(kind)) {
                for (int i=0;i<3;i++) { float y=6+i*6; float x=i==1?15:8; canvas.drawLine(3,y,x-2,y,p); canvas.drawLine(x+2,y,21,y,p); canvas.drawCircle(x,y,2,p); }
            } else if ("chip".equals(kind)) {
                canvas.drawRoundRect(5,5,19,19,3,3,p); canvas.drawRoundRect(9,9,15,15,1,1,p);
                for(int i=8;i<=16;i+=4) {canvas.drawLine(i,2,i,5,p);canvas.drawLine(i,19,i,22,p);canvas.drawLine(2,i,5,i,p);canvas.drawLine(19,i,22,i,p);}
            } else if ("memory".equals(kind)) {
                canvas.drawRoundRect(3,6,21,18,3,3,p); for(int i=7;i<20;i+=5) canvas.drawLine(i,10,i,14,p); for(int i=7;i<20;i+=5) canvas.drawLine(i,18,i,21,p);
            } else if ("bolt".equals(kind)) {
                Path path = new Path(); path.moveTo(13,2); path.lineTo(5,13); path.lineTo(11,13); path.lineTo(10,22); path.lineTo(19,10); path.lineTo(13,10); path.close(); canvas.drawPath(path,p);
            } else if ("arrow".equals(kind)) {
                canvas.drawLine(7,12,17,12,p);canvas.drawLine(13,8,17,12,p);canvas.drawLine(13,16,17,12,p);
            } else if ("play".equals(kind)) {
                p.setStyle(Paint.Style.FILL); Path path = new Path();path.moveTo(8,5);path.lineTo(19,12);path.lineTo(8,19);path.close();canvas.drawPath(path,p);
            } else if ("pause".equals(kind)) {
                p.setStyle(Paint.Style.FILL);canvas.drawRoundRect(6,5,10,19,1,1,p);canvas.drawRoundRect(14,5,18,19,1,1,p);
            }
            canvas.restore();
        }
    }

    static final class Trend extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final List<Float> samples = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        long lastTime;
        Trend(Context context) { super(context); setContentDescription("CPU 使用率最近六十秒趋势图"); }
        void append(long time, String value) {
            if (time <= lastTime) return;
            float n=number(value); if(Float.isNaN(n)) return;
            lastTime=time; samples.add(Math.max(0,Math.min(100,n)));times.add(time);
            while(times.size()>1 && time-times.get(0)>60000) {times.remove(0);samples.remove(0);}
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d=getResources().getDisplayMetrics().density, w=getWidth(),h=getHeight(), top=12*d,bottom=h-23*d;
            p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(9*d);p.setStrokeWidth(d);
            for(int i=0;i<3;i++) {
                float y=top+(bottom-top)*i/2;
                p.setColor(Color.rgb(37,49,66)); canvas.drawLine(0,y,w-27*d,y,p);
                p.setColor(MUTED);canvas.drawText(String.valueOf(100-i*50),w-22*d,y+3*d,p);
            }
            p.setColor(MUTED);canvas.drawText("−60 秒",0,h-3*d,p);p.setTextAlign(Paint.Align.RIGHT);canvas.drawText("现在",w-28*d,h-3*d,p);p.setTextAlign(Paint.Align.LEFT);
            if(samples.size()<2) {
                p.setTextSize(12*d);p.setTextAlign(Paint.Align.CENTER);p.setColor(MUTED);canvas.drawText(samples.isEmpty()?"开始监测后，趋势在这里呈现":"正在记录真实数据…",(w-25*d)/2,(top+bottom)/2+4*d,p);p.setTextAlign(Paint.Align.LEFT); return;
            }
            Path line=new Path();float right=w-29*d;
            long now=times.get(times.size()-1);
            float firstX=0,lastX=0,lastY=0;
            for(int i=0;i<samples.size();i++) {
                float x=right*(1-(now-times.get(i))/60000f),y=bottom-(bottom-top)*samples.get(i)/100f;
                if(i==0){line.moveTo(x,y);firstX=x;}else line.lineTo(x,y);
                lastX=x;lastY=y;
            }
            Path fill=new Path(line);fill.lineTo(lastX,bottom);fill.lineTo(firstX,bottom);fill.close();
            p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,top,0,bottom,new int[]{Color.argb(72,120,232,198),Color.argb(0,120,232,198)},null,Shader.TileMode.CLAMP));canvas.drawPath(fill,p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);p.setColor(MINT);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);canvas.drawPath(line,p);p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(30,120,232,198));canvas.drawCircle(lastX,lastY,7*d,p);p.setColor(MINT);canvas.drawCircle(lastX,lastY,3*d,p);
        }
    }

    static final class Cores extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        MetricsSnapshot snapshot;
        Cores(Context context){super(context);setContentDescription("各 CPU 核心当前频率");}
        void setSnapshot(MetricsSnapshot value){snapshot=value;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight();
            float gap=7*d,bw=(w-gap*7)/8,bottom=h-39*d,top=26*d;
            p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextAlign(Paint.Align.CENTER);
            float scaleMax=0;
            for(int i=0;i<8;i++){MetricsSnapshot.Metric max=snapshot==null?null:snapshot.find("cpu_max_freq_"+i);if(max!=null&&max.available){float n=number(max.value);if("GHz".equalsIgnoreCase(max.unit))n*=1000;if(!Float.isNaN(n))scaleMax=Math.max(scaleMax,n);}}
            if(scaleMax<=0)scaleMax=4500;
            for(int i=0;i<8;i++) {
                float x=i*(bw+gap);MetricsSnapshot.Metric m=snapshot==null?null:snapshot.find("cpu_freq_"+i);
                float freq=m!=null&&m.available?number(m.value):Float.NaN;
                if(m!=null&&"GHz".equalsIgnoreCase(m.unit)) freq*=1000;
                MetricsSnapshot.Metric cluster=snapshot==null?null:snapshot.find("cpu_cluster_"+i);
                boolean prime=cluster!=null&&cluster.available&&cluster.value.contains("超大");
                MetricsSnapshot.Metric maximum=snapshot==null?null:snapshot.find("cpu_max_freq_"+i);
                float max=maximum!=null&&maximum.available?number(maximum.value):Float.NaN;
                if(maximum!=null&&"GHz".equalsIgnoreCase(maximum.unit))max*=1000;
                p.setColor(Color.rgb(28,40,54));canvas.drawRoundRect(x,top,x+bw,bottom,4*d,4*d,p);
                if(!Float.isNaN(freq)){float bh=(bottom-top)*Math.min(1,freq/scaleMax);p.setColor(prime?LILAC:MINT);p.setAlpha(prime?230:175);canvas.drawRoundRect(x,bottom-Math.max(4*d,bh),x+bw,bottom,4*d,4*d,p);p.setAlpha(255);}
                p.setColor(Color.rgb(219,228,241));p.setTextSize(9*d);p.setTypeface(Typeface.create("monospace",Typeface.NORMAL));
                String label=Float.isNaN(freq)?"—":String.format(java.util.Locale.US,"%.2f",freq/1000);
                canvas.drawText(label,x+bw/2,16*d,p);
                p.setColor(prime?LILAC:MUTED);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(9*d);canvas.drawText("C"+i,x+bw/2,h-21*d,p);
                p.setTypeface(Typeface.create("monospace",Typeface.NORMAL));p.setTextSize(8*d);p.setColor(MUTED);canvas.drawText(Float.isNaN(max)?"—":String.format(java.util.Locale.US,"%.2f",max/1000),x+bw/2,h-6*d,p);
            }
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    static final class Preview extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        final MonitorPrefs prefs;
        MetricsSnapshot snapshot;
        Preview(Context c,MonitorPrefs prefs){super(c);this.prefs=prefs;setContentDescription("悬浮窗样式预览，数值来自实时采集");}
        void setSnapshot(MetricsSnapshot s){snapshot=s;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight();
            p.setColor(Color.rgb(11,18,29));canvas.drawRoundRect(0,0,w,h,16*d,16*d,p);
            p.setColor(Color.rgb(29,42,58));for(float y=16*d;y<h;y+=18*d)for(float x=16*d;x<w;x+=18*d)canvas.drawCircle(x,y,.7f*d,p);
            int accent=MINT;String theme=prefs.getString("theme","mint");if("ice".equals(theme))accent=Color.rgb(139,203,255);if("amber".equals(theme))accent=Color.rgb(255,204,131);if("mono".equals(theme))accent=Color.rgb(232,238,249);
            float boxW=Math.min(w-36*d,prefs.getInt("overlay_width",210)*d),text=prefs.getInt("overlay_size",12)*d;
            List<MetricsSnapshot.Metric> chosen=new ArrayList<>();List<MetricsSnapshot.Metric> all=snapshot==null?MetricsCollector.catalog():snapshot.metrics;
            for(MetricsSnapshot.Metric m:all) if(prefs.isMetricEnabled(m.id)){chosen.add(m);if(chosen.size()==5)break;}
            boolean compact=prefs.getBoolean("compact",true);float rowH=text+(compact?9:14)*d,boxH=Math.min(h-30*d,39*d+Math.max(1,chosen.size())*rowH);
            float x=(w-boxW)/2,y=(h-boxH)/2;
            p.setColor(Color.argb(Math.round(prefs.getInt("overlay_alpha",88)*2.55f),19,29,43));canvas.drawRoundRect(x,y,x+boxW,y+boxH,13*d,13*d,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);p.setColor(Color.argb(65,120,232,198));canvas.drawRoundRect(x,y,x+boxW,y+boxH,13*d,13*d,p);p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(10*d);p.setColor(accent);canvas.drawText("PULSE",x+13*d,y+21*d,p);p.setColor(Color.rgb(141,156,177));p.setTextSize(8*d);p.setTextAlign(Paint.Align.RIGHT);canvas.drawText("LIVE",x+boxW-13*d,y+21*d,p);p.setTextAlign(Paint.Align.LEFT);
            if(chosen.isEmpty()){p.setColor(MUTED);p.setTextSize(11*d);canvas.drawText("在「指标」中选择显示内容",x+13*d,y+46*d,p);return;}
            canvas.save();canvas.clipRect(x+10*d,y+30*d,x+boxW-10*d,y+boxH-5*d);
            for(int i=0;i<chosen.size();i++) {
                MetricsSnapshot.Metric m=chosen.get(i);float yy=y+37*d+i*rowH+text;
                String value=m.available?m.value+(m.unit==null||m.unit.isEmpty()?"":" "+m.unit):"—";
                p.setTypeface(Typeface.create("monospace",Typeface.NORMAL));p.setTextSize(text);float valueWidth=p.measureText(value);
                p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(Math.min(12*d,text));p.setColor(Color.rgb(164,180,200));String label=m.label;float maxLabel=Math.max(0,boxW-35*d-valueWidth);while(label.length()>0&&p.measureText(label)>maxLabel)label=label.substring(0,label.length()-1);if(!label.equals(m.label)&&label.length()>1)label=label.substring(0,label.length()-1)+"…";canvas.drawText(label,x+13*d,yy,p);
                p.setTextAlign(Paint.Align.RIGHT);p.setTypeface(Typeface.create("monospace",Typeface.NORMAL));p.setColor(accent);p.setTextSize(text);canvas.drawText(value,x+boxW-13*d,yy,p);p.setTextAlign(Paint.Align.LEFT);
            }
            canvas.restore();
        }
    }
}
