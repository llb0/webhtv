package com.fongmi.android.tv.player;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

public class PlayerHelper {

    public static String getDefaultUa() {
        return Util.getUserAgent(App.get(), BuildConfig.APPLICATION_ID);
    }

    public static String getUa() {
        return resolveUa(Setting.getUa(), PlayerHelper::getDefaultUa);
    }

    public static String resolveUa(String ua, Supplier<String> fallback) {
        return TextUtils.isEmpty(ua) ? fallback.get() : ua;
    }

    public static String getSubtitleMimeType(String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (path.endsWith(".ssa") || path.endsWith(".ass")) return MimeTypes.TEXT_SSA;
        if (path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    public static Bundle toBundle(Map<String, String> headers) {
        Bundle bundle = new Bundle();
        headers.forEach(bundle::putString);
        return bundle;
    }

    public static String describeFormat(Format format) {
        StringJoiner joiner = new StringJoiner(",");
        if (format.id != null) joiner.add(format.id);
        if (format.label != null) joiner.add(format.label);
        if (format.codecs != null) joiner.add(format.codecs);
        if (format.language != null) joiner.add(format.language);
        if (format.sampleMimeType != null) joiner.add(format.sampleMimeType);
        if (format.containerMimeType != null) joiner.add(format.containerMimeType);
        if (format.width != C.LENGTH_UNSET) joiner.add(String.valueOf(format.width));
        if (format.height != C.LENGTH_UNSET) joiner.add(String.valueOf(format.height));
        if (format.sampleRate != C.RATE_UNSET_INT) joiner.add(String.valueOf(format.sampleRate));
        if (format.channelCount != C.LENGTH_UNSET) joiner.add(String.valueOf(format.channelCount));
        if (format.averageBitrate != C.LENGTH_UNSET) joiner.add(String.valueOf(format.averageBitrate));
        return joiner.toString();
    }

    public static void share(Activity activity, String url, Map<String, String> headers, CharSequence title) {
        try {
            if (url == null || url.isEmpty()) return;
            Bundle bundle = toBundle(headers);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_TEXT, url);
            intent.putExtra("extra_headers", bundle);
            intent.putExtra("title", title).putExtra("name", title);
            intent.setType("text/plain");
            activity.startActivity(getChooser(intent));
        } catch (Exception ignored) {
        }
    }

    public static void choose(Activity activity, String url, Map<String, String> headers, boolean isVod, long position, CharSequence title) {
        try {
            if (url == null || url.isEmpty()) return;
            url = url.replace("clan://", "file://tvbox/");
            List<String> list = new ArrayList<>();
            headers.forEach((key, value) -> {
                list.add(key);
                list.add(value);
            });
            Uri data = url.startsWith("file://") || url.startsWith("/") ? FileUtil.getShareUri(url) : Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(data, "video/*");
            intent.putExtra("title", title).putExtra("return_result", isVod);
            intent.putExtra("headers", list.toArray(String[]::new));
            if (isVod) intent.putExtra("position", (int) position);
            chooseExternal(activity, intent);
        } catch (Exception ignored) {
        }
    }

    public static void onExternalResult(Intent data, Runnable onNext, LongConsumer seekTo) {
        try {
            if (data == null || data.getExtras() == null) return;
            long position = data.getExtras().getInt("position", 0);
            String endBy = data.getExtras().getString("end_by", "");
            if ("playback_completion".equals(endBy)) App.post(onNext);
            if ("user".equals(endBy)) seekTo.accept(position);
        } catch (Exception ignored) {
        }
    }


    /**
     * 自带应用选择器：部分 MIUI/电视固件的系统 chooser 会直接使用默认应用，
     * 或在一个外部播放器上停留后闪退。这里先枚举真实可处理 video/* 的应用，
     * 由应用自己的列表让用户选择，选中后再带 Component 启动。
     */
    private static void chooseExternal(Activity activity, Intent intent) {
        try {
            List<ResolveInfo> infos = App.get().getPackageManager().queryIntentActivities(intent, 0);
            List<ResolveInfo> candidates = new ArrayList<>();
            for (ResolveInfo info : infos) {
                if (info == null || info.activityInfo == null) continue;
                if (App.get().getPackageName().equals(info.activityInfo.packageName)) continue;
                candidates.add(info);
            }
            if (candidates.isEmpty()) {
                Toast.makeText(activity, "没有可用的外部播放器", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] labels = new String[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                ResolveInfo info = candidates.get(i);
                labels[i] = String.valueOf(info.loadLabel(App.get().getPackageManager()));
            }
            new AlertDialog.Builder(activity)
                    .setTitle("选择打开方式")
                    .setItems(labels, (dialog, which) -> {
                        try {
                            ResolveInfo info = candidates.get(which);
                            Intent launch = new Intent(intent);
                            launch.setFlags(launch.getFlags() & ~Intent.FLAG_ACTIVITY_NEW_TASK);
                            launch.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                            launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivityForResult(launch, 1001);
                        } catch (Exception ignored) {
                            Toast.makeText(activity, "该应用无法打开此视频", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Exception ignored) {
        }
    }

    private static Intent getChooser(Intent intent) {
        List<ComponentName> components = new ArrayList<>();
        for (ResolveInfo resolveInfo : App.get().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            String pkgName = resolveInfo.activityInfo.packageName;
            if (pkgName.equals(App.get().getPackageName())) {
                components.add(new ComponentName(pkgName, resolveInfo.activityInfo.name));
            }
        }
        return Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, components.toArray(new ComponentName[0]));
    }
}
