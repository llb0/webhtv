package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 站源初始化的时机管理。
 *
 * <p>壳不管 jar 内部把状态放在实例上还是放在静态字段上、也不判断是哪一种源——那是 jar 自己的事。
 * 壳只需要保证一件事：**初始化在什么时候发生、什么时候不允许被打断**。因为一次初始化可能改写
 * 该 jar 里的共享状态，而这个状态正是"进详情"和"随后的播放"要接着用的：详情里算出来的变量
 * （{{…}} 缓存值、写入-* 字段）如果在起播前被另一次初始化重写，详情或播放就会拿到别人那份环境。
 *
 * <p>时机规则于是只有两条：
 * <ul>
 *   <li>**进详情 → 正常起播**：这段时间独占。进详情时按该源的 ext 初始化一次，起播前不再重建；
 *       期间别的初始化排队等（初始化本身不可跳过，只是要等）。起播、播放报错、退回列表浏览即放手。</li>
 *   <li>**其余所有时候**（纯搜索、首页/分类、换源、起播之后的继续搜索）：不拿锁、不排队，各源正常并发初始化。</li>
 * </ul>
 *
 * <p>排队只发生在第二条之外的那段窗口里，并且**不计入任何调用方的超时**：等多久是环境的事，
 * 干活多久才是任务的事。
 *
 * <p>独占用公平信号量而不是线程锁：占用的线程（详情）与放手的时机（播放器起播回调）不在同一个
 * 线程上，锁没法跨线程释放，信号量可以。
 *
 * <p>兜底：上一个详情占着但用户没起播就退回去了，新的详情会先把它放掉；占住超过
 * {@link #HOLD_TIMEOUT_MS} 还没起播也会自动放手，免得初始化被永久卡住。
 */
public final class SiteContext {

    private static final long HOLD_TIMEOUT_MS = 120_000L;
    private static final Semaphore ENV = new Semaphore(1, true);
    /** [0]=init 嵌套层数，[1]=1 表示本线程确实持有信号量（详情占用线程的 init 是复用的，不持有）。 */
    private static final ThreadLocal<int[]> INIT_STATE = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[]{0, 0};
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
        initOwner(site, spider);
    }

    /**
     * 环境不属于这个源时按它的 ext 重建。
     *
     * <p>进详情的顺序是「先占环境、再取 spider」：spider 第一次创建会 init，而 init 只有
     * 占用线程自己的那次才被放行（见 {@link #enterInit}）。所以必须先 {@code beginRequest(site, null)}
     * 占住，拿到 spider 之后再调这里补重建，否则会拿到被跳过的空 spider、详情直接空白。
     */
    public static void initOwner(Site site, Spider spider) {
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
    }

    /**
     * JarLoader 建 spider 时的 init。
     *
     * <p>init 是搜索的必要步骤（每个源搜索前都要按自己的 ext 初始化），不能跳过。分两种情况：
     * <ul>
     *   <li>**没有详情独占**（纯搜索、首页/分类、换源）：不拿锁、不排队，各源照旧并发 init。</li>
     *   <li>**进详情→起播这段独占期间**：搜索的 init 必须等，不能改写详情正在用的环境——
     *       这里排队等待，不设时限，也不计入搜索任务的 30s 超时（计时在排队结束后才开始，
     *       见 SiteViewModel.submitTimed）。唯一提前放弃的途径是任务被取消、线程被中断。</li>
     * </ul>
     *
     * <h3>详情占用线程自己</h3>
     * 进详情的线程已经持有信号量（beginRequest），它自己的 init 直接放行、不再抢锁，
     * 也不在退出时重复释放。
     */
    public static boolean enterInit(String siteKey) {
        int[] state = INIT_STATE.get();
        if (state[0] > 0) {
            state[0]++;
            return true;
        }
        if (held && HOLD_GEN.get()[0] == holdGen) {
            // 本线程就是当前独占者：环境已经归它，直接 init。
            state[0] = 1;
            state[1] = 0;
            return true;
        }
        if (!held) {
            // 没有独占：不上锁，各源并发 init（跟影图一样，搜索不依赖静态环境谁先谁后）。
            state[0] = 1;
            state[1] = 0;
            return true;
        }
        // 详情/播放占着：排队等它放手（不计时），排到了再按自己的 ext 初始化。
        int result = acquireUnlimited();
        if (result <= 0) {
            SpiderDebug.log("site-context", "init canceled while waiting site=%s", siteKey);
            return false;
        }
        state[0] = 1;
        state[1] = 1;
        return true;
    }

    /** init 结束：静态环境现在归这个源所有；真持过信号量的才释放。 */
    public static void exitInit(String siteKey) {
        int[] state = INIT_STATE.get();
        if (state[0] > 0) {
            state[0]--;
            if (state[0] == 0) {
                if (state[1] == 1) ENV.release();
                INIT_STATE.remove();
            }
        }
        if (!TextUtils.isEmpty(siteKey)) owner = siteKey;
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

    /** 不设时限的排队：拿到返回 1；被中断（任务被判取消）返回 -1。 */
    private static int acquireUnlimited() {
        try {
            ENV.acquire();
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
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
