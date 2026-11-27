package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.SiteContext;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.karaoke.KaraokeResult;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteBlockSetting;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** 环境被详情/播放占着时挂起的搜索：只带还没搜完的源，进度接着原来的单位算。 */
    private volatile SearchRequest pendingSearch;
    /** 本代搜索还没出结果的源；出了结果（含失败）就从这里摘掉。 */
    private final List<Site> searchRemaining = new CopyOnWriteArrayList<>();
    private volatile long pauseAt;
    private volatile boolean pendingArmed;
    private volatile String searchKeyword = "";
    private volatile boolean searchQuick;
    private volatile int searchTotal;
    private final AtomicInteger searchDone = new AtomicInteger();
    private final SiteContext.Observer envObserver = new SiteContext.Observer() {
        @Override
        public void onHold(String siteKey) {
            synchronized (searchLock) {
                if (cleared) return;
                if (searchExecutor != null) {
                    snapshotLocked();
                    int done = searchDone.get();
                    int total = searchTotal;
                    pendingArmed = true;
                    stopSearchLocked();
                    // 已经搜到的结果留在原列表里不动，只有没出结果的源要重来。
                    SpiderDebug.log("search", "paused by env hold site=%s done=%d/%d", siteKey, done, total);
                } else if (pendingSearch != null) {
                    // 搜索页先退到后台（页面 onStop 已把它软挂起），紧接着进详情：这才接管这份挂起。
                    boolean fresh = System.currentTimeMillis() - pauseAt <= ADOPT_WINDOW_MS;
                    pendingArmed = fresh && !pendingSearch.sites.isEmpty();
                    SpiderDebug.log("search", "hold adopts page pause site=%s armed=%s done=%d/%d", siteKey, pendingArmed, pendingSearch.done, pendingSearch.total);
                } else {
                    SpiderDebug.log("search", "hold but no running search site=%s", siteKey);
                }
            }
        }

        @Override
        public void onRelease() {
            SearchRequest request = pendingSearch;
            pendingSearch = null;
            boolean armed = pendingArmed && request != null;
            pendingArmed = false;
            if (!armed || request.sites.isEmpty() || cleared) return;
            if (SiteContext.isHeld()) {
                pendingSearch = request;
                return;
            }
            synchronized (searchLock) {
                if (cleared) return;
                SpiderDebug.log("search", "resume after env release keyword=%s done=%d/%d remaining=%d", request.keyword, request.done, request.total, request.sites.size());
                dispatchLocked(request.sites, request.keyword, request.quick, request.done, request.total, true);
            }
        }
    };

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
        SiteContext.addObserver(envObserver);
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
        try {
            future = FluentFuture.from(isolatedPlayerExecutor.submit(
                    () -> SiteApi.playerContentIsolated(key, flag, id, playerType)))
                    .withTimeout(Constant.TIMEOUT_VOD, TimeUnit.MILLISECONDS, Task.scheduler());
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
                    if (error instanceof CancellationException) return;
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
        long start = System.currentTimeMillis();
        execute(TaskType.RESULT, result, SearchTask.create(site, keyword, quick, page),
                result -> SiteHealthStore.recordSearch(site, true, result.getList().size(), System.currentTimeMillis() - start, ""),
                error -> SiteHealthStore.recordSearch(site, false, 0, System.currentTimeMillis() - start, error.getMessage()));
    }

    public void searchContent(List<Site> sites, String keyword, boolean quick) {
        if (sites == null || sites.isEmpty()) return;
        if (SiteContext.isHeld()) {
            // 环境正被详情/播放占着：现在派发只会让每个源在 init 里干等，最后被 30s 任务超时
            // 结算掉（进度照样 +1，结果一条没有）。挂起来，等 release 再派剩下的源。
            pendingSearch = new SearchRequest(sites, keyword, quick, 0, 0);
            SpiderDebug.log("search", "deferred env held keyword=%s sites=%d", keyword, sites.size());
            return;
        }
        synchronized (searchLock) {
            // 清理后不再新建搜索执行器：否则会为已死的 ViewModel 起一批无人接收的搜索。
            if (cleared) return;
            List<Site> tasks = new ArrayList<>();
            for (Site site : sites) {
                if (quick && !site.isQuickSearch()) continue;
                // 屏蔽/隐藏的源不参与搜索：以前照样派出去，既白耗一个并发，也被算进 60/60。
                if (SiteBlockSetting.isBlocked(site) || site.isHide()) continue;
                tasks.add(site);
            }
            dispatchLocked(tasks, keyword, quick, 0, tasks.size(), false);
        }
    }

    /**
     * 派发一代搜索。resume=true 表示这是被环境独占打断后接着搜：不清进度、不从 0 数，
     * 只把还没出结果的源再派一次，已搜到的结果原样留在结果列表里。
     */
    private void dispatchLocked(List<Site> tasks, String keyword, boolean quick, int base, int total, boolean resume) {
        int epoch = stopSearchLocked();
        searchKeyword = keyword;
        searchQuick = quick;
        searchTotal = total;
        searchDone.set(base);
        searchRemaining.clear();
        searchRemaining.addAll(tasks);
        searchProgress.postValue(resume ? SearchProgress.of(base, total) : SearchProgress.start(total));
        // A site spider may ignore interruption after its timeout. Isolate every
        // generation so an uncooperative old worker cannot starve the next search;
        // shutdownNow() remains best-effort because the JVM cannot forcibly stop it.
        ListeningExecutorService executor = searchExecutor = Task.newSearchExecutor(Setting.getSearchThread());
        for (Site site : tasks) {
            long start = System.currentTimeMillis();
            FluentFuture<Result> future = FluentFuture.from(executor.submit(SearchTask.create(site, keyword, quick))).withTimeout(Constant.TIMEOUT_SEARCH, TimeUnit.MILLISECONDS, Task.scheduler());
            searchFuture.add(future);
            future.addCallback(Task.callback(
                    result -> {
                        if (searchEpoch.get() != epoch) return;
                        int items = result == null || result.getList() == null ? 0 : result.getList().size();
                        SiteHealthStore.recordSearch(site, true, items, System.currentTimeMillis() - start, "");
                        // 进度是"源数"不是"条目数"：把每个源出了几条也记一笔，免得 60/60 看起来像 60 条内容。
                        SpiderDebug.log("search", "source done site=%s items=%d cost=%sms", site.getName(), items, System.currentTimeMillis() - start);
                        markSearchDone(epoch, site);
                        postSearchResult(epoch, result);
                        postSearchProgress(epoch);
                    },
                    error -> {
                        if (searchEpoch.get() != epoch) return;
                        if (error instanceof CancellationException) return;
                        SiteHealthStore.recordSearch(site, false, 0, System.currentTimeMillis() - start, error.getMessage());
                        markSearchDone(epoch, site);
                        postSearchProgress(epoch);
                        error.printStackTrace();
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
        if (cleared) return;
        AtomicInteger taskId = Objects.requireNonNull(taskIds.get(type));
        int currentId = taskId.incrementAndGet();
        ListenableFuture<?> old = futures.get(type);
        if (old != null) old.cancel(true);
        ListeningExecutorService executor = type == TaskType.PLAYER ? playerExecutor : Task.executor();
        FluentFuture<Result> future;
        try {
            future = FluentFuture.from(executor.submit(callable)).withTimeout(Constant.TIMEOUT_VOD, TimeUnit.MILLISECONDS, Task.scheduler());
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
                    if (error instanceof CancellationException) return;
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

    /**
     * 页面退到后台（进详情、切页、锁屏）时的软暂停：停掉在跑的搜索、把还没出结果的源记下来，
     * 但先不认作"用户主动放弃"——如果紧接着是进详情，环境独占放开后就接着搜。
     */
    public void pauseSearch() {
        synchronized (searchLock) {
            if (cleared || searchExecutor == null) return;
            snapshotLocked();
            int done = searchDone.get();
            int total = searchTotal;
            int pending = pendingSearch == null ? 0 : pendingSearch.sites.size();
            pauseAt = System.currentTimeMillis();
            pendingArmed = false;
            stopSearchLocked();
            SpiderDebug.log("search", "paused by page done=%d/%d pending=%d", done, total, pending);
        }
    }

    /** 把当前这代搜索的"还没出结果的源 + 进度"存成待续的搜索。 */
    private void snapshotLocked() {
        List<Site> remaining = new ArrayList<>(searchRemaining);
        pendingSearch = remaining.isEmpty() ? null : new SearchRequest(remaining, searchKeyword, searchQuick, searchDone.get(), searchTotal);
    }

    private void postSearchResult(int epoch, Result result) {
        if (searchEpoch.get() != epoch) return;
        search.postValue(result);
    }

    /** 绝对进度：已完成的源数接着原来的口径往上加，恢复后总数不变。 */
    private void postSearchProgress(int epoch) {
        if (searchEpoch.get() != epoch) return;
        searchProgress.postValue(SearchProgress.of(searchDone.incrementAndGet(), searchTotal));
    }

    public int stopSearch() {
        // 用户明确退出搜索：连挂起的那份也一起丢掉，不要在后台接着搜。
        pendingSearch = null;
        pendingArmed = false;
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
        SiteContext.removeObserver(envObserver);
        pendingSearch = null;
        stopSearch();
        futures.values().forEach(future -> future.cancel(true));
        cancelPlayerContentIsolated();
        playerExecutor.shutdownNow();
        isolatedPlayerExecutor.shutdownNow();
    }

    /** 进详情接管"页面软暂停"的时间窗口：超过这个时长就别再认领，免得凭空续搜。 */
    private static final long ADOPT_WINDOW_MS = 60_000L;

    /** 挂起的搜索：sites 是还没出结果的源，done/total 是原来的进度口径。 */
    private record SearchRequest(List<Site> sites, String keyword, boolean quick, int done, int total) {
    }

    private enum TaskType {RESULT, PLAYER, ACTION}
}
