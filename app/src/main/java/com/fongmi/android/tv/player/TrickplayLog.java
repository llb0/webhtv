package com.fongmi.android.tv.player;

import android.os.SystemClock;

import com.github.catvod.crawler.SpiderDebug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽帧的诊断日志入口：同一类消息按最小间隔节流，避免后台每抽一格就刷一条把播放日志顶掉。
 *
 * <p>节流只作用在“高频过程日志”（每格进度、暂停轮询、每窗状态）；失败原因照常记，
 * 只是同一条失败在 5 秒内不重复刷屏。被省略的条数会附在下一条里，不丢量级信息。</p>
 */
public final class TrickplayLog {

    /** 播放日志分类下有“播放流程/网络/系统”等多类，用不落在“播放流程”的 tag，避免分类关掉就全丢。 */
    public static final String TAG = "trickplay";

    private static final long WINDOW_MS = 600L;
    private static final long ERROR_WINDOW_MS = 5_000L;
    private static final Map<String, Bucket> BUCKETS = new ConcurrentHashMap<>();

    private TrickplayLog() {
    }

    /** 高频过程日志：同一 key 每 {@link #WINDOW_MS} 最多一条。 */
    public static void log(String key, String format, Object... args) {
        emit(key, WINDOW_MS, format, args);
    }

    /** 失败/关键节点：同一条内容 5 秒内只记一次，换内容立刻记。 */
    public static void once(String key, String format, Object... args) {
        emit(key, ERROR_WINDOW_MS, format, args);
    }

    /** 用消息首词当 key：同类消息共用一个节流桶。 */
    public static String keyOf(String format) {
        if (format == null) return "";
        int end = 0;
        while (end < format.length() && format.charAt(end) != ' ' && format.charAt(end) != '%'
               && format.charAt(end) != '=') end++;
        return format.substring(0, end);
    }

    private static void emit(String key, long window, String format, Object... args) {
        if (format == null) return;
        String message;
        try {
            message = args == null || args.length == 0 ? format : String.format(format, args);
        } catch (Throwable error) {
            message = format;
        }
        String bucketKey = key == null ? "" : key;
        Bucket bucket = BUCKETS.computeIfAbsent(bucketKey, ignored -> new Bucket());
        long now = SystemClock.elapsedRealtime();
        synchronized (bucket) {
            if (now - bucket.lastAt < window) {
                bucket.suppressed++;
                return;
            }
            long suppressed = bucket.suppressed;
            bucket.suppressed = 0;
            bucket.lastAt = now;
            if (suppressed > 0) message = message + "（另有 " + suppressed + " 条同类已省略）";
        }
        try {
            SpiderDebug.log(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private static final class Bucket {
        long lastAt;
        long suppressed;
    }
}
