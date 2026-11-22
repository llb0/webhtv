package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.github.catvod.crawler.diagnostics.DiagnosticText;

import java.util.Locale;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

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
     * 一条消息 = 一条记录，换行原样保留在消息里。
     *
     * <p>不能按行拆：源站（XBPQ 这类 jar）会把整页 HTML/CSS/JSON 一次打出来，里面有几百个换行，
     * 其中还有只写了一个 {@code }} 的 CSS 行，按行拆会刷出几百条"接口插件日志"。
     * 只有消息里夹着另一条带时间戳的日志时（别人把多行日志拼进一个 message）才在那一行断开。</p>
     */
    private static void emit(String tag, String prefix, String message, boolean critical) {
        if (message == null || message.isEmpty()) return;
        String body = message.replace("\r\n", "\n").replace('\r', '\n');
        String[] chunks = body.split("\n(?=\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
        for (int i = 0; i < chunks.length; i++) {
            String chunk = chunks[i];
            if (chunk.isEmpty()) continue;
            DebugLogStore.add(tag, i == 0 ? prefix + chunk : chunk, critical); // 级别前缀只挂第一段
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
