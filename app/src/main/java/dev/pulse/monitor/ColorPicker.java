package dev.pulse.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** Native palette and RGB input. The caller only receives a color after explicit Apply. */
final class ColorPicker {
    static AlertDialog show(Activity activity,String title,int initial,IntConsumer apply) {
        float density=activity.getResources().getDisplayMetrics().density;
        int gap=Math.round(6*density),pad=Math.round(20*density);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(pad,gap,pad,gap);
        TextView swatch=new TextView(activity);swatch.setGravity(Gravity.CENTER);swatch.setTextSize(15);
        swatch.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        body.addView(swatch,new LinearLayout.LayoutParams(-1,Math.round(52*density)));
        EditText input=new EditText(activity);input.setId(R.id.color_hex);input.setSingleLine(true);
        input.setHint("#RRGGBB，例如 #FFFFFF");input.setContentDescription("RGB 颜色代码");input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});input.setSelectAllOnFocus(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout.LayoutParams field=new LinearLayout.LayoutParams(-1,-2);field.topMargin=gap;body.addView(input,field);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count) {
                Integer color=WidgetStyle.parseHex(text.toString());if(color==null)return;
                GradientDrawable background=new GradientDrawable();background.setColor(color);background.setCornerRadius(8*density);
                background.setStroke(Math.max(1,Math.round(density)),0xff64758e);swatch.setBackground(background);
                int brightness=((color>>16&255)*299+(color>>8&255)*587+(color&255)*114)/1000;
                swatch.setTextColor(brightness>=150?0xff101824:0xfff1f5fc);swatch.setText(WidgetStyle.hex(color));
                input.setError(null);
            }
            public void afterTextChanged(Editable text){}
        });
        input.setText(WidgetStyle.hex(initial));
        int[] palette={0xfff1f5fc,0xff000000,0xff0d1621,0xff64758e,0xffa7b7c9,
            0xff78e8c6,0xff89c9ff,0xffffce85,0xffff9eaa,0xffb7a4ff};
        for(int start=0;start<palette.length;start+=5) {
            LinearLayout row=new LinearLayout(activity);
            for(int i=start;i<start+5;i++) {
                final int color=palette[i];TextView chip=new TextView(activity);chip.setGravity(Gravity.CENTER);
                chip.setText("●");chip.setTextSize(27);chip.setTextColor(color);chip.setBackgroundColor(0xff243044);
                chip.setContentDescription("颜色 "+WidgetStyle.hex(color));chip.setFocusable(true);
                chip.setOnClickListener(v->input.setText(WidgetStyle.hex(color)));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,Math.round(48*density),1);
                if(i>start)lp.leftMargin=gap;row.addView(chip,lp);
            }
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=gap;body.addView(row,lp);
        }
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title).setView(body)
            .setNegativeButton("取消",null).setPositiveButton("应用",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            Integer color=WidgetStyle.parseHex(input.getText().toString());
            if(color==null){input.setError("请输入 6 位 RGB 颜色，例如 #FFFFFF");return;}
            apply.accept(color);dialog.dismiss();
        });
        return dialog;
    }
}
