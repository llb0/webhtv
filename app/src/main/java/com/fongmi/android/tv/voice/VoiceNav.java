package com.fongmi.android.tv.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.Notify;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 语音指令里的"打开历史 / 打开收藏 / 切换站点"这类导航动作。
 *
 * <p>历史页两端同名；收藏页在电视端叫 {@code CollectActivity}、手机端叫 {@code KeepActivity}，
 * main source set 不能直接引用任一端的类，所以按类名反射调用它们的 {@code start(Activity)}。</p>
 */
public final class VoiceNav {

    private static final String HISTORY = "com.fongmi.android.tv.ui.activity.HistoryActivity";
    private static final String COLLECT = "com.fongmi.android.tv.ui.activity.CollectActivity";
    private static final String KEEP = "com.fongmi.android.tv.ui.activity.KeepActivity";

    private VoiceNav() {
    }

    public static boolean openHistory() {
        return start(HISTORY);
    }

    public static boolean openCollect() {
        return start(COLLECT) || start(KEEP);
    }

    private static boolean start(String className) {
        Activity activity = App.activity();
        if (activity == null) return false;
        try {
            Class<?> clazz = Class.forName(className);
            Method method = clazz.getMethod("start", Activity.class);
            method.invoke(null, activity);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 切站点：站点名去掉 | PQ、[H] 这类后缀标签后做包含匹配，命中即设为首页站点并刷新。 */
    public static boolean switchSite(String name) {
        if (TextUtils.isEmpty(name)) return false;
        Site site = find(name);
        if (site == null) return false;
        Activity activity = App.activity();
        if (activity == null) return false;
        App.post(() -> {
            VodConfig.get().setHome(site);
            RefreshEvent.home();
        });
        return true;
    }

    public static Site find(String name) {
        String query = clean(name);
        if (query.isEmpty()) return null;
        Site fuzzy = null;
        for (Site site : VodConfig.get().getSites()) {
            if (site == null || TextUtils.isEmpty(site.getName())) continue;
            String title = clean(site.getName());
            if (title.equals(query)) return site;
            if (fuzzy == null && title.contains(query)) fuzzy = site;
        }
        return fuzzy;
    }

    /** 去掉 "| PQ"、"|H"、"[H]" 这类清晰度/画质标签，只留站点主体名。 */
    private static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("[|｜].*$", "");
        value = value.replaceAll("[\\[【(（][^\\]】)）]*[\\]】)）]", "");
        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    public static void notifySiteMissing(String name) {
        Context context = App.get();
        if (context != null) Notify.show(context.getString(R.string.voice_site_not_found, name));
    }
}
