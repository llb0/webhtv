package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.github.catvod.crawler.diagnostics.DiagnosticText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    /** 单条记录字数上限（分段阈值），以及一次最多记录几段。 */
    private static final int CHUNK_CHARS = 3000;
    private static final int MAX_CHUNKS = 10;

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
            DebugLogStore.add(tag, prefix + record, critical);
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
