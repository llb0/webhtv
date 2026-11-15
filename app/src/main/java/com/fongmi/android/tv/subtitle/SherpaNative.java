package com.fongmi.android.tv.subtitle;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.GithubProxy;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sherpa-ONNX 的 Android JNI 原生库按需下载。
 *
 * <p>原生库约 15–22MB，随 APK 打包会抵消全量 IJK 带来的格式兼容收益，因此和实时字幕
 * 模型一样只在首次使用实时字幕时下载。下载文件放在应用私有目录，校验通过后用
 * {@code System.load} 加载；Sherpa 自带的自动加载会被关闭，避免重复加载同一路径。
 */
final class SherpaNative {

    private static final String VERSION = "1.13.4";
    private static final String ASSET_BASE = "https://github.com/llb0/webhtv/releases/download/native-sherpa-v" + VERSION + "/";
    private static final String LIB_NAME = "libsherpa-onnx-jni.so";
    private static final String NATIVE_PATH_PROP = "sherpa_onnx.native.path";

    private static final String ARM64 = "arm64-v8a";
    private static final String ARMV7 = "armeabi-v7a";

    private static final long ARM64_SIZE = 23_603_240L;
    private static final String ARM64_SHA256 = "f1f156ccb636d6411725771c830a24c6027be4d055c5e84bdce3c58468662241";
    private static final long ARMV7_SIZE = 16_110_348L;
    private static final String ARMV7_SHA256 = "630c30b8df6ec4f62de17f5c8280f7e62f9aa0ee53ef802e7513822adc8d491c";

    private static volatile boolean loaded;

    interface Progress {
        void onProgress(long done, long total);
    }

    private SherpaNative() {
    }

    static boolean installed(Context context) {
        String abi = abi(context);
        return !TextUtils.isEmpty(abi)
                && RealtimeSubtitleModelVerifier.isVerified(file(context, abi), size(abi), sha256(abi));
    }

    static long size(Context context) {
        String abi = abi(context);
        return TextUtils.isEmpty(abi) ? 0L : size(abi);
    }

    static long remainingBytes(Context context) {
        String abi = abi(context);
        if (TextUtils.isEmpty(abi)) return 0L;
        long expected = size(abi);
        File target = file(context, abi);
        if (RealtimeSubtitleModelVerifier.isVerified(target, expected, sha256(abi))) return 0L;
        File part = new File(target.getPath() + ".part");
        long length = part.isFile() ? part.length() : 0L;
        return length > 0L && length < expected ? expected - length : expected;
    }

    /**
     * 确保原生库已下载、校验并加载。阻塞，调用方必须放在后台线程。
     *
     * <p>必须在任何 {@code com.k2fsa.sherpa.onnx} 类被初始化前调用；否则这些类会先触发
     * {@code System.loadLibrary("sherpa-onnx-jni")}，APK 里已经没有这个库而直接失败。
     */
    static File ensure(Context context, Progress progress, BooleanSupplier cancelled) throws IOException {
        String abi = abi(context);
        if (TextUtils.isEmpty(abi)) throw new IOException(App.get().getString(R.string.subtitle_realtime_native_load_error, LIB_NAME));
        File target = file(context, abi);
        if (!RealtimeSubtitleModelVerifier.isVerified(target, size(abi), sha256(abi))) {
            download(abi, target, progress, cancelled);
        }
        if (!RealtimeSubtitleModelVerifier.isVerified(target, size(abi), sha256(abi))) {
            throw new IOException(App.get().getString(R.string.subtitle_realtime_native_checksum_error, target.getName()));
        }
        load(target);
        return target;
    }

    private static synchronized void load(File target) throws IOException {
        if (loaded) return;
        System.setProperty(NATIVE_PATH_PROP, target.getParentFile().getAbsolutePath());
        try {
            // Sherpa 的 Java 类默认会自动 loadLibrary。APK 内没有原生库，必须关掉自动加载，
            // 再由 LibraryUtils 按 sherpa_onnx.native.path 读取已经校验过的文件。
            com.k2fsa.sherpa.onnx.LibraryLoader.setAutoLoadEnabled(false);
            com.k2fsa.sherpa.onnx.LibraryUtils.load();
            loaded = true;
        } catch (Throwable e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new IOException(App.get().getString(R.string.subtitle_realtime_native_load_error, target.getName()) + ": " + message, e);
        }
    }

    private static void download(String abi, File target, Progress progress, BooleanSupplier cancelled) throws IOException {
        long expected = size(abi);
        File dir = target.getParentFile();
        if (dir == null || !dir.exists() && !dir.mkdirs()) {
            throw new IOException(App.get().getString(R.string.subtitle_realtime_model_directory_error));
        }
        File part = new File(target.getPath() + ".part");
        long offset = part.isFile() ? part.length() : 0L;
        if (offset > expected) {
            deletePartial(part);
            offset = 0L;
        }
        if (offset == expected && RealtimeSubtitleModelVerifier.verify(part, expected, sha256(abi))) {
            install(part, target, expected, abi);
            return;
        }
        if (offset == expected) {
            deletePartial(part);
            offset = 0L;
        }
        IOException last = null;
        for (String url : urls(abi)) {
            try {
                download(url, part, target, offset, expected, progress, cancelled);
                return;
            } catch (InterruptedIOException cancelledError) {
                throw cancelledError;
            } catch (IOException error) {
                last = error;
                offset = part.isFile() ? part.length() : 0L;
            }
        }
        throw last == null ? new IOException(App.get().getString(R.string.subtitle_realtime_native_download_failed, LIB_NAME)) : last;
    }

    private static void download(String url, File part, File target, long offset, long expected, Progress progress, BooleanSupplier cancelled) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", "webhtv-realtime-subtitle");
        if (offset > 0L) builder.header("Range", "bytes=" + offset + "-");
        try (Response response = OkHttp.client().newCall(builder.build()).execute()) {
            ResponseBody body = response.body();
            boolean append = offset > 0L && response.code() == 206;
            if (!response.isSuccessful() || body == null) throw new IOException(App.get().getString(R.string.subtitle_realtime_native_download_failed, "HTTP " + response.code()));
            if (append) {
                String contentRange = response.header("Content-Range", "");
                if (!contentRange.startsWith("bytes " + offset + "-")) throw new IOException(App.get().getString(R.string.subtitle_realtime_model_partial_error));
            }
            if (offset > 0L && !append) {
                deletePartial(part);
                offset = 0L;
            }
            try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(part, append)) {
                byte[] buffer = new byte[64 * 1024];
                long current = offset;
                long lastReport = -1L;
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (cancelled != null && cancelled.getAsBoolean()) throw new InterruptedIOException("download_cancelled");
                    output.write(buffer, 0, length);
                    current += length;
                    if (progress != null && (current - lastReport >= 512L * 1024L || current >= expected)) {
                        lastReport = current;
                        progress.onProgress(current, expected);
                    }
                }
                output.getFD().sync();
            }
        }
        if (part.length() != expected) throw new IOException(App.get().getString(R.string.subtitle_realtime_model_incomplete, target.getName()));
        if (!RealtimeSubtitleModelVerifier.verify(part, expected, sha256(abi))) {
            deletePartial(part);
            throw new IOException(App.get().getString(R.string.subtitle_realtime_native_checksum_error, target.getName()));
        }
        install(part, target, expected, abi);
    }

    private static void install(File part, File target, long expected, String abi) throws IOException {
        RealtimeSubtitleModelVerifier.deleteMarker(target);
        if (target.exists() && !target.delete() || !part.renameTo(target)) {
            throw new IOException(App.get().getString(R.string.subtitle_realtime_native_load_error, target.getName()));
        }
        RealtimeSubtitleModelVerifier.markVerified(target, expected, sha256(abi));
        RealtimeSubtitleModelVerifier.deleteMarker(part);
    }

    private static List<String> urls(String abi) {
        String origin = ASSET_BASE + "libsherpa-onnx-jni-" + abi + ".so";
        return GithubProxy.applyAll(origin);
    }

    private static File file(Context context, String abi) {
        return new File(dir(context, abi), LIB_NAME);
    }

    private static File dir(Context context, String abi) {
        return new File(context.getDir("sherpa-libs", Context.MODE_PRIVATE), abi);
    }

    static String abi(Context context) {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (!TextUtils.isEmpty(nativeDir)) {
            String name = new File(nativeDir).getName().toLowerCase(Locale.US);
            if ("arm64".equals(name) || "arm64-v8a".equals(name)) return ARM64;
            if ("arm".equals(name) || "armeabi-v7a".equals(name)) return ARMV7;
        }
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String value : abis) {
                String normalized = value == null ? "" : value.toLowerCase(Locale.US);
                if (ARM64.equals(normalized)) return ARM64;
                if (ARMV7.equals(normalized)) return ARMV7;
            }
        }
        return "";
    }

    private static long size(String abi) {
        return ARM64.equals(abi) ? ARM64_SIZE : ARMV7.equals(abi) ? ARMV7_SIZE : 0L;
    }

    private static String sha256(String abi) {
        return ARM64.equals(abi) ? ARM64_SHA256 : ARMV7.equals(abi) ? ARMV7_SHA256 : "";
    }

    private static void deletePartial(File file) {
        RealtimeSubtitleModelVerifier.deleteMarker(file);
        if (file.exists()) file.delete();
    }
}
