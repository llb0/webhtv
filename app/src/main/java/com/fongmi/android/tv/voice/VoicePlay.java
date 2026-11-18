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
            // 搜索结果里只有 id/名称/封面，没有线路（flags）；直接扔给"跳过详情"的播放页，
            // 播放页拿不到线路就拉不到详情、也播不了。这里补一次详情，把线路塞进去。
            Vod detailed = vod == null ? null : detail(vod);
            App.post(() -> {
                Activity activity = App.activity();
                if (activity == null) return;
                if (vod == null) {
                    Notify.show(App.get().getString(R.string.voice_play_not_found, keyword));
                    return;
                }
                if (detailed == null || detailed.getFlags().isEmpty()) {
                    // 详情/线路还是拿不到，退回正常详情页，至少能看到片子并手动选线路。
                    VoiceNav.stopPlayback();
                    VideoActivity.start(activity, vod.getSiteKey(), vod.getId(), vod.getName(), vod.getPic());
                    Notify.show(App.get().getString(R.string.voice_play_detail_failed, vod.getName()));
                    return;
                }
                VoiceNav.stopPlayback();
                VideoActivity.startDirectResolved(activity, detailed, true);
            });
        });
        return true;
    }

    /** 拉一次详情，把线路（flags）补到搜索结果上；失败就用原对象。 */
    private static Vod detail(Vod vod) {
        try {
            Result result = SiteApi.detailContent(vod.getSiteKey(), vod.getId());
            Vod detail = result == null ? null : result.getVod();
            if (detail == null || detail.getFlags().isEmpty()) return vod;
            detail.setFlags(); // 把 Flag 里的 urls 展开成 episodes，播放页按集选集
            vod.setFlags(detail.getFlags());
            vod.checkPic(detail.getPic());
            vod.checkName(detail.getName());
            vod.checkContent(detail.getContent());
            if (TextUtils.isEmpty(vod.getRemarks())) vod.setRemarks(detail.getRemarks());
            return vod;
        } catch (Exception e) {
            return vod;
        }
    }

    /** 先在当前源里找，找不到再按顺序扫其它可搜索站点。 */
    private static Vod search(String keyword) {
        Site home = VodConfig.get().getHome();
        Vod hit = search(home, keyword);
        if (hit != null) return hit;
        List<Site> sites = new ArrayList<>(VodConfig.get().getSites());
        int tried = 0;
        for (Site site : sites) {
            if (site == null || !site.isSearchable()) continue;
            if (home != null && home.getKey().equals(site.getKey())) continue;
            if (tried++ >= MAX_SITES) break;
            hit = search(site, keyword);
            if (hit != null) return hit;
        }
        return null;
    }

    private static Vod search(Site site, String keyword) {
        if (site == null || !site.isSearchable()) return null;
        try {
            Result result = SiteApi.searchContent(site, keyword, true, "1");
            if (result == null) return null;
            for (Vod vod : result.getList()) {
                if (vod != null && !TextUtils.isEmpty(vod.getId())) return vod;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
