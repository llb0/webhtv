package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.fongmi.android.tv.App;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 快进预览条的取帧缓存：按「媒体 + 秒位」取一帧，内存 LRU + 磁盘各存一份。
 *
 * <p>取帧走 {@link MediaMetadataRetriever}，独立于播放器解码，所以拖动游标时不会打断播放。
 * 老容器（只有 IJK 能解的 avi/rmvb 等）在部分盒子上 MMR 也解不开，这种情况返回 null，
 * 由调用方退化为「用已有截图铺底 + 只显示时间」。</p>
 */
public final class SeekPreviewFrameCache {

    public static final String DIR = "seek_previews";

    private static final int MEMORY_BYTES = 12 * 1024 * 1024;
    private static final long DISK_MAX_BYTES = 64L * 1024 * 1024;
    private static final long DISK_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long TRIM_INTERVAL_MS = 60L * 1000L;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "seek-preview");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> PENDING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final AtomicLong LAST_TRIM = new AtomicLong(0);
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(MEMORY_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null || value.isRecycled() ? 0 : value.getByteCount();
        }
    };

    private SeekPreviewFrameCache() {
    }

    public interface Callback {
        /** 主线程回调；bitmap 为 null 表示这一帧取不到。 */
        void onFrame(String key, Bitmap bitmap);
    }

    public static String keyFor(String media, long positionMs) {
        return digest(media == null ? "" : media) + "_" + Math.max(0, positionMs / 1000);
    }

    public static void clearMemory() {
        MEMORY.evictAll();
    }

    public static void clearDisk() {
        deleteTree(dir(App.get()));
    }

    /**
     * 请求一帧；命中内存直接回调，命中磁盘只解码不取帧，其余交给后台线程。
     */
    public static void request(Context context, String media, Map<String, String> headers,
                               long positionMs, int width, int height, Callback callback) {
        if (context == null || media == null || media.isEmpty()) {
            if (callback != null) callback.onFrame(null, null);
            return;
        }
        final String key = keyFor(media, positionMs);
        Bitmap cached = MEMORY.get(key);
        if (cached != null && !cached.isRecycled()) {
            if (callback != null) callback.onFrame(key, cached);
            return;
        }
        if (!PENDING.add(key)) return;
        final Context app = context.getApplicationContext();
        final long position = Math.max(0, positionMs);
        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decodeDisk(app, key, width, height);
                if (bitmap == null) bitmap = extract(media, headers, position, width, height);
                if (bitmap != null) {
                    MEMORY.put(key, bitmap);
                    saveDisk(app, key, bitmap);
                    trimIfNeeded(app);
                }
            } catch (Throwable ignored) {
            } finally {
                PENDING.remove(key);
            }
            final Bitmap result = bitmap;
            MAIN.post(() -> {
                if (callback != null) callback.onFrame(key, result);
            });
        });
    }

    private static Bitmap extract(String media, Map<String, String> headers, long positionMs, int width, int height) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            bindSource(retriever, media, headers);
            long timeUs = positionMs * 1000L;
            // 先走列表缩略图同一套 getFrameAtTime：不少盒子上 getScaledFrameAtTime 直接返回 null，
            // 之前一屏占位图主要就是这条路挂了。取到之后自己缩放。
            Bitmap frame = grab(retriever, timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = grab(retriever, timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) frame = grab(retriever, timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC);
            if (frame == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && width > 0 && height > 0) {
                try {
                    frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height);
                } catch (Throwable ignored) {
                    frame = null;
                }
            }
            return scale(frame, width, height);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void bindSource(MediaMetadataRetriever retriever, String media, Map<String, String> headers) {
        if (media.startsWith("/") || media.startsWith("file://")) {
            retriever.setDataSource(media.startsWith("file://") ? media.substring(7) : media);
            return;
        }
        Map<String, String> safe = headers == null ? Collections.emptyMap() : headers;
        try {
            retriever.setDataSource(media, safe);
        } catch (Throwable ignored) {
            // 带上请求头打不开时再退回不带头的裸地址，SMB 代理多数情况下不吃头。
            retriever.setDataSource(media, Collections.emptyMap());
        }
    }

    private static Bitmap grab(MediaMetadataRetriever retriever, long timeUs, int option) {
        try {
            return retriever.getFrameAtTime(timeUs, option);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap scale(Bitmap source, int width, int height) {
        if (source == null) return null;
        if (width <= 0 || height <= 0) return source;
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) return source;
        float ratio = Math.min((float) width / sourceWidth, (float) height / sourceHeight);
        if (ratio >= 1f) return source;
        Bitmap scaled = Bitmap.createScaledBitmap(source, Math.max(1, (int) (sourceWidth * ratio)),
                Math.max(1, (int) (sourceHeight * ratio)), true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    private static Bitmap decodeDisk(Context context, String key, int width, int height) {
        File file = fileOf(context, key);
        if (!file.exists()) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            int sample = 1;
            if (width > 0 && options.outWidth > width * 2) {
                while (options.outWidth / (sample * 2) >= width) sample *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return scale(bitmap, width, height);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void saveDisk(Context context, String key, Bitmap bitmap) {
        File file = fileOf(context, key);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
        } catch (Throwable ignored) {
            file.delete();
        }
    }

    private static void trimIfNeeded(Context context) {
        long now = System.currentTimeMillis();
        long last = LAST_TRIM.get();
        if (now - last < TRIM_INTERVAL_MS || !LAST_TRIM.compareAndSet(last, now)) return;
        trim(context, now);
    }

    /**
     * 目录自管上限：超过 64MB 或超过 7 天的条目先删。清理调度会直接删整个目录，
     * 这里的自管只保证没人清理时也不无限增长。
     */
    public static void trim(Context context, long now) {
        File dir = dir(context);
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) {
            if (file.isFile()) total += file.length();
        }
        if (total <= DISK_MAX_BYTES) {
            for (File file : files) {
                if (file.isFile() && now - file.lastModified() > DISK_MAX_AGE_MS) file.delete();
            }
            return;
        }
        java.util.Arrays.sort(files, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        for (File file : files) {
            if (total <= DISK_MAX_BYTES) break;
            if (!file.isFile()) continue;
            total -= file.length();
            file.delete();
        }
    }

    private static File dir(Context context) {
        return new File(context.getCacheDir(), DIR);
    }

    private static File fileOf(Context context, String key) {
        return new File(dir(context), key + ".jpg");
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
