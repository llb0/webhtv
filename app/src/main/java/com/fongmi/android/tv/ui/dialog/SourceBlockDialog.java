package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SourceBlockDialog {

    /** 依赖项不可操作时的文字透明度：只做视觉变淡，勾选状态原样保留。 */
    private static final float DISABLED_ALPHA = 0.45f;

    public interface Callback {
        void onConfirm();
    }

    public static void show(Context context, Callback callback) {
        int[] sources = Setting.SOURCE_ALL;
        String[] names = new String[sources.length + 2];
        String[] blockedNames = {
                context.getString(R.string.source_vod_url),
                context.getString(R.string.source_live_url),
                context.getString(R.string.source_sites_json),
                context.getString(R.string.source_sites_js),
                context.getString(R.string.source_sites_py),
                context.getString(R.string.source_sites_raw),
                context.getString(R.string.source_lives_file)
        };
        for (int i = 0; i < blockedNames.length; i++) names[i] = blockedNames[i];
        // 末两项同样「勾选=屏蔽」，但独立存 idj_*（取反），不进来源屏蔽掩码
        final int idjIndex = sources.length;
        final int adultIndex = sources.length + 1;
        names[idjIndex] = context.getString(R.string.source_idj_sites);
        names[adultIndex] = context.getString(R.string.source_idj_adult);
        final boolean[] checked = new boolean[sources.length + 2];
        int mask = Setting.getSourceBlockMask();
        for (int i = 0; i < sources.length; i++) checked[i] = (mask & sources[i]) != 0;
        checked[idjIndex] = !Setting.isIdjSitesEnabled();
        checked[adultIndex] = !Setting.isIdjAdultAllowed();
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_source_block_dialog_title)
                .setMultiChoiceItems(names, checked, null)
                .setPositiveButton(R.string.dialog_positive, (d, which) -> {
                    int newMask = 0;
                    for (int i = 0; i < sources.length; i++) if (checked[i]) newMask |= sources[i];
                    Setting.putSourceBlockMask(newMask);
                    Setting.putIdjSitesEnabled(!checked[idjIndex]);
                    Setting.putIdjAdultAllowed(!checked[adultIndex]);
                    if (callback != null) callback.onConfirm();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
        // 自己接管点击：插件站点整体被屏蔽时，成人项要变淡并锁住，但勾选状态原样保留，
        // 解除屏蔽后直接恢复它原来的勾选/未勾选，不因为中途被禁用而改写。
        dialog.setOnShowListener(ignored -> {
            ListView list = dialog.getListView();
            if (list == null) return;
            updateAdultRow(list, checked[idjIndex], adultIndex);
            list.setOnItemClickListener((parent, view, position, id) -> {
                if (position == adultIndex && checked[idjIndex]) return;
                boolean now = !list.isItemChecked(position);
                list.setItemChecked(position, now);
                checked[position] = now;
                if (position == idjIndex) updateAdultRow(list, now, adultIndex);
            });
        });
        dialog.show();
    }

    private static void updateAdultRow(ListView list, boolean disabled, int position) {
        int child = position - list.getFirstVisiblePosition();
        if (child < 0 || child >= list.getChildCount()) return;
        View row = list.getChildAt(child);
        if (row == null) return;
        row.setAlpha(disabled ? DISABLED_ALPHA : 1f);
    }
}
