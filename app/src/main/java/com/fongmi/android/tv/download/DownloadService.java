package com.fongmi.android.tv.download;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** 后台下载服务：前台常驻，状态栏显示进度条。 */
public class DownloadService extends Service {
    private static final int NOTIFY_ID = 8001;
    private static final String CHANNEL = Notify.DEFAULT;  // 复用 Notify.createChannel() 已创建的 "default" 频道

    private final Map<String, Integer> taskNotifyIds = new HashMap<>();
    private long lastNotifyTime;  // 进度通知节流时间戳

    public static void start(Context ctx, String name, String url, Map<String, String> headers) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.putExtra("name", name);
        i.putExtra("url", url);
        i.putExtra("headers", new HashMap<>(headers != null ? headers : new HashMap<>()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void cancel(Context ctx, String taskId) {
        DownloadManager.get().cancelTask(taskId);
        ctx.stopService(new Intent(ctx, DownloadService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        Notify.createChannel();
        // 确保下载目录存在
        new File(Setting.getDownloadDir()).mkdirs();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String name = intent.getStringExtra("name");
        String url = intent.getStringExtra("url");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) intent.getSerializableExtra("headers");

        startForeground(NOTIFY_ID, buildProgressNotification(getString(R.string.download_notify_title), name, 0, 0).build());

        File outputDir = new File(Setting.getDownloadDir());
        outputDir.mkdirs();
        DownloadManager.get().setListener(new DownloadManager.Listener() {
            @Override public void onTaskStarted(DownloadTask task) {
                taskNotifyIds.put(task.id, NOTIFY_ID + Math.abs(task.id.hashCode()) % 1000);
                updateNotification(task, getString(R.string.download_notify_title));
            }
            @Override public void onTaskProgress(DownloadTask task, long bytes, long total) {
                // 分片每读 64KB 回调一次，这里节流，避免高频 notify 拖垮系统
                long now = SystemClock.elapsedRealtime();
                if (now - lastNotifyTime < 500) return;
                lastNotifyTime = now;
                updateNotification(task, getString(R.string.download_notify_title));
            }
            @Override public void onTaskCompleted(DownloadTask task, File output) {
                task.status = DownloadTask.Status.COMPLETED;
                showNotification(task, getString(R.string.download_done_notify_title), android.R.drawable.stat_sys_download_done);
                stopIfIdle();
            }
            @Override public void onTaskFailed(DownloadTask task, String msg) {
                showNotification(task, getString(R.string.download_failed_notify_title), android.R.drawable.stat_notify_error);
                stopIfIdle();
            }
            @Override public void onTaskCancelled(DownloadTask task) {
                stopIfIdle();
            }
        });

        DownloadManager.get().addTask(name, url, headers, outputDir);
        return START_STICKY;
    }

    /** 仅当没有正在进行的任务时才停服务，避免多任务并行时把别人的下载一起杀掉。 */
    private void stopIfIdle() {
        if (DownloadManager.get().getActiveCount() == 0) stopSelf();
    }
 
    private void updateNotification(DownloadTask task, String title) {
        int nid = taskNotifyIds.getOrDefault(task.id, NOTIFY_ID);
        NotificationCompat.Builder nb = buildProgressNotification(title, task.name, task.downloadedBytes, task.totalSize);
        getSystemService(NotificationManager.class).notify(nid, nb.build());
    }

    private void showNotification(DownloadTask task, String title, int icon) {
        int nid = taskNotifyIds.getOrDefault(task.id, NOTIFY_ID);
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(task.name)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false);
        getSystemService(NotificationManager.class).notify(nid, nb.build());
    }

    private NotificationCompat.Builder buildProgressNotification(String title, String name, long bytes, long total) {
        int progress = total > 0 ? (int) (bytes * 100 / total) : 0;
        String text;
        if (total > 0) {
            text = String.format("%s  %d%%  %s / %s", name, progress, formatSize(bytes), formatSize(total));
        } else {
            text = String.format("%s  %s downloaded", name, formatSize(bytes));
        }
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (total > 0) nb.setProgress(100, progress, false);
        else nb.setProgress(0, 0, true);
        return nb;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format("%.1f %s", v, units[i]);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        DownloadManager.get().setListener(null);
        super.onDestroy();
    }
}
