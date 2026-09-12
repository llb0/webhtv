package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.view.KeyEvent;

import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.RowPresenter;

import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;

public class CustomRowPresenter extends ListRowPresenter {

    private final int spacing;
    private final int strategy;

    public CustomRowPresenter(int spacing) {
        this(spacing, FocusHighlight.ZOOM_FACTOR_SMALL);
    }

    @SuppressLint("RestrictedApi")
    public CustomRowPresenter(int spacing, int focusZoomFactor) {
        this(spacing, focusZoomFactor, HorizontalGridView.FOCUS_SCROLL_ITEM);
    }

    public CustomRowPresenter(int spacing, int focusZoomFactor, int strategy) {
        super(focusZoomFactor);
        this.spacing = spacing;
        this.strategy = strategy;
        setShadowEnabled(false);
        setSelectEffectEnabled(false);
        setKeepChildForeground(false);
    }

    @Override
    @SuppressLint("RestrictedApi")
    protected void initializeRowViewHolder(RowPresenter.ViewHolder holder) {
        super.initializeRowViewHolder(holder);
        ViewHolder vh = (ViewHolder) holder;
        HorizontalGridView grid = vh.getGridView();
        grid.setFocusScrollStrategy(strategy);
        grid.setHorizontalSpacing(ResUtil.dp2px(spacing));
        grid.setOnKeyListener((v, keyCode, event) -> {
            if (!KeyUtil.isActionDown(event)) return false;
            int position = grid.getSelectedPosition();
            int count = grid.getAdapter() == null ? 0 : grid.getAdapter().getItemCount();
            if (count <= 1) return false;
            if (KeyUtil.isLeftKey(event) && position == 0) {
                grid.setSelectedPosition(count - 1);
                return true;
            }
            if (KeyUtil.isRightKey(event) && position == count - 1) {
                grid.setSelectedPosition(0);
                return true;
            }
            return false;
        });
    }
}
