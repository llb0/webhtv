package com.fongmi.android.tv.voice;

import android.text.TextUtils;

import java.util.regex.Pattern;

/**
 * 把一句中文（或中英混排）命令解析成 {@link VoiceCommand}。
 *
 * <p>规则优先、不依赖网络，命中不了就把整句当搜索关键词——语音输入本来就不精确，
 * "搜不到就当关键词搜"比"提示不支持"更符合点播场景的预期。</p>
 *
 * <p>支持的句式见"使用帮助 → 语音控制"，改规则时两边要一起改。</p>
 */
public final class VoiceCommandParser {

    /** 句尾常见的语气词/标点，解析前统一去掉。 */
    private static final Pattern TAIL = Pattern.compile("[。，、！？!?,.\\s]+$");
    private static final Pattern PREFIX = Pattern.compile("^(请|麻烦|帮我|给我|我要|我想|我想看|我要看|来|直接)");
    private static final Pattern POLITE = Pattern.compile("^(打开|启动|播放|看|切到|切换到|换到|转到|调至|调到)");

    private static final String[] NEXT_WORDS = {"下一集", "下一个", "下集", "换下一集", "next", "下一部"};
    private static final String[] PREV_WORDS = {"上一集", "上一个", "上集", "prev", "previous", "上一部"};
    private static final String[] PAUSE_WORDS = {"暂停", "停止", "pause", "停一下", "别放了"};
    private static final String[] PLAY_WORDS = {"继续", "继续播放", "恢复播放", "播放吧", "接着放", "play", "resume"};

    private static final String[] SEARCH_VERBS = {"搜索", "搜一下", "搜", "查找", "查一下", "找一下", "找"};
    private static final String[] PLAY_VERBS = {"播放", "看一下", "看看", "看", "打开"};
    private static final String[] LIVE_VERBS = {"看", "看直播", "打开", "播放", "切到", "切换到", "换到", "转到", "调到", "换"};
    private static final String[] LIVE_WORDS = {"台", "频道", "卫视", "cctv", "央视", "直播"};

    private VoiceCommandParser() {
    }

    public static VoiceCommand parse(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) return VoiceCommand.none();
        // 1. 播放控制优先：这些词很短，且经常单独出现。
        VoiceCommand control = control(text);
        if (control != null) return control;
        // 2. 直播：出现"台/频道/卫视/CCTV/直播"这类特征词时按台名处理。
        if (isLive(text)) return VoiceCommand.live(stripVerbs(text, LIVE_VERBS));
        // 3. "搜索 XXX" 只出搜索结果列表，不自动播。
        if (matchesAny(text, SEARCH_VERBS)) return VoiceCommand.search(stripVerbs(text, SEARCH_VERBS));
        // 4. "看/播放 XXX" 是直接全屏播放（"看CCTV5"已在第 2 步被拦走）。
        String stripped = stripVerbs(text, PLAY_VERBS);
        if (!stripped.isEmpty() && !stripped.equals(text)) return VoiceCommand.play(stripped);
        // 5. 兜底：整句当关键词搜索，不贸然替用户开播。
        return VoiceCommand.search(text);
    }

    private static VoiceCommand control(String text) {
        if (matchesAny(text, NEXT_WORDS) && text.length() <= 8) return VoiceCommand.control(VoiceCommand.Type.CONTROL_NEXT);
        if (matchesAny(text, PREV_WORDS) && text.length() <= 8) return VoiceCommand.control(VoiceCommand.Type.CONTROL_PREV);
        if (matchesAny(text, PAUSE_WORDS) && text.length() <= 8) return VoiceCommand.control(VoiceCommand.Type.CONTROL_PAUSE);
        if (matchesAny(text, PLAY_WORDS) && text.length() <= 8) return VoiceCommand.control(VoiceCommand.Type.CONTROL_PLAY);
        return null;
    }

    private static boolean isLive(String text) {
        String lower = text.toLowerCase();
        for (String word : LIVE_WORDS) if (lower.contains(word)) return true;
        // "看中央五套" / "中央一台" 这类没写"台"字的，靠"中央"+数字组合判断。
        return lower.contains("中央") && lower.matches(".*第?[一二三四五六七八九十0-9]+(套|台|频道).*");
    }

    /** 去掉动词前缀，留下真正的频道名/片名。 */
    private static String stripVerbs(String text, String[] verbs) {
        String result = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String verb : verbs) {
                if (result.startsWith(verb) && result.length() > verb.length()) {
                    result = result.substring(verb.length());
                    changed = true;
                }
            }
            String[] prefixes = {"一下", "看", "我要", "我想"};
            for (String prefix : prefixes) {
                if (result.startsWith(prefix) && result.length() > prefix.length()) {
                    result = result.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return result.trim();
    }

    private static boolean matchesAny(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        text = TAIL.matcher(text).replaceAll("");
        text = PREFIX.matcher(text).replaceAll("");
        text = POLITE.matcher(text).replaceAll("");
        // 中文里夹的全角空格/引号：去掉，避免台名匹配失败。
        text = text.replaceAll("[\u3000\"]", "").trim();
        return TextUtils.isEmpty(text) ? "" : text;
    }
}
