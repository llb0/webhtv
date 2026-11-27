package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.karaoke.KaraokeResult;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteSearchScope;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class SiteViewModel extends ViewModel {

    private final MutableLiveData<Result> result;
    private final MutableLiveData<Result> player;
    private final MutableLiveData<Result> search;
    private final MutableLiveData<SearchProgress> searchProgress;
    private final MutableLiveData<Result> action;

    private final Map<TaskType, ListenableFuture<?>> futures;
    private final Map<TaskType, AtomicInteger> taskIds;
    private final List<Future<?>> searchFuture;
    private final ListeningExecutorService playerExecutor;
    private final ListeningExecutorService isolatedPlayerExecutor;
    private final AtomicInteger searchEpoch;
    private final Object searchLock;
    private final Object isolatedPlayerLock;
    private final AtomicInteger isolatedPlayerId;
    private ListeningExecutorService searchExecutor;
    private ListenableFuture<Result> isolatedPlayerFuture;
    private KaraokeResult karaokeResult;
    private int karaokeResultAction;
    /**
     * onCleared() 会用 shutdownNow() 终止两个串行执行器。此后任何 submit 都会抛
     * RejectedExecutionException，并顺着宿主（播放页、阅读器）的调用栈冒出去打崩 App。
     * 清理后的提交一律降级为「本次请求作废」：调用方拿到取消结果或直接不发请求，
     * 宿主不再需要为 ViewModel 的生命周期兜异常。
     */
    private volatile boolean cleared;
    /**
     * 搜索页在后台（进详情、播放期间）时，LiveData 只会保留最后一个值，
     * 中间几十个源的结果会被覆盖掉。这里按到达顺序排队，页面回到前台再按序取走。
     */
    private final List<Result> searchBacklog = new CopyOnWriteArrayList<>();
    /**
     * 结果到达信号：只带一个计数，页面收到就自己来取队列里的结果。
     *
     * <p>不能让结果本身走 LiveData：多个源几乎同时返回时，postValue 会合并成最后一个，
     * 页面就只看到"进度在涨、卡片不增加"。信号合并没关系——每次取的都是整条队列。
     */
    private final MutableLiveData<Integer> searchTick = new MutableLiveData<>(0);
    /** 本代搜索还没出结果的源；出了结果（含失败）就从这里摘掉。 */
    private final List<Site> searchRemaining = new CopyOnWriteArrayList<>();
    private volatile String searchKeyword = "";
    private volatile boolean searchQuick;
    private volatile int searchTotal;
    private final AtomicInteger searchDone = new AtomicInteger();
    public SiteViewModel() {
        result = new MutableLiveData<>();
        player = new MutableLiveData<>();
        search = new MutableLiveData<>();
        searchProgress = new MutableLiveData<>();
        action = new MutableLiveData<>();
        searchEpoch = new AtomicInteger(0);
        searchFuture = new CopyOnWriteArrayList<>();
        searchLock = new Object();
        // Player spiders can share a loopback proxy and may ignore interruption.
        // Keep resolutions serial so a canceled source fully exits before the next starts.
        playerExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        // The queue resolver must not share the foreground PLAYER slot: a late next-item
        // result must never replace or cancel the item the user explicitly selected.
        isolatedPlayerExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        isolatedPlayerLock = new Object();
        isolatedPlayerId = new AtomicInteger(0);
        futures = new EnumMap<>(TaskType.class);
        taskIds = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) taskIds.put(type, new AtomicInteger(0));
    }

    public LiveData<Result> getResult() {
        return result;
    }

    public LiveData<Result> getPlayer() {
        return player;
    }

    public LiveData<Result> getSearch() {
        return search;
    }

    public LiveData<Integer> getSearchTick() {
        return searchTick;
    }

    public LiveData<SearchProgress> getSearchProgress() {
        return searchProgress;
    }

    public LiveData<Result> getAction() {
        return action;
    }

    public KaraokeResult getKaraokeResult() {
        return karaokeResult;
    }

    public int getKaraokeResultAction() {
        return karaokeResultAction;
    }

    public void setKaraokeResult(KaraokeResult result, int action) {
        karaokeResult = result;
        karaokeResultAction = action;
    }

    public void clearKaraokeResult() {
        karaokeResult = null;
        karaokeResultAction = 0;
    }

    public SiteViewModel init() {
        search.setValue(null);
        searchProgress.setValue(null);
        result.setValue(null);
        player.setValue(null);
        action.setValue(null);
        return this;
    }

    public void homeContent() {
        execute(TaskType.RESULT, result, () -> SiteApi.homeContent(VodConfig.get().getHome()));
    }

    public void categoryContent(String key, String tid, String page, boolean filter, HashMap<String, String> extend) {
        execute(TaskType.RESULT, result, () -> SiteApi.categoryContent(key, tid, page, filter, extend));
    }

    public void action(String key, String act) {
        execute(TaskType.ACTION, action, () -> SiteApi.action(key, act));
    }

    public void detailContent(String key, String id) {
        detailContent(key, id, false);
    }

    public void detailContent(String key, String id, boolean refresh) {
        execute(TaskType.RESULT, result, () -> SiteApi.detailContent(key, id, refresh));
    }

    public void playerContent(String key, String flag, String id) {
        execute(TaskType.PLAYER, player, () -> SiteApi.playerContent(key, flag, id));
    }

    /** 按指定内核取播放地址：取址要按内核区分线路，所以内核必须显式传入而不是读全局默认。 */
    public void playerContent(String key, String flag, String id, int playerType) {
        execute(TaskType.PLAYER, player, () -> SiteApi.playerContent(key, flag, id, playerType));
    }

    /**
     * 换条目时使旧取流请求失效；LiveData 消费方只接受下一次请求或空值。
     */
    public void cancelPlayerContent() {
        taskIds.get(TaskType.PLAYER).incrementAndGet();
        ListenableFuture<?> future = futures.get(TaskType.PLAYER);
        if (future != null) future.cancel(true);
        player.setValue(null);
        futures.remove(TaskType.PLAYER);
    }

    /**
     * Resolves one future episode without touching the foreground PLAYER LiveData/future.
     *
     * <p>There is deliberately only one isolated request at a time.  Spider implementations
     * may share a loopback proxy and may not stop immediately on interruption, so a dedicated
     * serial executor gives cancellation a safe boundary without allowing it to interfere with
     * the user's current selection.</p>
     */
    public ListenableFuture<Result> playerContentIsolated(
            String key,
            String flag,
            String id,
            int playerType,
            Consumer<Result> onSuccess,
            Consumer<Throwable> onError) {
        if (cleared) return cancelledFuture();
        int requestId = isolatedPlayerId.incrementAndGet();
        cancelIsolatedFuture();
        FluentFuture<Result> future;
        TimedCall call;
        try {
            call = submitTimed(isolatedPlayerExecutor,
                    null,
                    () -> SiteApi.playerContentIsolated(key, flag, id, playerType),
                    Constant.TIMEOUT_VOD, new AtomicLong(0));
            future = call.future;
        } catch (RejectedExecutionException e) {
            // 与 onCleared() 的竞态窗口：执行器已终止，本次请求作废即可。
            return cancelledFuture();
        }
        synchronized (isolatedPlayerLock) {
            isolatedPlayerFuture = future;
        }
        future.addCallback(Task.callback(
                value -> App.post(() -> {
                    if (!isCurrentIsolatedRequest(requestId, future)) return;
                    if (onSuccess != null) onSuccess.accept(value);
                    clearIsolatedFuture(requestId, future);
                }),
                error -> {
                    if (error instanceof CancellationException && !call.timedOut.get()) return;
                    App.post(() -> {
                        if (!isCurrentIsolatedRequest(requestId, future)) return;
                        if (onError != null) onError.accept(error);
                        clearIsolatedFuture(requestId, future);
                    });
                }
        ), MoreExecutors.directExecutor());
        return future;
    }

    /** 已取消的占位 future：让「ViewModel 已清理」与「请求已作废」在调用方看来是同一件事。 */
    private ListenableFuture<Result> cancelledFuture() {
        return Futures.immediateCancelledFuture();
    }

    /** Cancels only the outstanding next-item resolver, leaving the foreground PLAYER request intact. */
    public void cancelPlayerContentIsolated() {
        isolatedPlayerId.incrementAndGet();
        cancelIsolatedFuture();
    }

    private void cancelIsolatedFuture() {
        ListenableFuture<Result> future;
        synchronized (isolatedPlayerLock) {
            future = isolatedPlayerFuture;
            isolatedPlayerFuture = null;
        }
        if (future != null) future.cancel(true);
    }

    private boolean isCurrentIsolatedRequest(int requestId, ListenableFuture<Result> future) {
        synchronized (isolatedPlayerLock) {
            return isolatedPlayerId.get() == requestId && isolatedPlayerFuture == future;
        }
    }

    private void clearIsolatedFuture(int requestId, ListenableFuture<Result> future) {
        synchronized (isolatedPlayerLock) {
            if (isolatedPlayerId.get() == requestId && isolatedPlayerFuture == future) isolatedPlayerFuture = null;
        }
    }

    public void searchContent(Site site, String keyword, boolean quick, String page) {
        AtomicLong startedAt = new AtomicLong(System.currentTimeMillis());
        execute(TaskType.RESULT, result, SearchTask.create(site, keyword, quick, page),
                result -> SiteHealthStore.recordSearch(site, true, result.getList().size(), System.currentTimeMillis() - startedAt.get(), ""),
                error -> SiteHealthStore.recordSearch(site, false, 0, System.currentTimeMillis() - startedAt.get(), error.getMessage()),
                startedAt);
    }

    public void searchContent(List<Site> sites, String keyword, boolean quick) {
        if (sites == null || sites.isEmpty()) return;
        synchronized (searchLock) {
            // 清理后不再新建搜索执行器：否则会为已死的 ViewModel 起一批无人接收的搜索。
            if (cleared) return;
            List<Site> tasks = new ArrayList<>();
            for (Site site : sites) {
                if (quick && !site.isQuickSearch()) continue;
                // 屏蔽/隐藏/内嵌网页这类搜不出东西的源不参与搜索：一律不派、不计。
                if (!SiteSearchScope.isSearchable(site)) continue;
                tasks.add(site);
            }
            dispatchLocked(tasks, keyword, quick);
        }
    }

    /**
     * 一次带超时的提交。
     *
     * <p>{@code future} 是本任务的句柄；{@code timedOut} 记下"这次取消是超时打的，不是用户/换代取消"，
     * 回调据此区分：超时要照常结算（记失败、进度 +1），换代取消才可以默默忽略。
     */
    private static final class TimedCall {
        final FluentFuture<Result> future;
        final AtomicBoolean timedOut;

        private TimedCall(FluentFuture<Result> future, AtomicBoolean timedOut) {
            this.future = future;
            this.timedOut = timedOut;
        }
    }

    /**
     * 提交一个带超时的任务，超时从**任务真正开始执行**计时，不含入队等待。
     *
     * <p>线程池空闲时两者等价；一旦前面有别的任务占着线程（几个源同时卡在 init / 网络里），
     * 排队时间不能记到后面这个任务头上，否则会得出"还没轮到它动手就已经超时"的错误结论，
     * 日志、耗时统计和站点健康记录都会失真。
     *
     * <p>真正的计时起点写进 {@code startedAt}，调用方的 cost 日志与健康记录都用这个数。
     */
    private static TimedCall submitTimed(ListeningExecutorService executor, Callable<?> prepare, Callable<Result> callable, long timeoutMs, AtomicLong startedAt) {
        AtomicReference<ListenableFuture<?>> self = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Callable<Result> timed = () -> {
            // 前置阶段（建 spider、按源 ext 初始化、必要时为环境排队）先走完，它不计时：
            // 排队和初始化是搜索的必要步骤，不该被算成这个源"搜得慢"。
            if (prepare != null) prepare.call();
            startedAt.set(System.currentTimeMillis());
            ScheduledFuture<?> watchdog = Task.scheduler().schedule(() -> {
                timedOut.set(true);
                ListenableFuture<?> future = self.get();
                if (future != null) future.cancel(true);
            }, timeoutMs, TimeUnit.MILLISECONDS);
            try {
                return callable.call();
            } finally {
                watchdog.cancel(false);
            }
        };
        FluentFuture<Result> future = FluentFuture.from(executor.submit(timed));
        self.set(future);
        return new TimedCall(future, timedOut);
    }

    /** 派发一代搜索：每个源一个任务，全部并发提交，不因环境独占而延后或减少。 */
    private void dispatchLocked(List<Site> tasks, String keyword, boolean quick) {
        int epoch = stopSearchLocked();
        searchKeyword = keyword;
        searchQuick = quick;
        searchTotal = tasks.size();
        searchDone.set(0);
        searchRemaining.clear();
        searchRemaining.addAll(tasks);
        searchBacklog.clear();
        searchProgress.postValue(SearchProgress.start(tasks.size()));
        // A site spider may ignore interruption after its timeout. Isolate every
        // generation so an uncooperative old worker cannot starve the next search;
        // shutdownNow() remains best-effort because the JVM cannot forcibly stop it.
        ListeningExecutorService executor = searchExecutor = Task.newSearchExecutor(Setting.getSearchThread());
        for (Site site : tasks) {
            AtomicLong startedAt = new AtomicLong(0);
            TimedCall call;
            try {
                call = submitTimed(executor, () -> {
                    SiteApi.prepareSpider(site);
                    return null;
                }, SearchTask.create(site, keyword, quick), Constant.TIMEOUT_SEARCH, startedAt);
            } catch (RejectedExecutionException e) {
                // 与 onCleared()/换代 的竞态：执行器已终止，这个源按"没出结果"记账即可。
                SpiderDebug.log("search", "source rejected site=%s", site.getName());
                markSearchDone(epoch, site);
                postSearchProgress(epoch);
                continue;
            }
            FluentFuture<Result> future = call.future;
            searchFuture.add(future);
            future.addCallback(Task.callback(
                    result -> {
                        if (searchEpoch.get() != epoch) return;
                        int items = result == null || result.getList() == null ? 0 : result.getList().size();
                        long cost = System.currentTimeMillis() - startedAt.get();
                        SiteHealthStore.recordSearch(site, true, items, cost, "");
                        // 进度是"源数"不是"条目数"：把每个源出了几条也记一笔，免得 60/60 看起来像 60 条内容。
                        SpiderDebug.log("search", "source done site=%s items=%d cost=%sms", site.getName(), items, cost);
                        markSearchDone(epoch, site);
                        postSearchResult(epoch, result);
                        postSearchProgress(epoch);
                    },
                    error -> {
                        if (searchEpoch.get() != epoch) return;
                        boolean timeout = call.timedOut.get();
                        // 换代/用户取消：这批结果已经作废，静默丢弃。
                        if (error instanceof CancellationException && !timeout) return;
                        long cost = System.currentTimeMillis() - startedAt.get();
                        SiteHealthStore.recordSearch(site, false, 0, cost, timeout ? "timeout" : error.getMessage());
                        SpiderDebug.log("search", "source %s site=%s cost=%sms err=%s",
                                timeout ? "timeout" : "fail", site.getName(), cost,
                                timeout ? "-" : String.valueOf(error.getMessage()));
                        markSearchDone(epoch, site);
                        postSearchProgress(epoch);
                        if (!timeout) error.printStackTrace();
                    }
            ), MoreExecutors.directExecutor());
        }
    }

    /** 这个源已有结果（成功或失败）：从待搜集合摘掉，进度计数 +1。 */
    private void markSearchDone(int epoch, Site site) {
        if (searchEpoch.get() != epoch) return;
        synchronized (searchLock) {
            searchRemaining.remove(site);
        }
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable) {
        execute(type, liveData, callable, null, null);
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable, Consumer<Result> onSuccess, Consumer<Throwable> onError) {
        execute(type, liveData, callable, onSuccess, onError, new AtomicLong(0));
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable, Consumer<Result> onSuccess, Consumer<Throwable> onError, AtomicLong startedAt) {
        if (cleared) return;
        AtomicInteger taskId = Objects.requireNonNull(taskIds.get(type));
        int currentId = taskId.incrementAndGet();
        ListenableFuture<?> old = futures.get(type);
        if (old != null) old.cancel(true);
        ListeningExecutorService executor = type == TaskType.PLAYER ? playerExecutor : Task.executor();
        FluentFuture<Result> future;
        TimedCall call;
        try {
            call = submitTimed(executor, null, callable, Constant.TIMEOUT_VOD, startedAt);
            future = call.future;
        } catch (RejectedExecutionException e) {
            // 与 onCleared() 的竞态窗口：执行器已终止，本次请求作废即可。
            return;
        }
        futures.put(type, future);
        future.addCallback(Task.callback(
                result -> {
                    if (taskId.get() != currentId) return;
                    if (type == TaskType.PLAYER) App.post(() -> {
                        if (taskId.get() != currentId) return;
                        if (onSuccess != null) onSuccess.accept(result);
                        liveData.setValue(result);
                    });
                    else {
                        if (onSuccess != null) onSuccess.accept(result);
                        liveData.postValue(result);
                    }
                },
                error -> {
                    if (taskId.get() != currentId) return;
                    if (error instanceof CancellationException && !call.timedOut.get()) return;
                    Result failure = error instanceof ExtractException ? Result.error(error.getMessage()) : Result.empty();
                    if (type == TaskType.PLAYER) App.post(() -> {
                        if (taskId.get() != currentId) return;
                        if (onError != null) onError.accept(error);
                        liveData.setValue(failure);
                    });
                    else {
                        if (onError != null) onError.accept(error);
                        liveData.postValue(failure);
                    }
                    error.printStackTrace();
                }
        ), MoreExecutors.directExecutor());
    }

    private void postSearchResult(int epoch, Result result) {
        if (searchEpoch.get() != epoch) return;
        if (result == null) return;
        searchBacklog.add(result);
        search.postValue(result);
        Integer tick = searchTick.getValue();
        searchTick.postValue(tick == null ? 1 : tick + 1);
    }

    /**
     * 页面去播放途中拿到、但还没刷进列表的结果，原样放回队列，回前台按序补。
     *
     * <p>重复放回无副作用：同一个 Result 对象只留一份。
     */
    public void restoreSearchResult(Result result) {
        if (result == null) return;
        if (!searchBacklog.contains(result)) searchBacklog.add(result);
    }

    /** 页面回到前台时取走在后台攒下的结果，按到达顺序。 */
    public List<Result> drainSearchBacklog() {
        List<Result> items = new ArrayList<>(searchBacklog);
        searchBacklog.clear();
        return items;
    }

    /** 进度按"源数"计：每有一个源出结果（成功或失败）就 +1，总数是本次派发的源数。 */
    private void postSearchProgress(int epoch) {
        if (searchEpoch.get() != epoch) return;
        searchProgress.postValue(SearchProgress.of(searchDone.incrementAndGet(), searchTotal));
    }

    public int stopSearch() {
        synchronized (searchLock) {
            return stopSearchLocked();
        }
    }

    private int stopSearchLocked() {
        int epoch = searchEpoch.incrementAndGet();
        searchFuture.forEach(future -> future.cancel(true));
        searchFuture.clear();
        searchRemaining.clear();
        searchDone.set(0);
        if (searchExecutor != null) {
            searchExecutor.shutdownNow();
            searchExecutor = null;
        }
        return epoch;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // 必须先置位再关池：反过来会留下「池已终止但 cleared=false」的窗口。
        cleared = true;
        stopSearch();
        futures.values().forEach(future -> future.cancel(true));
        cancelPlayerContentIsolated();
        playerExecutor.shutdownNow();
        isolatedPlayerExecutor.shutdownNow();
    }


    private enum TaskType {RESULT, PLAYER, ACTION}
}
