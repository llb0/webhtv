package com.fongmi.android.tv.ui.dialog;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.textview.MaterialTextView;

public final class DebugLogDialog {

    private DebugLogDialog() {
    }

    public static void show(Fragment fragment) {
        show(fragment.requireActivity());
    }
    
    public static void show(FragmentActivity activity) {
        Server.get().start();
        String localUrl = Server.get().getAddress("/debug/logs");
        String lanUrl = Server.get().getAddress(false) + "/debug/logs";
        String xbpqUrl = Server.get().getAddress("/proxy?do=log");
        SpiderDebug.log("debug", "logs service ready url=%s lan=%s xbpq=%s", localUrl, lanUrl, xbpqUrl);
        String message = activity.getString(R.string.debug_log_dialog_message, lanUrl, localUrl, xbpqUrl);
        MaterialTextView content = new MaterialTextView(activity);
        content.setText(message);
        content.setTextColor(Color.parseColor("#5F6368"));
        content.setTextSize(14);
        content.setLineSpacing(ResUtil.dp2px(2), 1f);
    
        android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(activity);
        rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
    
        android.widget.LinearLayout scrollContentPanel = new android.widget.LinearLayout(activity);
        scrollContentPanel.setOrientation(android.widget.LinearLayout.VERTICAL);
        scrollContentPanel.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scrollContentPanel.addView(content);
    
        boolean isLand = ResUtil.isLand(activity);
        for (com.github.catvod.crawler.diagnostics.DiagnosticCategories.Category category : com.github.catvod.crawler.diagnostics.DiagnosticCategories.Category.values()) {
            androidx.appcompat.widget.SwitchCompat toggle = new androidx.appcompat.widget.SwitchCompat(activity);
            toggle.setText(category.title);
            toggle.setTextColor(Color.parseColor("#202124"));
            toggle.setPadding(0, ResUtil.dp2px(8), 0, ResUtil.dp2px(8));
            toggle.setFocusable(true);
            toggle.setChecked(com.github.catvod.crawler.diagnostics.DiagnosticCategories.accepts(com.github.catvod.crawler.DebugLogStore.categories(), category));
            toggle.setOnCheckedChangeListener((button, checked) -> com.github.catvod.crawler.DebugLogStore.setCategory(category, checked));
            if (isLand) {
                toggle.setBackgroundResource(R.drawable.selector_dialog_step_button);
                toggle.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(12), ResUtil.dp2px(8));
            }
            scrollContentPanel.addView(toggle);
        }
    
        MaterialTextView captureNote = new MaterialTextView(activity);
        captureNote.setText("标准日志按容量轮转；深度统计只保留数值，不保存画面或声音。");
        captureNote.setTextSize(14);
        scrollContentPanel.addView(captureNote);
    
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        scroll.addView(scrollContentPanel);
    
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0
        );
        scrollLp.weight = 1;
        scroll.setLayoutParams(scrollLp);
        rootLayout.addView(scroll);
    
        android.widget.LinearLayout btnPanel = new android.widget.LinearLayout(activity);
        btnPanel.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnPanel.setGravity(android.view.Gravity.CENTER);
        btnPanel.setPadding(0, ResUtil.dp2px(16),0,0);
    
        android.widget.Button mark = new android.widget.Button(activity);
        mark.setText("标记此刻故障");
        mark.setFocusable(true);
        if (isLand) {
            mark.setBackgroundResource(R.drawable.selector_dialog_step_button);
        }
        btnPanel.addView(mark);
        mark.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(activity).setTitle("选择当前现象")
                .setItems(com.fongmi.android.tv.player.DiagnosticControls.SYMPTOMS, (d, which) -> {
                    try {
                        com.fongmi.android.tv.player.DiagnosticControls.mark(com.fongmi.android.tv.player.DiagnosticControls.SYMPTOMS[which]);
                        Notify.show("已标记，继续记录后 15 秒");
                    } catch (RuntimeException error) {
                        Notify.show(error.getMessage());
                    }
                }).show());
    
        android.widget.Button depth = new android.widget.Button(activity);
        depth.setText("深度统计 60 秒");
        depth.setFocusable(true);
        if (isLand) {
            depth.setBackgroundResource(R.drawable.selector_dialog_step_button);
        }
        btnPanel.addView(depth);
        depth.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(activity).setTitle("限时深度统计")
                .setMessage("对当前播放做少量低分辨率画面和 PCM 数值统计，不保存图像或声音。到期、切换播放或关闭诊断自动停止。")
                .setNegativeButton("取消", null).setPositiveButton("开启 60 秒", (d, which) -> {
                    try {
                        com.fongmi.android.tv.player.DiagnosticControls.startDepth(60);
                        Notify.show("限时统计已开启");
                    } catch (RuntimeException error) {
                        Notify.show(error.getMessage());
                    }
                }).show());
    
        android.widget.Button stop = new android.widget.Button(activity);
        stop.setText("停止深度统计");
        stop.setFocusable(true);
        if (isLand) {
            stop.setBackgroundResource(R.drawable.selector_dialog_step_button);
        }
        btnPanel.addView(stop);
        stop.setOnClickListener(v -> {
            com.github.catvod.crawler.diagnostics.DiagnosticCapture.stop("user-stopped");
            Notify.show("深度统计已停止");
        });
    
        android.widget.LinearLayout.LayoutParams btnLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rootLayout.addView(btnPanel, btnLp);
    
        android.app.Dialog dialog = LightDialog.create(activity, activity.getString(R.string.setting_debug_log), rootLayout,
                activity.getString(R.string.debug_log_open_browser), v -> open(activity, localUrl),
                activity.getString(R.string.dialog_negative), null,
                activity.getString(R.string.debug_log_copy_url), v -> copy(activity, lanUrl));
    
        // 仅TV横屏：监听布局，限制root整体最大高度
        if (isLand) {
            rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    rootLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    int screenH = ResUtil.getScreenHeight(activity);
                    int maxH = (int)(screenH * 0.90f);
                    if(rootLayout.getHeight() > maxH){
                        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) rootLayout.getLayoutParams();
                        lp.height = maxH;
                        rootLayout.setLayoutParams(lp);
                    }
                }
            });
        }
    
        dialog.show();
    }

    private static void open(FragmentActivity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Notify.show(R.string.debug_log_no_browser);
        }
    }

    private static void copy(FragmentActivity activity, String url) {
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.setting_debug_log), url));
        Notify.show(R.string.debug_log_url_copied);
    }
}
