package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 进入详情/播放前的环境准备。
 *
 * <p>站源 spider 的"环境"不只活在这一个对象实例上。XBPQ 系（api=csp_XBPQ）在 jar 里用
 * {@code private static XBPQ xbpq} 存站点环境，init 时把本实例的 rule/extend/oComand/homeUrl
 * 写进静态实例（XBPQ.java:393-395、:516-523），WebView 渲染桥 {@code XBPQ.tool/getRV/getBL/setBL}
 * 也全从这个静态实例读（XBPQ.java:11917-11932、:8192-8205）。webhtv 把 jar 下所有站点的 spider
 * 长期缓存（JarLoader 按 jar+siteKey），跨源搜索又会并发 init，静态环境常常停在别的站点上，
 * 或者停在同一个站点的一份不完整规则上；此时进详情，渲染桥查不到本站点的规则字段
 * （如 {@code 调用函数-js交互} 要的 {@code js交互}），脚本解析失败、渲染结果为空。
 *
 * <p>所以进详情/播放时<b>一律</b>按该源的 ext 重建一次，不再判断"是不是当前源"：能判同源的
 * 凭据只有规则的站名/主页url，而这两项在"坏掉的环境"和"能用的环境"里完全一样，判不出来就
 * 只能一路沿用坏环境（日志实证：同源跳过时 20:34:05 / 20:34:20 / 20:34:41 三次进详情全部
 * 报"未定义js交互字段"，而 20:35:07 重建后同一次详情正常）。
 *
 * <p>同时用一把读写锁把"写静态环境的 init"和"读静态环境的详情/播放"隔开：
 * init 取读锁（各源之间仍并发），详情/播放从重建开始一直持写锁到 spider 调用返回
 * （WebView 渲染是同步在 detailContent 内完成的），期间别的源改不了静态环境。
 * 锁都带超时，拿不到就照旧执行并记日志，避免把详情/搜索挂死。
 */
public final class SiteContext {

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final ThreadLocal<int[]> INIT_DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[]{0};
        }
    };
    private static final ThreadLocal<int[]> REQUEST_DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[]{0};
        }
    };

    /** 当前正在浏览（首页/分类）的源，只用于日志，不再作为"要不要重建"的依据。 */
    private static volatile String browsing = "";

    private SiteContext() {
    }

    /** 首页/分类取数据时记录"当前源"。 */
    public static void markBrowse(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        browsing = site.getKey();
    }

    /**
     * 进入详情/播放之前调用：持写锁并把这个源的环境重建一遍。
     * 与 {@link #endRequest()} 成对使用，务必放在 finally 里。
     */
    public static void beginRequest(Site site, Spider spider) {
        enterRequest();
        if (site == null || spider == null) return;
        try {
            spider.init(App.get(), site.getExt());
            SpiderDebug.log("site-context", "reinit done site=%s current=%s", site.getKey(), browsing);
        } catch (Throwable e) {
            SpiderDebug.log("site-context", "reinit error site=%s error=%s", site.getKey(), e);
        }
    }

    /** 详情/播放结束，释放写锁。 */
    public static void endRequest() {
        exitRequest();
    }

    /** JarLoader 建 spider 时的 init：取读锁，多个源的 init 之间仍并发。 */
    public static void enterInit() {
        enterRead();
    }

    /** init 结束，释放读锁。 */
    public static void exitInit() {
        exitRead();
    }

    private static void enterRequest() {
        int[] depth = REQUEST_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]++;
            return;
        }
        boolean locked;
        try {
            locked = LOCK.writeLock().tryLock(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (locked) {
            depth[0] = 1;
        } else {
            depth[0] = -1;
            SpiderDebug.log("site-context", "request lock timeout, proceed unguarded");
        }
    }

    private static void exitRequest() {
        int[] depth = REQUEST_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
            if (depth[0] == 0) LOCK.writeLock().unlock();
        }
        if (depth[0] <= 0) REQUEST_DEPTH.remove();
    }

    private static void enterRead() {
        int[] depth = INIT_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]++;
            return;
        }
        boolean locked;
        try {
            locked = LOCK.readLock().tryLock(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (locked) {
            depth[0] = 1;
        } else {
            depth[0] = -1;
            SpiderDebug.log("site-context", "init lock timeout, proceed unguarded");
        }
    }

    private static void exitRead() {
        int[] depth = INIT_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
            if (depth[0] == 0) LOCK.readLock().unlock();
        }
        if (depth[0] <= 0) INIT_DEPTH.remove();
    }
}
