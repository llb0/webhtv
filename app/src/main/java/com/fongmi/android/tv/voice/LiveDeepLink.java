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

    public static boolean open(String name) {
        Channel channel = find(name);
        if (channel == null) return false;
        Context context = App.get();
        Intent intent = new Intent(context, LiveActivity.class);
        intent.putExtra(EXTRA_CHANNEL, channel.getName());
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
        Channel fuzzy = null;
        for (Group group : live.getGroups()) {
            if (group == null || group.getChannel() == null) continue;
            if (TextUtils.isEmpty(group.getName()) && group.getChannel().isEmpty()) continue;
            if (!TextUtils.isEmpty(group.getPass())) continue;
            for (Channel channel : group.getChannel()) {
                if (channel == null) continue;
                String number = normalize(channel.getNumber());
                String title = normalize(channel.getName());
                String show = normalize(channel.getShow());
                if ((!number.isEmpty() && number.equals(query)) || title.equals(query) || show.equals(query)) return channel;
                if (fuzzy == null && (title.contains(query) || show.contains(query)
                        || (!number.isEmpty() && number.contains(query)))) fuzzy = channel;
            }
        }
        return fuzzy;
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
        return text.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("\u3000", "");
    }
}
