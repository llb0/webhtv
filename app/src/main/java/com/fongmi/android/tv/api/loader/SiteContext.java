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
 * <p>处理办法：app 自己记账。JarLoader 每次 init 成功后把"静态环境现在归哪个源"记到
 * {@link #owner}；进详情/播放时持写锁看一眼——环境已经是这个源就不动（详情阶段算出来的变量
 * 挂在这套环境上，重建会把它们打回原样，接着播放就出错），只有环境被别的源占住时才按该源的
 * ext 重建。这样既纠正了并发搜索的遗留，又不会平白重置已经准备好的环境。
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
    /**
     * 最后一次写静态环境的源（每次 init 成功后更新）。这是"环境属于谁"的唯一凭据：
     * 不再去猜规则里的站名/主页url，那两项在坏环境和好环境里可能一模一样。
     */
    private static volatile String owner = "";

    private SiteContext() {
    }

    /** 首页/分类取数据时记录"当前源"。 */
    public static void markBrowse(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        browsing = site.getKey();
    }

    /**
     * 进入详情/播放之前调用：持写锁，只有静态环境不属于这个源时才重建。
     *
     * <p>环境已经是这个源的就不动它：详情阶段算出来的变量（{{…}} 的缓存值、写入-vid 之类）
     * 都挂在这套环境上，重建会把这些打回原样，接着播放就会出错；而环境被别的源占住时，
     * 不重建则渲染桥查不到本站点的字段。两种情况只能靠 {@link #owner} 区分。
     *
     * <p>与 {@link #endRequest()} 成对使用，务必放在 finally 里。
     */
    public static void beginRequest(Site site, Spider spider) {
        enterRequest();
        if (site == null || spider == null) return;
        if (TextUtils.equals(owner, site.getKey())) {
            SpiderDebug.log("site-context", "keep site=%s current=%s", site.getKey(), browsing);
            return;
        }
        String was = owner;
        try {
            spider.init(App.get(), site.getExt());
            owner = site.getKey();
            SpiderDebug.log("site-context", "reinit done site=%s was=%s current=%s", site.getKey(), was, browsing);
        } catch (Throwable e) {
            SpiderDebug.log("site-context", "reinit error site=%s error=%s", site.getKey(), e);
        }
    }

    /** 详情/播放结束，释放写锁。 */
    public static void endRequest() {
        exitRequest();
    }

    /** JarLoader 建 spider 时的 init：取读锁，多个源的 init 之间仍并发。 */
    public static void enterInit(String siteKey) {
        enterRead();
    }

    /** init 结束，释放读锁；静态环境现在归这个源所有。 */
    public static void exitInit(String siteKey) {
        if (!TextUtils.isEmpty(siteKey)) owner = siteKey;
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
