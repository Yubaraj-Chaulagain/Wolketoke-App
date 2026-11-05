package com.yubaraj.walkietalkie;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground keeper for Hands-Free mode. AudioRecord/network capture remains
 * in MainActivity's process; this service keeps that process and Wi-Fi awake
 * while the display is off. It must be started while the activity is visible. */
public class WalkieBackgroundService extends Service {
    private static final String CHANNEL = "walkie_background";
    private static final int NOTIFICATION_ID = 78;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        // Promote to foreground immediately, before acquiring long-running locks.
        startForegroundCompat(buildNotification());
        acquireLocks();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Walkie-Talkie background", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps Hands-Free voice communication active while the screen is off");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setContentTitle("Wolketoke Walkie-Talkie is active")
            .setContentText("Hands-Free voice and Wi-Fi are active. Turn Hands-Free off to stop.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":WalkieHandsFree");
                wakeLock.setReferenceCounted(false);
                if (!wakeLock.isHeld()) wakeLock.acquire();
            }
        } catch (Exception ignored) { }

        try {
            WifiManager wm = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    getPackageName() + ":WalkieWiFi");
                wifiLock.setReferenceCounted(false);
                if (!wifiLock.isHeld()) wifiLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-assert foreground status if Android recreates this sticky service.
        startForegroundCompat(buildNotification());
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Do not stop: user may swipe the activity from Recents while using
        // the foreground Hands-Free session. Android Settings Force Stop still stops it.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
