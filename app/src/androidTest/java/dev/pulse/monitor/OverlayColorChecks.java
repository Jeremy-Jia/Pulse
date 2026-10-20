package dev.pulse.monitor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Focused overlay-color check that never launches an Activity (HyperOS may freeze those). */
final class OverlayColorChecks {
    static JSONObject run(Instrumentation test)throws Exception {
        Context target=test.getTargetContext();
        check(Settings.canDrawOverlays(target),"Overlay permission is required for the overlay color check");
        SharedPreferences scratch=target.getSharedPreferences("pulse_overlay_color_test",Context.MODE_PRIVATE);
        scratch.edit().clear().commit();
        Context isolated=new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return "pulse_config".equals(name)?scratch:super.getSharedPreferences(name,mode);
            }
        };
        MonitorPrefs prefs=new MonitorPrefs(isolated);
        prefs.setEnabledMetrics(new LinkedHashSet<>(Arrays.asList("cpu_usage","cpu_temp_avg")));
        prefs.putString("overlay_label_color","#FFCE85");
        prefs.putString("overlay_value_color","#78E8C6");
        prefs.putString("overlay_background_color","#19324D");
        prefs.putInt("overlay_alpha",55);
        prefs.putBoolean("overlay_borderless",false);
        List<MetricsSnapshot.Metric> metrics=Arrays.asList(
            new MetricsSnapshot.Metric("cpu_usage","CPU 负载","CPU","42","%","test",true),
            new MetricsSnapshot.Metric("cpu_temp_avg","CPU 温度","CPU","—","°C","test",false));
        MetricsSnapshot snapshot=new MetricsSnapshot(System.currentTimeMillis(),metrics);
        final OverlayPanel[] overlay={null};
        try {
            main(test,()->{overlay[0]=new OverlayPanel(isolated,prefs);overlay[0].update(snapshot);overlay[0].show();});
            SystemClock.sleep(300);
            main(test,()->{try {
                View panel=(View)field(overlay[0],"panel");
                GradientDrawable background=(GradientDrawable)panel.getBackground();
                int color=background.getColor().getDefaultColor();
                check((color&0xffffff)==0x19324d,"Overlay background color did not apply: "+Integer.toHexString(color));
                check((color>>>24)==140,"Overlay opacity did not apply: "+Integer.toHexString(color));
                ViewGroup rows=(ViewGroup)field(overlay[0],"rows");
                check(rows.getChildCount()==2,"Overlay did not render the two selected readings");
                for(int i=0;i<rows.getChildCount();i++) {
                    ViewGroup row=(ViewGroup)rows.getChildAt(i);
                    check(((TextView)row.getChildAt(0)).getCurrentTextColor()==0xffffce85,"Overlay label color did not apply");
                    check(((TextView)row.getChildAt(1)).getCurrentTextColor()==0xff78e8c6,"Overlay value color did not apply");
                }
                save(target,panel,"overlay-colors.png");
            } catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
        } finally {main(test,()->{if(overlay[0]!=null)overlay[0].remove();});}

        prefs.remove(OverlayStyle.KEYS);prefs.putString("theme","ice");
        OverlayStyle reset=new OverlayStyle(prefs);
        check(reset.labelColor==0xffa7b7c9,"Overlay label color did not reset");
        check(reset.backgroundColor==0xff0d1621,"Overlay background color did not reset");
        check(reset.valueColor==0xff89c9ff&&!reset.customValue,"Overlay value color did not follow theme");
        check(reset.valueColor(false)==0xff66778b,"Overlay unavailable color changed");
        scratch.edit().clear().commit();
        return new JSONObject().put("result","pass").put("version","1.5.1")
            .put("overlay_label_color",true).put("overlay_value_color",true)
            .put("overlay_background_color",true).put("overlay_opacity",true)
            .put("overlay_unavailable_color",true).put("overlay_reset_theme",true);
    }
    private static Object field(Object target,String name)throws ReflectiveOperationException {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void save(Context context,View view,String name) {
        File dir=new File(context.getFilesDir(),"verification-1.5.1");dir.mkdirs();Bitmap bitmap=capture(view);
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        catch(java.io.IOException e){throw new RuntimeException(e);}finally {bitmap.recycle();}
    }
    private static Bitmap capture(View view){Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));return bitmap;}
    private static void main(Instrumentation test,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();test.runOnMainSync(()->{try {action.run();}catch(Throwable error){failure.set(error);}});
        Throwable error=failure.get();if(error instanceof Error)throw (Error)error;if(error!=null)throw new RuntimeException(error);
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
