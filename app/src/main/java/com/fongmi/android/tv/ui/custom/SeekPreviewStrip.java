package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import com.fongmi.android.tv.player.TrickplayLog;
import com.fongmi.android.tv.player.TrickplayNative;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.TrickplayStore;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 长按快进/快退时的预览层：一排缩略图在上、进度条在下，游标就是焦点。
 *
 * <p>放手后不立刻收起，而是停留 {@link #HOLD_MS}，期间任何一次方向键移动都重新计时；
 * 这期间按上键把焦点移进缩略图排，左右选图，OK 直接跳到该图时间点。</p>
 *
 * <p>缩略图间隔不跟 10 秒步进走，而是按片长摊开：短片段 30 秒一格，长片按总时长 / 30 放大，
 * 最多 5 分钟一格 —— 一排图覆盖几分钟到十几分钟的区间，不会挤在 1 分钟里反复跳。</p>
 */
public class SeekPreviewStrip extends FrameLayout {

    /** 固定 8 格，格宽按可用宽度等分（16:9 比例），整排铺满，不自动算格数。 */
    private static final int CELL_COUNT = 8;
    /** 快退/快进两种排法，当前帧固定在最边上那一格。 */
    private static final int DIR_FORWARD = 1;
    private static final int DIR_REWIND = -1;
    private static final int CELL_GAP_DP = 5;
    private static final long HOLD_MS = 5000L;
    private static final long MIN_SPACING_MS = 30L * 1000L;
    private static final long MAX_SPACING_MS = 5L * 60L * 1000L;
    /** 靠近片头/片尾时每格最少也别挤成 0 秒。 */
    private static final long MIN_SLOT_MS = 3L * 1000L;
    /** 格位与抽帧缓存共用的网格。 */
    private static final long SNAP_MS = 10_000L;
    /** 游标停稳一会儿再取帧，避免拖动时把还没解码完的请求全作废。 */
    private static final long FRAME_DEBOUNCE_MS = 260L;
    /** 一排图最多等这么久；等不到全齐也不再拿"图还没齐"当理由挂着。 */
    private static final long SETTLE_TIMEOUT_MS = 30000L;
    private static final long SETTLE_POLL_MS = 400L;
    private static final int FOCUS_NONE = -1;

    public interface Listener {
        void onCommit(long positionMs);
        void onDismiss();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable holdRunner = this::dismiss;
    private final Runnable frameRunner = this::loadFrames;
    private final Runnable settleRunner = this::onSettleTimeout;
    private final Runnable settlePoll = this::pollFramesSettled;
    private final LinearLayout container;
    private final TextView titleView;
    private final LinearLayout rowView;
    /** 只做进度指示：不获取焦点、半透明，焦点只在缩略图排里移动。 */
    private final BarView barView;
    private final List<Cell> cells = new ArrayList<>();
    private final List<Bitmap> recentFrames = new ArrayList<>();
    private final int cellCount;
    private int cellWidth;
    private int cellHeight;
    private float aspect = 16f / 9f;

    private Listener listener;
    private String media;
    private Map<String, String> headers;
    private long duration;
    private long cursor;
    private int focus = FOCUS_NONE;
    /** +1 快进（8 格都在当前之后，当前在最左）；-1 快退（8 格都在当前之前，当前在最右）。 */
    private int direction = DIR_FORWARD;
    /** 焦点默认停在缩略图下面那条游标上（高亮），上键进排、下键回游标。 */
    private boolean barFocused;
    private boolean active;
    private boolean dragging;
    private boolean reportedUnavailable;
    /** 当前这一排还有格子没出图；出图完成前不收计时器，免得图还没刷出来就整条消失。 */
    private boolean framesPending;
    private long framesStartedAt;
    /** 已经松手/停稳、在等用户点格子：出图完成后再开始倒计时。 */
    private boolean holdArmed;
    /** 上一次真正发起取帧请求的时刻：补帧被跳转打断后，靠它隔一会儿自动重来。 */
    private long lastLoadAt;
    /** 8 格位置在显示这一刻冻结，之后步进只挪游标。 */
    private List<Long> frozenPositions;

    /** 本轮已经做过一次「缺格交接」：做过就不再重复申请，避免一遍遍重抽。 */
    private boolean handoffUsed;

    public SeekPreviewStrip(Context context) {
        this(context, null);
    }

    public SeekPreviewStrip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setVisibility(GONE);
        setClipChildren(false);

        int usable = usableWidth();
        int gap = dp(CELL_GAP_DP);
        cellCount = CELL_COUNT;
        // 严格等分：多出来的像素按格均匀分掉，避免右侧留白或某格被拉伸。
        cellWidth = Math.max(1, (usable - gap * (cellCount - 1)) / cellCount);
        cellHeight = Math.max(1, Math.round(cellWidth * 9f / 16f));

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(12), dp(8), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2141418);
        background.setCornerRadius(dp(12));
        container.setBackground(background);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14f);
        titleView.setPadding(dp(2), 0, dp(2), dp(4));
        container.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rowView = new LinearLayout(context);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(rowView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < cellCount; i++) {
            Cell cell = new Cell(context);
            final int index = i;
            cell.root.setOnClickListener(view -> onCellClicked(index));
            cells.add(cell);
            // 宽度给权重，由父容器实际宽度均分，手机横屏不会再按竖屏宽度算窄。
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, cellHeight, 1f);
            if (i > 0) params.leftMargin = gap;
            rowView.addView(cell.root, params);
        }

        barView = new BarView(context);
        barView.setAlpha(0.45f);
        barView.setFocusable(false);
        barView.setClickable(false);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
        barParams.topMargin = dp(8);
        container.addView(barView, barParams);

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        containerParams.gravity = Gravity.BOTTOM;
        containerParams.leftMargin = dp(20);
        containerParams.rightMargin = dp(20);
        containerParams.bottomMargin = dp(26);
        addView(container, containerParams);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutCells(w);
    }

    /** 按真实宽度均分 8 格，格高跟随片源比例。 */
    private void layoutCells(int width) {
        int gap = dp(CELL_GAP_DP);
        int containerPadding = dp(12) * 2;
        int usable = width - containerPadding - dp(20) * 2;
        if (usable <= 0) usable = width - containerPadding;
        if (usable <= 0) return;
        cellWidth = Math.max(1, (usable - gap * (cellCount - 1)) / cellCount);
        cellHeight = Math.max(1, Math.round(cellWidth / aspect));
        for (int i = 0; i < cells.size(); i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, cellHeight, 1f);
            if (i > 0) params.leftMargin = gap;
            cells.get(i).root.setLayoutParams(params);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
    }

    /** 焦点是否已经进到缩略图排；只有这时才把左右键当成选图。 */
    public boolean isRowFocused() {
        return focus != FOCUS_NONE;
    }

    /** 长按开始：给定时长、起始游标、以及取帧用的媒体源。 */
    public void begin(long durationMs, long cursorMs, String mediaUrl, Map<String, String> mediaHeaders) {
        begin(durationMs, cursorMs, true, mediaUrl, mediaHeaders);
    }

    /**
     * 长按开始。{@code forward} 是这一刻的拖动/按键方向。
     *
     * <p>这 8 格的时间点在显示的这一刻就定下来，之后 {@link #update(long)} 只挪游标、不再重排格子，
     * 否则跟着原生快进一步一重排，就会一直在抽新位置的帧，越刷越卡。</p>
     */
    public void begin(long durationMs, long cursorMs, boolean forward, String mediaUrl, Map<String, String> mediaHeaders) {
        boolean sameMedia = TextUtils.equals(mediaUrl, media);
        this.duration = Math.max(0, durationMs);
        this.cursor = clamp(cursorMs);
        this.media = mediaUrl;
        this.headers = mediaHeaders;
        if (!sameMedia) recentFrames.clear();
        if (getContext() != null) {
            applyAspect(TrickplayStore.aspect(getContext().getApplicationContext(), mediaUrl));
        }
        direction = forward ? DIR_FORWARD : DIR_REWIND;
        frozenPositions = layoutPositions();
        // 焦点交给下面那条游标：左右键留给原生快进（10 秒一步），预览条只做显示。
        barFocused = true;
        focus = FOCUS_NONE;
        active = true;
        holdArmed = false;
        framesPending = true;
        handler.removeCallbacks(holdRunner);
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        bringToFront();
        render();
    }

    /** 按住期间的每次步进：只挪游标和时间，格子布局与已取到的帧保持不动。 */
    public void update(long cursorMs) {
        if (!active) return;
        cursor = clamp(cursorMs);
        titleView.setText(String.format(Locale.getDefault(), "%s / %s", format(cursor), format(duration)));
        barView.setProgress(duration <= 0 ? 0f : (float) cursor / duration);
        barView.invalidate();
    }

    /** 松手：跳转交给播放页原生那一步，预览条直接收起。 */
    public void hold() {
        if (!active) return;
        dismiss();
    }

    public void dismiss() {
        handler.removeCallbacks(holdRunner);
        handler.removeCallbacks(settlePoll);
        handler.removeCallbacks(settleRunner);
        handler.removeCallbacks(frameRunner);
        active = false;
        focus = FOCUS_NONE;
        dragging = false;
        holdArmed = false;
        framesPending = false;
        recentFrames.clear();
        frozenPositions = null;
        // 收起时把「已刷新」标记连同图一起清掉：标记的意义是「这一排此刻名下已有图」，
        // 留着标记又不留图，下次弹出来就会当成已刷新而不再取图，格子里就一直是空的。
        for (Cell cell : cells) cell.clearImage();
        if (getVisibility() == VISIBLE) setVisibility(GONE);
    }

    private void scheduleHold() {
        handler.removeCallbacks(holdRunner);
        if (!active) return;
        // 手指还按着、或者这一排图还没出齐，都不算「停留结束」，不启动收起倒计时。
        if (dragging || framesPending) return;
        handler.postDelayed(holdRunner, HOLD_MS);
    }

    /** 遥控器 OK：跳当前选中的那一格（没选就跳当前帧）。 */
    public boolean commitCurrent() {
        if (!active) return false;
        if (cells.isEmpty()) return false;
        if (barFocused || focus == FOCUS_NONE) {
            commit(cursor);
            return true;
        }
        commit(cells.get(Math.min(focus, cells.size() - 1)).position);
        return true;
    }

    private void onCellClicked(int index) {
        if (!active) return;
        focus = index;
        render();
        commit(cells.get(index).position);
    }

    private void commit(long positionMs) {
        long target = clamp(positionMs);
        trace("commit focus=%d pos=%d cursor=%d", focus, target, cursor);
        if (listener != null) listener.onCommit(target);
        dismiss();
    }

    /**
     * 遥控器按键：预览条只做显示，不接管左右键——左右键留给播放页原生的「按住快进」，
     * 按 10 秒一步走、松手由原生跳转。这里只吃掉返回键（收起预览条）。
     */
    public boolean handleKey(int keyCode, boolean actionDown) {
        if (!active) return false;
        if (keyCode == KeyEvent.KEYCODE_BACK && !actionDown) {
            dismiss();
            return true;
        }
        return false;
    }

    private void onCellClicked(int index) {
        if (!active) return;
        focus = index;
        render();
        commit(cells.get(index).position);
    }

    private void commit(long positionMs) {
        long target = clamp(positionMs);
        trace("commit focus=%d pos=%d cursor=%d", focus, target, cursor);
        if (listener != null) listener.onCommit(target);
        dismiss();
    }

    /**
     * 遥控器按键。只有焦点确实在缩略图排时，左右键才在这里吃掉；
     * 其余时候返回 false，让播放页的「按住快进」会话继续正常累计并在松手时提交。
     */
    public boolean handleKey(int keyCode, boolean actionDown) {
        if (!active) return false;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!actionDown) return true;
            dismiss();
            return true;
        }
        if (barFocused) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    // 上键进缩略图排，焦点落在当前帧那一格。
                    if (!actionDown) return true;
                    barFocused = false;
                    focus = anchorIndex();
                    render();
                    scheduleHold();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!actionDown) return true;
                    direction = DIR_REWIND;
                    cursor = clamp(cursor - spacing());
                    render();
                    scheduleHold();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!actionDown) return true;
                    direction = DIR_FORWARD;
                    cursor = clamp(cursor + spacing());
                    render();
                    scheduleHold();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!actionDown) return true;
                    commit(cursor);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    return true;
                default:
                    return false;
            }
        }
        if (focus == FOCUS_NONE) {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (!actionDown) return true;
            focus = anchorIndex();
            render();
            scheduleHold();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                // 上键在排内：焦点回到当前帧那一格。
                if (!actionDown) return true;
                focus = anchorIndex();
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 下键回下面的游标，别让焦点跑回播放器控制栏。
                if (!actionDown) return true;
                barFocused = true;
                focus = FOCUS_NONE;
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 8 格内左右环形移动：第 1 格往左回到第 8 格，第 8 格往右回到第 1 格。
                if (!actionDown) return true;
                focus = focus == 0 ? cellCount - 1 : focus - 1;
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!actionDown) return true;
                focus = focus == cellCount - 1 ? 0 : focus + 1;
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!actionDown) return true;
                commit(cells.get(focus).position);
                return true;
            default:
                return false;
        }
    }

    /**
     * 手机端：在预览层上横拖移动游标，松手进入停留窗口；点缩略图直接跳。
     */
    public boolean handleTouch(MotionEvent event) {
        if (!active) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!insideStrip(event)) return false;
                dragging = true;
                holdArmed = false;
                updateFromTouch(event);
                handler.removeCallbacks(holdRunner);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                updateFromTouch(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                hold();
                return true;
            default:
                return false;
        }
    }

    private boolean insideStrip(MotionEvent event) {
        int[] location = new int[2];
        container.getLocationOnScreen(location);
        float y = event.getRawY();
        return y >= location[1] - dp(12) && y <= location[1] + container.getHeight() + dp(12);
    }

    private void updateFromTouch(MotionEvent event) {
        int[] location = new int[2];
        container.getLocationOnScreen(location);
        float ratio = (event.getRawX() - location[0]) / Math.max(1f, container.getWidth());
        long next = clamp((long) (clampRatio(ratio) * duration));
        // 往左拖是快退、往右拖是快进：整排 8 格跟着方向翻成同一向。
        if (next < cursor) direction = DIR_REWIND;
        else if (next > cursor) direction = DIR_FORWARD;
        cursor = next;
        focus = FOCUS_NONE;
        render();
    }

    private void render() {
        handoffUsed = false;
        titleView.setText(String.format(Locale.getDefault(), "%s / %s", format(cursor), format(duration)));
        List<Long> positions = cellPositions();
        barView.setProgress(duration <= 0 ? 0f : (float) cursor / duration);
        barView.setFocused(barFocused);
        barView.setTicks(ticks());
        barView.invalidate();

        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            if (cell.position != positions.get(i)) cell.clearImage();
            cell.position = positions.get(i);
            cell.label.setText(format(cell.position));
            cell.setFocused(focus == i);
            cell.setHighlight(i == anchorIndex());
        }
        // 游标停稳再取帧：拖动过程中只更新时间文字和游标，免得请求全被后面的位置作废。
        framesPending = true;
        framesStartedAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(settleRunner);
        handler.postDelayed(settleRunner, SETTLE_TIMEOUT_MS);
        handler.removeCallbacks(frameRunner);
        handler.postDelayed(frameRunner, FRAME_DEBOUNCE_MS);
    }

    private void loadFrames() {
        loadFrames(true);
    }

    /**
     * 读缩略图：命中 trickplay 磁盘缓存就直接贴；缺格时按“这 8 个位置”逐格补，
     * 补到一格就刷新一格，不用等整排抽完。仍然缺（源不支持或抽帧失败）才退回 MMR 兜底。
     */
    private void loadFrames(boolean allowPrepare) {
        if (!active) return;
        lastLoadAt = SystemClock.elapsedRealtime();
        final android.content.Context context = getContext() == null ? null : getContext().getApplicationContext();
        if (context == null) return;
        final List<Long> positions = cellPositions();
        final int width = Math.max(1, cellWidth);
        final int height = Math.max(1, cellHeight);
        final String mediaUrl = media;
        final Map<String, String> mediaHeaders = headers;
        // 已经刷出图的那几格直接跳过：刷新过的格子再抽一遍就是重复，既不省时间还跟播放抢连接。
        List<Long> missing = new ArrayList<>();
        for (int i = 0; i < cells.size() && i < positions.size(); i++) {
            Cell cell = cells.get(i);
            if (cell.filled && cell.position == positions.get(i)) continue;
            missing.add(positions.get(i));
        }
        if (missing.isEmpty()) {
            handler.post(this::pollFramesSettled);
            return;
        }
        if (TextUtils.isEmpty(media)) {
            handler.post(() -> {
                loadFallbackFrames(positions);
                pollFramesSettled();
            });
            return;
        }
        if (!allowPrepare) {
            handler.post(() -> {
                loadFallbackFrames(positions);
                pollFramesSettled();
            });
            return;
        }
        // 抽帧顺序跟着当前方向走：快进从当前帧往右，快退从当前帧往左。
        final List<Long> priority = refreshOrder(positions);
        final List<Long> missingList = missing;
        missingList.sort((a, b) -> Integer.compare(priority.indexOf(a), priority.indexOf(b)));
        final List<Long> all = positions;
        TrickplayStore.ensureFrames(context, media, headers, missingList,
                () -> TrickplayStore.io().execute(() -> {
                    // 每落一格就只读这一格，贴上去；已贴过的格子不动。
                    for (long position : missingList) {
                        Bitmap bitmap = TrickplayStore.read(context, media, position, width, height);
                        if (bitmap == null) continue;
                        final Bitmap found = bitmap;
                        final long at = position;
                        handler.post(() -> applyAt(all, at, found));
                    }
                }),
                () -> handler.post(() -> onWindowDone(context, all)));
    }

    /**
     * 上一轮补帧结束（正常抽完，或被一次跳转 cancelAll 打断）：只把**仍然缺图**的格子交接给下一轮，
     * 交接一次为止；已经刷过的格子绝不重抽。交接完还缺就铺兜底图收口。
     */
    private void onWindowDone(android.content.Context context, List<Long> positions) {
        if (!active) return;
        String error = TrickplayNative.lastError();
        if (!TextUtils.isEmpty(error)) reportOnce(context, error);
        List<Long> still = new ArrayList<>();
        for (int i = 0; i < cells.size() && i < positions.size(); i++) {
            if (!cells.get(i).filled || cells.get(i).position != positions.get(i)) still.add(positions.get(i));
        }
        if (!still.isEmpty() && !handoffUsed) {
            handoffUsed = true;
            trace("preview handoff count=%d", still.size());
            List<Long> ordered = new ArrayList<>(still);
            List<Long> priority = refreshOrder(positions);
            ordered.sort((a, b) -> Integer.compare(priority.indexOf(a), priority.indexOf(b)));
            TrickplayStore.ensureFrames(context, media, headers, ordered, null,
                    () -> handler.post(() -> onWindowDone(context, positions)));
            return;
        }
        loadFallbackFrames(positions);
        pollFramesSettled();
    }

    /**
     * 这一排的图出齐了（或等到超时）：此时才开始停留倒计时。
     * 否则「队列忙、本轮结束」这类回调都会被当成齐了，条在图只出一两张时就消失。
     */
    private void onFramesSettled() {
        if (!active) return;
        handler.removeCallbacks(settlePoll);
        handler.removeCallbacks(settleRunner);
        framesPending = false;
        if (holdArmed && !dragging) scheduleHold();
    }

    /** 等满超时：还空着的格子用兜底图铺上，别让用户对着空白排等到整条消失。 */
    private void onSettleTimeout() {
        if (!active) return;
        if (!allCellsFilled()) {
            loadFallbackFrames(cellPositions());
        }
        onFramesSettled();
    }

    /** 还没齐就继续等，最多等到 {@link #SETTLE_TIMEOUT_MS}。 */
    private void pollFramesSettled() {
        if (!active) return;
        if (allCellsFilled() || SystemClock.elapsedRealtime() - framesStartedAt >= SETTLE_TIMEOUT_MS) {
            onFramesSettled();
            return;
        }
        handler.postDelayed(settlePoll, SETTLE_POLL_MS);
    }

    private boolean allCellsFilled() {
        for (Cell cell : cells) {
            if (cell.image.getDrawable() == null) return false;
        }
        return true;
    }

    /** 每贴一格就看一眼是否已经全齐，齐了立刻开始计时，不必等到轮询。 */
    private void checkFramesSettled() {
        if (!active || !framesPending) return;
        if (allCellsFilled()) onFramesSettled();
    }

    private void trace(String format, Object... args) {
        try {
            TrickplayLog.log(TrickplayLog.keyOf(format), format, args);
        } catch (Throwable ignored) {
        }
    }

    /** 只在“位置还没变”的格子上贴图，避免拖动后贴到错位。 */
    private void applyAt(List<Long> positions, long position, Bitmap bitmap) {
        if (!active || bitmap == null) return;
        List<Long> current = cellPositions();
        for (int i = 0; i < cells.size() && i < positions.size() && i < current.size(); i++) {
            if (positions.get(i).longValue() != position) continue;
            if (current.get(i).longValue() != position) continue;
            Cell cell = cells.get(i);
            applyFallback(cell, bitmap);
            cell.filled = true;
        }
    }

    /** 抽帧失败时提示一次原因，省得用户只能靠猜。 */
    private void reportOnce(android.content.Context context, String reason) {
        if (reportedUnavailable) return;
        reportedUnavailable = true;
        if (TextUtils.isEmpty(reason)) reason = "内置 FFmpeg 未加载";
        try {
            android.widget.Toast.makeText(context,
                    "预览抽帧不可用：" + reason + "（已退回旧取帧）",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /** ffmpeg 这条路走不通时，沿用 MMR 单帧抽取，只处理还空着的格子。 */
    private void loadFallbackFrames(List<Long> positions) {
        if (!active) return;
        List<Long> current = cellPositions();
        for (int i = 0; i < cells.size() && i < positions.size(); i++) {
            if (current.get(i).longValue() != positions.get(i).longValue()) continue;
            Cell cell = cells.get(i);
            if (cell.image.getDrawable() != null) continue;
            requestFrame(cell, positions.get(i));
        }
    }

    /**
     * 缩略图间隔：按片长摊开，30 秒到 5 分钟之间。短片段 30 秒一格，
     * 两小时的片子约 4 分钟一格，一排图覆盖约半小时，挑段更有意义。
     */
    private long spacing() {
        if (duration <= 0) return MIN_SPACING_MS;
        long value = Math.max(MIN_SPACING_MS, duration / 30);
        return Math.min(value, MAX_SPACING_MS);
    }

    /**
     * 8 格全部同向：快进时 0 号格就是当前帧、往后都是快进；快退时 7 号格是当前帧、往前都是快退。
     *
     * <p>以前是"当前进度居中、左右各摊一半"，方向感很差；现在按用户滑动/按键的方向整排铺开，
     * 当前帧固定在最边上，位置序列单调，靠近片头/片尾时间距自动收紧。</p>
     */
    private List<Long> cellPositions() {
        if (frozenPositions != null) return new ArrayList<>(frozenPositions);
        return layoutPositions();
    }

    /** 按显示这一刻的方向与游标算出 8 格位置；只在 begin/dismiss 前后调用，不跟着每次步进重算。 */
    private List<Long> layoutPositions() {
        long step = spacing();
        int slots = cellCount - 1;
        if (direction == DIR_FORWARD) {
            if (duration > 0) {
                long remain = Math.max(0, duration - cursor);
                step = Math.min(step, Math.max(MIN_SLOT_MS, remain / slots));
            }
        } else {
            if (cursor > 0) step = Math.min(step, Math.max(MIN_SLOT_MS, cursor / slots));
        }
        List<Long> positions = new ArrayList<>(cellCount);
        for (int i = 0; i < cellCount; i++) {
            long value = direction == DIR_FORWARD
                    ? cursor + i * step
                    : cursor - (slots - i) * step;
            if (value < 0) value = 0;
            if (duration > 0 && value > duration) value = duration;
            // 对齐到 10 秒网格：抽帧缓存按这个网格落盘，格子时间与画面、跳转目标三者一致。
            value = (value / SNAP_MS) * SNAP_MS;
            if (duration > 0 && value > duration) value = duration;
            positions.add(value);
        }
        return positions;
    }

    /** 当前帧所在的那一格：快进是 0，快退是最后一格。 */
    private int anchorIndex() {
        return direction == DIR_FORWARD ? 0 : cellCount - 1;
    }

    /** 抽帧顺序：当前帧那一格起，顺着方向从近到远。 */
    private List<Long> refreshOrder(List<Long> positions) {
        List<Long> order = new ArrayList<>(cellCount);
        if (direction == DIR_FORWARD) {
            for (int i = 0; i < positions.size(); i++) order.add(positions.get(i));
        } else {
            for (int i = positions.size() - 1; i >= 0; i--) order.add(positions.get(i));
        }
        return order;
    }


    private float[] ticks() {
        List<Long> positions = cellPositions();
        float[] ticks = new float[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            ticks[i] = duration <= 0 ? 0f : (float) positions.get(i) / duration;
        }
        return ticks;
    }

    private void requestFrame(Cell cell, long position) {
        if (media == null || media.isEmpty()) {
            applyFallback(cell, null);
            return;
        }
        final String expected = SeekPreviewFrameCache.keyFor(media, position);
        cell.key = expected;
        int width = Math.max(1, cell.root.getWidth() > 0 ? cell.root.getWidth() : cellWidth);
        int height = Math.max(1, cell.root.getHeight() > 0 ? cell.root.getHeight() : cellHeight);
        SeekPreviewFrameCache.request(getContext(), media, headers, position, width, height, (key, bitmap) -> {
            if (!active) return;
            if (!TextUtils.equals(expected, cell.key)) return;
            applyFallback(cell, bitmap);
        });
    }

    /**
     * 取不到帧（只有 IJK 能解的老容器最常见）时用本次已取到的截图铺底：
     * 先拿同一媒体的最近一帧，实在没有就只留时间文字。
     */
    private void applyFallback(Cell cell, Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            applyAspect(bitmap.getWidth() / (float) Math.max(1, bitmap.getHeight()));
            cell.image.setImageBitmap(bitmap);
            cell.image.setAlpha(1f);
            remember(bitmap);
            checkFramesSettled();
            return;
        }
        Bitmap fallback = nearestFrame();
        if (fallback != null) {
            cell.image.setImageBitmap(fallback);
            cell.image.setAlpha(0.55f);
        } else {
            cell.image.setImageDrawable(null);
            cell.image.setAlpha(1f);
        }
    }

    /**
     * 按片源比例排版：格宽仍按可用宽度等分（8 格铺满），格高跟随视频宽高比，
     * 手机竖拍、4:3 老片、2.35:1 电影都不会被硬拉成 16:9。
     */
    private void applyAspect(float value) {
        if (value <= 0.2f || value > 4f) value = 16f / 9f;
        if (Math.abs(value - aspect) < 0.01f) return;
        aspect = value;
        layoutCells(getWidth());
        requestLayout();
    }

    private void remember(Bitmap bitmap) {
        if (bitmap == null) return;
        if (recentFrames.size() >= 4) recentFrames.remove(recentFrames.size() - 1);
        recentFrames.add(0, bitmap);
    }

    private Bitmap nearestFrame() {
        for (Bitmap bitmap : recentFrames) if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        return null;
    }

    private float clampRatio(float ratio) {
        return Math.max(0f, Math.min(1f, ratio));
    }

    private long clamp(long position) {
        long max = duration > 0 ? duration : Long.MAX_VALUE;
        return Math.max(0, Math.min(position, max));
    }

    private String format(long millis) {
        long total = Math.max(0, millis) / 1000;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    /** 可用宽度：屏幕宽减去浮层左右外边距与容器内边距。 */
    private int usableWidth() {
        return Math.max(dp(120), ResUtil.getScreenWidth() - dp(20 + 20 + 12 + 12));
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }

    private static final class Cell {
        final FrameLayout root;
        final ImageView image;
        final TextView label;
        final View focusRing;
        long position;
        String key;
        boolean highlight;
        /** 这一格已经贴上「本位置」的图了：已刷新过的格子不再重新请求、也不重新贴。 */
        boolean filled;

        Cell(Context context) {
            root = new FrameLayout(context);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xFF1C1C22);
            background.setCornerRadius(ResUtil.dp2px(8));
            root.setBackground(background);
            root.setFocusable(false);
            root.setClickable(true);
            root.setPadding(ResUtil.dp2px(1), ResUtil.dp2px(1), ResUtil.dp2px(1), ResUtil.dp2px(1));

            image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(image);

            label = new TextView(context);
            label.setTextColor(Color.WHITE);
            label.setTextSize(11f);
            label.setGravity(Gravity.BOTTOM | Gravity.START);
            label.setPadding(ResUtil.dp2px(6), 0, ResUtil.dp2px(6), ResUtil.dp2px(3));
            label.setShadowLayer(4f, 0f, 1f, Color.BLACK);
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.gravity = Gravity.BOTTOM;
            root.addView(label, labelParams);

            focusRing = new View(context);
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke(ResUtil.dp2px(2), 0xFFFF7A2F);
            ring.setCornerRadius(ResUtil.dp2px(8));
            focusRing.setBackground(ring);
            focusRing.setVisibility(View.GONE);
            root.addView(focusRing, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        /** 位置换了先把上一排的图擦掉：以前旧图留着，用户看到"有时间但图是上一条的"。 */
        void clearImage() {
            filled = false;
            image.setImageDrawable(null);
            image.setAlpha(1f);
        }

        void setFocused(boolean focused) {
            focusRing.setVisibility(focused || highlight ? View.VISIBLE : View.GONE);
            focusRing.setAlpha(focused ? 1f : 0.55f);
        }

        /** 当前进度那一格常亮一个淡描边，一眼就能认出「现在在这」。 */
        void setHighlight(boolean on) {
            highlight = on;
            label.setTextColor(on ? 0xFFFFC08A : Color.WHITE);
            label.setTextSize(on ? 12f : 11f);
        }
    }

    /** 缩略图下面那条细进度条：纯指示，不接受焦点。 */
    private final class BarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progress;
        private float[] ticks;
        private boolean focused;

        BarView(Context context) {
            super(context);
        }

        void setProgress(float progress) {
            this.progress = progress;
        }

        void setTicks(float[] ticks) {
            this.ticks = ticks;
        }

        void setFocused(boolean focused) {
            this.focused = focused;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int height = getHeight();
            float trackHeight = dp(3);
            float top = height / 2f - trackHeight / 2f;
            paint.setColor(0xFF3A3A44);
            rect.set(dp(6), top, getWidth() - dp(6), top + trackHeight);
            canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, paint);

            paint.setColor(0xFFFF7A2F);
            rect.set(dp(6), top, dp(6) + (getWidth() - dp(12)) * clampRatio(progress), top + trackHeight);
            canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, paint);

            if (ticks != null) {
                paint.setColor(0x44FFFFFF);
                for (float tick : ticks) {
                    float x = dp(6) + (getWidth() - dp(12)) * clampRatio(tick);
                    canvas.drawRect(x - dp(1), top - dp(2), x + dp(1), top + trackHeight + dp(2), paint);
                }
            }

            float x = dp(6) + (getWidth() - dp(12)) * clampRatio(progress);
            if (focused) {
                // 焦点在游标上时给一圈亮环，免得看不出焦点在哪。
                paint.setColor(0xFFFFD9A0);
                canvas.drawCircle(x, height / 2f, dp(8), paint);
            }
            paint.setColor(0xFFFF7A2F);
            canvas.drawCircle(x, height / 2f, dp(5), paint);
        }
    }
}
