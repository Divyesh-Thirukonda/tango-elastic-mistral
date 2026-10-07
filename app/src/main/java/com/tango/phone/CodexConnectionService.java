package com.tango.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps an explicitly connected USB session visible and gives the user a one-tap revocation control. */
public final class CodexConnectionService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable checkAccount=new Runnable() {
        @Override public void run() {if(!CodexConnection.accountValid())stopSelf();else main.postDelayed(this,2000);}
    };
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("codex_connection","Codex connection",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        PendingIntent open=PendingIntent.getActivity(this,86,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disconnect=PendingIntent.getService(this,87,new Intent(this,CodexConnectionService.class).setAction("disconnect"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(86,new Notification.Builder(this,"codex_connection").setSmallIcon(R.drawable.ic_tango)
            .setContentTitle("Codex is connected to Tango").setContentText("USB connection · prompts and phone observations")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Disconnect",disconnect).build()).build());
        if(intent==null || "disconnect".equals(intent.getAction()) || !CodexConnection.startListener()) {CodexConnection.disconnect(this);stopSelf();return START_NOT_STICKY;}
        main.removeCallbacks(checkAccount);main.postDelayed(checkAccount,2000);return START_NOT_STICKY;
    }
    @Override public void onDestroy() {main.removeCallbacks(checkAccount);CodexConnection.serviceDestroyed();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent) {return null;}
}
