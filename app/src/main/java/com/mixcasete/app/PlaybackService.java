package com.mixcasete.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class PlaybackService extends Service {

    public static final String CHANNEL = "mixcasete_play";
    public static final String EXTRA_CMD = "cmd";
    private PowerManager.WakeLock wl;

    public static void start(android.app.Activity a) {
        Intent i = new Intent(a, PlaybackService.class);
        if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i);
        else a.startService(i);
    }

    public static void stop(android.app.Activity a) {
        a.stopService(new Intent(a, PlaybackService.class));
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        crearCanal();
        String cmd = intent != null ? intent.getStringExtra(EXTRA_CMD) : null;
        if (cmd != null && MainActivity.self != null && MainActivity.self.get() != null) {
            MainActivity.self.get().onMediaCommand(cmd);
        }
        startForeground(1, notificacion());
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mixcasete:play");
        }
        if (!wl.isHeld()) wl.acquire(10 * 60 * 1000L);
        return START_STICKY;
    }

    private Notification notificacion() {
        Intent pause = new Intent(this, PlaybackService.class);
        pause.putExtra(EXTRA_CMD, "pause");
        Intent play = new Intent(this, PlaybackService.class);
        play.putExtra(EXTRA_CMD, "play");
        Intent stop = new Intent(this, PlaybackService.class);
        stop.putExtra(EXTRA_CMD, "stop");
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pP = PendingIntent.getService(this, 1, pause, fl);
        PendingIntent pR = PendingIntent.getService(this, 2, play, fl);
        PendingIntent pS = PendingIntent.getService(this, 3, stop, fl);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("🎵 Mix.Casete")
                .setContentText("Reproduciendo en segundo plano")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Pausa", pP)
                .addAction(android.R.drawable.ic_media_play, "Seguir", pR)
                .addAction(android.R.drawable.ic_delete, "Parar", pS)
                .build();
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Audio de Mix.Casete en segundo plano");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
