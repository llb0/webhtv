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

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 长按快进/快退时的预览层：顶部一条进度条（游标就是焦点），下面一排缩略图。
 *
 * <p>放手后不立刻收起，而是停留 {@link #HOLD_MS}，期间任何一次方向键移动都重新计时；
 * 这期间按上键把焦点移进缩略图排，左右选图，OK 直接跳到该图时间点。</p>
 */
public class SeekPreviewStrip extends FrameLayout {

    private static final String TAG = "SeekPreviewStrip";
    private static final int CELL_COUNT = 7;
    private static final long HOLD_MS = 3000L;
    private static final long STEP_MS = Constant.INTERVAL_SEEK;
    private static final int FOCUS_NONE = -1;

    public interface Listener {
        void onCommit(long positionMs);
        void onDismiss();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable holdRunner = this::dismiss;
    private final LinearLayout container;
    private final TextView titleView;
    private final BarView barView;
    private final LinearLayout rowView;
    private final List<Cell> cells = new ArrayList<>();
    private final List<Bitmap> recentFrames = new ArrayList<>();

    private Listener listener;
    private String media;
    private Map<String, String> headers;
    private long duration;
    private long cursor;
    private int focus = FOCUS_NONE;
    private boolean active;

    public SeekPreviewStrip(Context context) {
        this(context, null);
    }

    public SeekPreviewStrip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setVisibility(GONE);
        setClipChildren(false);

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6101014);
        background.setCornerRadius(dp(14));
        container.setBackground(background);

        titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15f);
        container.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        barView = new BarView(context);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        barParams.topMargin = dp(6);
        container.addView(barView, barParams);

        rowView = new LinearLayout(context);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        container.addView(rowView, rowParams);

        int cellWidth = cellWidth();
        int cellHeight = Math.max(dp(48), cellWidth * 9 / 16);
        for (int i = 0; i < CELL_COUNT; i++) {
            Cell cell = new Cell(context, cellWidth, cellHeight);
            final int index = i;
            cell.root.setOnClickListener(view -> onCellClicked(index));
            cells.add(cell);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cellWidth, cellHeight);
            if (i > 0) params.leftMargin = dp(6);
            rowView.addView(cell.root, params);
        }

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        containerParams.gravity = Gravity.BOTTOM;
        containerParams.leftMargin = dp(24);
        containerParams.rightMargin = dp(24);
        containerParams.bottomMargin = dp(28);
        addView(container, containerParams);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isActive() {
        return active;
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
        active = false;
        focus = FOCUS_NONE;
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
     * 遥控器按键。返回 true 表示这次事件已经被预览层吃掉，播放页不应再处理。
     */
    public boolean handleKey(int keyCode, boolean actionDown) {
        if (!active) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!actionDown) return true;
                if (focus == FOCUS_NONE) focus = CELL_COUNT / 2;
                else focus = FOCUS_NONE;
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!actionDown) return true;
                if (focus != FOCUS_NONE) focus = Math.max(0, focus - 1);
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!actionDown) return true;
                if (focus != FOCUS_NONE) focus = Math.min(CELL_COUNT - 1, focus + 1);
                render();
                scheduleHold();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!actionDown) return true;
                commit(focus == FOCUS_NONE ? cursor : cells.get(focus).position);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!actionDown) return true;
                dismiss();
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

    private boolean dragging;

    private boolean insideBar(MotionEvent event) {
        int[] location = new int[2];
        barView.getLocationOnScreen(location);
        float y = event.getRawY();
        return y >= location[1] - dp(8) && y <= location[1] + barView.getHeight() + dp(8);
    }

    private void updateFromTouch(MotionEvent event) {
        int[] location = new int[2];
        barView.getLocationOnScreen(location);
        float ratio = (event.getRawX() - location[0]) / Math.max(1f, barView.getWidth());
        cursor = clamp((long) (clampRatio(ratio) * duration));
        focus = FOCUS_NONE;
        render();
    }

    private float clampRatio(float ratio) {
        return Math.max(0f, Math.min(1f, ratio));
    }

    private void render() {
        titleView.setText(String.format(Locale.getDefault(), "%s / %s", format(cursor), format(duration)));
        barView.setProgress(duration <= 0 ? 0f : (float) cursor / duration);
        barView.setTicks(ticks());
        barView.invalidate();

        List<Long> positions = cellPositions();
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            long position = positions.get(i);
            cell.position = position;
            cell.label.setText(format(position));
            cell.setFocused(focus == i);
            requestFrame(cell, position);
        }
    }

    private List<Long> cellPositions() {
        List<Long> positions = new ArrayList<>(CELL_COUNT);
        long half = CELL_COUNT / 2;
        for (int i = 0; i < CELL_COUNT; i++) {
            positions.add(clamp(cursor + (i - half) * STEP_MS));
        }
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
        int width = cell.root.getWidth() > 0 ? cell.root.getWidth() : Math.max(1, cell.root.getLayoutParams().width);
        int height = cell.root.getHeight() > 0 ? cell.root.getHeight() : Math.max(1, cell.root.getLayoutParams().height);
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

    private int cellWidth() {
        int width = ResUtil.getScreenWidth();
        return Math.max(dp(64), (width - dp(24 + 24 + 28 + 36 + 6 * 6)) / CELL_COUNT);
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

        Cell(Context context, int width, int height) {
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
