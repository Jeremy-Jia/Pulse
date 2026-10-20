package dev.pulse.thermal;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static dev.pulse.thermal.ThermalViews.*;

public final class MainActivity extends Activity implements ThermalHub.Listener {
    ThermalHub hub; Policy policy;
    final java.util.Map<Integer,TextView> frequencies=new java.util.HashMap<>(), maxima=new java.util.HashMap<>();
    TextView connectionStatus;
    LinearLayout root, nav, body, dock;
    FrameLayout content;
    ScrollView pageScroll;
    TextView rootBadge, heroTitle, heroSub, countdown, tempBattery, tempBody, tempCpu, freq0, freq6, max0, max6,
        message, apply, restore, recordReason, recordTime, recordLimits;
    ThermalViews.Chart chart;
    int page=0;
    boolean resumed=false;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); hub=ThermalHub.get(this); policy=Policy.load(this);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        root=column(this); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            int top,bottom;
            if(Build.VERSION.SDK_INT>=30) { android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()); top=i.top; bottom=i.bottom; }
            else { top=insets.getSystemWindowInsetTop(); bottom=insets.getSystemWindowInsetBottom(); }
            root.setPadding(0,top,0,bottom); return insets;
        });
        setContentView(root);
        LinearLayout header=row(); header.setPadding(dp(this,24),dp(this,17),dp(this,24),dp(this,15));
        LinearLayout brand=column(this);
        TextView name=text(this,"‹  温控策略",23,INK); name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); name.setOnClickListener(v -> finish()); name.setContentDescription("返回 Pulse");
        brand.addView(name); TextView sub=text(this,"PULSE  /  "+policy.profile.name+" 性能控制",10,MUTED); marginTop(sub,7); brand.addView(sub);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        rootBadge=text(this,"连接中",11,TEAL); rootBadge.setPadding(dp(this,11),dp(this,8),dp(this,11),dp(this,8));
        rootBadge.setBackground(shape(0xff1b302f,0,14,this)); rootBadge.setOnClickListener(v -> hub.connect()); header.addView(rootBadge);
        root.addView(header);
        connectionStatus=text(this,hub.message,11,MUTED); connectionStatus.setPadding(dp(this,24),0,dp(this,24),dp(this,10));
        connectionStatus.setOnClickListener(v -> hub.connect()); root.addView(connectionStatus);
        content=new FrameLayout(this); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        dock=column(this); dock.setPadding(dp(this,20),dp(this,10),dp(this,20),dp(this,9));
        LinearLayout actions=row();
        apply=button("应用策略",AMBER,BG); apply.setContentDescription("应用所选 CPU 策略");
        actions.addView(apply,new LinearLayout.LayoutParams(0,dp(this,54),1));
        restore=button("恢复原厂",CARD,INK); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(this,112),dp(this,54)); rp.leftMargin=dp(this,10); actions.addView(restore,rp);
        apply.setOnClickListener(v -> startPolicy()); restore.setOnClickListener(v -> hub.stop()); dock.addView(actions); root.addView(dock);
        nav=row(); nav.setPadding(dp(this,20),dp(this,4),dp(this,20),dp(this,6)); root.addView(nav,new LinearLayout.LayoutParams(-1,dp(this,59)));
        showPage(0);
    }
    @Override protected void onResume() { super.onResume(); resumed=true; hub.listeners.add(this); if(!hub.busy) hub.sync(); update(); }
    @Override protected void onPause() { resumed=false; hub.listeners.remove(this); super.onPause(); }
    LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    void marginTop(View v,int n) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(this,n); v.setLayoutParams(p); }
    LinearLayout card(LinearLayout parent,int padding) {
        LinearLayout l=column(this); l.setPadding(dp(this,padding),dp(this,padding),dp(this,padding),dp(this,padding));
        l.setBackground(shape(CARD,LINE,23,this)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(this,12); parent.addView(l,p); return l;
    }
    TextView label(LinearLayout parent,String value,int size,int color) { TextView t=text(this,value,size,color); parent.addView(t); return t; }
    TextView button(String value,int background,int color) {
        TextView t=text(this,value,15,color); t.setGravity(Gravity.CENTER); t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setBackground(shape(background,background==CARD?LINE:0,17,this)); t.setMinHeight(dp(this,48)); t.setClickable(true); t.setFocusable(true); return t;
    }
    void gap(LinearLayout parent,int h) { View v=new View(this); parent.addView(v,new LinearLayout.LayoutParams(1,dp(this,h))); }
    void heading(String title,String subtitle) {
        TextView t=label(body,title,28,INK); t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        gap(body,8); label(body,subtitle,12,MUTED); gap(body,22);
    }
    void showPage(int n) {
        int previousY=page==n && pageScroll!=null?pageScroll.getScrollY():0;
        page=n; content.removeAllViews();
        heroTitle=heroSub=countdown=tempBattery=tempBody=tempCpu=freq0=freq6=max0=max6=message=recordReason=recordTime=recordLimits=null; chart=null;
        frequencies.clear(); maxima.clear();
        ScrollView scroll=new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(false);
        pageScroll=scroll;
        body=column(this); body.setPadding(dp(this,20),dp(this,12),dp(this,20),dp(this,10)); scroll.addView(body); content.addView(scroll);
        nav.removeAllViews(); String[] labels={"◉  控制台","≋  策略","▤  记录"};
        for(int i=0;i<3;i++) {
            final int selected=i; TextView b=text(this,labels[i],13,i==page?AMBER:MUTED); b.setGravity(Gravity.CENTER);
            if(i==page) b.setBackground(shape(0xff302821,0,15,this)); b.setOnClickListener(v -> showPage(selected));
            nav.addView(b,new LinearLayout.LayoutParams(0,-1,1));
        }
        dock.setVisibility(page==2?View.GONE:View.VISIBLE);
        if(page==0) dashboard(); else if(page==1) settings(); else records(); update();
        if(previousY>0) scroll.post(() -> scroll.scrollTo(0,previousY));
    }
    void dashboard() {
        LinearLayout hero=card(body,22);
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff29313a,0xff171d25}); g.setCornerRadius(dp(this,25)); g.setStroke(dp(this,1),0xff3a4149); hero.setBackground(g);
        LinearLayout top=row(); TextView eyebrow=text(this,"CPU POLICY",11,AMBER); eyebrow.setLetterSpacing(.15f); top.addView(eyebrow,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text(this,policy.profile.runtimeMount?"3 CPU GROUPS":"8 ELITE",10,MUTED); badge.setLetterSpacing(.12f); top.addView(badge); hero.addView(top); gap(hero,22);
        heroTitle=label(hero,"原厂温控",31,INK); heroTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); gap(hero,10);
        heroSub=label(hero,"按需放开频率，让每次测试都有据可查。",12,MUTED); heroSub.setLineSpacing(dp(this,3),1); gap(hero,24);
        LinearLayout timer=row(); LinearLayout tc=column(this); label(tc,"恢复倒计时",11,MUTED); gap(tc,7);
        countdown=label(tc,"未运行",27,AMBER); countdown.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); timer.addView(tc,new LinearLayout.LayoutParams(0,-2,1));
        TextView preserved=text(this,"原厂底层\n保护保留",11,TEAL); preserved.setGravity(Gravity.END); preserved.setLineSpacing(dp(this,5),1); timer.addView(preserved); hero.addView(timer);
        LinearLayout cores=row(); for(int group:policy.profile.groups) coreCard(cores,group); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.bottomMargin=dp(this,12); body.addView(cores,cp);
        LinearLayout temperatures=row(); tempBattery=tempCard(temperatures,"电池",AMBER,0); tempBody=tempCard(temperatures,"估算机身",TEAL,1); tempCpu=tempCard(temperatures,"CPU 热点",0xffb9b5f3,2);
        body.addView(temperatures); gap(body,14);
        LinearLayout trend=card(body,18); LinearLayout tr=row(); TextView title=text(this,"温度趋势",14,INK); tr.addView(title,new LinearLayout.LayoutParams(0,-2,1)); tr.addView(text(this,"● 电池   ",10,AMBER)); tr.addView(text(this,"● 机身",10,TEAL)); trend.addView(tr);
        chart=new ThermalViews.Chart(this); trend.addView(chart,new LinearLayout.LayoutParams(-1,dp(this,122)));
        message=label(body,hub.message,12,MUTED); message.setLineSpacing(dp(this,4),1); message.setOnClickListener(v -> hub.connect()); gap(body,8);
    }
    void coreCard(LinearLayout parent,int group) {
        LinearLayout c=column(this); c.setPadding(dp(this,17),dp(this,17),dp(this,17),dp(this,17)); c.setBackground(shape(CARD,LINE,22,this));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); if(group!=0) p.leftMargin=dp(this,8); parent.addView(c,p);
        label(c,policy.profile.label(group),10,MUTED); gap(c,13);
        LinearLayout value=row(); TextView num=text(this,"—",31,INK); num.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); value.addView(num); TextView unit=text(this," GHz",10,MUTED); value.addView(unit); c.addView(value); gap(c,9);
        num.setTextSize(policy.profile.runtimeMount?25:31);
        TextView max=label(c,"上限 —",10,group==6?AMBER:TEAL); frequencies.put(group,num); maxima.put(group,max);
    }
    TextView tempCard(LinearLayout row,String title,int color,int index) {
        LinearLayout c=column(this); c.setPadding(dp(this,12),dp(this,16),dp(this,8),dp(this,16)); c.setBackground(shape(CARD,0,19,this));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); if(index>0) p.leftMargin=dp(this,8); row.addView(c,p);
        label(c,title,11,MUTED); gap(c,11); TextView t=label(c,"—",25,color); t.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); return t;
    }
    void settings() {
        heading("你的性能策略","选择频率策略，再设置退出条件。");
        LinearLayout modes=card(body,17);
        choice(modes,"全频释放","所有温度档使用所选频率上限",policy.mode.equals("fullmax"),() -> { policy.mode="fullmax"; save(); showPage(1); });
        divider(modes); choice(modes,"阶梯增强 +"+policy.boostPercent+"%","自选增幅 · 不超过硬件频率表上限",policy.mode.equals("boost"),() -> { policy.mode="boost"; save(); showPage(1); });
        divider(modes); choice(modes,"逐档自定义","分别编辑各组核心每个温度档的频率",policy.mode.equals("custom"),() -> { policy.mode="custom"; save(); showPage(1); });
        if(policy.mode.equals("boost")) {
            LinearLayout boost=card(body,18); LinearLayout r=row(); r.addView(text(this,"阶梯增幅",14,INK),new LinearLayout.LayoutParams(0,-2,1));
            TextView amount=text(this,"+"+policy.boostPercent+"%",24,AMBER); r.addView(amount); boost.addView(r); gap(boost,12);
            SeekBar seek=new SeekBar(this); seek.setMax(100); seek.setProgress(policy.boostPercent); seek.setContentDescription("阶梯频率增加百分比"); seek.setProgressTintList(ColorStateList.valueOf(AMBER)); seek.setThumbTintList(ColorStateList.valueOf(AMBER));
            boost.addView(seek,new LinearLayout.LayoutParams(-1,dp(this,40)));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s,int n,boolean user) { amount.setText("+"+n+"%"); if(user) { policy.boostPercent=n; save(); } }
                public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
            });
            gap(boost,8); label(boost,"25°C 档使用所选上限；其余档按比例增加，向下取支持的频率。",11,MUTED);
        }
        if(policy.mode.equals("custom")) {
            LinearLayout edit=card(body,18); label(edit,"温度档位曲线",15,INK); gap(edit,12);
            for(int group:policy.profile.groups) {
                TextView b=button("编辑 "+policy.profile.label(group)+" · "+policy.profile.curve(group).length+" 档",BG,group==6?AMBER:TEAL);
                edit.addView(b,new LinearLayout.LayoutParams(-1,dp(this,46))); gap(edit,10); b.setOnClickListener(v -> curveEditor(group));
            }
            label(edit,"档位温度是系统估算机身温度；不会改动原来的触发温度和回落滞后。",11,MUTED);
        }
        LinearLayout caps=card(body,18); label(caps,"频率上限",15,INK); gap(caps,14);
        for(int group:policy.profile.groups) { frequency(caps,policy.profile.label(group),group); gap(caps,13); }
        if(policy.profile.runtimeMount) label(caps,"CPU6–7：4.704 GHz 为普通最高档；4.8576 / 4.9536 / 5.0112 GHz 为 Boost 档。上限不等于持续频率。",11,MUTED);
        gap(body,8); label(body,"温度停止线",19,INK); gap(body,8); TextView intro=label(body,"触发任一条件即恢复原厂，之后需手动重新启用。",11,MUTED); intro.setLineSpacing(dp(this,3),1); gap(body,14);
        LinearLayout limits=card(body,18);
        slider(limits,"电池温度",35,50,policy.batteryC,n -> { policy.batteryC=n; save(); },AMBER);
        gap(limits,17); slider(limits,"估算机身温度",35,50,policy.bodyC,n -> { policy.bodyC=n; save(); },TEAL);
        gap(limits,8); divider(limits);
        toggle(limits,"CPU 热点停止线","可选的额外保护，不是系统硬件阈值",policy.cpuGuard,true,checked -> { policy.cpuGuard=checked; save(); showPage(1); });
        if(policy.cpuGuard) slider(limits,"CPU 热点",70,110,policy.cpuC,n -> { policy.cpuC=n; save(); },0xffb9b5f3);
        LinearLayout time=card(body,18); label(time,"什么时候恢复",16,INK); gap(time,16);
        LinearLayout segments=row(); TextView timed=button("定时恢复",policy.unlimited?BG:0xff302821,policy.unlimited?MUTED:AMBER), endless=button("持续运行",policy.unlimited?0xff302821:BG,policy.unlimited?AMBER:MUTED);
        segments.addView(timed,new LinearLayout.LayoutParams(0,dp(this,44),1)); LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,dp(this,44),1); ep.leftMargin=dp(this,8); segments.addView(endless,ep); time.addView(segments);
        timed.setOnClickListener(v -> { policy.unlimited=false; policy.resume=false; save(); if(hub.connected) hub.updateBootResume(false); showPage(1); }); endless.setOnClickListener(v -> { policy.unlimited=true; save(); showPage(1); }); gap(time,16);
        if(!policy.unlimited) {
            LinearLayout duration=row(); TextView val=text(this,policy.minutes+" 分钟",24,INK); duration.addView(val,new LinearLayout.LayoutParams(0,-2,1));
            TextView custom=text(this,"修改时间  ›",12,AMBER); custom.setPadding(dp(this,8),dp(this,10),0,dp(this,10)); custom.setOnClickListener(v -> durationDialog()); duration.addView(custom); time.addView(duration); gap(time,13);
            LinearLayout quick=row(); for(int n:new int[]{5,15,30,60}) {
                TextView b=text(this,n+" 分",12,policy.minutes==n?BG:MUTED); b.setGravity(Gravity.CENTER); b.setBackground(shape(policy.minutes==n?AMBER:BG,0,10,this));
                LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,dp(this,36),1); q.rightMargin=dp(this,5); quick.addView(b,q);
                b.setOnClickListener(v -> { policy.minutes=n; save(); showPage(1); });
            } time.addView(quick);
        } else {
            toggle(time,"重启后继续",policy.profile.runtimeMount?"临时 Root 仅本次开机有效；重启恢复原厂":"关闭时，重启会恢复原厂温控",policy.resume,!policy.profile.runtimeMount,checked -> { policy.resume=checked; save(); if(hub.connected) hub.updateBootResume(checked); });
            gap(time,9); TextView t=label(time,"持续模式没有倒计时；温度停止线始终有效。"+(policy.profile.runtimeMount?"":" 运行中切换会立即写入当前会话的启动钩子。"),11,MUTED); t.setLineSpacing(dp(this,3),1);
        }
        TextView footer=label(body,"只调整 CPU 频率曲线，保留芯片、充电和其他系统保护。设置修改后，先恢复原厂再应用新策略。",11,MUTED); footer.setLineSpacing(dp(this,4),1); gap(body,10);
    }
    interface IntChange { void set(int n); }
    void curveEditor(int group) {
        int[] working=policy.curve(group).clone(), temps=policy.profile.triggers(group), table=policy.profile.table(group);
        LinearLayout list=column(this); list.setPadding(dp(this,20),dp(this,8),dp(this,20),dp(this,12));
        TextView note=label(list,"点击每个档位选择上限，最终还受核心组总上限约束。",12,MUTED); note.setLineSpacing(dp(this,4),1); gap(list,12);
        TextView[] values=new TextView[working.length];
        LinearLayout presets=row(); String[] names={"原厂","全频","当前增幅"};
        for(int j=0;j<names.length;j++) { final int kind=j; TextView btn=button(names[j],BG,AMBER); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(this,42),1); lp.rightMargin=dp(this,6); presets.addView(btn,lp);
            btn.setOnClickListener(v -> { int[] original=policy.profile.curve(group);
                for(int i=0;i<working.length;i++) { int desired=kind==0?original[i]:kind==1||i==0?table[table.length-1]:original[i]*(100+policy.boostPercent)/100;
                    working[i]=table[0]; for(int f:table) if(f<=desired) working[i]=f; values[i].setText(String.format(Locale.ROOT,"%.4f GHz  ›",working[i]/1000000d)); }
            });
        } list.addView(presets); gap(list,8);
        for(int i=0;i<working.length;i++) { final int index=i; LinearLayout r=row(); r.setPadding(0,dp(this,13),0,dp(this,13));
            r.addView(text(this,String.format(Locale.ROOT,"%.1f°C 档",temps[i]/1000d),13,INK),new LinearLayout.LayoutParams(0,-2,1));
            TextView value=text(this,String.format(Locale.ROOT,"%.4f GHz  ›",working[i]/1000000d),13,group==6?AMBER:TEAL); r.addView(value); values[i]=value; list.addView(r);
            r.setOnClickListener(v -> { String[] items=new String[table.length]; int selected=0; for(int k=0;k<table.length;k++) { items[k]=String.format(Locale.ROOT,"%.4f GHz",table[k]/1000000d); if(table[k]==working[index]) selected=k; }
                new AlertDialog.Builder(this).setTitle("温度 "+temps[index]/1000d+"°C 档").setSingleChoiceItems(items,selected,(d,which) -> { working[index]=table[which]; value.setText(items[which]+"  ›"); d.dismiss(); }).setNegativeButton("取消",null).show();
            });
        }
        ScrollView scroll=new ScrollView(this); scroll.addView(list);
        new AlertDialog.Builder(this).setTitle(policy.profile.label(group)+"曲线").setView(scroll).setNegativeButton("取消",null)
            .setPositiveButton("保存曲线",(d,w) -> { policy.curve(group,working); save(); }).show();
    }
    interface BoolChange { void set(boolean b); }
    void slider(LinearLayout parent,String title,int min,int max,int value,IntChange changed,int color) {
        LinearLayout r=row(); r.addView(text(this,title,13,INK),new LinearLayout.LayoutParams(0,-2,1));
        TextView v=text(this,value+"°C",22,color); r.addView(v); parent.addView(r); gap(parent,7);
        SeekBar seek=new SeekBar(this); seek.setMax(max-min); seek.setProgress(value-min); seek.setProgressTintList(ColorStateList.valueOf(color)); seek.setThumbTintList(ColorStateList.valueOf(color)); seek.setContentDescription(title);
        parent.addView(seek,new LinearLayout.LayoutParams(-1,dp(this,38)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s,int n,boolean fromUser) { v.setText((n+min)+"°C"); if(fromUser) changed.set(n+min); }
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
        });
    }
    void frequency(LinearLayout parent,String title,int group) {
        LinearLayout r=row(); r.addView(text(this,title,12,MUTED),new LinearLayout.LayoutParams(0,-2,1));
        TextView value=text(this,String.format(Locale.ROOT,"%.4f GHz  ›",policy.cap(group)/1000000d),14,INK); r.addView(value); parent.addView(r);
        r.setPadding(0,dp(this,6),0,dp(this,6)); r.setOnClickListener(v -> {
            int[] table=policy.profile.table(group); String[] items=new String[table.length]; int selected=0;
            for(int i=0;i<table.length;i++) { items[i]=String.format(Locale.ROOT,"%.4f GHz",table[i]/1000000d); if(table[i]==policy.cap(group)) selected=i; }
            new AlertDialog.Builder(this).setTitle(title+"上限").setSingleChoiceItems(items,selected,(d,which) -> { policy.cap(group,table[which]); save(); d.dismiss(); showPage(1); }).setNegativeButton("取消",null).show();
        });
    }
    void choice(LinearLayout parent,String title,String desc,boolean selected,Runnable callback) {
        LinearLayout r=row(); r.setPadding(0,dp(this,9),0,dp(this,9)); LinearLayout t=column(this); label(t,title,16,selected?AMBER:INK); gap(t,7); label(t,desc,11,MUTED);
        r.addView(t,new LinearLayout.LayoutParams(0,-2,1)); r.addView(text(this,selected?"●":"○",22,selected?AMBER:MUTED)); r.setOnClickListener(v -> callback.run()); parent.addView(r);
    }
    void divider(LinearLayout parent) { View v=new View(this); v.setBackgroundColor(LINE); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(this,1)); p.topMargin=dp(this,12); p.bottomMargin=dp(this,12); parent.addView(v,p); }
    void toggle(LinearLayout parent,String title,String desc,boolean checked,boolean enabled,BoolChange change) {
        LinearLayout r=row(); LinearLayout t=column(this); label(t,title,13,INK); gap(t,6); TextView d=label(t,desc,10,MUTED); d.setLineSpacing(dp(this,3),1);
        r.addView(t,new LinearLayout.LayoutParams(0,-2,1)); Switch sw=new Switch(this); sw.setContentDescription(title); sw.setChecked(checked); sw.setEnabled(enabled);
        sw.setThumbTintList(ColorStateList.valueOf(checked?AMBER:MUTED)); sw.setTrackTintList(ColorStateList.valueOf(0xff4b4140));
        sw.setOnCheckedChangeListener((v,b) -> change.set(b)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(this,48)); p.leftMargin=dp(this,10); r.addView(sw,p); parent.addView(r);
    }
    void durationDialog() {
        EditText input=new EditText(this); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setText(String.valueOf(policy.minutes)); input.setSelectAllOnFocus(true);
        input.setPadding(dp(this,24),dp(this,16),dp(this,24),dp(this,16));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("定时恢复 · 分钟").setMessage("1–1440 分钟，时间到后恢复原厂配置。").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try { int n=Integer.parseInt(input.getText().toString()); if(n<1 || n>1440) throw new IllegalArgumentException(); policy.minutes=n; save(); dialog.dismiss(); showPage(1); }
            catch(Exception e) { input.setError("请输入 1–1440"); }
        })); dialog.show();
    }
    void records() {
        heading("每次释放，都有记录","实时采样 · 可导出 CSV · 保留恢复原因");
        LinearLayout c=card(body,22); label(c,"最近一次策略",11,AMBER); gap(c,15);
        recordReason=label(c,"尚无运行记录",22,INK); recordReason.setLineSpacing(dp(this,4),1); gap(c,17);
        recordTime=label(c,"运行时长 —",13,MUTED); gap(c,10); recordLimits=label(c,"停止条件 —",12,MUTED); gap(c,20);
        TextView export=button("导出温度与频率记录",0xff243631,TEAL); c.addView(export,new LinearLayout.LayoutParams(-1,dp(this,48))); export.setOnClickListener(v -> exportDialog());
        LinearLayout scope=card(body,20); label(scope,"已验证的工作范围",16,INK); gap(scope,15);
        TextView details=label(scope,policy.profile.name+" / "+policy.profile.device+"\n"+(policy.profile.runtimeMount?"HyperOS 4.0.24.0 · 三组 CPU 曲线\n临时 Root · 重启恢复原厂":"HyperOS 4.0.0.19 · 两组 CPU 曲线")+"\n\n只对匹配版本启用修改。系统更新后若校验不匹配，会拒绝应用旧配置。",12,MUTED); details.setLineSpacing(dp(this,6),1);
        LinearLayout recovery=card(body,20); label(recovery,"恢复与卸载",16,INK); gap(recovery,12);
        TextView text=label(recovery,"手动停止、时间到、温度触线或守护异常，都会恢复原厂配置。\n\n卸载前请先点“恢复原厂”。持续模式的开机继续也会一并关闭。",12,MUTED); text.setLineSpacing(dp(this,5),1);
        TextView b=button("立即恢复原厂",BG,AMBER); gap(recovery,17); recovery.addView(b,new LinearLayout.LayoutParams(-1,dp(this,46))); b.setOnClickListener(v -> hub.stop());
        label(body,"Pulse 1.5  ·  无网络权限",11,MUTED); gap(body,10);
    }
    void exportDialog() { Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Pulse-Thermal-"+System.currentTimeMillis()+".csv"); startActivityForResult(i,18); }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(request!=18 || result!=RESULT_OK || data==null) return;
        Uri uri=data.getData(); hub.worker.execute(() -> { try { String csv=hub.engine.export(); if(csv.trim().isEmpty()) throw new IllegalStateException("还没有采样记录");
            try(OutputStream out=getContentResolver().openOutputStream(uri)) { if(out==null) throw new IllegalStateException("无法打开导出文件"); out.write(csv.getBytes(StandardCharsets.UTF_8)); }
            runOnUiThread(() -> Toast.makeText(this,"记录已导出",Toast.LENGTH_SHORT).show());
        } catch(Exception e) { runOnUiThread(() -> Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show()); } });
    }
    void save() { policy.save(this); }
    void startPolicy() {
        try { policy.validate(); } catch(Exception e) { Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show(); return; }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},19);
        policy.save(this); startForegroundService(new Intent(this,ThermalService.class).setAction(ThermalService.START));
    }
    @Override public void update() {
        if(!resumed) return;
        Snapshot s=hub.snapshot; boolean active=s.active();
        connectionStatus.setText(hub.message);
        rootBadge.setText(hub.busy?"处理中":hub.connected?"● ROOT 已连接":"连接 ROOT"); rootBadge.setTextColor(hub.connected?TEAL:AMBER);
        apply.setText(hub.busy?"处理中…":s.paused()?"等待场景恢复":active?"策略运行中":hub.connected?"应用策略":"连接并应用策略");
        apply.setEnabled(!hub.busy && !active); apply.setAlpha((hub.busy||active)?.55f:1);
        restore.setEnabled(!hub.busy && (hub.connected||hub.engine.rootGranted)); restore.setText(s.recoveryFailed()?"重试恢复":"恢复原厂");
        if(heroTitle!=null) {
            heroTitle.setText(s.paused()?"策略已暂停":active?(s.s("mode").equals("fullmax")?"全频释放":s.s("mode").equals("custom")?"逐档自定义":"阶梯增强 +"+s.n("boost_percent")+"%"):s.recoveryFailed()?"恢复待确认":!hub.statusKnown?"正在核对状态":"原厂温控");
            heroSub.setText(s.paused()?s.reason():active?"频率曲线已放开 · 达到停止条件自动恢复":s.s("state").equals("restored")?s.reason():"按需放开频率，让每次测试都有据可查。");
            countdown.setText(s.countdown());
            for(int group:policy.profile.groups) { frequencies.get(group).setText(s.frequency("freq"+group)); maxima.get(group).setText("上限 "+s.frequency("max"+group)+" GHz"); }
            tempBattery.setText(s.temperature("battery_dc",10)); tempBody.setText(s.temperature("body_mc",1000)); tempCpu.setText(s.temperature("cpu_mc",1000));
            chart.add(s); message.setText(hub.message);
        }
        if(recordReason!=null) {
            recordReason.setText(active?"策略正在运行":s.reason()); long elapsed=s.n("elapsed"); recordTime.setText("运行时长 "+elapsed/60+" 分 "+elapsed%60+" 秒");
            recordLimits.setText(s.s("session").isEmpty()?"还没有运行策略":"电池 "+s.n("battery_limit")/10+"°C  ·  机身 "+s.n("body_limit")/1000+"°C"+(s.n("cpu_limit")>0?"\nCPU 热点 "+s.n("cpu_limit")/1000+"°C":"  ·  CPU 随系统保护"));
        }
    }
}
