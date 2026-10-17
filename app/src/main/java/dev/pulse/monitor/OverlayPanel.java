package dev.pulse.monitor;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Only selected readings, with a minimal frame. Drag readings; lock position to scroll. */
final class OverlayPanel {
    private final Context context;
    private final MonitorPrefs prefs;
    private final WindowManager manager;
    private final LinkedHashMap<String,TextView> values=new LinkedHashMap<>();
    private final LinearLayout panel,rows;
    private final ScrollView scroll;
    private final WindowManager.LayoutParams params;
    private String signature="";
    private boolean shown;
    private int accent;
    private final int font;
    private final boolean compact;
    private final boolean bold;
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    OverlayPanel(Context context,MonitorPrefs prefs) {
        this.context=context; this.prefs=prefs; manager=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        String theme=prefs.getString("theme","mint");
        accent="ice".equals(theme)?0xff89c9ff:"amber".equals(theme)?0xffffce85:"mono".equals(theme)?0xffebeff7:0xff78e8c6;
        font=Math.max(10,Math.min(22,prefs.getInt("overlay_size",12))); compact=prefs.getBoolean("compact",true);
        bold=prefs.getBoolean("overlay_bold",false);
        panel=new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(3),dp(2),dp(3),dp(2));
        panel.setVisibility(View.INVISIBLE);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.argb(Math.max(40,Math.min(255,prefs.getInt("overlay_alpha",88)*255/100)),13,22,33));
        bg.setCornerRadius(dp(4)); bg.setStroke(Math.max(1,dp(.5f)),0x406caaab); panel.setBackground(bg);
        scroll=new ScrollView(context); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rows=new LinearLayout(context); rows.setOrientation(LinearLayout.VERTICAL); scroll.addView(rows);
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        boolean pass=prefs.getBoolean("touch_through",false);
        if(pass) flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        int screenWidth=context.getResources().getDisplayMetrics().widthPixels;
        int width=Math.min(screenWidth,dp(Math.max(100,Math.min(320,prefs.getInt("overlay_width",210)))));
        params=new WindowManager.LayoutParams(width,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        if(android.os.Build.VERSION.SDK_INT>=30) {
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if(android.os.Build.VERSION.SDK_INT>=28) {
            params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        params.x=Math.min(Math.max(0,prefs.getInt("overlay_x",screenWidth-width-dp(12))),screenWidth-width);
        params.y=Math.max(0,prefs.getInt("overlay_y",dp(150)));
        // Android blocks underlying touches for untrusted overlays above the obscuring threshold.
        params.alpha=pass?0.78f:1f;
        params.setTitle("Pulse performance overlay");
        View.OnTouchListener dragHandle=new View.OnTouchListener() {
            float x,y; int startX,startY;
            @Override public boolean onTouch(View v,android.view.MotionEvent e) {
                if(prefs.getBoolean("position_locked",false)) return false;
                switch(e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        x=e.getRawX(); y=e.getRawY(); startX=params.x; startY=params.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        if(!prefs.getBoolean("position_locked",false)) {
                            params.x=startX+Math.round(e.getRawX()-x);
                            params.y=startY+Math.round(e.getRawY()-y);
                            clampPosition();
                            if(shown) manager.updateViewLayout(panel,params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        prefs.putInt("overlay_x",params.x); prefs.putInt("overlay_y",params.y); return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false); return true;
                    default:return true;
                }
            }
        };
        scroll.setOnTouchListener(dragHandle);
        // Side padding remains reachable when system bars cover the first row at y=0.
        panel.setOnTouchListener(dragHandle);
    }
    private Rect screenBounds() {
        if(android.os.Build.VERSION.SDK_INT>=30)return manager.getMaximumWindowMetrics().getBounds();
        Point size=new Point();manager.getDefaultDisplay().getRealSize(size);return new Rect(0,0,size.x,size.y);
    }
    private void clampPosition() {
        Rect bounds=screenBounds();
        params.x=Math.max(0,Math.min(Math.max(0,bounds.width()-params.width),params.x));
        int height=panel.getHeight()>0?panel.getHeight():panel.getMeasuredHeight();
        params.y=Math.max(0,Math.min(Math.max(0,bounds.height()-Math.max(1,height)),params.y));
    }
    void show() {
        clampPosition();manager.addView(panel,params);shown=true;
        panel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int x=params.x,y=params.y;clampPosition();
            if(shown&&(x!=params.x||y!=params.y))manager.updateViewLayout(panel,params);
        });
    }
    void remove() { if(shown) { try{manager.removeView(panel);}catch(IllegalArgumentException ignored){} shown=false; } }
    void update(MetricsSnapshot snapshot) {
        java.util.List<MetricsSnapshot.Metric> selected=new ArrayList<>();
        StringBuilder key=new StringBuilder();
        for(MetricsSnapshot.Metric metric:snapshot.metrics) if(prefs.isMetricEnabled(metric.id)) { selected.add(metric); key.append(metric.id).append('|'); }
        if(!signature.equals(key.toString())) {
            signature=key.toString(); rows.removeAllViews(); values.clear();
            for(MetricsSnapshot.Metric metric:selected) {
                LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(compact?1:4),0,dp(compact?1:4));
                TextView label=text(metric.label,font-1,0xffa7b7c9,bold); label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView value=text("—",font,accent,bold); value.setGravity(Gravity.END); value.setSingleLine(true);
                value.setTypeface(Typeface.create("monospace",bold?Typeface.BOLD:Typeface.NORMAL));
                value.setMaxWidth(Math.round((params.width-dp(6))*.62f));value.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=dp(3);
                row.addView(label,new LinearLayout.LayoutParams(0,-2,1)); row.addView(value,vp);
                rows.addView(row); values.put(metric.id,value);
            }
        }
        for(MetricsSnapshot.Metric m:selected) { TextView value=values.get(m.id); if(value!=null) {
            value.setText(m.available?m.value+(m.unit.isEmpty()?"":" "+m.unit):"—");
            value.setTextColor(m.available?accent:0xff66778b); value.setContentDescription(m.label+" "+(m.available?m.value+m.unit:"暂不可用"));
        } }
        panel.setVisibility(selected.isEmpty()?View.GONE:View.VISIBLE);
        int maxHeight=Math.max(dp(80),(int)(screenBounds().height()*.64f));
        rows.measure(View.MeasureSpec.makeMeasureSpec(params.width-dp(6),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(rows.getMeasuredHeight(),maxHeight);
        if(scroll.getLayoutParams().height!=height)scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,height));
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return t;
    }
    private int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
}
