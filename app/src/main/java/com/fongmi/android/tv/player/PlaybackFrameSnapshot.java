package com.fongmi.android.tv.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 从当前播放视图抓一帧，作为历史缺失海报（推送占位/普通站源空图）时的兜底封面。
 *
 * <p>只做一次 PixelCopy/TextureView 截图，不 seek、不改播放位置；黑屏帧由调用方丢弃后重试。
 * 截图缩到最长边 {@link #TARGET_MAX_EDGE}，保存为私有目录下的 JPEG，返回 {@code file://} URI，
 * 交给现有的 {@code ImgUtil} 走本地文件加载。</p>
 */
public final class PlaybackFrameSnapshot {

    private static final int TARGET_MAX_EDGE = 640;
    private static final int JPEG_QUALITY = 82;
    private static final float BLACK_LUMA = 26f;
    private static final float MOSTLY_BLACK_RATIO = 0.85f;

    private PlaybackFrameSnapshot() {
    }

    public interface Callback {
        /** 成功返回 file:// URI，失败或黑屏返回空串；回调在主线程。 */
        void onCaptured(@NonNull String uri);
    }

    public interface BitmapCallback {
        /** 成功返回非黑屏的一帧（调用方负责回收），失败返回 null；回调在主线程。 */
        void onCaptured(@Nullable Bitmap bitmap);
    }

    public static void capture(@NonNull Activity activity, @Nullable PlayerView playerView, @NonNull Callback callback) {
        captureBitmap(activity, playerView, bitmap -> {
            if (bitmap == null) {
                callback.onCaptured("");
                return;
            }
            Task.execute(() -> {
                String uri = "";
                try {
                    uri = save(activity, bitmap);
                } catch (Throwable ignored) {
                } finally {
                    bitmap.recycle();
                }
                final String result = uri;
                App.post(() -> callback.onCaptured(result));
            });
        });
    }

    /** 只取一帧交回调用方，落盘方式由调用方决定（SMB 缩略图就走这里）。 */
    public static void captureBitmap(@NonNull Activity activity, @Nullable PlayerView playerView, @NonNull BitmapCallback callback) {
        if (playerView == null) {
            callback.onCaptured(null);
            return;
        }
        View surface = playerView.getVideoSurfaceView();
        if (!playerView.isAttachedToWindow() || surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) {
            callback.onCaptured(null);
            return;
        }
        int[] size = targetSize(surface.getWidth(), surface.getHeight());
        Handler handler = new Handler(Looper.getMainLooper());
        if (surface instanceof SurfaceView surfaceView) {
            Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(surfaceView, bitmap, result -> finishBitmap(bitmap, result == PixelCopy.SUCCESS, callback), handler);
            } catch (RuntimeException error) {
                bitmap.recycle();
                callback.onCaptured(null);
            }
            return;
        }
        if (surface instanceof TextureView textureView) {
            Bitmap bitmap = null;
            try {
                bitmap = textureView.getBitmap(size[0], size[1]);
            } catch (RuntimeException ignored) {
            }
            finishBitmap(bitmap, bitmap != null, callback);
            return;
        }
        callback.onCaptured(null);
    }

    private static void finishBitmap(Bitmap bitmap, boolean success, BitmapCallback callback) {
        if (!success || bitmap == null || isMostlyBlack(bitmap)) {
            if (bitmap != null) bitmap.recycle();
            callback.onCaptured(null);
            return;
        }
        callback.onCaptured(bitmap);
    }

    private static boolean isMostlyBlack(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return true;
        int stepX = Math.max(1, width / 32);
        int stepY = Math.max(1, height / 18);
        int total = 0;
        int dark = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int color = bitmap.getPixel(x, y);
                float luma = 0.2126f * ((color >> 16) & 255) + 0.7152f * ((color >> 8) & 255) + 0.0722f * (color & 255);
                if (luma < BLACK_LUMA) dark++;
                total++;
            }
        }
        return total > 0 && dark >= total * MOSTLY_BLACK_RATIO;
    }

    private static String save(Activity activity, Bitmap bitmap) throws Exception {
        File directory = new File(activity.getFilesDir(), "webhome_snapshot");
        if (!directory.exists() && !directory.mkdirs()) return "";
        File file = new File(directory, "snapshot-" + System.currentTimeMillis() + "-" + System.nanoTime() + ".jpg");
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                file.delete();
                return "";
            }
        }
        return Uri.fromFile(file).toString();
    }

    /**
     * 播放画面抓不到（网页源叠层、黑屏、还没出图）时，改从片源直接抽一帧封面。
     * 走 ffmpeg 关键帧抽取，HLS / 老容器也能拿到图；失败返回空串。
     */
    public static String captureFromSource(Activity activity, String media, java.util.Map<String, String> headers) {
        if (activity == null || TextUtils.isEmpty(media)) return "";
        File directory = new File(activity.getFilesDir(), "webhome_snapshot");
        if (!directory.exists() && !directory.mkdirs()) return "";
        File file = new File(directory, "snapshot-" + System.currentTimeMillis() + "-" + System.nanoTime() + ".jpg");
        if (!TrickplayStore.saveCover(activity.getApplicationContext(), media, headers, file)) {
            file.delete();
            return "";
        }
        return Uri.fromFile(file).toString();
    }

    /**
     * 把已经抽好的图片复制进历史封面目录，返回 {@code file://} URI。
     *
     * <p>不直接引用原文件：缩略图缓存有容量上限和过期清理，历史封面若指着缓存目录，
     * 清一次缓存历史图就集体失效。复制一份到 {@code webhome_snapshot}，生命周期就和其他
     * 截图一致（删历史/清空历史时一起删），也不会反过来影响目录预览用的缓存。
     */
    public static String importArtwork(File source) {
        if (source == null || !source.isFile() || source.length() <= 0) return "";
        File directory = new File(App.get().getFilesDir(), "webhome_snapshot");
        if (!directory.exists() && !directory.mkdirs()) return "";
        File target = new File(directory, "snapshot-" + System.currentTimeMillis() + "-" + System.nanoTime() + ".jpg");
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (Throwable e) {
            target.delete();
            return "";
        }
        return Uri.fromFile(target).toString();
    }

    /** 删除某条历史对应的截图缓存；只接受本站 webhome_snapshot 目录下的 file:// 地址。 */
    public static void delete(String uri) {
        if (uri == null || !uri.startsWith("file://")) return;
        try {
            File file = new File(Uri.parse(uri).getPath()).getAbsoluteFile();
            File root = new File(App.get().getFilesDir(), "webhome_snapshot").getAbsoluteFile();
            if (file.getParentFile() != null && file.getParentFile().equals(root)) file.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 清空全部兜底截图缓存；用于清空整个历史或恢复备份前。 */
    public static void deleteAll() {
        File root = new File(App.get().getFilesDir(), "webhome_snapshot");
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
    }

    private static int[] targetSize(int width, int height) {
        if (width >= height) {
            int targetWidth = Math.min(width, TARGET_MAX_EDGE);
            return new int[]{targetWidth, Math.max(1, Math.round(height * targetWidth / (float) width))};
        }
        int targetHeight = Math.min(height, TARGET_MAX_EDGE);
        return new int[]{Math.max(1, Math.round(width * targetHeight / (float) height)), targetHeight};
    }
}
