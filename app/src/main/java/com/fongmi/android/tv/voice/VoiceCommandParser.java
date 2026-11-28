package com.fongmi.android.tv.voice;

import android.text.TextUtils;

import java.util.regex.Pattern;

/**
 * 把一句中文（或中英混排）命令解析成 {@link VoiceCommand}。
 *
 * <p>规则优先、不依赖网络，命中不了就把整句当搜索关键词——语音输入本来就不精确，
 * "搜不到就当关键词搜"比"提示不支持"更符合点播场景的预期。</p>
 *
 * <p>关键区别：{@code 搜索X} 只出结果列表，{@code 看X} 直接全屏播放；
 * {@code 看直播/打开直播} 不指定台时直接进直播页、不切台。
 * 支持的句式见"使用帮助 → 语音控制"，改规则时两边要一起改。</p>
 */
public final class VoiceCommandParser {

    /** 句尾常见的语气词/标点，解析前统一去掉。 */
    private static final Pattern TAIL = Pattern.compile("[。，、！？!?,.\\s]+$");
    private static final Pattern PREFIX = Pattern.compile("^(请|麻烦|帮我|给我|我要|我想|我想看|我要看|来|直接)");

    private static final String[] NEXT_WORDS = {"下一集", "下一个", "下集", "next"};
    private static final String[] PREV_WORDS = {"上一集", "上一个", "上集", "prev", "previous"};
    private static final String[] PAUSE_WORDS = {"暂停", "停止", "pause", "停一下"};
    private static final String[] PLAY_WORDS = {"继续", "继续播放", "恢复播放", "接着放", "play", "resume"};
    private static final String[] VOLUME_UP_WORDS = {"音量加", "音量+", "音量大", "调大音量", "调高音量", "声音大点", "声音调大", "大声点", "开大点", "volume up", "louder"};
    private static final String[] VOLUME_DOWN_WORDS = {"音量减", "音量-", "音量小", "调小音量", "调低音量", "声音小点", "声音调小", "小声点", "关小点", "volume down", "quieter"};

    private static final String[] SEARCH_VERBS = {"搜索", "搜一下", "搜", "查找", "查一下", "找一下", "找"};
    private static final String[] PLAY_VERBS = {"播放", "看一下", "看看", "看"};
    private static final String[] LIVE_WORDS = {"台", "频道", "卫视", "cctv", "央视", "直播"};

    /** "看直播"这类不指定台的说法，参数就是这些词本身，由直播页直接打开不切台。 */
    private static final String[] LIVE_PLACEHOLDERS = {"直播", "电视", "直播频道", "电视台"};

    private VoiceCommandParser() {
    }

    public static VoiceCommand parse(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) return VoiceCommand.none();
        // 1. 播放控制优先：这些词很短，且经常单独出现。
        VoiceCommand control = control(text);
        if (control != null) return control;
        // 2. 历史 / 收藏：先判，避免"打开历史"被当成开播片名。
        if (matchesAny(text, new String[]{"历史", "最近观看", "观看记录"})) return VoiceCommand.open(VoiceCommand.Type.HISTORY);
        if (matchesAny(text, new String[]{"收藏", "追剧"})) return VoiceCommand.open(VoiceCommand.Type.COLLECT);
        // 3. 切换站点（"切换站点XX" / "选择站点XX"）：站点名里的 | PQ、[H] 标签可省略。
        if (text.contains("站点") || text.startsWith("切换")) {
            String site = stripVerbs(text, new String[]{"切换到", "切换", "选择", "换到", "换", "站点", "站"});
            if (!site.isEmpty() && !site.equals(text)) return VoiceCommand.site(site);
        }
        // 4. 直播：出现"台/频道/卫视/CCTV/直播"特征词按台名处理；只说了"看直播"就裸开直播页。
        if (isLive(text)) {
            String channel = stripVerbs(text, new String[]{"看", "打开", "播放", "切到", "切换到", "换到", "转到", "调到", "换"});
            for (String placeholder : LIVE_PLACEHOLDERS) if (placeholder.equals(channel)) return VoiceCommand.live("");
            return VoiceCommand.live(channel);
        }
        // 5. "搜索 XXX" 只出搜索结果列表，不自动播。
        if (matchesAny(text, SEARCH_VERBS)) return VoiceCommand.search(stripVerbs(text, SEARCH_VERBS));
        // 6. "看/播放 XXX" 是直接全屏播放。
        String play = stripVerbs(text, PLAY_VERBS);
        if (!play.isEmpty() && !play.equals(text)) return VoiceCommand.play(play);
        // 7. 不再兜底搜索：普通话不标准、说错指令时整句当关键词会不停乱搜，直接判为"没听清"。
        return VoiceCommand.none();
    }

    private static VoiceCommand control(String text) {
        if (matchesAny(text, NEXT_WORDS) && text.length() <= 8) return VoiceCommand.control(VoiceCommand.Type.CONTROL_NEXT);
        if (matchesAny(text, PREV_WORDS) && text.length() <= 8) return VoiceCommand.control(VoiceCommand.Type.CONTROL_PREV);
        if (matchesAny(text, PAUSE_WORDS) && text.length() <= 8) return VoiceCommand.control(VoiceCommand.Type.CONTROL_PAUSE);
        if (matchesAny(text, PLAY_WORDS) && text.length() <= 8) return VoiceCommand.control(VoiceCommand.Type.CONTROL_PLAY);
        // 音量放在最后：词里常带"声音/大点"这类泛词，先让暂停、播放等精确短句命中。
        if (matchesAny(text, VOLUME_UP_WORDS)) return VoiceCommand.control(VoiceCommand.Type.CONTROL_VOLUME_UP);
        if (matchesAny(text, VOLUME_DOWN_WORDS)) return VoiceCommand.control(VoiceCommand.Type.CONTROL_VOLUME_DOWN);
        return null;
    }

    private static boolean isLive(String text) {
        String lower = text.toLowerCase();
        for (String word : LIVE_WORDS) if (lower.contains(word)) return true;
        // "看中央五套" / "中央一台" 这类没写"台"字的，靠"中央"+数字组合判断。
        return lower.contains("中央") && lower.matches(".*第?[一二三四五六七八九十0-9]+(套|台|频道).*");
    }

    /** 去掉动词/前缀，留下真正的频道名、片名、站点名。 */
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
            for (String prefix : new String[]{"一下", "看", "我要", "我想"}) {
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
        // 只去掉礼貌前缀；"看/播放"留给后面判断"搜索还是直接播"。
        text = PREFIX.matcher(text).replaceAll("");
        text = text.replaceAll("[\u3000\"]", "").trim();
        return TextUtils.isEmpty(text) ? "" : text;
    }
}
