package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogAboutBinding;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.ResUtil;

public final class AboutDialog {

    private AboutDialog() {
    }

    public static void show(FragmentActivity activity, Runnable updateAction) {
        DialogAboutBinding binding = DialogAboutBinding.inflate(LayoutInflater.from(activity));
        binding.version.setText(activity.getString(R.string.about_version, AppVersion.fullName(), BuildConfig.FLAVOR_mode, BuildConfig.FLAVOR_abi));

        // 置零MaterialButton默认inset，扩大文字区域
        binding.checkUpdate.setInsetLeft(0);
        binding.checkUpdate.setInsetRight(0);
        binding.checkUpdate.setInsetTop(0);
        binding.checkUpdate.setInsetBottom(0);

        binding.confirm.setInsetLeft(0);
        binding.confirm.setInsetRight(0);
        binding.confirm.setInsetTop(0);
        binding.confirm.setInsetBottom(0);

        configureContentHeight(activity, binding);

        Dialog dialog = LightDialog.create(activity, null, binding.getRoot());
        binding.confirm.setOnClickListener(v -> dialog.dismiss());
        binding.updateSettings.setOnClickListener(v -> {
            dialog.dismiss();
            UpdateSettingsDialog.show(activity);
        });
        binding.checkUpdate.setOnClickListener(v -> {
            dialog.dismiss();
            if (updateAction != null) updateAction.run();
        });
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        configureWindow(activity, dialog);
        binding.confirm.requestFocus();
    }

    private static void configureContentHeight(FragmentActivity activity, DialogAboutBinding binding) {
        int screenHeight = ResUtil.getScreenHeight(activity);
        int maxHeight = (int) (screenHeight * (ResUtil.isLand(activity) ? 0.42f : 0.38f));
        int minHeight = ResUtil.dp2px(220);
        ViewGroup.LayoutParams params = binding.contentScroll.getLayoutParams();
        params.height = Math.max(minHeight, Math.min(maxHeight, ResUtil.dp2px(420)));
        binding.contentScroll.setLayoutParams(params);
    }

    private static boolean configureWindow(FragmentActivity activity, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return false;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.62f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
        return true;
    }
}
