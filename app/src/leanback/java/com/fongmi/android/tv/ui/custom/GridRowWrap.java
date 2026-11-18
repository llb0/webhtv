package com.fongmi.android.tv.ui.custom;

import android.view.KeyEvent;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 电视端网格列表（历史、收藏）的左右环跳：按到行首再按左回到同一行末尾，
 * 按到行尾再按右回到同一行开头，避免焦点顶在边界不动、也没有跨行跳的不自然感。
 *
 * <p>只处理"同一行内"环绕：上下键仍交给 GridLayoutManager 自己走，跨行不在这里做。</p>
 */
public final class GridRowWrap {

    private GridRowWrap() {
    }

    /**
     * @return true 表示这次按键已经被这里消费（调用了焦点转移），调用方应直接 return true。
     */
    public static boolean handle(RecyclerView recycler, int columns, KeyEvent event) {
        if (recycler == null || event == null || columns <= 1) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (!recycler.hasFocus()) return false;
        if (!(recycler.getLayoutManager() instanceof GridLayoutManager)) return false;
        RecyclerView.Adapter<?> adapter = recycler.getAdapter();
        if (adapter == null) return false;
        int count = adapter.getItemCount();
        if (count <= 1) return false;
        int position = focusedPosition(recycler);
        if (position == RecyclerView.NO_POSITION) return false;
        return handle(recycler, columns, keyCode, position, count);
    }

    /**
     * 已知道当前焦点位置时的入口：Activity 自己在 dispatchKeyEvent 里取过位置就不必重复取。
     */
    public static boolean handle(RecyclerView recycler, int columns, int position, int count, KeyEvent event) {
        if (recycler == null || event == null || columns <= 1) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (count <= 1 || position == RecyclerView.NO_POSITION || position >= count) return false;
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        return handle(recycler, columns, keyCode, position, count);
    }

    private static boolean handle(RecyclerView recycler, int columns, int keyCode, int position, int count) {
        int column = position % columns;
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (column != columns - 1) return false;
            target = position - (columns - 1);
        } else {
            if (column != 0) return false;
            target = Math.min(count - 1, position + (columns - 1));
        }
        if (target == position || target < 0) return false;
        return focus(recycler, target);
    }

    /** 当前焦点条目的 adapter 位置；焦点不在列表中时返回 NO_POSITION。 */
    public static int focusedPosition(RecyclerView recycler) {
        if (recycler == null || !recycler.hasFocus()) return RecyclerView.NO_POSITION;
        android.view.View focused = recycler.getFocusedChild();
        return focused == null ? RecyclerView.NO_POSITION : recycler.getChildAdapterPosition(focused);
    }

    private static boolean focus(RecyclerView recycler, int position) {
        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        android.view.View target = manager == null ? null : manager.findViewByPosition(position);
        if (target != null) return target.requestFocus();
        // 目标还没布局出来（例如一整行刚好滚出屏幕）：先滚过去，下一帧再取焦点。
        recycler.scrollToPosition(position);
        recycler.post(() -> {
            RecyclerView.LayoutManager current = recycler.getLayoutManager();
            android.view.View view = current == null ? null : current.findViewByPosition(position);
            if (view != null) view.requestFocus();
        });
        return true;
    }
}
