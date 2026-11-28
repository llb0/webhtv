package com.fongmi.android.tv.voice;

/**
 * 一条语音/文字指令的解析结果：类型 + 参数。
 *
 * <p>解析只在 TV 端做一次（服务端 {@code /voice?cmd=say} 收到原文后解析再执行），
 * 手机端只负责把文本发出去，避免两端各维护一套规则。</p>
 */
public record VoiceCommand(Type type, String arg) {

    public enum Type {
        SEARCH,
        PLAY,
        LIVE,
        HISTORY,
        COLLECT,
        SITE,
        CONTROL_PLAY,
        CONTROL_PAUSE,
        CONTROL_NEXT,
        CONTROL_PREV,
        CONTROL_VOLUME_UP,
        CONTROL_VOLUME_DOWN,
        NONE
    }

    public static VoiceCommand none() {
        return new VoiceCommand(Type.NONE, "");
    }

    public static VoiceCommand search(String word) {
        return new VoiceCommand(Type.SEARCH, word);
    }

    public static VoiceCommand play(String name) {
        return new VoiceCommand(Type.PLAY, name);
    }

    public static VoiceCommand live(String name) {
        return new VoiceCommand(Type.LIVE, name);
    }

    public static VoiceCommand open(Type type) {
        return new VoiceCommand(type, "");
    }

    public static VoiceCommand site(String name) {
        return new VoiceCommand(Type.SITE, name);
    }

    public static VoiceCommand control(Type type) {
        return new VoiceCommand(type, "");
    }

    public boolean isEmpty() {
        if (type == Type.NONE) return true;
        // LIVE 允许空参数（"看直播"就是不指定台直接开直播页），其它需要关键词的类型才要求参数。
        return (arg == null || arg.isEmpty()) && (type == Type.SEARCH || type == Type.PLAY || type == Type.SITE);
    }
}
