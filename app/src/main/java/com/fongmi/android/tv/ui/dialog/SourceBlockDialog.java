package com.fongmi.android.tv.ui.dialog;

import android.content.Context;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SourceBlockDialog {

    public interface Callback {
        void onConfirm();
    }

    public static void show(Context context, Callback callback) {
        int[] sources = Setting.SOURCE_ALL;
        String[] names = new String[sources.length + 1];
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
        // 末项语义相反：勾选=允许，独立存 idj_sites_enabled，不进来源屏蔽掩码
        names[sources.length] = context.getString(R.string.source_idj_sites);
        boolean[] checked = new boolean[sources.length + 1];
        int mask = Setting.getSourceBlockMask();
        for (int i = 0; i < sources.length; i++) checked[i] = (mask & sources[i]) != 0;
        checked[sources.length] = Setting.isIdjSitesEnabled();
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_source_block_dialog_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    int newMask = 0;
                    for (int i = 0; i < sources.length; i++) if (checked[i]) newMask |= sources[i];
                    Setting.putSourceBlockMask(newMask);
                    Setting.putIdjSitesEnabled(checked[sources.length]);
                    if (callback != null) callback.onConfirm();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }
}
