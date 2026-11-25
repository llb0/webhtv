package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvDiscMenuPolicy;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackFrameSnapshot;
import com.fongmi.android.tv.player.PlaybackOwnershipKeys;
import com.fongmi.android.tv.player.PlaybackServiceReleasePolicy;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.VideoAspectMode;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.exo.ExoOutputModeManager;
import com.fongmi.android.tv.player.exo.ExoOutputModePolicy;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.plugin.PluginHostEvents;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.player.exo.ass.ExoAssSession;
import com.fongmi.android.tv.player.exo.subtitle.ExoSubtitleSession;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.subtitle.RealtimeSubtitleController;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.DiscMenuDialog;
import com.fongmi.android.tv.ui.dialog.VideoAspectModeDialog;
import com.fongmi.android.tv.ui.novel.NovelRouter;
import com.fongmi.android.tv.ui.custom.CustomSeekView;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.web.WebHomeInlineVodStore;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.function.IntConsumer;

public abstract class PlaybackActivity extends BaseActivity implements MediaController.Listener, Player.Listener, ServiceConnection {

    private static final String SIZE_TAG = "MPV_SIZE";
    private static final String STATE_PLAYBACK_KEY = "playback:ownershipKey";

    private ListenableFuture<MediaController> mControllerFuture;
    private MediaController mController;
    private PlaybackService mService;
    private boolean audioOnly;
    private boolean redirect;
    private boolean playbackExiting;
    private String preparedPlaybackKey;
    private String pinnedPlaybackKey;
    private boolean nativeOutputPending;
    private boolean bound;
    private boolean stop;
    private boolean lock;
    private int render = -1;
    private int videoRotation;
    private boolean rotationEngaged;
    private boolean applyingRotation;
    private View.OnLayoutChangeListener rotationLayoutListener;
    private int requestedAspectMode = VideoAspectMode.ORIGINAL;
    private boolean resizeApplied;
    private int resizeAppliedMode;
    private int resizeAppliedEffectiveMode;
    private int resizeAppliedTargetAspect;
    private int resizeAppliedContentAspect;
    private boolean resizeAppliedStretch;
    private ExoOutputModeManager exoOutputModeManager;
    private ExoAssSession attachedAssSession;
    private ExoSubtitleSession attachedSubtitleSession;
    private final com.fongmi.android.tv.player.SurfaceDiagnosticCollector surfaceDiagnostics =
            new com.fongmi.android.tv.player.SurfaceDiagnosticCollector();
    /**
     * 无图历史的兜底截图按「当前进度」判断：进度只要过了 60 秒就截，不是从起播开始计时。
     * 前 60 秒常是片头广告和黑场，起播时没到 60 秒就先等到那一刻；用户手动跳广告直接跳到
     * 1 分 20 秒时进度已经够了，就按跳转后的进度立刻补截。黑屏帧由取帧端丢掉，取不到就隔几秒再补。
     */
    private static final long SNAPSHOT_MIN_POSITION_MS = 60000L;
    /** 起播或 seek 后稍等一下，避免截到解码还没出图的帧。 */
    private static final long SNAPSHOT_SETTLE_MS = 800L;
    private static final long SNAPSHOT_RETRY_MS = 5000L;
    private static final int SNAPSHOT_MAX_ATTEMPTS = 3;
    private final Handler webHomeSnapshotHandler = new Handler(Looper.getMainLooper());
    private String webHomeSnapshotKey;
    private int webHomeSnapshotAttempt;
    private boolean webHomeSnapshotRunning;
    private boolean webHomeSnapshotDone;
    /** 播放画面抓不到时，ffmpeg 封面兜底每次播放只试一次。 */
    private boolean webHomeCoverTried;
    /** SMB 视频缩略图补图：只有 IJK 能解的老容器列表阶段取不到帧，播过一次再补，时间点与历史截图同一套。 */
    private final Handler smbThumbHandler = new Handler(Looper.getMainLooper());
    private String smbThumbKey;
    private int smbThumbAttempt;
    private boolean smbThumbRunning;
    private boolean smbThumbDone;

    protected MediaController controller() {
        return mController;
    }

    protected PlaybackService service() {
        return mService;
    }

    protected PlayerManager player() {
        return mService == null ? null : mService.player();
    }

    protected boolean isServiceReady() {
        return mService != null && mService.player() != null && !mService.player().isReleased();
    }

    protected View.OnClickListener guarded(Runnable action) {
        return v -> {
            if (isServiceReady()) action.run();
        };
    }

    protected View.OnClickListener guardedView(java.util.function.Consumer<View> action) {
        return v -> {
            if (isServiceReady()) action.accept(v);
        };
    }

    protected boolean isRedirect() {
        return redirect;
    }

    protected void setRedirect(boolean redirect) {
        this.redirect = redirect;
        if (mService == null) return;
        if (redirect) mService.clearNavigationCallback(getNavigationCallback());
        else mService.setNavigationCallback(getNavigationCallback(), activePlaybackKey());
    }

    protected boolean isPlaybackExiting() {
        return playbackExiting;
    }

    protected void markPlaybackExiting() {
        this.playbackExiting = true;
    }

    protected void finishPlayback() {
        markPlaybackExiting();
        stopPlayback();
        finish();
    }

    /**
     * 退出播放页时的确定性停播。
     *
     * <p>归属令牌（{@link #activePlaybackKey()}）在一次会话里可能失配：换集、onNewIntent 换条目，
     * 或 TMDB 富集改写 intent 的 id 之后 {@link #isOwner()} 会落到 false。旧实现此时退化成
     * {@code mController.stop()}，而控制器是异步连上的、播放又是由服务侧直接起播的，于是留下两个
     * 漏播窗口：控制器还没连上时整次调用是空操作；播放器处于 IDLE（取流中退出）时会被会话里的
     * stop 直接忽略。两者都不撤销前台通知，用户看到的就是「退回详情页后还有声音、通知栏还显示播放中」。
     *
     * <p>所以只要服务还在，退出就必须由服务自己停播：归属方关停服务，非归属方也让服务停播并撤掉
     * 前台通知，避免退出被迟到的会话状态吞掉。控制器只保留为「服务不可用」时的兜底。
     */
    protected void stopPlayback() {
        if (mService == null) {
            if (mController != null) mController.stop();
            return;
        }
        if (isOwner()) mService.shutdown();
        else mService.suspend();
    }

    protected void updateNavigationKey() {
        if (mService != null) mService.setNavigationCallback(getNavigationCallback(), activePlaybackKey());
    }

    protected boolean isAudioOnly() {
        return audioOnly;
    }

    protected void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    protected boolean isStop() {
        return stop;
    }

    protected void setStop(boolean stop) {
        this.stop = stop;
    }

    protected boolean isLock() {
        return lock;
    }

    protected void setLock(boolean lock) {
        this.lock = lock;
    }

    protected abstract PlaybackService.NavigationCallback getNavigationCallback();

    protected abstract CustomSeekView getSeekView();

    protected abstract PlayerView getExoView();

    protected abstract String getPlaybackKey();

    /**
     * Last known total duration for the current item. Engines that never publish
     * a duration (EXO on some HLS/TS sources) leave the timeline at 0, so the
     * seek view uses this while the engine still reports an unknown duration.
     */
    protected long getDurationFallbackMs() {
        return C.TIME_UNSET;
    }

    protected boolean deferPlaybackServiceBinding() {
        return false;
    }

    /**
     * 本次播放会话的归属令牌。
     *
     * <p>{@link #getPlaybackKey()} 由子类从 intent 现算，而 intent 的 id 会在起播之后被
     * 详情结果改写（TMDB 富集回来的 vodId，见 VideoActivity#updateVod）。播放器里的 key 是
     * 起播那一刻固化进 PlaySpec 的，之后无从更改；两者一旦不等，{@link #isOwner()} 便永久
     * 为 false，于是 STATE_READY 不再下发（转圈不收）、每秒的进度采样直接返回（进度不落库、
     * 刷新回起点）、切集也因 PlaybackService#isNavigationOwner 失配而不派发。
     *
     * <p>该 key 的语义是"归属与路由令牌"，必须在一次播放会话内保持稳定；History 行的 key
     * 迁移是另一件事，二者解耦。因此起播时钉住，会话结束或换集重新起播时再更新。
     */
    protected final String activePlaybackKey() {
        return pinnedPlaybackKey != null ? pinnedPlaybackKey : getPlaybackKey();
    }

    /**
     * 丢弃上一次播放会话的归属令牌，让 {@link #activePlaybackKey()} 重新回落到 intent。
     * 换条目（onNewIntent）时调用：此时旧会话已作废，新会话尚未起播。
     */
    protected final void resetPlaybackOwnership() {
        pinnedPlaybackKey = null;
    }

    /**
     * 本次播放会话是否由本页面拥有。
     *
     * <p>比较必须走 {@link PlaybackOwnershipKeys#sameSession(String, String)} 而不是字符串相等：
     * 自动连播时服务侧用「浏览下一集」的 {@code VE:<historyKey>|<线路>|<集>} 键起播，与本页面现算的
     * 记录键不是同一个字符串，但仍是同一次播放会话。旧实现在这里判假，导致回调里的
     * {@code onPlayerRebuild(...)} 整段跳过、Surface 永不重绑 —— 设备日志里那次「有声音没画面、
     * 重开才恢复」正是这条路径。
     */
    protected boolean isOwner() {
        String key = activePlaybackKey();
        PlayerManager manager = player();
        return PlaybackOwnershipKeys.sameSession(key, manager == null ? null : manager.getKey());
    }

    public final boolean pauseForOverlayPlayback() {
        if (!isOwner() || isFinishing() || isDestroyed()) return false;
        PlayerManager manager = player();
        if (manager == null || manager.isReleased() || manager.isEmpty()) return false;
        Player active = mController != null ? mController : manager.getPlayer();
        int state = active.getPlaybackState();
        boolean shouldResume = active.isPlaying() || (active.getPlayWhenReady() && (state == Player.STATE_BUFFERING || state == Player.STATE_READY));
        if (!shouldResume) return false;
        if (mController != null) mController.pause();
        else manager.pause();
        syncKeepScreenOn();
        return true;
    }

    public final void resumeAfterOverlayPlayback(boolean shouldResume) {
        if (!shouldResume || isFinishing() || isDestroyed() || !isOwner()) return;
        PlayerManager manager = player();
        if (manager == null || manager.isReleased() || manager.isEmpty()) return;
        Player active = mController != null ? mController : manager.getPlayer();
        int state = active.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED || active.getPlayWhenReady()) return;
        if (mController != null) mController.play();
        else manager.play();
        syncKeepScreenOn();
    }

    protected boolean isIdle() {
        return mController.getPlaybackState() == Player.STATE_IDLE;
    }

    protected boolean isEnded() {
        return mController.getPlaybackState() == Player.STATE_ENDED;
    }

    protected boolean isBuffering() {
        return mController.getPlaybackState() == Player.STATE_BUFFERING;
    }

    protected boolean isPaused() {
        return !isBuffering() && !isIdle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (dispatchDiscMenuKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    protected boolean dispatchDiscMenuKey(KeyEvent event) {
        if (mService == null || !isOwner() || event == null) return false;
        Player active = player().getPlayer();
        if (!(active instanceof MpvPlayer mpv)) return false;
        String action = MpvDiscMenuPolicy.keyAction(event.getKeyCode());
        if (action == null) return false;
        if (!mpv.isDiscMenuActive()
                && !(event.getKeyCode() == KeyEvent.KEYCODE_MENU && mpv.isDiscMenuAvailable())) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return true;
        mpv.sendDiscNav(action);
        return true;
    }

    protected boolean hasDiscMenu() {
        return mService != null && isOwner() && player().getPlayer() instanceof MpvPlayer mpv
                && mpv.isDiscMenuAvailable();
    }

    protected boolean hasDiscNavigationTimeline() {
        return mService != null && isOwner() && player().getPlayer() instanceof MpvPlayer mpv
                && mpv.hasDiscNavigationTimeline();
    }

    protected boolean canSavePlaybackHistory(History history) {
        if (history == null) return false;
        boolean navigationStarted = mService != null && isOwner()
                && player().getPlayer() instanceof MpvPlayer mpv
                && mpv.hasStartedDiscNavigation();
        return MpvDiscMenuPolicy.canSaveHistory(history.canSave(), navigationStarted);
    }

    protected void showDiscMenuControls() {
        if (hasDiscMenu()) DiscMenuDialog.show(this, (MpvPlayer) player().getPlayer());
    }

    protected void openDiscMenu() {
        if (hasDiscMenu()) ((MpvPlayer) player().getPlayer()).sendDiscNav("menu");
    }

    protected boolean dispatchDiscMenuTouch(MotionEvent event) {
        if (isLock() || mService == null || !isOwner()) return false;
        Player active = player().getPlayer();
        if (!(active instanceof MpvPlayer mpv) || !mpv.isDiscMenuActive()) return false;
        View surface = getExoView().getVideoSurfaceView();
        if (surface == null || event.getPointerCount() != 1) return false;
        int[] origin = new int[2];
        surface.getLocationOnScreen(origin);
        int pointerX = Math.round(event.getRawX() - origin[0]);
        int pointerY = Math.round(event.getRawY() - origin[1]);
        if (pointerX < 0 || pointerY < 0 || pointerX >= surface.getWidth() || pointerY >= surface.getHeight()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
            showDiscMenuControls();
        } else if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_MOVE
                || event.getActionMasked() == MotionEvent.ACTION_UP) {
            mpv.sendDiscNavPointer(pointerX, pointerY, event.getActionMasked() == MotionEvent.ACTION_UP);
        }
        return true;
    }

    protected void onServiceConnected() {
    }

    protected boolean isLutAllowed() {
        return true;
    }

    protected void onPrepare() {
    }

    protected void onPlayerRebuilt() {
    }

    /**
     * 保证「当前引擎」此刻确实挂在 PlayerView 上，必要时补一次绑定。
     *
     * <p>这是设备日志里那次「有声音没画面、重开才恢复」的收口点：自动连播由服务侧
     * {@code PlaybackService#startBrowse} 用 {@code VE:} 选集键起播，页面一度判不出归属，
     * 于是既没有重建回调去重绑，也没有其它路径补绑；解码器与音频照常工作，视频帧却全部被丢弃。
     *
     * <p>与 {@link #attachSurface(boolean)} 的区别：它不重设 render、不接 ASS/字幕会话，也不解绑
     * （引擎未就绪时直接返回），只把「当前引擎」补到视图上并重放当前快门策略；绑定已正确时立刻
     * 返回。且只在页面 RESUMED 时执行——后台的旧播放页同样会收到服务侧回调，在那里补绑会把引擎
     * 从用户正在看的页面上抢走。
     */
    private void ensureVideoSurfaceBound(String reason) {
        if (mService == null || isFinishing() || isDestroyed()) return;
        // 只有正处在 RESUMED 的页面可能是这条会话的显示方。后台/返回栈里的旧播放页同样会收到
        // 服务侧回调；在那里补绑会把同一个引擎从用户正在看的页面上抢走，结果还是黑屏。
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
        PlayerManager manager = player();
        if (manager == null || manager.isReleased()) return;
        PlayerView view = getExoView();
        if (view == null) return;
        Player currentPlayer = view.getPlayer();
        Player nextPlayer = manager.getPlayer();
        // 引擎尚未就绪时保持现状：这个兜底路径只负责「绑上当前引擎」，绝不在这里解绑。
        if (nextPlayer == null) return;
        VideoSurfaceBinding.Result<Player> binding = VideoSurfaceBinding.resolve(currentPlayer, nextPlayer);
        if (!binding.applied()) return;
        if (binding.detachedFirst()) view.setPlayer(null);
        view.setPlayer(binding.player());
        surfaceDiagnostics.rebind(reason, binding.detachedFirst(), view.getVideoSurfaceView());
        logSurfaceState("ensure bound reason=" + reason);
        syncVideoSurfaceSize(null);
        syncShutter();
        if (manager.isNativePlayer()) view.post(this::syncShutter);
    }

    /**
     * The rendered frame is revealed by clearing the shutter in the caller. The
     * loading spinner deliberately stays up until STATE_READY: a first frame does
     * not mean playback can proceed, and hiding the spinner here would present a
     * still-buffering session as a frozen picture. Subclasses own the spinner and
     * clear it from their own state handling.
     */
    protected void onExoFirstFrame() {
    }

    protected void onTracksChanged() {
    }

    /**
     * 用户选中了一个外挂字幕。有本地历史语义的子类覆写它把来源记下来，
     * 好让下次从历史进来时自动挂回同一个字幕。
     */
    protected void onSubtitleSelected(Sub sub) {
    }

    protected void onTitlesChanged() {
    }

    protected boolean onSourceHttpError(int statusCode, String msg) {
        return false;
    }

    protected void onControllerReady(Player controller) {
    }

    protected void onPlayerPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
    }

    protected void onError(String msg) {
    }

    protected void onReload(String msg) {
        onError(msg);
    }

    protected void onPlayingChanged(boolean isPlaying) {
    }

    protected void onStateChanged(int state) {
    }

    protected void onSizeChanged(VideoSize size) {
    }

    protected void onSurfaceAttached() {
    }

    protected void onFirstFrameRendered() {
    }

    /** 子类返回当前播放历史，供 WebHome 封面截图兜底使用；没有历史时返回 null。 */
    protected History getPlaybackHistory() {
        return null;
    }

    private boolean needsPlaybackSnapshot(History history) {
        if (history == null) return false;
        // 普通站源也参与兜底：详情页没给海报（或跨设备 file:// 已失效）时，
        // 用当前播放画面的非黑屏截图补历史卡片。
        return History.isMissingArtwork(history.getVodPic());
    }

    protected void scheduleWebHomeSnapshot() {
        History history = getPlaybackHistory();
        if (!needsPlaybackSnapshot(history)) return;
        if (TextUtils.equals(history.getKey(), webHomeSnapshotKey)) {
            if (webHomeSnapshotDone || webHomeSnapshotRunning
                    || webHomeSnapshotAttempt >= SNAPSHOT_MAX_ATTEMPTS) return;
        } else {
            webHomeSnapshotKey = history.getKey();
            webHomeSnapshotAttempt = 0;
            webHomeSnapshotDone = false;
            webHomeCoverTried = false;
        }
        armWebHomeSnapshot(-1L);
    }

    /** delay 传负数表示按当前进度现算：没到 60 秒就等到那一刻，到了就只等画面稳定。 */
    private void armWebHomeSnapshot(long delay) {
        if (webHomeSnapshotDone) return;
        webHomeSnapshotHandler.removeCallbacksAndMessages(null);
        long actual = delay < 0 ? snapshotDelayFromPosition() : delay;
        webHomeSnapshotHandler.postDelayed(this::captureWebHomeSnapshot, actual);
    }

    private long currentPlaybackPosition() {
        try {
            return Math.max(0L, player().getPosition());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private long snapshotDelayFromPosition() {
        long position = currentPlaybackPosition();
        if (position >= SNAPSHOT_MIN_POSITION_MS) return SNAPSHOT_SETTLE_MS;
        return SNAPSHOT_MIN_POSITION_MS - position + SNAPSHOT_SETTLE_MS;
    }

    /** seek 后按新进度重排：跳广告跳到 60 秒之后能立刻截，往回拖则重新等到 60 秒。 */
    private void rescheduleSnapshotsForPosition() {
        if (webHomeSnapshotKey != null && !webHomeSnapshotDone && !webHomeSnapshotRunning
                && webHomeSnapshotAttempt < SNAPSHOT_MAX_ATTEMPTS) armWebHomeSnapshot(-1L);
        if (smbThumbKey != null && !smbThumbDone && !smbThumbRunning
                && smbThumbAttempt < SNAPSHOT_MAX_ATTEMPTS) armSmbThumbnail(-1L);
    }

    /**
     * 播放中的 SMB 视频如果列表里还没有缩略图（典型是只有 IJK 能解的 avi/rmvb），
     * 在出画面后隔几个时间点各截一帧，取到非黑屏的就落进 smb_thumbs 并提前收工。
     */
    protected void scheduleSmbThumbnail() {
        String key = smbThumbnailKey();
        if (key == null) return;
        if (!key.equals(smbThumbKey)) {
            smbThumbKey = key;
            smbThumbAttempt = 0;
            smbThumbDone = false;
        }
        if (smbThumbDone || smbThumbAttempt >= SNAPSHOT_MAX_ATTEMPTS) return;
        boolean already = com.fongmi.android.tv.smb.SmbThumbnailCache.hasThumb(this, key);
        if (already) {
            smbThumbDone = true;
            applySmbThumbnailArtwork(key);
            return;
        }
        armSmbThumbnail(-1L);
    }

    /**
     * 列表里已经有这张缩略图时，直接拿它当本集的历史封面（详情图）。
     *
     * <p>以前只有播放到 60 秒后截帧一条路，SMB 老容器在头一分钟里既没图也没法提前截图；
     * 现在进目录抽的那张已经在盘上，开播就能用，也就不再需要播放时补截。
     * 用原子 UPDATE 只在字段仍为空/占位时写，不会覆盖 TMDB 海报。
     */
    private void applySmbThumbnailArtwork(String key) {
        History history = getPlaybackHistory();
        if (history == null) return;
        String existing = history.getVodPic();
        if (!History.isMissingArtwork(existing)) {
            // 历史里已经有图（可能是之前写进去的、或来自别的来源）：不用落库，
            // 但播放页背景还停在占位图上，得把这张直接用上。
            applyThumbnailArtwork(existing);
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("smb-thumb", "action=artwork-existing key=%s", key.substring(0, 8));
            }
            return;
        }
        java.io.File file = com.fongmi.android.tv.smb.SmbThumbnailCache.file(App.get(), key);
        if (!file.isFile() || file.length() <= 0) return;
        // 复制一份到历史封面目录，而不是直接引用缩略图缓存：缓存有上限和过期清理，
        // 清掉之后历史图会全失效；复制也保证目录预览那份不受任何影响。
        String uri = PlaybackFrameSnapshot.importArtwork(file);
        if (TextUtils.isEmpty(uri)) return;
        history.setVodPic(uri);
        final int cid = history.getCid();
        final String historyKey = history.getKey();
        Task.execute(() -> {
            History.updateVodPicIfEmptyOrPlaceholder(cid, historyKey, uri, ResUtil.getString(R.string.push_image));
            RefreshEvent.history();
        });
        // 历史卡片刷新了不等于播放页背景会跟着换：详情背景是进页时按占位图加载好的，
        // 不重新下发一次就一直是纸飞机。交给子类去套用（电视端/手机端的 setArtwork）。
        applyThumbnailArtwork(uri);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("smb-thumb", "action=artwork-from-thumb key=%s", key.substring(0, 8));
    }

    /** 子类覆盖：SMB 缩略图已写进历史后，把它同时套到播放页的详情背景上。 */
    protected void applyThumbnailArtwork(String uri) {
    }

    private void armSmbThumbnail(long delay) {
        if (smbThumbDone) return;
        smbThumbHandler.removeCallbacksAndMessages(null);
        long actual = delay < 0 ? snapshotDelayFromPosition() : delay;
        smbThumbHandler.postDelayed(this::captureSmbThumbnail, actual);
    }

    private String smbThumbnailKey() {
        try {
            androidx.media3.common.MediaItem item = player().getCurrentMediaItem();
            if (item == null || item.localConfiguration == null || item.localConfiguration.uri == null) return null;
            return com.fongmi.android.tv.smb.SmbPlaybackProxy.keyForUrl(item.localConfiguration.uri.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void captureSmbThumbnail() {
        if (smbThumbDone || smbThumbRunning || isFinishing() || isDestroyed()) return;
        final String key = smbThumbnailKey();
        if (key == null || !key.equals(smbThumbKey)) return;
        if (smbThumbAttempt >= SNAPSHOT_MAX_ATTEMPTS) return;
        smbThumbAttempt++;
        smbThumbRunning = true;
        com.fongmi.android.tv.player.PlaybackFrameSnapshot.captureBitmap(this, getExoView(), bitmap -> {
            smbThumbRunning = false;
            if (bitmap == null) {
                // 黑屏或还没出图：隔几秒再补，次数用尽就作罢，等下次播放再试。
                if (!smbThumbDone && smbThumbAttempt < SNAPSHOT_MAX_ATTEMPTS) armSmbThumbnail(SNAPSHOT_RETRY_MS);
                return;
            }
            final String target = key;
            Task.execute(() -> {
                try {
                    com.fongmi.android.tv.smb.SmbThumbnailCache.saveFromPlayback(App.get(), target, bitmap);
                } catch (Throwable ignored) {
                } finally {
                    bitmap.recycle();
                }
                // 刚截到的这张同时当历史封面用，免得历史卡片还要再等下一次截图。
                App.post(() -> applySmbThumbnailArtwork(target));
            });
            smbThumbDone = true;
            smbThumbHandler.removeCallbacksAndMessages(null);
        });
    }

    private void captureWebHomeSnapshot() {
        if (webHomeSnapshotDone || webHomeSnapshotRunning || isFinishing() || isDestroyed()) return;
        History history = getPlaybackHistory();
        if (!needsPlaybackSnapshot(history)) return;
        final String key = history.getKey();
        final int cid = history.getCid();
        final String vodId = history.getVodId();
        final String siteKey = history.getSiteKey();
        if (webHomeSnapshotAttempt >= SNAPSHOT_MAX_ATTEMPTS) return;
        webHomeSnapshotAttempt++;
        webHomeSnapshotRunning = true;
        PlaybackFrameSnapshot.capture(this, getExoView(), uri -> {
            webHomeSnapshotRunning = false;
            if (isFinishing() || isDestroyed()) return;
            History current = getPlaybackHistory();
            if (current == null || !TextUtils.equals(current.getKey(), key)) return;
            if (TextUtils.isEmpty(uri)) {
                // 黑屏或还没出图：先从片源直接抽一帧兜底（网页叠层/黑屏时也能拿到图），
                // 抽不到再隔几秒补截图，次数用尽就作罢，等下次播放再试。
                if (!webHomeCoverTried) captureWebHomeCover(key, cid, vodId, siteKey);
                if (!webHomeSnapshotDone && webHomeSnapshotAttempt < SNAPSHOT_MAX_ATTEMPTS) armWebHomeSnapshot(SNAPSHOT_RETRY_MS);
                return;
            }
            webHomeSnapshotDone = true;
            persistWebHomeSnapshot(cid, key, vodId, siteKey, uri);
        });
    }

    /** ffmpeg 封面兜底：从当前片源抽一帧，当作历史卡片图。 */
    private void captureWebHomeCover(String key, int cid, String vodId, String siteKey) {
        webHomeCoverTried = true;
        final String media = player().getUrl();
        final java.util.Map<String, String> headers = player().getHeaders();
        Task.execute(() -> {
            String uri = "";
            try {
                uri = PlaybackFrameSnapshot.captureFromSource(this, media, headers);
            } catch (Throwable ignored) {
            }
            final String result = uri;
            App.post(() -> {
                if (isFinishing() || isDestroyed() || TextUtils.isEmpty(result)) return;
                History current = getPlaybackHistory();
                if (current == null || !TextUtils.equals(current.getKey(), key)) return;
                webHomeSnapshotDone = true;
                persistWebHomeSnapshot(cid, key, vodId, siteKey, result);
                webHomeSnapshotHandler.removeCallbacksAndMessages(null);
            });
        });
    }

    /**
     * 后台落库：用原子 UPDATE 只在字段仍为空、仍是 push 占位图或仍是失效的
     * file:// 路径时写入，避免截图期间 TMDB 海报或播放进度被覆盖。webhome 历史同时回写内嵌 VOD 存储。
     */
    private void persistWebHomeSnapshot(int cid, String key, String vodId, String siteKey, String uri) {
        final History currentHistory = getPlaybackHistory();
        Task.execute(() -> {
            try {
                boolean webhome = WebHomeInlineVodStore.KEY.equals(siteKey);
                boolean updated = History.updateVodPicIfEmptyOrPlaceholder(cid, key, uri, ResUtil.getString(R.string.push_image));
                // 首次播放时历史行可能还没插入，原子 UPDATE 会返回 0；此时用当前
                // 正在播放的历史补插/补写，确保第一张卡片也能拿到截图，而不是等下次播放。
                if (!updated && currentHistory != null && TextUtils.equals(currentHistory.getKey(), key)) {
                    History row = History.find(cid, key);
                    if (row == null) {
                        History snapshot = currentHistory.copy();
                        snapshot.setVodPic(uri);
                        snapshot.save();
                        updated = true;
                    } else if (History.isMissingArtwork(row.getVodPic())) {
                        row.setVodPic(uri);
                        row.save();
                        updated = true;
                    }
                }
                if (webhome) WebHomeInlineVodStore.setPic(vodId, uri);
                if (updated) App.post(() -> {
                    History current = getPlaybackHistory();
                    if (current != null && TextUtils.equals(current.getKey(), key)) current.setVodPic(uri);
                    RefreshEvent.history();
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private long previewSeekAtMs;
    private long previewSeekTargetMs = -1L;

    protected void onSeekStarted() {
    }

    /** 预览条自己提交了一次跳转：记下目标与时刻，供同页其他跳转入口让路。 */
    protected final void markPreviewSeek(long targetMs) {
        previewSeekAtMs = SystemClock.elapsedRealtime();
        previewSeekTargetMs = targetMs;
        SpiderDebug.log("playback", "seek-commit source=preview target=%d", targetMs);
    }

    /**
     * 预览条刚跳完，别处的跳转（控件游标残留值、手势累计值）再来「补一刀」就会把位置抢走：
     * 1.5 秒内、且目标差一分钟以上的跳转一律丢弃，只留日志。
     */
    private boolean isStraySeek(long targetMs) {
        if (previewSeekAtMs <= 0L || previewSeekTargetMs < 0L) return false;
        if (SystemClock.elapsedRealtime() - previewSeekAtMs > 1500L) return false;
        return Math.abs(targetMs - previewSeekTargetMs) > 60_000L;
    }

    protected void showResizeModeDialog(int currentMode, IntConsumer action) {
        if (action == null) return;
        VideoAspectModeDialog.show(this, currentMode, action);
    }

    protected void applyResizeMode(int resizeMode) {
        int mode = VideoAspectMode.sanitize(resizeMode);
        requestedAspectMode = mode;
        applyResizeModeNow(mode);
        PlayerView view = getExoView();
        view.post(() -> {
            if (requestedAspectMode == mode && !isFinishing() && !isDestroyed()) applyResizeModeNow(mode);
        });
    }

    private void applyResizeModeNow(int resizeMode) {
        PlayerView view = getExoView();
        VideoAspectMode.Spec spec = VideoAspectMode.resolve(resizeMode, viewportAspectRatio(view), PlayerSetting.getCustomAspectRatio());
        int effectiveResizeMode = videoRotation != 0 && rotationEngaged && mService != null && player().isExo()
                ? AspectRatioFrameLayout.RESIZE_MODE_FILL : effectiveResizeMode(spec.resizeMode());
        float contentAspectRatio = spec.hasTargetAspectRatio() ? spec.targetAspectRatio() : sourceAspectRatio();
        if (!VideoAspectMode.isValidRatio(contentAspectRatio)) contentAspectRatio = 0f;
        // 同一次进入播放页里，这个方法会被 onPlayerRenderRequired / onPlayerRebuild / 子类 setScale
        // 以及 applyResizeMode 自己排的第二次 view.post 反复触发（设备实测 14-16 对）。
        // 每一对都要拼 20+ 字段的日志、写 4 行 logcat、再 requestLayout 两次，
        // 全都压在首帧窗口的主线程上。决策输入完全相同时直接返回。
        // 键包含所有影响结果的量（模式/有效模式/目标比例/来源比例/stretch），
        // 任一输入变化（视频尺寸到位、切集、切渲染、改比例）都会让键不同，仍然真实施加。
        if (isResizeModeApplied(resizeMode, effectiveResizeMode, spec.targetAspectRatio(), contentAspectRatio, spec.stretch())) return;
        logSurfaceState("applyResizeMode before mode=" + resizeMode + " effective=" + effectiveResizeMode + " aspect=" + spec.targetAspectRatio());
        if (mService != null) player().setVideoAspect(spec.targetAspectRatio(), spec.stretch());
        view.setResizeMode(effectiveResizeMode);
        AspectRatioFrameLayout content = view.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if (content != null) content.setAspectRatio(contentAspectRatio);
        view.requestLayout();
        View surface = view.getVideoSurfaceView();
        if (surface != null) surface.requestLayout();
        logSurfaceState("applyResizeMode after mode=" + resizeMode + " effective=" + effectiveResizeMode + " aspect=" + contentAspectRatio);
    }

    protected void setVideoRotation(int rotation) {
        videoRotation = ((rotation % 360) + 360) % 360;
        if (videoRotation != 0) switchToExoForRotation();
        if (mService != null && player().isExo() && videoRotation != 0) rotationEngaged = true;
        if (mService != null && player().isExo() && render != getRender()) {
            // 切渲染后 surface 异步重建，此时立即 applyVideoRotation 会落在旧 surface 上
            //（首次旋转看似没反应、随后闪烁）。与 fish 一致：由 onSurfaceAttached/onSizeChanged
            // 重新应用旋转与缩放，仅在 SDK<=27 时补一次重播。
            setRender();
            if (android.os.Build.VERSION.SDK_INT <= 27) getExoView().post(this::onReset);
        } else {
            applyVideoRotation();
        }
        applyResizeMode(requestedAspectMode);
    }

    /**
     * 旋转前先把内核切到 Exo。
     *
     * <p>画面旋转靠 {@link android.view.TextureView#setTransform} 实现，而渲染路径只在
     * {@code player().isExo()} 且 {@code rotationEngaged} 时才切到 TextureView
     *（见 {@link #getRender()}）。IJK/MPV 走 SurfaceView，setTransform 根本落不到 native 面，
     * 点旋转按钮等于没点。这里先手动切到 Exo（保留进度续播）再走原来的旋转流程：
     * 切换同步更新 playerType，onPlayerRebuild 会重新 setRender + 绑 Surface，
     * 新的 TextureView 面挂上后由 onSurfaceAttached / onSizeChanged 重新施加旋转。</p>
     */
    private void switchToExoForRotation() {
        if (mService == null || player() == null || player().getPlayer() == null) return;
        if (player().isExo()) return;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("playback-flow", "rotate switch kernel from=%d to=%d", player().getPlayerType(), PlayerSetting.EXO);
        }
        player().switchPlayerManually(PlayerSetting.EXO);
    }

    protected void onReset() {
    }

    protected int getVideoRotation() {
        return videoRotation;
    }

    protected void applyVideoRotation() {
        if (applyingRotation) return;
        applyingRotation = true;
        try {
            applyVideoRotationInternal();
        } finally {
            applyingRotation = false;
        }
    }

    private void applyVideoRotationInternal() {
        PlayerView view = getExoView();
        if (view == null) return;
        View surface = view.getVideoSurfaceView();
        if (surface == null) return;
        if (videoRotation == 0) {
            surface.setRotation(0);
            if (surface instanceof TextureView textureView) textureView.setTransform(new Matrix());
            removeRotationLayoutListener(surface);
            return;
        }
        if (!(surface instanceof TextureView textureView)) return;
        if (mService == null || player() == null) return;
        int vw = player().getVideoWidth();
        int vh = player().getVideoHeight();
        if (vw <= 0 || vh <= 0) return;
        surface.setRotation(0);
        int viewW = textureView.getWidth();
        int viewH = textureView.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            ensureRotationLayoutListener(textureView);
            return;
        }
        float rotatedW = (videoRotation == 90 || videoRotation == 270) ? vh : vw;
        float rotatedH = (videoRotation == 90 || videoRotation == 270) ? vw : vh;
        float fitScale = Math.min((float) viewW / rotatedW, (float) viewH / rotatedH);
        float scaleX = (vw / (float) viewW) * fitScale;
        float scaleY = (vh / (float) viewH) * fitScale;
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        matrix.postRotate(videoRotation, viewW * scaleX / 2f, viewH * scaleY / 2f);
        matrix.postTranslate((viewW - viewW * scaleX) / 2f, (viewH - viewH * scaleY) / 2f);
        textureView.setTransform(matrix);
        ensureRotationLayoutListener(textureView);
    }

    private void ensureRotationLayoutListener(View surface) {
        if (rotationLayoutListener == null) {
            rotationLayoutListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (videoRotation != 0) applyVideoRotation();
            };
        }
        surface.removeOnLayoutChangeListener(rotationLayoutListener);
        surface.addOnLayoutChangeListener(rotationLayoutListener);
    }

    private void removeRotationLayoutListener(View surface) {
        if (rotationLayoutListener != null) surface.removeOnLayoutChangeListener(rotationLayoutListener);
    }

    private boolean isResizeModeApplied(int resizeMode, int effectiveResizeMode,
                                        float targetAspectRatio, float contentAspectRatio, boolean stretch) {
        int targetBits = Float.floatToIntBits(targetAspectRatio);
        int contentBits = Float.floatToIntBits(contentAspectRatio);
        if (resizeApplied && resizeAppliedMode == resizeMode
                && resizeAppliedEffectiveMode == effectiveResizeMode
                && resizeAppliedTargetAspect == targetBits
                && resizeAppliedContentAspect == contentBits
                && resizeAppliedStretch == stretch) {
            return true;
        }
        resizeApplied = true;
        resizeAppliedMode = resizeMode;
        resizeAppliedEffectiveMode = effectiveResizeMode;
        resizeAppliedTargetAspect = targetBits;
        resizeAppliedContentAspect = contentBits;
        resizeAppliedStretch = stretch;
        return false;
    }

    private void invalidateAppliedResizeMode() {
        resizeApplied = false;
    }

    private float viewportAspectRatio(PlayerView view) {
        View container = view.getParent() instanceof View parent ? parent : view;
        int width = container.getWidth();
        int height = container.getHeight();
        if (width <= 0 || height <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
            height = getResources().getDisplayMetrics().heightPixels;
        }
        return width > 0 && height > 0 ? (float) width / height : 0f;
    }

    private float sourceAspectRatio() {
        if (mService == null || player() == null || player().getPlayer() == null) return 0f;
        VideoSize size = player().getPlayer().getVideoSize();
        return size.width > 0 && size.height > 0 ? size.width * size.pixelWidthHeightRatio / size.height : 0f;
    }

    private int effectiveResizeMode(int resizeMode) {
        if (mService != null && player().isMpv() && !player().isMpvSurfaceDirect() && resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            return AspectRatioFrameLayout.RESIZE_MODE_FILL;
        }
        return resizeMode;
    }

    protected void onReclaim() {
    }

    protected boolean shouldBindPlaybackService() {
        return true;
    }

    protected boolean shouldPauseOnBackground() {
        return PlayerSetting.isBackgroundOff();
    }

    protected boolean shouldAutoPlay() {
        return PlayerSetting.isAutoPlay();
    }

    protected boolean seekTo(long deltaMs) {
        onSeekStarted();
        long targetMs = Math.max(0, player().getPosition() + deltaMs);
        long durationMs = player().getDuration();
        boolean seekToEnd = durationMs > 0 && targetMs >= durationMs;
        long finalTarget = seekToEnd ? durationMs : targetMs;
        if (isStraySeek(finalTarget)) {
            SpiderDebug.log("playback", "seek-stray drop target=%d keeper=%d delta=%d", finalTarget, previewSeekTargetMs, deltaMs);
            return false;
        }
        pinSeekDisplay(finalTarget);
        mController.seekTo(finalTarget);
        if (!seekToEnd) mController.play();
        logSeekVerify(finalTarget);
        return seekToEnd;
    }

    /**
     * 屏显上的时间是「请求值」，引擎没跳成功时它照样显示目标点位，过一会又被真实位置拉回去。
     * 这里跳完 1.2 秒回读一次引擎位置，日志里 seek-verify 给出 target / actual / duration，
     * 一眼就能分清「没跳成」和「跳成了只是播放慢」。
     */
    /** 跳转期间把屏显当前时间钉在目标点上，引擎报 0 也不跟着掉回 00:00。 */
    private long startupDisplayFloorMs = C.TIME_UNSET;
    private long startupDisplayFloorAt;

    /**
     * 起播阶段的屏显下限：有历史进度就先把时间显示在历史进度上，不要等引擎报位置。
     * 引擎到位（或超过 25 秒）后自动失效，之后完全跟随引擎。
     */
    protected final void pinStartupFloor(long positionMs) {
        if (positionMs <= 0) return;
        startupDisplayFloorMs = positionMs;
        startupDisplayFloorAt = android.os.SystemClock.elapsedRealtime();
    }

    /** 屏显用的位置：起播未到位时用历史进度，其余时候就是引擎位置。 */
    protected final long displayPosition() {
        long position = player() == null ? 0 : Math.max(0, player().getPosition());
        if (startupDisplayFloorMs != C.TIME_UNSET) {
            if (position >= startupDisplayFloorMs - 1500
                    || android.os.SystemClock.elapsedRealtime() - startupDisplayFloorAt > 25000L) {
                startupDisplayFloorMs = C.TIME_UNSET;
            } else {
                return startupDisplayFloorMs;
            }
        }
        return position;
    }

    protected final void pinSeekDisplay(long targetMs) {
        try {
            CustomSeekView seek = getSeekView();
            if (seek != null) seek.pinSeekPosition(targetMs);
        } catch (Throwable ignored) {
        }
    }

    protected final void logSeekVerify(long targetMs) {
        if (!SpiderDebug.isEnabled()) return;
        App.post(() -> {
            try {
                PlayerManager p = player();
                if (p == null || isFinishing() || isDestroyed()) return;
                long actual = p.getPosition();
                SpiderDebug.log("playback", "seek-verify target=%d actual=%d duration=%d state=%d", targetMs, actual, p.getDuration(), p.getPlaybackState());
            } catch (Throwable ignored) {
            }
        }, 1200L);
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, MediaMetadata metadata) {
        startPlayer(key, result, useParse, timeout, metadata, C.TIME_UNSET);
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout,
                               MediaMetadata metadata, long startPositionMs) {
        // 已经决定退出的播放页不能再起播。自动换源、换线路、取流结果这些链路最终都回到这里，
        // 迟到的一次起播会在服务里正常播放并挂上前台通知，而页面已经退出，没有人再停它——
        // 用户看到的就是「退回详情页后还有声音、通知栏还显示播放中」。
        if (isPlaybackExiting() || isFinishing() || isDestroyed()) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "startPlayer skipped exiting=%s finishing=%s destroyed=%s key=%s", isPlaybackExiting(), isFinishing(), isDestroyed(), key);
            return;
        }
        // 小说/漫画阅读器路由（播放内核前的最后一道拦截）
        // novel:// / pics:// / manga:// 是「阅读内容协议」而非播放地址。
        // 正常情况下 ContentDispatcher 已在更早的汇聚点分流；这里兜底处理漏网的解析结果。
        if (NovelRouter.isReaderUrl(result)) {
            if (NovelRouter.routeReaderEngine(this, result, key, getReaderVod())) return;
            return;
        }
        if (rejectUnsupportedDrm(key, result)) {
            return;
        } else if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            onError(ResUtil.getString(R.string.error_play_drm));
        } else if (result.hasMsg()) {
            onError(result.getMsg());
        } else if (result.getRealUrl().isEmpty()) {
            onError(ResUtil.getString(R.string.error_play_url));
        } else if (result.needParse() || useParse) {
            preparedPlaybackKey = null;
            pinnedPlaybackKey = key;
            attachSurface();
            player().parse(key, result, useParse, metadata, shouldAutoPlay(), startPositionMs);
        } else {
            preparedPlaybackKey = null;
            pinnedPlaybackKey = key;
            attachSurface();
            player().start(PlaySpec.from(result, key, metadata), timeout, shouldAutoPlay(), startPositionMs);
        }
        syncKeepScreenOn();
    }

    /**
     * 阅读器路由所需的当前 Vod 上下文（章节列表/书名/海报）。
     * 子类（VideoActivity / TmdbDetailActivity）覆写以返回正在播放的 Vod；
     * 默认 null 时阅读器仍会显示当前章内容（内联 payload），只是无章节导航。
     */
    protected Vod getReaderVod() {
        return null;
    }

    private boolean rejectUnsupportedDrm(String key, Result result) {
        if (result == null || result.getDrm() == null || !isSelectedMpvPlayer()) return false;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "reject drm for mpv key=%s drm=%s", key, result.getDrm().getType());
        onError(ResUtil.getString(R.string.error_play_mpv_drm_unsupported));
        return true;
    }

    private boolean isSelectedMpvPlayer() {
        return mService != null ? player().isMpv() : PlayerSetting.getActivePlayer() == PlayerSetting.MPV;
    }

    private void bindPlaybackService() {
        if (bound || shouldRejectPlaybackConnection()) return;
        long start = System.currentTimeMillis();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "bind service start key=%s", getPlaybackKey());
        startService(new Intent(this, PlaybackService.class));
        bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
        bound = true;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "bind service requested cost=%dms key=%s", System.currentTimeMillis() - start, getPlaybackKey());
    }

    private void bindPlaybackServiceAfterFirstFrame() {
        View root = getExoView().getRootView();
        root.postDelayed(this::bindPlaybackServiceIfIdle, 500L);
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
                root.post(() -> {
                    if (!isFinishing() && !isDestroyed()) bindPlaybackService();
                });
                return true;
            }
        });
    }

    // C101: safety net for the deferred binding. If the first frame never arrives (the
    // activity is finishing or never draws), playback must still connect instead of
    // hanging on the loading spinner forever.
    private void bindPlaybackServiceIfIdle() {
        if (isFinishing() || isDestroyed() || bound) return;
        bindPlaybackService();
    }

    private void buildControllerAsync(SessionToken token) {
        if (mControllerFuture != null || shouldRejectPlaybackConnection()) return;
        if (token == null) {
            handleControllerConnectionFailure(new IllegalStateException("Playback session token is unavailable"));
            return;
        }
        long start = System.currentTimeMillis();
        try {
            mControllerFuture = new MediaController.Builder(this, token).setListener(this).buildAsync();
            mControllerFuture.addListener(this::handleControllerConnected, ContextCompat.getMainExecutor(this));
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "controller build requested cost=%dms key=%s", System.currentTimeMillis() - start, getPlaybackKey());
        } catch (RuntimeException e) {
            handleControllerConnectionFailure(e);
        }
    }

    private void handleControllerConnectionFailure(Exception e) {
        SpiderDebug.log("playback-flow", e);
        releaseController();
        if (!shouldRejectPlaybackConnection()) finishPlayback();
    }

    protected void onControllerConnected() {
    }

    protected void onControllerReadyReconciled() {
    }

    private void reconcileControllerReadyState() {
        PlayerManager manager = player();
        if (mController == null || manager == null) return;
        MediaItem managerItem = manager.getCurrentMediaItem();
        MediaItem controllerItem = mController.getCurrentMediaItem();
        String managerMediaId = managerItem == null ? null : managerItem.mediaId;
        String controllerMediaId = controllerItem == null ? null : controllerItem.mediaId;
        if (!PlaybackStateReconciliation.shouldReplayReady(activePlaybackKey(), preparedPlaybackKey, manager.getKey(), managerMediaId, controllerMediaId, manager.getPlaybackState(), mController.getPlaybackState())) return;
        onControllerReadyReconciled();
    }

    private void handleControllerConnected() {
        if (shouldRejectPlaybackConnection() || mControllerFuture == null) return;
        long start = System.currentTimeMillis();
        try {
            mController = mControllerFuture.get();
            getSeekView().setPlayer(mController);
            getSeekView().setSeekListener(this::onSeekStarted);
            getSeekView().setDurationFallback(this::getDurationFallbackMs);
            onControllerReady(mController);
            mController.addListener(this);
            reconcileControllerReadyState();
        } catch (Exception e) {
            handleControllerConnectionFailure(e);
            return;
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "controller connected cost=%dms key=%s", System.currentTimeMillis() - start, getPlaybackKey());
        syncKeepScreenOn();
        if (mController != null) onControllerConnected();
    }

    private boolean shouldRejectPlaybackConnection() {
        return playbackExiting || isFinishing() || isDestroyed();
    }

    private PendingIntent buildSessionIntent() {
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        Bundle extras = getIntent().getExtras();
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private boolean shouldReclaim() {
        return mService != null && !isOwner();
    }

    private void closePiP() {
        if (!isInPictureInPictureMode()) return;
        detach();
        finish();
    }

    private void attachSurface() {
        attachSurface(true);
    }

    private void attachSurface(boolean restoreExoShutter) {
        if (mService == null) return;
        int targetRender = getRender();
        logSurfaceState("attach start target=" + targetRender);
        if (restoreExoShutter) syncShutter(true);
        else hideVideoShutter();
        if (render != targetRender) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "switch render from=%d to=%d", render, targetRender);
            if (getExoView().getPlayer() != null) getExoView().setPlayer(null);
            getExoView().setRender(targetRender);
            render = targetRender;
            if (restoreExoShutter) syncShutter(true);
            else hideVideoShutter();
            logSurfaceState("attach after setRender target=" + targetRender);
        }
        surfaceDiagnostics.bind(getExoView(), player().getPlaybackTraceId());
        // 切集换新媒体前 PlayerManager.clear() 会把 spec 置空，preparePlayer() 再重建引擎。
        // 此时 render 值通常不变，旧的 attachSurface 只按“PlayerView 是否为空”判断，
        // 会把已释放的旧引擎继续留在 PlayerView 上，新引擎从未绑定 Surface。
        // 以引擎实例身份而不是“是否为空”判断，自动下一集的黑屏路径才能强制重绑。
        //
        // 判定本身交给 VideoSurfaceBinding：口径是「PlayerView 上挂着的引擎实例是否就是当前引擎」，
        // 与 Activity 是否自认归属无关。设备日志里的那次「有声音没画面」正因归属判假而漏绑。
        Player currentPlayer = getExoView().getPlayer();
        Player nextPlayer = player().getPlayer();
        VideoSurfaceBinding.Result<Player> binding = VideoSurfaceBinding.resolve(currentPlayer, nextPlayer);
        if (binding.applied()) {
            if (binding.detachedFirst()) getExoView().setPlayer(null);
            getExoView().setPlayer(binding.player());
            // 换了引擎实例，PlayerView 内部状态与视频尺寸都可能变，比例必须重算一次。
            invalidateAppliedResizeMode();
            surfaceDiagnostics.rebind("attach", binding.detachedFirst(), getExoView().getVideoSurfaceView());
            logSurfaceState("attach after setPlayer");
            syncVideoSurfaceSize(null);
            if (restoreExoShutter) syncShutter();
            else hideVideoShutter();
            if (player().isNativePlayer()) getExoView().post(this::syncShutter);
        }
        publishRenderTarget(getExoView().getVideoSurfaceView());
        ExoAssSession assSession = player().getAssSession();
        if (attachedAssSession != assSession) {
            detachAssSurface();
            attachedAssSession = assSession;
        }
        if (attachedAssSession != null) attachedAssSession.attach(getExoView());
        ExoSubtitleSession subtitleSession = player().getSubtitleSession();
        if (attachedSubtitleSession != subtitleSession) {
            if (attachedSubtitleSession != null) attachedSubtitleSession.detach();
            attachedSubtitleSession = subtitleSession;
        }
        if (attachedSubtitleSession != null) attachedSubtitleSession.attach(getExoView());
        onSurfaceAttached();
        logSurfaceState("attach done");
    }

    private void syncVideoSurfaceSize(VideoSize size) {
        if (mService == null) return;
        View surface = getExoView().getVideoSurfaceView();
        if (!(surface instanceof SurfaceView surfaceView)) return;
        if (!PlaybackPerformanceSetting.isSurfaceFixedSizeEnabled() || getRender() != PlayerSetting.RENDER_SURFACE || player().isNativePlayer()) {
            surfaceDiagnostics.resize("layout", -1, -1);
            surfaceView.getHolder().setSizeFromLayout();
            logSurfaceState("syncVideoSurfaceSize layout size=" + (size == null ? "null" : size.width + "x" + size.height));
            return;
        }
        int width = size != null && size.width > 0 ? size.width : player().getVideoWidth();
        int height = size != null && size.height > 0 ? size.height : player().getVideoHeight();
        if (width <= 0 || height <= 0) return;
        ExoUtil.EnhancedVideoProfile profile = ExoUtil.getEnhancedVideoProfile();
        float scale = Math.min((float) profile.width() / width, (float) profile.height() / height);
        if (scale < 1f) {
            width = Math.max(1, Math.round(width * scale));
            height = Math.max(1, Math.round(height * scale));
        }
        surfaceDiagnostics.resize("fixed", width, height);
        surfaceView.getHolder().setFixedSize(width, height);
        logSurfaceState("syncVideoSurfaceSize fixed=" + width + "x" + height);
    }

    private void logSurfaceState(String step) {
        PlayerView view = getExoView();
        if (view == null) return;
        View surface = view.getVideoSurfaceView();
        View content = view.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        String playerText = mService == null ? "none" : player().getPlayerText();
        boolean nativePlayer = mService != null && player().isNativePlayer();
        int targetRender = mService == null ? -1 : getRender();
        String message = "playback " + step
                + " key=" + getPlaybackKey()
                + " player=" + playerText
                + " native=" + nativePlayer
                + " render=" + render
                + " target=" + targetRender
                + " resize=" + view.getResizeMode()
                + " playerView=" + viewSize(view)
                + " content=" + viewSize(content)
                + " surface=" + surfaceName(surface) + ":" + viewSize(surface)
                + " holder=" + surfaceHolderSize(surface)
                + " rotation=" + displayRotation()
                + " orientation=" + getResources().getConfiguration().orientation;
        Log.d(SIZE_TAG, message);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("surface-size", "%s", message);
    }


    @SuppressWarnings("deprecation")
    private int displayRotation() {
        Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? getDisplay() : getWindowManager().getDefaultDisplay();
        return display == null ? -1 : display.getRotation();
    }

    private static String viewSize(View view) {
        if (view == null) return "null";
        return view.getWidth() + "x" + view.getHeight();
    }

    private static String surfaceName(View view) {
        return view == null ? "null" : view.getClass().getSimpleName();
    }

    private static String surfaceHolderSize(View view) {
        if (!(view instanceof SurfaceView surfaceView)) return "n/a";
        android.graphics.Rect frame = surfaceView.getHolder().getSurfaceFrame();
        return frame.width() + "x" + frame.height() + "/valid=" + surfaceView.getHolder().getSurface().isValid();
    }

    private void syncShutter() {
        syncShutter(false);
    }

    private void syncShutter(boolean restoreExo) {
        if (mService == null) return;
        boolean nativePlayer = player().isNativePlayer();
        View shutter = getExoView().findViewById(androidx.media3.ui.R.id.exo_shutter);
        if (nativePlayer) {
            boolean keepClosed = nativeOutputPending
                    || player().shouldKeepVideoShutterClosed();
            View videoSurface = getExoView().getVideoSurfaceView();
            // Native MPV uses SurfaceView, which is composed above the normal
            // PlayerView shutter. Alpha hides its buffer without replacing or
            // detaching the Surface while automatic output is being decided.
            if (videoSurface != null) videoSurface.setAlpha(keepClosed ? 0f : 1f);
            getExoView().setShutterBackgroundColor(keepClosed ? Color.BLACK : Color.TRANSPARENT);
            if (shutter != null) shutter.setVisibility(keepClosed ? View.VISIBLE : View.GONE);
        } else if (restoreExo) {
            View videoSurface = getExoView().getVideoSurfaceView();
            if (videoSurface != null) videoSurface.setAlpha(1f);
            getExoView().setShutterBackgroundColor(Color.BLACK);
            if (shutter != null) shutter.setVisibility(View.VISIBLE);
        }
        surfaceDiagnostics.snapshot("shutter-policy");
    }

    private void hideVideoShutter() {
        View shutter = getExoView().findViewById(androidx.media3.ui.R.id.exo_shutter);
        getExoView().setShutterBackgroundColor(Color.TRANSPARENT);
        if (shutter != null) shutter.setVisibility(View.GONE);
    }

    private void detachSurface() {
        surfaceDiagnostics.unbind();
        detachAssSurface();
        getExoView().setPlayer(null);
        if (mService != null) player().publishPlaybackRenderTarget(PlaybackAutoContext.RenderTarget.DETACHED);
    }

    private void detachAssSurface() {
        if (attachedAssSession != null) attachedAssSession.detach();
        attachedAssSession = null;
        if (attachedSubtitleSession != null) attachedSubtitleSession.detach();
        attachedSubtitleSession = null;
    }

    private void resetVideoSurfaceForDecoderSwitch() {
        detachAssSurface();
        int targetRender = getRender();
        int temporaryRender = targetRender == PlayerSetting.RENDER_TEXTURE ? PlayerSetting.RENDER_SURFACE : PlayerSetting.RENDER_TEXTURE;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "reset video surface for decoder switch temp=%d target=%d", temporaryRender, targetRender);
        getExoView().setPlayer(null);
        getExoView().setRender(temporaryRender);
        getExoView().setRender(targetRender);
        render = -1;
        // 引擎换过、PlayerView 也被重置过，上一次施加的比例不再可信。
        invalidateAppliedResizeMode();
    }

    protected void reattachVideoSurfaceAfterReparent() {
        if (mService == null) return;
        PlayerView view = getExoView();
        view.setKeepContentOnPlayerReset(true);
        hideVideoShutter();
        detachSurface();
        boolean posted = view.post(() -> {
            if (mService != null && !isFinishing() && !isDestroyed()) attachSurface(false);
            view.setKeepContentOnPlayerReset(false);
        });
        if (!posted) view.setKeepContentOnPlayerReset(false);
    }

    protected void setRender() {
        render = -1;
        detachSurface();
        attachSurface();
    }

    private int getRender() {
        if (mService != null && player().isNativePlayer()) return 0;
        if (mService != null && player().requiresTextureRenderForLut()) return PlayerSetting.RENDER_TEXTURE;
        if (rotationEngaged && mService != null && player().isExo()) return PlayerSetting.RENDER_TEXTURE;
        return PlayerSetting.getRender();
    }

    private void releasePlaybackService() {
        if (mService != null) releaseService(isOwner());
        detach();
    }

    private void releaseService(boolean owner) {
        mService.removePlayerCallback(mPlayerCallback);
        mService.clearNavigationCallback(getNavigationCallback());
        boolean hasConsumer = mService.hasExternalClient() || mService.hasPlayerCallback();
        switch (PlaybackServiceReleasePolicy.decide(owner, mService.isKeepAlive(), hasConsumer)) {
            case RESET_SESSION -> mService.resetSessionActivity();
            case SUSPEND_AND_RESET -> {
                mService.suspend();
                mService.resetSessionActivity();
            }
            case SHUTDOWN -> mService.shutdown();
            case DETACH -> {
            }
        }
    }

    private void detach() {
        releaseController();
        releaseBinding();
    }

    private void releaseController() {
        getSeekView().setPlayer(null);
        if (mController != null) mController.removeListener(this);
        if (mControllerFuture != null) MediaController.releaseFuture(mControllerFuture);
        mControllerFuture = null;
        mController = null;
    }

    private void releaseBinding() {
        if (!bound) return;
        bound = false;
        if (mService != null) mService.removePlayerCallback(mPlayerCallback);
        getSeekView().setProgressPlayer(null);
        // 页面解绑后引擎可能被别的页面接管，兜底必须跟着本页面一起撤掉，
        // 否则别的播放会读到上一集的时长。
        if (player() != null) player().setDurationFallback(null);
        unbindService(this);
        mService = null;
    }

    private void syncKeepScreenOn() {
        if (shouldKeepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private boolean shouldKeepScreenOn() {
        if (!isOwner()) return false;
        PlayerManager manager = player();
        if (manager == null || manager.isReleased() || manager.isEmpty()) return false;
        Player active = mController != null ? mController : manager.getPlayer();
        int state = active.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return false;
        if (active.isPlaying()) return true;
        return active.getPlayWhenReady() && (state == Player.STATE_BUFFERING || state == Player.STATE_READY);
    }

    private String lifecycleState() {
        String playerKey = null;
        boolean released = true;
        if (mService != null && mService.player() != null) {
            released = mService.player().isReleased();
            if (!released) playerKey = mService.player().getKey();
        }
        return "activity=" + getClass().getSimpleName() +
                " key=" + getPlaybackKey() +
                " playerKey=" + playerKey +
                " owner=" + isOwner() +
                " bound=" + bound +
                " service=" + (mService != null) +
                " controller=" + (mController != null) +
                " released=" + released +
                " redirect=" + redirect +
                " stop=" + stop +
                " finishing=" + isFinishing() +
                " destroyed=" + isDestroyed() +
                " keepScreen=" + ((getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
    }

    private final PlaybackService.PlayerCallback mPlayerCallback = new PlaybackService.PlayerCallback() {

        @Override
        public void onPrepare() {
            if (isOwner()) {
                MediaItem item = player().getCurrentMediaItem();
                preparedPlaybackKey = item == null ? null : item.mediaId;
                PlaybackActivity.this.onPrepare();
                reconcileControllerReadyState();
            }
        }

        @Override
        public void onTracksChanged() {
            if (isOwner()) PlaybackActivity.this.onTracksChanged();
        }

        @Override
        public void onTitlesChanged() {
            if (isOwner()) PlaybackActivity.this.onTitlesChanged();
        }

        @Override
        public boolean onSourceHttpError(int statusCode, String msg) {
            return isOwner() && PlaybackActivity.this.onSourceHttpError(statusCode, msg);
        }

        @Override
        public void onError(String msg) {
            if (isOwner()) PlaybackActivity.this.onError(msg);
        }

        @Override
        public void onReload(String msg) {
            if (isOwner()) PlaybackActivity.this.onReload(msg);
        }

        @Override
        public void onPlayerRenderRequired() {
            if (!isOwner()) return;
            int targetRender = getRender();
            if (render == targetRender) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "LUT switch render from=%d to=%d", render, targetRender);
            setRender();
            applyResizeMode(requestedAspectMode);
        }

        @Override
        public void onPlayerOutputPending() {
            if (!isOwner()) return;
            nativeOutputPending = true;
            syncShutter();
        }

        @Override
        public void onPlayerOutputReady() {
            if (!isOwner()) return;
            nativeOutputPending = false;
            syncShutter();
        }

        @Override
        public void onExoFirstFrame() {
            if (!isOwner() || !player().isExo()) return;
            View shutter = getExoView().findViewById(androidx.media3.ui.R.id.exo_shutter);
            if (shutter != null) shutter.setVisibility(View.INVISIBLE);
            getExoView().setShutterBackgroundColor(Color.TRANSPARENT);
            PlaybackActivity.this.onExoFirstFrame();
            PlaybackActivity.this.onFirstFrameRendered();
        }

        @Override
        public void onSubtitleSelected(Sub sub) {
            if (isOwner()) PlaybackActivity.this.onSubtitleSelected(sub);
        }

        @Override
        public void onPlayerRebuild(Player player, boolean resetVideoSurface) {
            // 归属必须与「当前引擎是否属于本页面」一致：AUTONEXT-02 之前这里判假的原因是
            // 服务侧用 VE: 选集键起播而页面拿记录键比对（见 PlaybackOwnershipKeys）。判假时
            // 整段被跳过，新引擎永远拿不到 Surface，画面全黑而声音正常。
            if (isOwner()) {
                nativeOutputPending = player().shouldKeepVideoShutterClosed();
                getSeekView().setProgressPlayer(player);
                if (resetVideoSurface) resetVideoSurfaceForDecoderSwitch();
                setRender();
                applyResizeMode(requestedAspectMode);
                PlaybackActivity.this.onPlayerRebuilt();
            }
        }
    };

    @Override
    protected void initView(Bundle savedInstanceState) {
        long start = System.currentTimeMillis();
        super.initView(savedInstanceState);
        restorePlaybackKey(savedInstanceState);
        if (!shouldBindPlaybackService()) return;
        ExoUtil.setPlayerView(getExoView());
        RealtimeSubtitleController.get().bind(getExoView());
        exoOutputModeManager = new ExoOutputModeManager(getWindow());
        if (deferPlaybackServiceBinding()) bindPlaybackServiceAfterFirstFrame();
        else bindPlaybackService();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "initView cost=%dms key=%s deferred=%s", System.currentTimeMillis() - start, getPlaybackKey(), deferPlaybackServiceBinding());
    }

    private void restorePlaybackKey(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        String key = savedInstanceState.getString(STATE_PLAYBACK_KEY);
        if (key != null) pinnedPlaybackKey = key;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!playbackExiting && pinnedPlaybackKey != null) {
            outState.putString(STATE_PLAYBACK_KEY, pinnedPlaybackKey);
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        syncKeepScreenOn();
        // 埋点放在 isOwner() 早退之前：同一台设备上非 owner 的页面同样在播（服务侧起播、画中画、
        // 多窗口），只有 owner 才广播会让订阅者以为"播放停了"。埋点只带宿主身份与本页拿到的
        // 布尔值，不触碰视图也不再读 controller，越权观感与早退后的既有逻辑无关。
        PluginHostEvents.playbackPlaying(this, isPlaying);
        if (!isOwner()) return;
        syncShutter();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "playing changed isPlaying=%s state=%d %s", isPlaying, mController == null ? -1 : mController.getPlaybackState(), lifecycleState());
        onPlayingChanged(isPlaying);
    }

    @Override
public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        syncKeepScreenOn();
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (isOwner()) {
            onPlayerPositionDiscontinuity(oldPosition, newPosition, reason);
            rescheduleSnapshotsForPosition();
        }
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        // 同样放在 isOwner() 早退之前：Surface 绑定那段注释已经证明状态回调对非 owner 也照常到达，
        // 而状态机是播放器的客观事实，不随本页是否有所有权而改变。
        PluginHostEvents.playbackState(this, state);
        // 绑定视频 Surface 是所有权无关的必经动作：服务侧「浏览下一集」（VE: 选集键）起播时
        // isOwner() 为假，旧实现让下面的早退把 Surface 重绑一起吞掉，新引擎于是永远拿不到
        // Surface —— 解码器/音频正常、画面全黑，只有重开 App 才好。每次 BUFFERING/READY 都是
        // 新媒体起播的必经点，在这里补一次「当前引擎是否已挂在视图上」的校验。
        if (state == Player.STATE_BUFFERING || state == Player.STATE_READY) ensureVideoSurfaceBound("state=" + state);
        if (mService != null && isOwner()) surfaceDiagnostics.bind(getExoView(), player().getPlaybackTraceId());
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "state changed state=%d %s", state, lifecycleState());
        syncKeepScreenOn();
        if (!isOwner()) return;
        syncShutter();
        onStateChanged(state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize size) {
        if (!isOwner()) return;
        syncShutter();
        logSurfaceState("onVideoSizeChanged size=" + size.width + "x" + size.height + " ratio=" + size.pixelWidthHeightRatio);
        publishRenderTarget(getExoView().getVideoSurfaceView());
        syncVideoSurfaceSize(size);
        applyExoOutputMode();
        onSizeChanged(size);
    }

    private void applyExoOutputMode() {
        if (mService == null || exoOutputModeManager == null) return;
        publishRenderTarget(getExoView().getVideoSurfaceView());
        ExoOutputModeManager.Result result;
        if (player().isExo() && getRender() == PlayerSetting.RENDER_SURFACE) {
            Format format = player().getVideoFormat();
            result = exoOutputModeManager.apply(format);
        } else {
            result = exoOutputModeManager.observe("observation-only");
        }
        publishDisplayFacts(result);
        if (SpiderDebug.isEnabled() && result.decision() != null && result.decision().mode() != null) {
            ExoOutputModePolicy.Mode mode = result.decision().mode();
            SpiderDebug.log("playback-flow", "exo output mode reason=%s applied=%s target=%dx%d@%.3fHz", result.reason(), result.applied(), mode.width(), mode.height(), mode.refreshRateMilliHz() / 1000f);
        }
    }

    private void restoreExoOutputMode() {
        if (exoOutputModeManager == null) return;
        ExoOutputModeManager.Result result = exoOutputModeManager.restore();
        if (mService != null) publishDisplayFacts(result);
    }

    private void publishDisplayFacts(ExoOutputModeManager.Result result) {
        if (mService == null || result == null) return;
        surfaceDiagnostics.snapshot("display-mode-request");
        surfaceDiagnostics.display(result);
        player().publishPlaybackDisplayFacts(
                toDisplayMode(result.currentMode()),
                toDisplayMode(result.requestedMode()));
        ExoOutputModePolicy.Decision decision = result.decision();
        boolean hasTarget = decision != null && decision.mode() != null;
        PlaybackTelemetry.DecisionOutcome outcome = result.applied()
                ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                : !hasTarget ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                : decision.changeRequired() ? PlaybackTelemetry.DecisionOutcome.SELECTED
                : PlaybackTelemetry.DecisionOutcome.HELD;
        player().publishPlaybackDecision(new PlaybackTelemetry.DecisionEvent(
                PlaybackTelemetry.DecisionDomain.DISPLAY_MODE,
                outcome,
                displayModeLabel(result.currentMode()),
                displayModeLabel(result.requestedMode()),
                result.applied() ? "window-requested" : displayModeLabel(result.currentMode()),
                result.reason(),
                result.applied() ? "none" : result.reason(),
                java.util.List.of(
                        displayModeInput("current_mode_id", result.currentMode() == null ? null : result.currentMode().id(), PlaybackAutoContext.ValueSource.SYSTEM_API),
                        displayModeInput("current_width", result.currentMode() == null ? null : result.currentMode().width(), PlaybackAutoContext.ValueSource.SYSTEM_API),
                        displayModeInput("current_height", result.currentMode() == null ? null : result.currentMode().height(), PlaybackAutoContext.ValueSource.SYSTEM_API),
                        displayModeInput("current_refresh_millihz", result.currentMode() == null ? null : result.currentMode().refreshRateMilliHz(), PlaybackAutoContext.ValueSource.SYSTEM_API),
                        displayModeInput("target_mode_id", result.requestedMode() == null ? null : result.requestedMode().id(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER),
                        displayModeInput("target_width", result.requestedMode() == null ? null : result.requestedMode().width(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER),
                        displayModeInput("target_height", result.requestedMode() == null ? null : result.requestedMode().height(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER),
                        displayModeInput("target_refresh_millihz", result.requestedMode() == null ? null : result.requestedMode().refreshRateMilliHz(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER),
                        PlaybackTelemetry.DecisionInput.bool("change_required", decision != null && decision.changeRequired(),
                                PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                        PlaybackTelemetry.DecisionInput.bool("window_request", result.applied(),
                                PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH))));
    }

    private static PlaybackTelemetry.DecisionInput displayModeInput(
            String name, Integer value, PlaybackAutoContext.ValueSource source) {
        return value == null
                ? PlaybackTelemetry.DecisionInput.unknown(name)
                : PlaybackTelemetry.DecisionInput.number(name, value,
                source, PlaybackAutoContext.Confidence.HIGH);
    }

    private static String displayModeLabel(ExoOutputModePolicy.Mode mode) {
        return mode == null ? "unknown" : "mode-" + mode.id();
    }

    private void publishRenderTarget(View surface) {
        if (mService == null) return;
        PlaybackAutoContext.RenderTarget target = surface instanceof SurfaceView
                ? PlaybackAutoContext.RenderTarget.SURFACE_VIEW
                : surface instanceof TextureView
                ? PlaybackAutoContext.RenderTarget.TEXTURE_VIEW
                : PlaybackAutoContext.RenderTarget.UNKNOWN;
        player().publishPlaybackRenderTarget(target);
    }

    private static PlaybackAutoContext.DisplayMode toDisplayMode(ExoOutputModePolicy.Mode mode) {
        return mode == null ? PlaybackAutoContext.DisplayMode.unknown()
                : new PlaybackAutoContext.DisplayMode(mode.id(), mode.width(), mode.height(), mode.refreshRateMilliHz());
    }

    @Override
    public void onRenderedFirstFrame() {
        if (!isOwner()) return;
        onFirstFrameRendered();
        scheduleWebHomeSnapshot();
        scheduleSmbThumbnail();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (shouldRejectPlaybackConnection()) return;
        long start = System.currentTimeMillis();
        PlaybackService connectedService = ((PlaybackService.LocalBinder) binder).getService();
        if (shouldRejectPlaybackConnection()) return;
        mService = connectedService;
        buildControllerAsync(mService.getSessionToken());
        if (shouldRejectPlaybackConnection()) return;
        mService.replaceBinding(this::closePiP);
        mService.setSessionActivity(buildSessionIntent());
        mService.setPlaybackForeground(true);
        mService.setNavigationCallback(getNavigationCallback(), activePlaybackKey());
        mService.addPlayerCallback(mPlayerCallback);
        getSeekView().setProgressPlayer(player().getPlayer());
        // 引擎可能还没吐出总时长，展示层要能用本页记住的这一集时长先顶上。
        player().setDurationFallback(this::getDurationFallbackMs);
        player().setLutAllowed(isLutAllowed());
        syncKeepScreenOn();
        player().setDanmakuForeground(true);
        publishRenderTarget(getExoView().getVideoSurfaceView());
        applyExoOutputMode();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "service connected cost=%dms key=%s", System.currentTimeMillis() - start, getPlaybackKey());
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service connected %s", lifecycleState());
        // 放在收口内、onServiceConnected() 之前：此刻控制器、回调与 Surface 都已就位，
        // 订阅者拿到事件就能安全地读播放器状态。
        PluginHostEvents.playbackReady(this);
        onServiceConnected();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service disconnected name=%s %s", name, lifecycleState());
        releaseController();
        getSeekView().setProgressPlayer(null);
        if (player() != null) player().setDurationFallback(null);
        mService = null;
        preparedPlaybackKey = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mService != null) {
            mService.setPlaybackForeground(true);
            if (isOwner()) player().setDanmakuForeground(true);
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity resume %s", lifecycleState());
        playbackExiting = false;
        setRedirect(false);
        applyExoOutputMode();
        if (shouldReclaim()) {
            detachSurface();
            onReclaim();
        }
        syncKeepScreenOn();
        PluginHostEvents.playbackResume(this);
    }

    @Override
    protected void onPause() {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity pause %s", lifecycleState());
        super.onPause();
        if (isRedirect() && mController != null) mController.pause();
        PluginHostEvents.playbackPause(this);
    }

    @Override
    protected void onStop() {
        if (mService != null) {
            mService.setPlaybackForeground(false);
            if (isOwner()) player().setDanmakuForeground(false);
        }
        restoreExoOutputMode();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity stop backgroundOff=%s %s", PlayerSetting.isBackgroundOff(), lifecycleState());
        super.onStop();
        if (isOwner() && !isAudioOnly() && shouldPauseOnBackground() && mController != null) mController.pause();
    }

    @Override
    public void onTrimMemory(int level) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity trimMemory level=%d %s", level, lifecycleState());
        super.onTrimMemory(level);
    }

    @Override
    protected void onDestroy() {
        webHomeSnapshotHandler.removeCallbacksAndMessages(null);
        smbThumbHandler.removeCallbacksAndMessages(null);
        surfaceDiagnostics.unbind();
        detachAssSurface();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity destroy beforeRelease %s", lifecycleState());
        RealtimeSubtitleController.get().unbind(getExoView());
        restoreExoOutputMode();
        super.onDestroy();
        if (isChangingConfigurations()) {
            if (mService != null) mService.removePlayerCallback(mPlayerCallback);
            detach();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity destroy configuration change preserved service key=%s", getPlaybackKey());
            return;
        }
        releasePlaybackService();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "activity destroy afterRelease activity=%s key=%s", getClass().getSimpleName(), getPlaybackKey());
    }
}
