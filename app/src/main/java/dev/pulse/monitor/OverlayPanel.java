package dev.pulse.monitor;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A draggable, scrollable display: one privileged collector feeds all surfaces. */
final class OverlayPanel {
    private final Context context;
    private final MonitorPrefs prefs;
    private final WindowManager manager;
    private final LinkedHashMap<String,TextView> values=new LinkedHashMap<>();
    private final LinearLayout panel,rows;
    private final ScrollView scroll;
    private final TextView status;
    private final Sparkline graph;
    private final WindowManager.LayoutParams params;
    private String signature="";
    private boolean shown,collapsed;
    private int accent;
    private final int font;
    private final boolean compact;
    OverlayPanel(Context context,MonitorPrefs prefs) {
        this.context=context; this.prefs=prefs; manager=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        String theme=prefs.getString("theme","mint");
        accent="ice".equals(theme)?0xff89c9ff:"amber".equals(theme)?0xffffce85:"mono".equals(theme)?0xffebeff7:0xff78e8c6;
        font=Math.max(10,Math.min(22,prefs.getInt("overlay_size",12))); compact=prefs.getBoolean("compact",true);
        panel=new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(13),dp(10),dp(13),dp(10));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.argb(Math.max(40,Math.min(255,prefs.getInt("overlay_alpha",88)*255/100)),13,22,33));
        bg.setCornerRadius(dp(18)); bg.setStroke(dp(1),0x406caaab); panel.setBackground(bg); panel.setElevation(dp(8));
        LinearLayout header=new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("●  P U L S E",10,accent,true); header.addView(title,new LinearLayout.LayoutParams(0,dp(26),1));
        TextView open=text("↗",18,0xffb4c3d6,false); open.setGravity(Gravity.CENTER); open.setContentDescription("打开脉冲设置");
        open.setOnClickListener(v->context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        header.addView(open,new LinearLayout.LayoutParams(dp(32),dp(28)));
        panel.addView(header);
        graph=new Sparkline(context,accent); panel.addView(graph,new LinearLayout.LayoutParams(-1,dp(compact?23:34)));
        scroll=new ScrollView(context); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rows=new LinearLayout(context); rows.setOrientation(LinearLayout.VERTICAL); scroll.addView(rows);
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        status=text("正在读取传感器…",9,0xff8d9db2,false); status.setPadding(0,dp(7),0,0); panel.addView(status);
        int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        boolean pass=prefs.getBoolean("touch_through",false);
        if(pass) flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        int screenWidth=context.getResources().getDisplayMetrics().widthPixels;
        int width=Math.min(screenWidth-dp(24),dp(Math.max(150,Math.min(320,prefs.getInt("overlay_width",210)))));
        params=new WindowManager.LayoutParams(width,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        params.x=Math.min(Math.max(0,prefs.getInt("overlay_x",screenWidth-width-dp(12))),screenWidth-width);
        params.y=Math.max(dp(36),Math.min(context.getResources().getDisplayMetrics().heightPixels-dp(120),prefs.getInt("overlay_y",dp(150))));
        // Android blocks underlying touches for untrusted overlays above the obscuring threshold.
        params.alpha=pass?0.78f:1f;
        params.setTitle("Pulse performance overlay");
        title.setOnClickListener(v->{
            collapsed=!collapsed;
            scroll.setVisibility(collapsed?View.GONE:View.VISIBLE);
            graph.setVisibility(collapsed?View.GONE:View.VISIBLE);
            status.setVisibility(collapsed?View.GONE:View.VISIBLE);
        });
        title.setOnTouchListener(new View.OnTouchListener() {
            float x,y; int startX,startY; boolean dragged;
            @Override public boolean onTouch(View v,android.view.MotionEvent e) {
                switch(e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: x=e.getRawX(); y=e.getRawY(); startX=params.x; startY=params.y; dragged=false; return true;
                    case MotionEvent.ACTION_MOVE:
                        if(Math.abs(e.getRawX()-x)+Math.abs(e.getRawY()-y)>dp(5)) dragged=true;
                        if(!prefs.getBoolean("position_locked",false)) {
                            params.x=Math.max(0,Math.min(context.getResources().getDisplayMetrics().widthPixels-params.width,startX+(int)(e.getRawX()-x)));
                            params.y=Math.max(dp(28),Math.min(context.getResources().getDisplayMetrics().heightPixels-dp(100),startY+(int)(e.getRawY()-y)));
                            if(shown) manager.updateViewLayout(panel,params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if(!dragged) v.performClick();
                        prefs.putInt("overlay_x",params.x); prefs.putInt("overlay_y",params.y); return true;
                    default:return true;
                }
            }
        });
    }
    void show() { manager.addView(panel,params); shown=true; }
    void remove() { if(shown) { try{manager.removeView(panel);}catch(IllegalArgumentException ignored){} shown=false; } }
    void update(MetricsSnapshot snapshot) {
        java.util.List<MetricsSnapshot.Metric> selected=new ArrayList<>();
        StringBuilder key=new StringBuilder();
        for(MetricsSnapshot.Metric metric:snapshot.metrics) if(prefs.isMetricEnabled(metric.id)) { selected.add(metric); key.append(metric.id).append('|'); }
        if(!signature.equals(key.toString())) {
            signature=key.toString(); rows.removeAllViews(); values.clear(); String category="";
            for(MetricsSnapshot.Metric metric:selected) {
                if(!compact && !metric.category.equals(category)) { category=metric.category; TextView section=text(category.toUpperCase(Locale.ROOT),9,0xff8495ac,true); section.setPadding(0,dp(9),0,dp(3)); rows.addView(section); }
                LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(compact?3:5),0,dp(compact?3:5));
                TextView label=text(metric.label,font-1,0xffa7b7c9,false); label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView value=text("—",font,accent,true); value.setGravity(Gravity.END); value.setTypeface(Typeface.create("monospace",Typeface.NORMAL));
                row.addView(label,new LinearLayout.LayoutParams(0,-2,1)); row.addView(value,new LinearLayout.LayoutParams(-2,-2));
                rows.addView(row); values.put(metric.id,value);
            }
            if(selected.isEmpty()) { TextView empty=text("在指标页选择显示内容",11,0xffa7b7c9,false); rows.addView(empty); }
            int maxHeight=Math.max(dp(120),(int)(context.getResources().getDisplayMetrics().heightPixels*.64f)-dp(80));
            rows.measure(View.MeasureSpec.makeMeasureSpec(params.width-dp(26),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,Math.min(rows.getMeasuredHeight(),maxHeight)));
        }
        int available=0;
        for(MetricsSnapshot.Metric m:selected) { TextView value=values.get(m.id); if(value!=null) {
            value.setText(m.available?m.value+(m.unit.isEmpty()?"":" "+m.unit):"—");
            value.setTextColor(m.available?accent:0xff66778b); value.setContentDescription(m.label+" "+(m.available?m.value+m.unit:"暂不可用"));
            if(m.available) available++;
        } }
        MetricsSnapshot.Metric cpu=snapshot.find("cpu_usage");
        if(cpu!=null&&cpu.available) try{graph.push(Float.parseFloat(cpu.value));}catch(NumberFormatException ignored){}
        status.setText(available+" / "+selected.size()+" 指标可用  ·  "+String.format(Locale.US,"%.1fs",Math.max(500,prefs.getInt("refresh_ms",1000))/1000f));
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;
    }
    private int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    private static final class Sparkline extends View {
        private final float[] samples=new float[48]; private int count; private final Paint paint=new Paint(3); private final Path path=new Path(); private final int color;
        Sparkline(Context c,int color){super(c);this.color=color;}
        void push(float n){if(count<samples.length)samples[count++]=n;else{System.arraycopy(samples,1,samples,0,samples.length-1);samples[samples.length-1]=n;}invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float h=getHeight()-6,w=getWidth();paint.setColor(0x223d6a75);paint.setStrokeWidth(1);c.drawLine(0,h,w,h,paint);if(count<2)return;path.reset();for(int i=0;i<count;i++){float x=i*w/(samples.length-1),y=h-(Math.max(0,Math.min(100,samples[i]))/100f)*(h-4);if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.3f);paint.setColor(color);c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);}
    }
}
