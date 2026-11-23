package com.fongmi.android.tv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * JNI 抽帧桥：复用应用里已经随 mpv 打包的 FFmpeg 7.1.4（libmvformat / libmvcodec /
 * libmvutil / libmwscale，符号在运行时 dlopen+dlsym 绑定），不引入第二套 FFmpeg，
 * 也不会和 nextlib 里的 libavcodec.so 撞名。
 *
 * <p>那几个库本身从 {@code assets/mpv-libs/<abi>} 补缺后 System.load；加载/打开失败的原因
 * 会记在 {@link #lastError()} 里，界面可以据此提示，不用非得抓 logcat。</p>
 */
public final class TrickplayNative {

    private static final String TAG = "TrickplayNative";
    private static final Object LOCK = new Object();
    /** 依赖顺序和 MPVLib 一致：C++ 运行时 → FFmpeg 各部件。 */
    private static final String[] LIBRARIES = {
            "c++_shared", "mvutil", "mwresample", "mwscale", "mvcodec", "mvformat"
    };

    private static volatile boolean ready;
    private static volatile String failure;
    private static volatile String nativeError;

    private TrickplayNative() {
    }

    public static boolean isReady() {
        return ready;
    }

    public static String failureReason() {
        return failure;
    }

    /** 原生侧最近一次失败原因（打开失败、无视频流、解不出来等），没有则为 null。 */
    public static String lastError() {
        if (!ready) return failure;
        return nativeError;
    }

    public static boolean ensureLoaded(Context context) {
        if (ready) return true;
        if (context == null) return false;
        synchronized (LOCK) {
            if (ready) return true;
            try {
                Context app = context.getApplicationContext();
                String abi = loadBundledLibraries(app);
                if (abi == null) {
                    failure = "找不到内置 FFmpeg 库";
                    return false;
                }
                System.loadLibrary("trickplay_extractor");
                File dir = new File(app.getDir("mpv-libs", Context.MODE_PRIVATE), abi);
                ready = nativeSetup(dir.getAbsolutePath());
                if (!ready) failure = "FFmpeg 符号绑定失败";
            } catch (Throwable error) {
                failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                Log.e(TAG, "unable to load extractor", error);
                ready = false;
            }
            if (ready) Log.i(TAG, "trickplay ready");
            else Log.e(TAG, "trickplay unavailable: " + failure);
            return ready;
        }
    }

    /**
     * 把 mpv 那套 FFmpeg 从 assets 补到私有目录并按依赖顺序 System.load。
     * 已经由 mpv 加载过就复用，不重复解压。
     */
    private static String loadBundledLibraries(Context app) {
        File root = app.getDir("mpv-libs", Context.MODE_PRIVATE);
        String abi = null;
        for (String candidate : Build.SUPPORTED_ABIS) {
            if (assetExists(app, candidate)) {
                abi = candidate;
                break;
            }
        }
        if (abi == null) return null;
        File dir = new File(root, abi);
        if (!dir.exists() && !dir.mkdirs()) return null;
        for (String name : LIBRARIES) {
            String fileName = System.mapLibraryName(name);
            File file = new File(dir, fileName);
            if (!file.isFile() || file.length() == 0) {
                if (!copyAsset(app, "mpv-libs/" + abi + "/" + fileName, file)) {
                    Log.w(TAG, "missing bundled library " + fileName);
                    return null;
                }
            }
            try {
                System.load(file.getAbsolutePath());
            } catch (Throwable error) {
                // 可能已被 mpv 从同一路径加载过，忽略；能不能用由符号绑定决定。
                Log.w(TAG, "load " + fileName + " failed: " + error.getMessage());
            }
        }
        return abi;
    }

    private static boolean assetExists(Context app, String abi) {
        try (InputStream ignored = app.getAssets().open("mpv-libs/" + abi + "/libmvformat.so")) {
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean copyAsset(Context app, String asset, File target) {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (InputStream input = app.getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        } catch (Throwable error) {
            tmp.delete();
            return false;
        }
        target.delete();
        if (tmp.renameTo(target)) return true;
        tmp.delete();
        return false;
    }

    public static Bitmap grab(Context context, String url, String[] headers, long positionMs, int width) {
        if (!ensureLoaded(context)) return null;
        long handle = nativeOpen(url, headers);
        if (handle == 0) return null;
        try {
            return bitmapOf(nativeGrab(handle, Math.max(0, positionMs), width));
        } finally {
            nativeClose(handle);
        }
    }

    /** [w, h, ARGB...] -> Bitmap。 */
    public static Bitmap bitmapOf(int[] frame) {
        if (frame == null || frame.length < 3) return null;
        int width = frame[0];
        int height = frame[1];
        if (width <= 0 || height <= 0 || frame.length < 2 + width * height) return null;
        int[] pixels = new int[width * height];
        System.arraycopy(frame, 2, pixels, 0, pixels.length);
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static native boolean nativeSetup(String libDir);

    static native long nativeOpen(String url, String[] headers);

    /** 片源显示宽高比（w/h），拿不到返回 0。 */
    static native float nativeAspect(long handle);

    /** 顺序抽帧前定位一次；之后一路往前解，不再每格 seek。 */
    static native boolean nativeRestart(long handle, long positionMs);

    /** 往前解到 targetMs 或之后的第一帧；到片尾返回 null。 */
    static native int[] nativeAdvance(long handle, long targetMs, int width);

    static native int[] nativeGrab(long handle, long positionMs, int width);

    static native void nativeClose(long handle);

    static native String nativeLastError();
}
