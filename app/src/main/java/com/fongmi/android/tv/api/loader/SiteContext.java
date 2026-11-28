package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 站源环境（jar 内共享状态）的归属管理。
 *
 * <p>壳不管 jar 把状态放在实例上还是静态字段上，也不判断是哪一种源——那是 jar 自己的事。
 * 壳只需要知道一件事：<b>当前这份环境是按哪个源的 ext 建起来的</b>。因为一次 init 会改写该 jar
 * 里的共享状态，而这份状态正是"进详情"和"随后的播放"要接着用的：详情里由"变量预加载"算出来的
 * 值（{{sign}}、{{client}} 这类写入-* 字段）如果被另一次 init 重写，详情或播放就会拿到别人那份环境。
 *
 * <p>环境的独占不再靠信号量，而是靠<b>调度</b>：进详情之前搜索就真的停掉（见
 * {@code SiteViewModel.pauseSearch}），详情到起播这段没有别的源在跑，也就没有需要排队的初始化。
 * 这里只做两件事：
 * <ul>
 *   <li>{@link #initOwner}：进详情时确认环境属于本源的，只记账，不重复 init（实例按站独立）；</li>
 *   <li>{@link #ensureOwner}：首页/分类刷新前同样确认一次，避免读到别的源留下的那份环境。</li>
 * </ul>
 */
public final class SiteContext {

    /** 最后一次写共享环境的源（每次 init 成功后更新）。 */
    private static volatile String owner = "";
    /** 当前正在浏览（首页/分类）的源，只用于日志。 */
    private static volatile String browsing = "";

    /**
     * 环境里已经跑过真实请求的源（key）。用途只有一个：判断进详情前要不要先预热。
     *
     * <p>jar 里由"变量预加载"算出来的东西是跟着规则状态走的，一次 init 会把规则重建、这些值就
     * 没了。所以判据不是"这个源以前跑过"，而是"**这次 init 之后**跑过"：任何一次真实 init 都会把
     * 它从台账里划掉（见 {@link #initOwner}、{@link #exitInit}），跑成功一次真实请求再重新记上。
     */
    private static final Set<String> WARM = ConcurrentHashMap.newKeySet();
    /**
     * 正在执行 init 的线程数。
     *
     * <p>不是锁、不让谁排队：只表示"现在有别的源正在写共享环境"。{@link #haltSearch} 只能中断
     * 搜索任务，中断不了已经进到 jar 里的 init（日志里见过 4.2s 才返回的 init 在停搜之后才落地）。
     * 进详情要等这些写操作落完，否则详情刚重建好的环境会再被它们覆盖一次——这是本轮 3Qpc 详情
     * 仍然 45x 的直接原因。
     */
    private static final AtomicInteger INITS = new AtomicInteger();

    /** 最近一次全源搜索的关键词：进详情时若需要"跑一次本源请求"把变量链走完，用的就是它。 */
    private static volatile String lastKeyword = "";

    private SiteContext() {
    }

    /** 该源在当前环境里跑成功过至少一次真实请求（变量已由自己的规则算出来）。 */
    public static boolean isWarm(Site site) {
        return site != null && !TextUtils.isEmpty(site.getKey()) && WARM.contains(site.getKey());
    }

    /** 该源跑成功过一次真实请求：环境里它的变量已经就绪。 */
    public static void markWarm(Site site) {
        if (site != null && !TextUtils.isEmpty(site.getKey())) WARM.add(site.getKey());
    }

    /** 搜索派发时记下关键词，供冷源进详情时重放一次本源请求。 */
    public static void markKeyword(String keyword) {
        if (!TextUtils.isEmpty(keyword)) lastKeyword = keyword;
    }

    /** 最近一次搜索关键词，没有搜索过时为空。 */
    public static String lastKeyword() {
        return lastKeyword;
    }

    /** 首页/分类取数据时记录"当前源"，只用于日志。 */
    public static void markBrowse(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        browsing = site.getKey();
    }

    /**
     * 环境不属于这个源时按它的 ext 重建。返回是否真的重建了（调用方据此决定要不要预热）。
     *
     * <p>进详情的顺序是「先取 spider、再确认归属」：spider 首次创建本身就会 init，那次 init 之后
     * 环境已经归本源，这里的比对不会重复 init 一遍。
     */
    /** 通道里那份"当前规则"（jar 里的静态实例）是否已经属于这个源。 */
    public static boolean needsRebuild(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return false;
        boolean need = !TextUtils.equals(owner, site.getKey());
        SpiderDebug.log("site-context", "owner site=%s owner=%s rebuild=%s", site.getKey(), owner, need);
        return need;
    }

    /**
     * 环境不属于这个源时该做什么：<b>换一个新实例</b>，不是在原实例上再 init 一次。
     *
     * <p>为什么不能再 init：jar 的 init 会把实例自己的 {@code rule} 从站点配置重建，运行中由
     * "变量预加载"写进去的 {@code sign}/{@code client} 一起没了；而实例的变量缓存（{@code
     * variableCache}）init 不重置，下一次预加载命中旧缓存直接返回，既不抓取也不再写入。
     * 换新实例则两者都归零，同时那次 init 会把 jar 内静态的"当前规则"切到本站——WebView 渲染
     * 回调（{@code [JavaBridge] 调用函数-js交互}）读的正是它，原来那份是上一个初始化过的源的。
     */
    public static boolean initOwner(Site site, Spider spider) {
        if (site == null || spider == null) return false;
        if (TextUtils.equals(owner, site.getKey())) return false;
        SpiderDebug.log("site-context", "adopt site=%s was=%s current=%s", site.getKey(), owner, browsing);
        owner = site.getKey();
        return false;
    }

    public static void ensureOwner(Site site, Spider spider) {
        initOwner(site, spider);
    }

    /** init 即将开始：登记一次"正在写环境"。 */
    public static boolean enterInit(String siteKey) {
        INITS.incrementAndGet();
        return true;
    }

    /** init 结束：共享环境现在归这个源所有，它之前算出来的变量随之作废。 */
    public static void exitInit(String siteKey) {
        if (!TextUtils.isEmpty(siteKey)) {
            owner = siteKey;
            WARM.remove(siteKey);
        }
        INITS.decrementAndGet();
    }

    /**
     * 等到没有别的线程在写共享环境，最多等 {@code timeoutMs}。
     *
     * <p>进详情前用：搜索已经停了，但在跑的 init 可能还在里面，环境随时会被它最后写一次。
     * 等完再重建本源环境，这份环境才不会又被覆盖。超时也照常往下走——详情不能无限等。
     */
    public static void awaitQuiescent(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (INITS.get() > 0 && System.currentTimeMillis() - start < timeoutMs) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = INITS.get();
        long cost = System.currentTimeMillis() - start;
        if (remaining > 0) SpiderDebug.log("site-context", "await timeout remaining=%d cost=%dms", remaining, cost);
        else if (cost >= 10) SpiderDebug.log("site-context", "await ok cost=%dms", cost);
    }
}
