package com.fongmi.android.tv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;

import java.io.File;

import is.xyz.mpv.MPVLib;

/**
 * JNI 抽帧桥：复用应用里已经随 mpv 打包的 FFmpeg 7.1.4（libmvformat / libmvcodec /
 * libmvutil / libmwscale，符号在运行时 dlopen+dlsym 绑定），不引入第二套 FFmpeg，
 * 也不会和 nextlib 里的 libavcodec.so 撞名。
 *
 * <p>{@code nativeOpen} 会把片源打开并建好解码器（一次探测），之后每一格只做 seek+解一帧，
 * 所以同一部片连续抽十几格不会重复打开片源。</p>
 */
public final class TrickplayNative {

    private static final Object LOCK = new Object();
    private static volatile boolean ready;
    private static volatile String failure;

    private TrickplayNative() {
    }

    public static boolean isReady() {
        return ready;
    }

    public static String failureReason() {
        return failure;
    }

    /**
     * 加载抽帧库。必须先确保 mpv 那套 FFmpeg 已经 System.load 过（同名 so 以 soname 共享），
     * 所以这里直接借助 {@link MPVLib#ensureLoaded(Context)}。
     */
    public static boolean ensureLoaded(Context context) {
        if (ready) return true;
        if (context == null) return false;
        synchronized (LOCK) {
            if (ready) return true;
            if (failure != null) return false;
            try {
                Context app = context.getApplicationContext();
                String dir = libraryDir(app);
                if (dir == null) {
                    failure = "no mv libraries";
                    return false;
                }
                if (!MPVLib.ensureLoaded(app)) {
                    failure = "mpv bundle unavailable";
                    return false;
                }
                System.loadLibrary("trickplay_extractor");
                ready = nativeSetup(new File(dir).getAbsolutePath());
                if (!ready) failure = "symbols unavailable";
            } catch (Throwable error) {
                failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                ready = false;
            }
            return ready;
        }
    }

    /** mpv 原生库被解压到 {@code files/mpv-libs/<abi>}，按实际存在的目录挑 ABI。 */
    private static String libraryDir(Context app) {
        File root = app.getDir("mpv-libs", Context.MODE_PRIVATE);
        for (String abi : Build.SUPPORTED_ABIS) {
            File dir = new File(root, abi);
            if (new File(dir, "libmvformat.so").isFile()) return dir.getAbsolutePath();
        }
        return null;
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

    static native int[] nativeGrab(long handle, long positionMs, int width);

    static native void nativeClose(long handle);
}
