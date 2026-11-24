package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.github.catvod.crawler.diagnostics.DiagnosticText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    /** 单条记录字数上限（分段阈值），以及一次最多记录几段。 */
    private static final int CHUNK_CHARS = 3000;
    private static final int MAX_CHUNKS = 10;

    /**
     * 节流：同一条 tag 下形状相同的消息（号码位归一化后）每秒最多留 3 条，突发 3 条。
     * 播放进度、歌词刷新这类每秒好几条的东西不再把有用的几步冲走；
     * 省掉的条数会附在下一条上，不是完全屏蔽。
     */
    private static final int THROTTLE_TOKENS = 3;
    private static final long THROTTLE_WINDOW_MS = 1000L;
    private static final int BUCKET_LIMIT = 512;
    /** 已经在调用点自己节流的 tag，这里不重复节流（否则会出现两条"已省略"）。 */
    private static final Set<String> THROTTLE_EXEMPT = Set.of("trickplay");
    private static final Map<String, Bucket> THROTTLE = new ConcurrentHashMap<>();

    private static final class Bucket {
        long windowStart;
        int used;
        int suppressed;
    }

    public static boolean isEnabled() {
        return DebugLogStore.isEnabled();
    }

    /** Capture only: the runtime remains the sole owner of its Logcat/stdout output. */
    public static void console(String tag, String level, String message) {
        if (!DebugLogStore.acceptsTag(tag) || message == null || message.isEmpty()) return;
        String severity = switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "DEBUG", "LOG" -> "DEBUG";
            case "WARN", "WARNING" -> "WARN";
            case "ERROR", "FATAL" -> "ERROR";
            case "STDERR" -> "STDERR"; // A stream is not a severity (logging.info can use stderr).
            default -> "INFO";
        };
        try {
            emit(tag, "[" + severity + "] ", message, "ERROR".equals(severity));
        } catch (RuntimeException ignored) {
            DebugLogStore.collectorFailure();
        }
    }

    public static void log(Throwable th) {
        log(TAG, th);
    }

    public static void log(String tag, Throwable th) {
        if (th == null) return;
        if (!DebugLogStore.acceptsTag(tag)) return;
        String message = DiagnosticText.throwable(th);
        DebugLogStore.add(tag, message, true);
        Logger.t(safeTag(tag)).e(message);
    }

    public static void log(String msg) {
        if (TextUtils.isEmpty(msg)) return;
        if (!DebugLogStore.acceptsTag(TAG)) return;
        emit(TAG, "", msg, false);
        Logger.t(TAG).d(DiagnosticText.clean(msg).text());
    }

    public static void log(String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        if (!DebugLogStore.acceptsTag(tag)) return;
        String message = format(msg, args);
        emit(tag, "", message, false);
        Logger.t(safeTag(tag)).d(DiagnosticText.clean(message).text());
    }

    /**
     * 一条消息 = 一条记录，换行原样保留在消息里；只有整段贴上来超过 {@link #CHUNK_CHARS} 的
     * 大块内容（整页 HTML、整段 JSON）才按字数切成带编号的若干条。
     *
     * <p>不能按行拆：源站（XBPQ 这类 jar）会把整页 HTML/CSS/JSON 一次打出来，里面有几百个换行，
     * 其中还有只写了一个 } 的 CSS 行，按行拆会刷出几百条"接口插件日志"。
     * 只有消息里夹着另一条带时间戳的日志时（别人把多行日志拼进一个 message）才在那一行断开。</p>
     */
    private static void emit(String tag, String prefix, String message, boolean critical) {
        if (message == null || message.isEmpty()) return;
        String body = message.replace("\r\n", "\n").replace('\r', '\n');
        String[] records = body.split("\n(?=\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
        for (int i = 0; i < records.length; i++) {
            String record = records[i];
            if (record.isEmpty()) continue;
            emitChunks(tag, i == 0 ? prefix : "", record, critical); // 级别前缀只挂第一条
        }
    }

    /** 超长内容按字数切段编号，避免单条记录撞上 1.2 万上限后被一刀截断。 */
    private static void emitChunks(String tag, String prefix, String record, boolean critical) {
        if (record.length() <= CHUNK_CHARS) {
            if (critical) { // 崩溃这类关键条目一律不节流
                DebugLogStore.add(tag, prefix + record, true);
                return;
            }
            String allowed = throttle(tag, record);
            if (allowed == null) return;
            DebugLogStore.add(tag, prefix + allowed, false);
            return;
        }
        List<String> parts = new ArrayList<>();
        int pos = 0;
        while (pos < record.length()) {
            int end = Math.min(record.length(), pos + CHUNK_CHARS);
            if (end < record.length()) {
                int newline = record.lastIndexOf('\n', end);
                if (newline > pos) end = newline + 1; // 尽量在换行处断开
            }
            parts.add(record.substring(pos, end));
            pos = end;
        }
        int total = parts.size();
        int emitted = Math.min(total, MAX_CHUNKS);
        for (int i = 0; i < emitted; i++) {
            String mark = "(" + (i + 1) + "/" + total + ") ";
            DebugLogStore.add(tag, (i == 0 ? prefix : "") + mark + parts.get(i), critical && i == 0);
        }
        if (total > emitted) {
            DebugLogStore.add(tag, "〔共 " + record.length() + " 字，仅保留第 1-" + emitted + " 段，其余 "
                    + (total - emitted) + " 段未记录〕", false);
        }
    }

    /**
     * 返回应该写出去的消息（必要时带上"已省略"提示），被压掉时返回 null。
     */
    private static String throttle(String tag, String record) {
        if (record == null || record.isEmpty() || tag == null) return record;
        if (THROTTLE_EXEMPT.contains(tag)) return record;
        String shape = shapeOf(tag, record);
        Bucket bucket = THROTTLE.get(shape);
        if (bucket == null) {
            if (THROTTLE.size() > BUCKET_LIMIT) THROTTLE.clear();
            bucket = new Bucket();
            Bucket existing = THROTTLE.putIfAbsent(shape, bucket);
            if (existing != null) bucket = existing;
        }
        synchronized (bucket) {
            long now = System.currentTimeMillis();
            if (now - bucket.windowStart >= THROTTLE_WINDOW_MS) {
                bucket.windowStart = now;
                bucket.used = 0;
            }
            if (bucket.used < THROTTLE_TOKENS) {
                bucket.used++;
                if (bucket.suppressed > 0) {
                    int skipped = bucket.suppressed;
                    bucket.suppressed = 0;
                    return record + "（另有 " + skipped + " 条同类已省略）";
                }
                return record;
            }
            bucket.suppressed++;
            return null;
        }
    }

    /** 把号码、十六进制这类每次都变的部分抹成 #，同形状的消息才归到一个桶里。 */
    private static String shapeOf(String tag, String record) {
        String text = record.length() > 160 ? record.substring(0, 160) : record;
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            builder.append(Character.isDigit(c) ? '#' : c);
        }
        return tag + '\u0000' + builder;
    }

    private static String safeTag(String tag) {
        return DiagnosticText.sanitizeTag(TextUtils.isEmpty(tag) ? TAG : tag);
    }

    private static String format(String msg, Object... args) {
        try {
            return args == null || args.length == 0 ? msg : String.format(Locale.US, msg, args);
        } catch (Throwable e) {
            return msg;
        }
    }
}
