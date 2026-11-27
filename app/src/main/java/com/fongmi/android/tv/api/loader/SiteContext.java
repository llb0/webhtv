package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 站源环境的独占。
 *
 * <p>站源 spider 的"环境"不只活在这一个对象实例上。XBPQ 系（api=csp_XBPQ）在 jar 里用
 * {@code private static XBPQ xbpq} 存站点环境，init 时把本实例的 rule/extend/oComand/homeUrl
 * 写进静态实例（XBPQ.java:393-395、:516-523），WebView 渲染桥 {@code XBPQ.tool/getRV/getBL/setBL}
 * 也全从这个静态实例读（XBPQ.java:11917-11932、:8192-8205）。webhtv 把 jar 下所有站点的 spider
 * 长期缓存（JarLoader 按 jar+siteKey），跨源搜索又会并发 init，静态环境常常停在别的站点上，
 * 此时进详情，渲染桥查不到本站点的规则字段（如 {@code 调用函数-js交互} 要的 {@code js交互}），
 * 脚本解析失败、渲染结果为空。
 *
 * <p>所以环境按"独占一段"来管：从进详情开始就占住，直到播放真正起播才放手——
 * 这期间别的源的 init 全部排队等着，谁也不能改写环境；详情里已经算出来的变量
 * （{{…}} 缓存值、写入-* 字段）也就不会被重算打回原样。播放正常起来了，搜索再继续。
 *
 * <p>独占用的是公平信号量而不是线程锁：占用的线程（详情）和放手的时机（播放器起播回调）
 * 不在同一个线程上，锁没法跨线程释放，信号量可以。
 *
 * <p>例外兜底：上一个详情占着但用户没起播就退回去了，新的详情请求会先把它放掉；
 * 占住超过 {@link #HOLD_TIMEOUT_MS} 还没起播也会自动放手，免得搜索被永久卡死。
 * 拿不到独占就照旧执行并记日志，不能把详情挂死。
 */
public final class SiteContext {

    private static final long HOLD_TIMEOUT_MS = 120_000L;
    private static final Semaphore ENV = new Semaphore(1, true);
    private static final ThreadLocal<int[]> INIT_STATE = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[]{0};
        }
    };

    /** 最后一次写静态环境的源（每次 init 成功后更新）。 */
    private static volatile String owner = "";
    /** 当前正在浏览（首页/分类）的源，只用于日志。 */
    private static volatile String browsing = "";
    /** 环境是否被某次详情占着（true 时别的 init 都在排队）。 */
    private static volatile boolean held = false;
    private static volatile long holdStart = 0L;
    /** 每次占用/放手递增；进详情那条线程凭它证明"这一轮是我占的"。 */
    private static volatile int holdGen = 0;
    private static final ThreadLocal<int[]> HOLD_GEN = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[]{-1};
        }
    };

    private SiteContext() {
    }

    /** 环境独占的旁观者：搜索用它来"暂停/继续"。回调都投递到主线程。 */
    public interface Observer {
        void onHold(String siteKey);

        void onRelease();
    }

    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();

    /** 环境是否被某次详情/播放占着。 */
    public static boolean isHeld() {
        return held;
    }

    public static void addObserver(Observer observer) {
        if (observer != null && !OBSERVERS.contains(observer)) OBSERVERS.add(observer);
    }

    public static void removeObserver(Observer observer) {
        if (observer != null) OBSERVERS.remove(observer);
    }

    private static void notifyHold(String siteKey) {
        for (Observer observer : OBSERVERS) App.post(() -> observer.onHold(siteKey));
    }

    private static void notifyRelease() {
        for (Observer observer : OBSERVERS) App.post(observer::onRelease);
    }

    /** 首页/分类取数据时记录"当前源"；回到列表浏览说明上一次详情没看到播放，环境该放手了。 */
    public static void markBrowse(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        browsing = site.getKey();
        if (held) releaseEnv("browse");
    }

    /**
     * 进详情时调用：占住环境（一直占到起播），环境不属于这个源时先按它的 ext 重建。
     * 不要配对释放，放手在 {@link #releaseEnv(String)}。
     */
    public static void beginRequest(Site site, Spider spider) {
        releaseStale();
        if (acquire(20, "request") <= 0) {
            SpiderDebug.log("site-context", "hold timeout, proceed unguarded site=%s", site == null ? "" : site.getKey());
            return;
        }
        held = true;
        holdGen++;
        HOLD_GEN.get()[0] = holdGen;
        holdStart = System.currentTimeMillis();
        SpiderDebug.log("site-context", "hold site=%s current=%s", site == null ? "" : site.getKey(), browsing);
        notifyHold(site == null ? "" : site.getKey());
        if (site == null || spider == null) return;
        if (TextUtils.equals(owner, site.getKey())) return;
        String was = owner;
        try {
            spider.init(App.get(), site.getExt());
            owner = site.getKey();
            SpiderDebug.log("site-context", "reinit done site=%s was=%s current=%s", site.getKey(), was, browsing);
        } catch (Throwable e) {
            SpiderDebug.log("site-context", "reinit error site=%s error=%s", site.getKey(), e);
        }
    }

    /** 放手：起播成功、播放报错、或者新详情抢占时调用。 */
    public static void releaseEnv(String reason) {
        if (!held) return;
        held = false;
        holdStart = 0L;
        holdGen++;
        ENV.release();
        SpiderDebug.log("site-context", "release reason=%s", reason);
        notifyRelease();
    }

    /**
     * JarLoader 建 spider 时的 init。
     *
     * <p>只有"进详情→起播"这段是独占的：这时别的线程要 init 直接放弃（{@code skip}），
     * 既不排队、也不无锁硬跑，因为排队会把搜索任务自己的超时预算吃光，硬跑会把详情
     * 正在用的静态环境冲掉。
     *
     * <p>没有独占时（纯搜索、首页/分类刷新、换源）**不上锁、不等、直接 init**：
     * 多源并发 init 之间互相覆盖是原有行为，跟影图一样谁先谁后不影响搜索。
     */
    public static boolean enterInit(String siteKey) {
        int[] state = INIT_STATE.get();
        if (state[0] > 0) {
            state[0]++;
            return true;
        }
        if (held && HOLD_GEN.get()[0] != holdGen) {
            // 详情/播放正占着，而这条线程不是占用方：这次 init 直接跳过。
            SpiderDebug.log("site-context", "init skip, env held site=%s", siteKey);
            return false;
        }
        state[0] = 1;
        return true;
    }

    /** init 结束：静态环境现在归这个源所有。 */
    public static void exitInit(String siteKey) {
        int[] state = INIT_STATE.get();
        if (state[0] > 0) state[0]--;
        if (!TextUtils.isEmpty(siteKey)) owner = siteKey;
        if (state[0] <= 0) INIT_STATE.remove();
    }

    private static void releaseStale() {
        if (!held) return;
        long now = System.currentTimeMillis();
        if (holdStart > 0 && now - holdStart < HOLD_TIMEOUT_MS) {
            SpiderDebug.log("site-context", "preempt previous hold");
        } else {
            SpiderDebug.log("site-context", "hold expired");
        }
        releaseEnv("preempt");
    }

    /** 1=拿到，0=超时，-1=被中断（调用方正在被取消）。 */
    private static int acquire(long timeoutSec, String tag) {
        try {
            boolean got = ENV.tryAcquire(timeoutSec, TimeUnit.SECONDS);
            if (!got) SpiderDebug.log("site-context", "%s wait timeout", tag);
            return got ? 1 : 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
