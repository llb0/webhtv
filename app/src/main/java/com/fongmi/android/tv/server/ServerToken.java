package com.fongmi.android.tv.server;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 语音控制配对令牌。
 *
 * <p>{@code /voice} 是唯一需要鉴权的本地接口：它能把"搜索、换台、暂停/下一集"打到电视上，
 * 等同于一块遥控器，所以局域网内其它设备必须先拿到这个令牌。</p>
 *
 * <p>令牌第一次用到时随机生成并持久化，只在电视端显示/确认（手机端通过 {@code /voice?cmd=pair}
 * 触发电视端弹窗，用户用遥控器点"允许"后才拿到明文），不会出现在 {@code /device} 之类的
 * 公开接口里。</p>
 */
public final class ServerToken {

    private static final String KEY = "voice_control_token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ServerToken() {
    }

    public static synchronized String get() {
        String token = Prefers.getString(KEY, "");
        if (!TextUtils.isEmpty(token)) return token;
        token = randomToken();
        Prefers.put(KEY, token);
        return token;
    }

    public static synchronized void reset() {
        Prefers.put(KEY, randomToken());
    }

    public static boolean matches(String candidate) {
        if (TextUtils.isEmpty(candidate)) return false;
        return constantTimeEquals(get(), candidate);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected.length() != actual.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) diff |= expected.charAt(i) ^ actual.charAt(i);
        return diff == 0;
    }

    private static String randomToken() {
        StringBuilder builder = new StringBuilder(8);
        for (int i = 0; i < 8; i++) builder.append(Integer.toHexString(RANDOM.nextInt(16)));
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
