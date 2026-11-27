package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 来源屏蔽多选弹窗。
 * <p>
 * 不用 MaterialAlertDialog 的多选列表：需要在「插件站点」被勾选时把「成人插件」压成不可点、
 * 但保留它自己的勾选状态，多选列表的点击由框架内部接管，禁用单行很难做到可靠。
 * 这里自己放复选框，勾选状态由 CheckBox 持有，禁用时只是 setEnabled + 变淡，不改勾选值。
 */
public class SourceBlockDialog {

    /** 依赖项不可操作时的透明度。 */
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
        int mask = Setting.getSourceBlockMask();
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < sources.length; i++) checked[i] = (mask & sources[i]) != 0;
        checked[idjIndex] = !Setting.isIdjSitesEnabled();
        checked[adultIndex] = !Setting.isIdjAdultAllowed();

        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(4 * density), pad, Math.round(4 * density));
        final CheckBox[] boxes = new CheckBox[names.length];
        for (int i = 0; i < names.length; i++) {
            MaterialCheckBox item = new MaterialCheckBox(context);
            item.setText(names[i]);
            item.setChecked(checked[i]);
            item.setTextSize(16);
            item.setPadding(0, Math.round(8 * density), 0, Math.round(8 * density));
            boxes[i] = item;
            box.addView(item, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);

        boxes[adultIndex].setEnabled(!checked[idjIndex]);
        boxes[adultIndex].setAlpha(checked[idjIndex] ? DISABLED_ALPHA : 1f);
        boxes[idjIndex].setOnCheckedChangeListener((button, isChecked) -> {
            checked[idjIndex] = isChecked;
            // 只切可操作性，不动成人项自己的勾选状态：解除后原样恢复
            boxes[adultIndex].setEnabled(!isChecked);
            boxes[adultIndex].setAlpha(isChecked ? DISABLED_ALPHA : 1f);
        });
        for (int i = 0; i < names.length; i++) {
            if (i == idjIndex) continue;
            final int index = i;
            boxes[i].setOnCheckedChangeListener((button, isChecked) -> checked[index] = isChecked);
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_source_block_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    int newMask = 0;
                    for (int i = 0; i < sources.length; i++) if (checked[i]) newMask |= sources[i];
                    Setting.putSourceBlockMask(newMask);
                    Setting.putIdjSitesEnabled(!checked[idjIndex]);
                    Setting.putIdjAdultAllowed(!checked[adultIndex]);
                    if (callback != null) callback.onConfirm();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }
}
