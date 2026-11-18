package com.fongmi.android.tv.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.ui.activity.LiveActivity;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 语音换台的深链：手机说"看CCTV5"，电视端把频道解析出来后带着频道名启动直播页，
 * 直播页在渲染完分组后消费这个待选频道，直接切到对应分组并起播。
 *
 * <p>只按"分组内频道的台名/台号/节目名"匹配，隐藏分组和加密分组不参与，
 * 匹配不上返回 false，由调用方给手机端回 404，不静默乱开一个台。</p>
 */
public final class LiveDeepLink {

    /** 直播页要消费的待选频道名；消费一次即清除，避免返回/重进时又跳一次。 */
    public static final String EXTRA_CHANNEL = "voice_live_channel";

    private LiveDeepLink() {
    }

    /** 带频道名打开：先找台，找不到就直接开直播页（不切台）。 */
    public static boolean open(String name) {
        if (TextUtils.isEmpty(name)) return openPlain();
        Channel channel = find(name);
        Context context = App.get();
        Intent intent = new Intent(context, LiveActivity.class);
        if (channel != null) intent.putExtra(EXTRA_CHANNEL, channel.getName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return channel != null;
    }

    /** 只打开直播页，不指定频道（"看直播 / 打开直播"走这里）。 */
    public static boolean openPlain() {
        Context context = App.get();
        Intent intent = new Intent(context, LiveActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    /** 在所有可见分组里按台号 → 台名 → 节目名的顺序找，先精确再包含。 */
    public static Channel find(String name) {
        Live live = LiveConfig.get().getHome();
        return find(live, name);
    }

    public static Channel find(Live live, String name) {
        if (live == null || name == null) return null;
        String query = normalize(name);
        if (query.isEmpty()) return null;
        // 央视台名归一化后先做一轮精确匹配："中央三台" / "中央台3套" / "央视三套" / "CCTV-3" 都等于 "cctv3"，
        // 直接命中源里的 "CCTV3"，不会被"包含匹配"抢到别的台。
        for (Group group : live.getGroups()) {
            if (!visible(group)) continue;
            for (Channel channel : group.getChannel()) {
                if (channel == null) continue;
                if (canonical(channel.getName()).equals(query) || canonical(channel.getShow()).equals(query)) return channel;
                if (isCctv(query) && channel.getNumber() != null && !normalize(channel.getNumber()).isEmpty()
                        && normalize(channel.getNumber()).equals(cctvNumber(query))) return channel;
            }
        }
        Channel fuzzy = null;
        for (Group group : live.getGroups()) {
            if (!visible(group)) continue;
            for (Channel channel : group.getChannel()) {
                if (channel == null) continue;
                String number = normalize(channel.getNumber());
                String title = canonical(channel.getName());
                String show = canonical(channel.getShow());
                if ((!number.isEmpty() && number.equals(query)) || title.equals(query) || show.equals(query)) return channel;
                if (fuzzy == null && (title.contains(query) || show.contains(query)
                        || (!number.isEmpty() && number.contains(query)))) fuzzy = channel;
            }
        }
        return fuzzy;
    }

    private static boolean visible(Group group) {
        if (group == null || group.getChannel() == null) return false;
        if (TextUtils.isEmpty(group.getName()) && group.getChannel().isEmpty()) return false;
        return TextUtils.isEmpty(group.getPass());
    }

    /** 央视一家：中央/央视/CCTV 统一成 cctv + 台号。 */
    private static boolean isCctv(String query) {
        return query.startsWith("cctv");
    }

    private static String cctvNumber(String query) {
        return query.substring(4).replace("+", "");
    }

    /**
     * 直播页渲染完分组后调用：有待选频道就切分组 + 起播，并立刻清掉 intent 里的标记。
     */
    public static void handlePending(Activity activity, Live live, Consumer<Channel> select) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Intent intent = activity.getIntent();
        if (intent == null) return;
        String pending = intent.getStringExtra(EXTRA_CHANNEL);
        if (TextUtils.isEmpty(pending)) return;
        intent.removeExtra(EXTRA_CHANNEL);
        Channel channel = find(live, pending);
        if (channel == null) return;
        // 等分组的适配器把第一轮数据放上去再切，否则 onItemClick(group) 拿不到频道列表。
        App.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            select.accept(channel);
        });
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return canonical(text);
    }

    /**
     * 频道名归一化：中文数字转阿拉伯数字，央视台名统一成 {@code cctv<台号>}。
     *
     * <p>直播源里通常写 "CCTV1""CCTV3"，而用户会说"中央三台""中央台3套""央视三套""中央5套"，
     * 不归一化就对不上；非央视台名（湖南卫视、凤凰中文…）只做数字和空白/标点处理。</p>
     */
    public static String canonical(String text) {
        if (text == null) return "";
        String value = text.trim().toLowerCase(Locale.ROOT);
        value = value.replace('＋', '+');
        value = value.replaceAll("[\\s\\u3000]", "").replaceAll("[－—_:：·•\\-]", "");
        value = chineseDigits(value);
        boolean cctv = value.contains("cctv") || value.contains("中央") || value.contains("央视");
        if (!cctv) return value;
        String number = cctvNumberIn(value);
        String name = "cctv" + number;
        if (value.contains("+")) name = name + "+";
        return name;
    }

    /** 从"中央三台""中央台3套""cctv-5"里取出台号，取不到返回空串（"中央电视台"→"cctv"）。 */
    private static String cctvNumberIn(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2})").matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static final char[] CN = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};

    /** 只处理台号量级的中文数字：三、五、十、十三、二十、二十三。 */
    private static String chineseDigits(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (cnValue(c) >= 0) {
                int j = i;
                while (j < value.length() && (cnValue(value.charAt(j)) >= 0 || value.charAt(j) == '十')) j++;
                out.append(convert(value.substring(i, j)));
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int cnValue(char c) {
        for (int i = 0; i < CN.length; i++) if (CN[i] == c) return i;
        return -1;
    }

    private static String convert(String sequence) {
        if (!sequence.contains("十")) return digitsOnly(sequence);
        String[] parts = sequence.split("十", -1);
        int tens = parts[0].isEmpty() ? 1 : Integer.parseInt(digitsOnly(parts[0]));
        int ones = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(digitsOnly(parts[1])) : 0;
        return String.valueOf(tens * 10 + ones);
    }

    private static String digitsOnly(String sequence) {
        int value = 0;
        for (char c : sequence.toCharArray()) {
            int digit = cnValue(c);
            if (digit >= 0) value = value * 10 + digit;
        }
        return String.valueOf(value);
    }
}
