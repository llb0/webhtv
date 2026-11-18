package com.fongmi.android.tv.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
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
        if (App.activity() == null) return false;
        // 历史/收藏这类页面不复用旧实例：先全关再开一个新的，返回一次就回首页
        collapse();
        // 退栈是异步的，等关闭完成、栈顶换成首页再启动，避免从一个正在 finish 的页面 startActivity
        launch(className, 2);
        return true;
    }

    private static void launch(String className, int retry) {
        App.post(() -> {
            Activity activity = App.activity();
            if (activity == null) return;
            if (activity.isFinishing()) {
                if (retry > 0) App.post(() -> launch(className, retry - 1), 120);
                return;
            }
            try {
                Class<?> clazz = Class.forName(className);
                Method method = clazz.getMethod("start", Activity.class);
                method.invoke(null, activity);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 开新播放前先把正在放的内容停掉（含"后台播放"开着的情况）。
     *
     * <p>不停的话，说"看某某"新起的播放页会盖在旧播放页上面，退出后旧会话接着放，
     * 听起来就是"没切过去"。这里直接停掉共享的 PlaybackService 播放器，新内容再重新起。</p>
     */
    public static void stopPlayback() {
        PlaybackService service = Server.get().getService();
        if (service == null) return;
        Runnable stop = () -> {
            try {
                service.player().pause();
                service.player().stop();
            } catch (Exception ignored) {
            }
        };
        // 已在主线程就同步停，保证"先停再开"的顺序；服务线程调用则投到主线程队列。
        if (Looper.myLooper() == Looper.getMainLooper()) stop.run();
        else App.post(stop);
    }

    /** 语音导航退栈：只留首页 +（可选）目标页，其余全部关掉。 */
    public static void collapse(String... keepClassName) {
        App.finishPagesExcept(keepClassName);
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
