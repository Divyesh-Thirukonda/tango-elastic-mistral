package com.tango.phone;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.Intent;

/** A small, always available control using the already-enabled accessibility service. */
public final class AgentOverlay {
    private View view;
    private WindowManager manager;
    private TextView text;
    private Button pause;
    public void show(AgentService service) {
        PhoneAccessibilityService a = PhoneAccessibilityService.instance;
        if (a==null || view!=null) return;
        manager=(WindowManager)a.getSystemService(android.content.Context.WINDOW_SERVICE);
        LinearLayout row=new LinearLayout(a);
        int unit=Math.max(1,(int)(a.getResources().getDisplayMetrics().density+.5f));
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(10*unit,4*unit,4*unit,4*unit);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.BLACK); bg.setCornerRadius(10*unit); bg.setStroke(unit,0xFF555555); row.setBackground(bg);
        text=new TextView(a); text.setText("Tango"); text.setTextColor(Color.WHITE); text.setTextSize(13); text.setGravity(Gravity.CENTER_VERTICAL);
        text.setMaxLines(1);text.setContentDescription("Open Tango");row.addView(text,new LinearLayout.LayoutParams(64*unit,48*unit));
        text.setOnClickListener(v -> open(service));
        pause=new Button(a); pause.setText("Ⅱ"); pause.setTextSize(16); pause.setTextColor(Color.WHITE); pause.setBackgroundColor(Color.TRANSPARENT);pause.setPadding(0,0,0,0);pause.setMinWidth(0);pause.setContentDescription("Pause task");
        row.addView(pause,new LinearLayout.LayoutParams(48*unit,48*unit));
        pause.setOnClickListener(v -> { if ("running".equals(AgentService.state)) service.pauseTask("Paused."); else open(service); });
        Button stop=new Button(a); stop.setText("■"); stop.setTextColor(Color.WHITE); stop.setBackgroundColor(Color.TRANSPARENT);stop.setPadding(0,0,0,0);stop.setMinWidth(0);stop.setContentDescription("Stop task");
        row.addView(stop,new LinearLayout.LayoutParams(48*unit,48*unit)); stop.setOnClickListener(v -> service.stopTask());
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.END; params.x=12*unit; params.y=64*unit;
        try { manager.addView(row,params); view=row; } catch (RuntimeException ignored) { view=null; }
    }
    public void update(String state) { if (text!=null) {text.setText(state.equals("running")?"Tango":"Paused");text.setContentDescription(state.equals("running")?"Tango is working. Open Tango":"Task paused. Open Tango");pause.setText(state.equals("running")?"Ⅱ":"↗");pause.setContentDescription(state.equals("running")?"Pause task":"Open Tango");} }
    private void open(AgentService s) { Intent i=new Intent(s,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP); s.startActivity(i); }
    public void hide() { if (view!=null && manager!=null) try { manager.removeView(view); } catch (RuntimeException ignored) {} view=null; text=null; }
}
