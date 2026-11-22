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
     * 一条消息按行拆成多条记录再入日志。
     *
     * <p>源站（XBPQ 这类 jar）经常把整段 HTML/JSON 一次打出来：以前整段当成一条记录，
     * 换行在存储层会被转义成字面的 {@code \n}，于是"好几行日志"变成一条没有换行的超长行，
     * 撞上 1.2 万字符上限后更是整段被截断成一条怪物。按行拆开后每条都是独立记录：
     * 行数、时间戳、分类都正常，也不会互相拖累到被截断。</p>
     */
    private static void emit(String tag, String prefix, String message, boolean critical) {
        if (message == null || message.isEmpty()) return;
        int start = 0;
        String head = prefix;
        while (start <= message.length()) {
            int newline = message.indexOf('\n', start);
            String line = newline < 0 ? message.substring(start) : message.substring(start, newline);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (!line.isEmpty()) DebugLogStore.add(tag, head + line, critical);
            head = ""; // 级别前缀只挂在第一行，续行就是原文
            if (newline < 0) break;
            start = newline + 1;
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
