package com.fongmi.android.tv.voice;

import android.app.Activity;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "看某某"和"搜索某某"的区别就在这里：搜索只打开搜索结果，看则直接进全屏播放。
 *
 * <p>做法是先在可搜索站点里跑一次快速搜索，取第一个命中结果，再用
 * {@link VideoActivity#startDirectResolved} 直接开播（跳过详情页），符合"说一句就开始看"的预期。
 * 搜索在后台线程跑，命中后回主线程起播。</p>
 */
public final class VoicePlay {

    /** 一次最多试几个站点：全站串行搜索太慢，语音场景要的是快。 */
    private static final int MAX_SITES = 6;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private VoicePlay() {
    }

    public static boolean play(String keyword) {
        if (TextUtils.isEmpty(keyword)) return false;
        if (App.activity() == null) return false;
        EXECUTOR.execute(() -> {
            Vod vod = search(keyword);
            App.post(() -> {
                Activity activity = App.activity();
                if (activity == null) return;
                if (vod == null) {
                    Notify.show(App.get().getString(R.string.voice_play_not_found, keyword));
                    return;
                }
                VideoActivity.startDirectResolved(activity, vod);
            });
        });
        return true;
    }

    private static Vod search(String keyword) {
        List<Site> sites = new ArrayList<>(VodConfig.get().getSites());
        int tried = 0;
        for (Site site : sites) {
            if (site == null || !site.isSearchable()) continue;
            if (tried++ >= MAX_SITES) break;
            try {
                Result result = SiteApi.searchContent(site, keyword, true, "1");
                if (result == null) continue;
                for (Vod vod : result.getList()) {
                    if (vod != null && !TextUtils.isEmpty(vod.getId())) return vod;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
