package dev.pulse.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Render real RemoteViews through Parcel with isolated prefs: never change user selections. */
final class AppearanceChecks {
    static JSONObject run(Instrumentation test)throws Exception {
        Context target=test.getTargetContext();
        SharedPreferences user=target.getSharedPreferences("pulse_config",Context.MODE_PRIVATE);
        Map<String,?> before=new HashMap<>(user.getAll());
        SharedPreferences scratch=target.getSharedPreferences("pulse_appearance_test",Context.MODE_PRIVATE);
        scratch.edit().clear().commit();
        Context isolated=new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return "pulse_config".equals(name)?scratch:super.getSharedPreferences(name,mode);
            }
        };
        MonitorPrefs prefs=new MonitorPrefs(isolated);
        prefs.setEnabledMetrics(new LinkedHashSet<>(Arrays.asList("cpu_freq_0","mem_available","battery_temp")));
        prefs.putInt("overlay_width",150);prefs.putInt("overlay_size",12);prefs.putInt("overlay_alpha",100);
        Activity activity=test.startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(test,()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        MetricsCollector collector=new MetricsCollector(target);
        try {
            collector.collect();SystemClock.sleep(250);MetricsSnapshot snapshot=collector.collect();
            final AppWidgetHostView[] widget={null};
            AppWidgetProviderInfo provider=AppWidgetManager.getInstance(target).getInstalledProviders().stream()
                .filter(info->new ComponentName(target,PulseWidgetProvider.class).equals(info.provider)).findFirst().orElseThrow();
            main(test,()->{widget[0]=new AppWidgetHostView(target);widget[0].setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID,provider);});

            prefs.putString("widget_label_color","#101824");prefs.putString("widget_value_color","#0066CC");
            prefs.putString("widget_background_color","#FFFFFF");prefs.putInt("widget_alpha",100);
            render(test,isolated,widget[0],snapshot);
            main(test,()->{
                assertColors(widget[0],0xff101824,0xff0066cc);
                check(widget[0].findViewById(R.id.widget_border).getVisibility()==View.VISIBLE,"Default border missing");
                int pixel=backgroundPixel(widget[0]);check(pixel==0xffffffff,"White background did not survive RemoteViews: "+Integer.toHexString(pixel));
                save(target,widget[0],"widget-light.png");
            });

            prefs.putBoolean("widget_borderless",true);prefs.putString("widget_label_color","#FFCE85");
            prefs.putString("widget_value_color","#78E8C6");prefs.putString("widget_background_color","#19324D");prefs.putInt("widget_alpha",55);
            render(test,isolated,widget[0],snapshot);
            main(test,()->{
                assertColors(widget[0],0xffffce85,0xff78e8c6);
                check(widget[0].findViewById(R.id.widget_border).getVisibility()==View.GONE,"Borderless widget still draws border");
                int pixel=backgroundPixel(widget[0]);
                check(Math.abs((pixel>>>24)-140)<=1,"Opacity did not apply");
                for(int shift:new int[]{0,8,16})check(Math.abs(((pixel>>shift)&255)-((0xff19324d>>shift)&255))<=1,"Background RGB changed");
                save(target,widget[0],"widget-custom-borderless.png");
            });

            prefs.putInt("widget_alpha",0);render(test,isolated,widget[0],snapshot);
            main(test,()->{
                check(backgroundPixel(widget[0])>>>24==0,"0% background is not transparent");
                check(widget[0].findViewById(R.id.widget_border).getVisibility()==View.GONE,"Transparent widget has outline");
                assertColors(widget[0],0xffffce85,0xff78e8c6);save(target,widget[0],"widget-transparent.png");
            });
            // Unavailable readings remain a dash and respect an explicitly chosen text color.
            MetricsSnapshot.Metric unavailable=new MetricsSnapshot.Metric("test","不可用","","—","MHz","",false);
            main(test,()->{
                View row=roundTrip(PulseWidgetProvider.row(isolated,unavailable)).apply(target,null);
                TextView value=row.findViewById(R.id.widget_value);
                check("—".contentEquals(value.getText()),"Unavailable reading fabricated a value");
                check(value.getCurrentTextColor()==0xff78e8c6,"Custom color ignored for dash");
            });

            // The Android 8–11 data factory uses the same color settings.
            PulseWidgetRowsService.Factory fallback=new PulseWidgetRowsService.Factory(isolated);fallback.onCreate();
            check(fallback.getCount()==3,"Legacy factory changed the selection");
            main(test,()->{
                View row=roundTrip(fallback.getViewAt(0)).apply(target,null);
                check(((TextView)row.findViewById(R.id.widget_label)).getCurrentTextColor()==0xffffce85,"Legacy label color lost");
                check(((TextView)row.findViewById(R.id.widget_value)).getCurrentTextColor()==0xff78e8c6,"Legacy value color lost");
            });fallback.onDestroy();

            prefs.putBoolean("overlay_borderless",true);prefs.putString("theme","ice");prefs.remove(WidgetStyle.KEYS);
            render(test,isolated,widget[0],snapshot);
            main(test,()->{
                check(widget[0].findViewById(R.id.widget_border).getVisibility()==View.VISIBLE,"Reset did not restore widget border");
                check(((ImageView)widget[0].findViewById(R.id.widget_background)).getImageAlpha()==224,"Reset did not restore opacity");
                check(((TextView)((ListView)widget[0].findViewById(R.id.widget_list)).getChildAt(0).findViewById(R.id.widget_label)).getCurrentTextColor()==0xffa7b7c9,"Reset did not restore label color");
                check(prefs.getBoolean("overlay_borderless",false),"Widget reset changed overlay border setting");
                View row=PulseWidgetProvider.row(isolated,snapshot.find("mem_available")).apply(target,null);
                check(((TextView)row.findViewById(R.id.widget_value)).getCurrentTextColor()==0xff89c9ff,"Default value color stopped following theme");
            });

            prefs.putString("overlay_label_color","#FFCE85");
            prefs.putString("overlay_value_color","#78E8C6");
            prefs.putString("overlay_background_color","#19324D");
            prefs.putInt("overlay_alpha",55);prefs.putBoolean("overlay_borderless",false);
            final OverlayPanel[] styled={null};
            try {
                main(test,()->{styled[0]=new OverlayPanel(isolated,prefs);styled[0].update(snapshot);styled[0].show();});
                SystemClock.sleep(300);
                main(test,()->{try {
                    java.lang.reflect.Field panelField=OverlayPanel.class.getDeclaredField("panel");panelField.setAccessible(true);
                    View panel=(View)panelField.get(styled[0]);
                    GradientDrawable background=(GradientDrawable)panel.getBackground();
                    int color=background.getColor().getDefaultColor();
                    check((color&0xffffff)==0x19324d,"Overlay background color did not apply: "+Integer.toHexString(color)+" pref="+prefs.getString("overlay_background_color",""));
                    check((color>>>24)==140,"Overlay opacity did not apply: "+Integer.toHexString(color));
                    java.lang.reflect.Field rowsField=OverlayPanel.class.getDeclaredField("rows");rowsField.setAccessible(true);
                    ViewGroup rows=(ViewGroup)rowsField.get(styled[0]);
                    check(rows.getChildCount()>0,"Overlay color test has no rows");
                    for(int i=0;i<rows.getChildCount();i++) {
                        ViewGroup row=(ViewGroup)rows.getChildAt(i);
                        check(((TextView)row.getChildAt(0)).getCurrentTextColor()==0xffffce85,"Overlay label color did not apply");
                        check(((TextView)row.getChildAt(1)).getCurrentTextColor()==0xff78e8c6,"Overlay value color did not apply");
                    }
                    save(target,panel,"overlay-colors.png");
                } catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
            } finally {main(test,()->{if(styled[0]!=null)styled[0].remove();});}
            prefs.remove(OverlayStyle.KEYS);prefs.putString("theme","ice");prefs.putInt("overlay_alpha",100);
            OverlayStyle resetOverlay=new OverlayStyle(prefs);
            check(resetOverlay.labelColor==0xffa7b7c9,"Overlay label color did not reset");
            check(resetOverlay.backgroundColor==0xff0d1621,"Overlay background color did not reset");
            check(resetOverlay.valueColor==0xff89c9ff&&!resetOverlay.customValue,"Overlay value color did not follow theme");
            check(resetOverlay.valueColor(false)==0xff66778b,"Overlay unavailable color changed");

            final int[] borderPixels=new int[2];
            for(int i=0;i<2;i++) {
                final int index=i;prefs.putBoolean("overlay_borderless",i==1);
                final OverlayPanel[] overlay={null};
                try {
                    main(test,()->{overlay[0]=new OverlayPanel(isolated,prefs);overlay[0].update(snapshot);overlay[0].show();});
                    SystemClock.sleep(300);
                    main(test,()->{try {
                        java.lang.reflect.Field field=OverlayPanel.class.getDeclaredField("panel");field.setAccessible(true);
                        View panel=(View)field.get(overlay[0]);
                        Bitmap image=capture(panel);borderPixels[index]=image.getPixel(image.getWidth()/2,0);image.recycle();
                        if(index==1)save(target,panel,"overlay-borderless.png");
                    } catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
                } finally {main(test,()->{if(overlay[0]!=null)overlay[0].remove();});}
            }
            check(borderPixels[0]!=borderPixels[1]&&borderPixels[1]==0xff0d1621,"Overlay border toggle did not change the rendered edge");
            main(test,()->{
                try {
                    java.lang.reflect.Field page=MainActivity.class.getDeclaredField("page");page.setAccessible(true);page.setInt(activity,1);
                    java.lang.reflect.Method build=MainActivity.class.getDeclaredMethod("buildScreen",boolean.class);build.setAccessible(true);build.invoke(activity,false);
                    View root=activity.findViewById(android.R.id.content);
                    for(String label:new String[]{"无边框","小部件无边框","名称颜色，点按选择颜色","数值颜色，点按选择颜色","背景颜色，点按选择颜色","小部件背景不透明度","悬浮窗名称颜色，点按选择颜色","悬浮窗数值颜色，点按选择颜色","悬浮窗背景颜色，点按选择颜色"})
                        check(hasDescription(root,label),"Missing settings control: "+label);
                    java.lang.reflect.Field preview=MainActivity.class.getDeclaredField("widgetPreview");preview.setAccessible(true);
                    check(preview.get(activity)!=null,"Settings page has no widget preview");
                } catch(ReflectiveOperationException e){throw new RuntimeException(e);}
            });
            checkPicker(test,activity);
            check(before.equals(user.getAll()),"Appearance test modified user preferences");
            return new JSONObject().put("result","pass").put("version","1.5.1")
                .put("widget_colors",true).put("widget_border_toggle",true).put("opacity_0_55_100",true)
                .put("overlay_colors",true).put("overlay_reset",true)
                .put("overlay_border_toggle",true).put("reset_and_theme",true).put("legacy_factory",true)
                .put("settings_controls_present",true).put("picker_validation_apply_cancel",true).put("user_preferences_unchanged",true);
        } finally {collector.close();scratch.edit().clear().commit();}
    }
    private static void render(Instrumentation test,Context context,AppWidgetHostView host,MetricsSnapshot snapshot) {
        main(test,()->host.updateAppWidget(roundTrip(PulseWidgetProvider.views(context,AppWidgetManager.INVALID_APPWIDGET_ID,snapshot))));
        long deadline=SystemClock.elapsedRealtime()+3000;final boolean[] ready={false};
        while(SystemClock.elapsedRealtime()<deadline) {
            main(test,()->{
                measure(host,dp(context,220),dp(context,125));ListView list=host.findViewById(R.id.widget_list);
                ready[0]=list!=null&&list.getAdapter()!=null&&list.getAdapter().getCount()==3&&list.getChildCount()>0;
            });
            if(ready[0])return;SystemClock.sleep(50);
        }
        throw new AssertionError("RemoteViews did not render selected rows");
    }
    private static void assertColors(View host,int labelColor,int valueColor) {
        ListView list=host.findViewById(R.id.widget_list);
        for(int i=0;i<list.getChildCount();i++) {
            View row=list.getChildAt(i);
            check(((TextView)row.findViewById(R.id.widget_label)).getCurrentTextColor()==labelColor,"Label color did not apply");
            check(((TextView)row.findViewById(R.id.widget_value)).getCurrentTextColor()==valueColor,"Value color did not apply");
        }
    }
    private static void checkPicker(Instrumentation test,Activity activity) {
        main(test,()->{
            int[] accepted={0};AlertDialog dialog=ColorPicker.show(activity,"颜色验证",0xff000000,color->accepted[0]=color);
            try {
                EditText input=dialog.findViewById(R.id.color_hex);input.setText("#GGGGGG");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                check(dialog.isShowing()&&input.getError()!=null&&accepted[0]==0,"Invalid color was accepted");
                input.setText("abc123");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                check(!dialog.isShowing()&&accepted[0]==0xffabc123,"Valid custom hex color was not applied");
            } finally {dialog.dismiss();}
            AlertDialog cancel=ColorPicker.show(activity,"取消验证",0xffffffff,color->accepted[0]=color);
            try {((EditText)cancel.findViewById(R.id.color_hex)).setText("#123456");cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();check(accepted[0]==0xffabc123,"Cancel applied a color");}
            finally {cancel.dismiss();}
        });
    }
    private static int backgroundPixel(View host) {
        View background=host.findViewById(R.id.widget_background);Bitmap bitmap=capture(background);
        int pixel=bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/2);bitmap.recycle();return pixel;
    }
    private static boolean hasDescription(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasDescription(((ViewGroup)view).getChildAt(i),description))return true;
        return false;
    }
    private static RemoteViews roundTrip(RemoteViews views) {
        Parcel parcel=Parcel.obtain();try {views.writeToParcel(parcel,0);parcel.setDataPosition(0);return RemoteViews.CREATOR.createFromParcel(parcel);}finally {parcel.recycle();}
    }
    private static void measure(View view,int width,int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,height==0?View.MeasureSpec.UNSPECIFIED:View.MeasureSpec.EXACTLY));
        view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
    }
    private static Bitmap capture(View view){Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));return bitmap;}
    private static void save(Context context,View view,String name) {
        File dir=new File(context.getFilesDir(),"verification-1.3");dir.mkdirs();Bitmap bitmap=capture(view);
        try(FileOutputStream out=new FileOutputStream(new File(dir,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        catch(java.io.IOException e){throw new RuntimeException(e);}finally {bitmap.recycle();}
    }
    private static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private static void main(Instrumentation test,Runnable action) {
        AtomicReference<Throwable> failure=new AtomicReference<>();test.runOnMainSync(()->{try {action.run();}catch(Throwable error){failure.set(error);}});
        Throwable error=failure.get();if(error instanceof Error)throw (Error)error;if(error!=null)throw new RuntimeException(error);
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
