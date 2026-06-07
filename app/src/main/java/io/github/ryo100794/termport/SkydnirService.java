package io.github.ryo100794.termport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;

public class SkydnirService extends Service {
    static final String ACTION_START = "io.github.ryo100794.termport.skydnir.START";
    static final String ACTION_STOP = "io.github.ryo100794.termport.skydnir.STOP";
    private static final String TAG = "SkydnirService";
    private static final String CHANNEL_ID = "termport-skydnir";
    private static final int NOTIFICATION_ID = 12375;
    private Thread engineThread;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean userStopped;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            userStopped = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        userStopped = false;
        startForegroundNotification();
        holdWakeLock();
        if (engineThread == null || !engineThread.isAlive()) startEngineThread();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "TermPort Skydnir", NotificationManager.IMPORTANCE_LOW));
            }
        }
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent openIntent = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), flags);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, new Intent(this, SkydnirService.class).setAction(ACTION_STOP), flags);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("TermPort Skydnir")
                .setContentText("Engine API is available through TermPort private socket")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startEngineThread() {
        engineThread = new Thread(() -> {
            try {
                File runtime = SkydnirRuntime.prepare(getApplicationContext());
                File home = SkydnirRuntime.homeDir(getApplicationContext());
                File sock = SkydnirRuntime.socketFile(getApplicationContext());
                if (sock.exists()) sock.delete();
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                Python.getInstance().getModule("skydnir_bridge").callAttr(
                        "run_engine",
                        sock.getAbsolutePath(),
                        home.getAbsolutePath(),
                        runtime.getAbsolutePath());
            } catch (Throwable t) {
                Log.e(TAG, "Skydnir engine crashed", t);
            } finally {
                if (!userStopped) stopSelf();
            }
        }, "termport-skydnir");
        engineThread.start();
    }

    private void holdWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) return;
            if (wakeLock == null) wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":skydnir");
            if (!wakeLock.isHeld()) wakeLock.acquire(30000L);
        } catch (Exception e) {
            Log.w(TAG, "wake lock failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }
}
