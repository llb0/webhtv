package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.web.WebHomeInlineVodStore;

/**
 * 全源搜索"参不参与"的唯一判定口。
 * <p>
 * 以前各入口各自判断：搜索页只查 isSearchable()，换源搜索查 isSearchable()+屏蔽，
 * 派发层再漏一层，于是"页面内嵌网页"这条伪源（webhome_inline）虽然是 searchable=1，
 * 也被派出去搜一遍，还占掉 xx/60 的一格。
 */
public final class SiteSearchScope {

    private SiteSearchScope() {
    }

    public static boolean isSearchable(Site site) {
        if (site == null) return false;
        // 内嵌网页浏览产生的 VOD 只存在内存里，没有搜索接口，永远搜不出东西。
        if (WebHomeInlineVodStore.KEY.equals(site.getKey())) return false;
        if (!site.isSearchable()) return false;
        if (site.isHide()) return false;
        return !SiteBlockSetting.isBlocked(site);
    }
}
