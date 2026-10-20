package com.fongmi.android.tv.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fongmi.android.tv.R;

import java.util.HashMap;
import java.util.Map;

public class DownloadService extends Service {

    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFY_ID = 1001;

    public static void start(Context ctx, String url, java.util.Map<String, String> headers, String title) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.putExtra("url", url);
        i.putExtra("title", title);
        if (headers != null) i.putExtra("headers", new HashMap<>(headers));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.download_notify_channel),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String url = intent.getStringExtra("url");
        String title = intent.getStringExtra("title");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) intent.getSerializableExtra("headers");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_control_download_shadow)
                .setContentTitle(title != null ? title : getString(R.string.download_notify_title))
                .setContentText(getString(R.string.download_notify_title))
                .setProgress(100, 0, false)
                .setOngoing(true);

        startForeground(NOTIFY_ID, builder.build());

        DownloadManager.get().setListener(new DownloadManager.ProgressListener() {
            @Override public void onProgress(String taskId, int percent, long d, long t) {
                builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
                builder.setContentText(percent >= 0 ? percent + "%" : getString(R.string.download_notify_title));
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIFY_ID, builder.build());
            }
            @Override public void onComplete(String taskId) {
                stop();
                notifyDone(getString(R.string.download_done_notify_title), title);
            }
            @Override public void onFailed(String taskId, String reason) {
                stop();
                notifyDone(getString(R.string.download_failed_notify_title), reason);
            }
        });

        DownloadManager.get().enqueue(url, title, headers);
        return START_NOT_STICKY;
    }

    private void stop() {
        stopForeground(true);
        stopSelf();
    }

    private void notifyDone(String title, String text) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_control_download_shadow)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setProgress(0, 0, false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_ID + 1, b.build());
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
