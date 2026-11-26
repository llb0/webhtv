package com.fongmi.android.tv.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.ui.activity.SmbBrowserActivity;

import java.io.File;

/**
 * 分享期间的常驻服务：服务器跑在进程里，没有前台服务的话切后台/息屏会被系统收掉，
 * 对面正播到一半就断了。通知里直接给出可用的局域网地址，点开进 SMB 浏览页。
 */
public class SmbShareService extends Service {

    private static final String CHANNEL = "smb_share";
    private static final int NOTIFY_ID = 0x5B11;

    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, SmbShareService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent);
            else context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SmbShareService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        startForeground(NOTIFY_ID, buildNotification());
        acquireWakeLock();
        if (!SmbShareController.isSharing(this)) {
            File root = SmbShareController.root(this);
            if (!SmbShareController.startServer(this, root, SmbShareController.port(this))) {
                // 起不来（端口被占/目录不可读）就别占着通知。
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        SmbShareController.stopServer(this);
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, SmbBrowserActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("正在分享本机文件")
                .setContentText(SmbShareController.label(this))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(SmbShareController.label(this)))
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pending)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "局域网文件分享", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (manager != null) {
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebHTV:SmbShare");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }
}
