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
import android.text.TextUtils;
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

    /** 缩略图目标高度；手机屏幕小，按 5 格保底，格宽再均分，整排铺满不留大空白。 */
    private static final int TARGET_CELL_HEIGHT_DP = 64;
    private static final int CELL_GAP_DP = 5;
    private static final int MIN_CELL_COUNT = 5;
    private static final long HOLD_MS = 3000L;
    private static final long MIN_SPACING_MS = 30L * 1000L;
    private static final long MAX_SPACING_MS = 5L * 60L * 1000L;
    /** 游标停稳一会儿再取帧，避免拖动时把还没解码完的请求全作废。 */
    private static final long FRAME_DEBOUNCE_MS = 260L;
    private static final int FOCUS_NONE = -1;

    public interface Listener {
        void onCommit(long positionMs);
        void onDismiss();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable holdRunner = this::dismiss;
    private final Runnable frameRunner = this::loadFrames;
    private final LinearLayout container;
    private final TextView titleView;
    private final LinearLayout rowView;
    private final BarView barView;
    private final List<Cell> cells = new ArrayList<>();
    private final List<Bitmap> recentFrames = new ArrayList<>();
    private final int cellCount;
    private final int cellWidth;
    private final int cellHeight;

    private Listener listener;
    private String media;
    private Map<String, String> headers;
    private long duration;
    private long cursor;
    private int focus = FOCUS_NONE;
    private boolean active;
    private boolean dragging;

    public SeekPreviewStrip(Context context) {
        this(context, null);
    }

    public SeekPreviewStrip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setVisibility(GONE);
        setClipChildren(false);

        int usable = usableWidth();
        int gap = dp(CELL_GAP_DP);
        int targetWidth = Math.round(dp(TARGET_CELL_HEIGHT_DP) * 16f / 9f);
        cellCount = Math.max(MIN_CELL_COUNT, Math.round((usable + gap) / (float) (targetWidth + gap)));
        cellWidth = Math.max(dp(48), (usable - gap * (cellCount - 1)) / cellCount);
        cellHeight = Math.max(dp(27), Math.round(cellWidth * 9f / 16f));

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

        // 缩略图在上，进度条在下。
        rowView = new LinearLayout(context);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(rowView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < cellCount; i++) {
            Cell cell = new Cell(context);
            final int index = i;
            cell.root.setOnClickListener(view -> onCellClicked(index));
            cells.add(cell);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cellWidth, cellHeight);
            if (i > 0) params.leftMargin = gap;
            rowView.addView(cell.root, params);
        }

        barView = new BarView(context);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
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
        boolean sameMedia = TextUtils.equals(mediaUrl, media);
        this.duration = Math.max(0, durationMs);
        this.cursor = clamp(cursorMs);
        this.media = mediaUrl;
        this.headers = mediaHeaders;
        if (!sameMedia) recentFrames.clear();
        focus = FOCUS_NONE;
        active = true;
        handler.removeCallbacks(holdRunner);
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        bringToFront();
        render();
    }

    /** 按住期间的每次步进。 */
    public void update(long cursorMs) {
        if (!active) return;
        cursor = clamp(cursorMs);
        render();
    }

    /** 松手：保留 3 秒选择窗口。 */
    public void hold() {
        if (!active) return;
        active = true;
        scheduleHold();
    }

    public void dismiss() {
        handler.removeCallbacks(holdRunner);
        handler.removeCallbacks(frameRunner);
        active = false;
        focus = FOCUS_NONE;
        dragging = false;
        recentFrames.clear();
        if (getVisibility() == VISIBLE) setVisibility(GONE);
    }

    private void scheduleHold() {
        handler.removeCallbacks(holdRunner);
        if (!active) return;
        handler.postDelayed(holdRunner, HOLD_MS);
    }

    private void onCellClicked(int index) {
        if (!active) return;
        focus = index;
        render();
        commit(cells.get(index).position);
    }

    private void commit(long positionMs) {
        long target = clamp(positionMs);
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
        if (focus == FOCUS_NONE) {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (!actionDown) return true;
            focus = cellCount / 2;
            render();
            scheduleHold();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!actionDown) return true;
                focus = FOCUS_NONE;
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!actionDown) return true;
                focus = Math.max(0, focus - 1);
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!actionDown) return true;
                focus = Math.min(cellCount - 1, focus + 1);
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
                if (!insideBar(event)) return false;
                dragging = true;
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

    private boolean insideBar(MotionEvent event) {
        int[] location = new int[2];
        barView.getLocationOnScreen(location);
        float y = event.getRawY();
        return y >= location[1] - dp(10) && y <= location[1] + barView.getHeight() + dp(10);
    }

    private void updateFromTouch(MotionEvent event) {
        int[] location = new int[2];
        barView.getLocationOnScreen(location);
        float ratio = (event.getRawX() - location[0]) / Math.max(1f, barView.getWidth());
        cursor = clamp((long) (clampRatio(ratio) * duration));
        focus = FOCUS_NONE;
        render();
    }

    private void render() {
        titleView.setText(String.format(Locale.getDefault(), "%s / %s", format(cursor), format(duration)));
        barView.setProgress(duration <= 0 ? 0f : (float) cursor / duration);
        barView.setTicks(ticks());
        barView.invalidate();

        List<Long> positions = cellPositions();
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            cell.position = positions.get(i);
            cell.label.setText(format(cell.position));
            cell.setFocused(focus == i);
        }
        // 游标停稳再取帧：拖动过程中只更新时间文字和游标，免得请求全被后面的位置作废。
        handler.removeCallbacks(frameRunner);
        handler.postDelayed(frameRunner, FRAME_DEBOUNCE_MS);
    }

    private void loadFrames() {
        loadFrames(true);
    }

    /**
     * 读缩略图：命中 trickplay 磁盘缓存就直接贴；缺格时补当前这一窗（一次 ffmpeg 调用），
     * 补完再读一遍。仍然缺（源不支持或抽帧失败）就退回旧的 MMR 现场取帧，只补缺的那几格。
     */
    private void loadFrames(boolean allowPrepare) {
        if (!active) return;
        final android.content.Context context = getContext() == null ? null : getContext().getApplicationContext();
        if (context == null) return;
        final List<Long> positions = cellPositions();
        final int width = Math.max(1, cellWidth);
        final int height = Math.max(1, cellHeight);
        final String mediaUrl = media;
        final Map<String, String> mediaHeaders = headers;
        TrickplayStore.io().execute(() -> {
            final List<Bitmap> results = new ArrayList<>(positions.size());
            final List<Long> missing = new ArrayList<>();
            for (long position : positions) {
                Bitmap bitmap = TextUtils.isEmpty(mediaUrl) ? null
                        : TrickplayStore.read(context, mediaUrl, position, width, height);
                results.add(bitmap);
                if (bitmap == null) missing.add(position);
            }
            handler.post(() -> {
                if (!active) return;
                List<Long> current = cellPositions();
                for (int i = 0; i < cells.size() && i < results.size(); i++) {
                    if (current.get(i).longValue() != positions.get(i).longValue()) continue;
                    applyFallback(cells.get(i), results.get(i));
                }
            });
            if (missing.isEmpty() || TextUtils.isEmpty(mediaUrl)) return;
            long first = missing.get(0);
            long last = missing.get(missing.size() - 1);
            int count = (int) ((last - first) / TrickplayStore.STEP_MS) + 1;
            if (allowPrepare) {
                TrickplayStore.prepareWindow(context, mediaUrl, mediaHeaders, first, count,
                        () -> handler.post(() -> loadFrames(false)));
            } else {
                handler.post(() -> loadFallbackFrames(positions));
            }
        });
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

    private List<Long> cellPositions() {
        long spacing = spacing();
        if (duration > 0) {
            // 片子比一整排还短时把间隔收到刚好铺满，不出现重复时间点。
            long span = spacing * (cellCount - 1);
            if (span > duration) spacing = Math.max(1_000L, duration / Math.max(1, cellCount - 1));
        }
        // 整个窗口平移，而不是逐格 clamp：接近片头/片尾时不会出现一排 00:00。
        long start = cursor - (cellCount / 2) * spacing;
        if (duration > 0) {
            long span = spacing * (cellCount - 1);
            start = Math.max(0, Math.min(start, Math.max(0, duration - span)));
        } else {
            start = Math.max(0, start);
        }
        List<Long> positions = new ArrayList<>(cellCount);
        for (int i = 0; i < cellCount; i++) positions.add(start + i * spacing);
        return positions;
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
            cell.image.setImageBitmap(bitmap);
            cell.image.setAlpha(1f);
            remember(bitmap);
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

        void setFocused(boolean focused) {
            focusRing.setVisibility(focused ? View.VISIBLE : View.GONE);
        }
    }

    private final class BarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progress;
        private float[] ticks;

        BarView(Context context) {
            super(context);
        }

        void setProgress(float progress) {
            this.progress = progress;
        }

        void setTicks(float[] ticks) {
            this.ticks = ticks;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int height = getHeight();
            float trackHeight = dp(4);
            float top = height / 2f - trackHeight / 2f;
            paint.setColor(0xFF3A3A44);
            rect.set(dp(6), top, getWidth() - dp(6), top + trackHeight);
            canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, paint);

            paint.setColor(0xFFFF7A2F);
            rect.set(dp(6), top, dp(6) + (getWidth() - dp(12)) * clampRatio(progress), top + trackHeight);
            canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, paint);

            if (ticks != null) {
                paint.setColor(0x66FFFFFF);
                for (float tick : ticks) {
                    float x = dp(6) + (getWidth() - dp(12)) * clampRatio(tick);
                    canvas.drawRect(x - dp(1), top - dp(2), x + dp(1), top + trackHeight + dp(2), paint);
                }
            }

            float x = dp(6) + (getWidth() - dp(12)) * clampRatio(progress);
            paint.setColor(0xFFFF7A2F);
            canvas.drawCircle(x, height / 2f, dp(7), paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, height / 2f, dp(3), paint);
        }
    }
}
