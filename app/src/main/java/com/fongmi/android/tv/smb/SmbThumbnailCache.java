package com.fongmi.android.tv.smb;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SMB 视频缩略图：从文件本身取一帧非黑屏画面，落盘到 {@code cache/smb_thumbs}。
 *
 * <p>缓存跟图片/海报同一套管理：目录挂在 {@code CacheModuleId.GLIDE} 下（见
 * CacheModuleRegistry），清理、锁定、上限都跟海报一致；这里再自管一个上限，
 * 防止用户把图片缓存上限设成无限时缩略图无界增长。</p>
 *
 * <p>取帧走 {@link SmbPlaybackProxy} 的本机 HTTP 地址（支持 Range），
 * 多试几个位置挑最亮的帧，全黑/全白（片头黑场）会被跳过。</p>
 */
public final class SmbThumbnailCache {

    public static final String DIR = "smb_thumbs";

    private static final int MAX_BYTES = 128 * 1024 * 1024;
    private static final int TRIM_TARGET_BYTES = 96 * 1024 * 1024;
    private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final double MIN_LUMA = 36;
    private static final int TARGET_WIDTH = 480;

    /**
     * 取帧失败过的文件短期内不再重试（老容器大多只有 IJK 能解，重试只会白读网络）。
     * 用时间戳做软过期：代理 token 被挤出、临时读失败之类的情况过几分钟还能再试，
     * 不会像以前那样一次失败就把这个文件永久钉死。
     */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final long FAILED_TTL_MS = 5L * 60 * 1000;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };

    private SmbThumbnailCache() {
    }

    public static File dir(Context context) {
        File dir = new File(context.getCacheDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 缓存键：同一主机 / 共享 / 路径下的一个文件。列表不带修改时间，文件被替换后缩略图会沿用旧的。 */
    public static String key(String host, String share, String path) {
        return hash((host == null ? "" : host) + "|" + (share == null ? "" : share) + "|"
                + (path == null ? "" : path));
    }

    /**
     * 异步把缩略图贴到 {@code view}：内存 → 磁盘 → 现取一帧。
     * 复用的行会在回来时校验 tag，避免图片落到别的条目上。
     */
    public static void load(Context context, String key, String url, ImageView view, int width, int height) {
        load(context, key, url, view, width, height, true, null);
    }

    /**
     * {@code onReady} 在主线程、图片真正贴上之后回调，供列表在缩略图就绪时再撑开左侧格子。
     * {@code allowGenerate=false} 时只查内存和磁盘（老容器只能用播放时截的图，不去白白取帧）。
     */
    public static void load(Context context, String key, String url, ImageView view, int width, int height,
                            boolean allowGenerate, Runnable onReady) {
        if (context == null || key == null || view == null) return;
        view.setTag(key);
        Bitmap memory = MEMORY.get(key);
        if (memory != null && !memory.isRecycled()) {
            view.setImageBitmap(memory);
            // 内存命中也要回调，否则列表把行的高度/缩略图格收回后再滑回来，
            // 图已经贴上了但格子还藏着，看起来就是“有截图却不显示”。
            if (onReady != null) onReady.run();
            return;
        }
        view.setImageDrawable(null);
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            boolean tried = false;
            try {
                File file = file(app, key);
                // 磁盘上的图永远先用：不管之前取帧失败没失败、列表是从哪个目录重新进来的。
                if (file.isFile()) bitmap = readDisk(file, width, height);
                if (bitmap == null && allowGenerate && url != null && !recentlyFailed(key)) {
                    tried = true;
                    bitmap = generate(app, key, url, file);
                }
            } catch (Throwable ignored) {
                bitmap = null;
            }
            if (bitmap == null && tried) FAILED.put(key, System.currentTimeMillis());
            Bitmap result = bitmap;
            MAIN.post(() -> {
                if (result == null) return;
                // 先入内存再校验 tag：行滑出屏幕被复用时不贴图，但图本身留着，滑回来直接命中。
                MEMORY.put(key, result);
                Object tag = view.getTag();
                if (tag == null || !tag.equals(key)) return;
                view.setImageBitmap(result);
                if (onReady != null) onReady.run();
            });
        });
    }

    /** 列表阶段是否已经有这个文件的缩略图（播放时补图的逻辑据此决定要不要再截）。 */
    public static boolean hasThumb(Context context, String key) {
        return context != null && key != null && file(context, key).isFile();
    }

    /**
     * 用播放时截下的一帧补缩略图：只有 IJK 能解的老容器列表阶段取不到帧，
     * 播过一次就能把画面存成缩略图。位图归调用方回收，这里自己留一份缩过的小图。
     */
    public static void saveFromPlayback(Context context, String key, Bitmap source) {
        if (context == null || key == null || source == null || source.isRecycled()) return;
        Context app = context.getApplicationContext();
        Bitmap scaled = null;
        try {
            scaled = source.getWidth() > TARGET_WIDTH
                    ? Bitmap.createScaledBitmap(source, TARGET_WIDTH,
                    Math.max(1, Math.round(source.getHeight() * TARGET_WIDTH / (float) source.getWidth())), true)
                    : source.copy(Bitmap.Config.ARGB_8888, false);
            write(scaled, file(app, key));
            MEMORY.put(key, scaled);
            FAILED.remove(key);
            trim(app);
            scaled = null;
        } catch (Throwable ignored) {
        } finally {
            // scaled 已经交给内存缓存时不能再回收。
            if (scaled != null) scaled.recycle();
        }
    }

    /** 图片缓存被清理后调用：把内存里的引用一起放掉，避免删了盘还占着。 */
    public static void clearMemory() {
        MEMORY.evictAll();
        FAILED.clear();
    }

    public static File file(Context context, String key) {
        return new File(dir(context), key + ".jpg");
    }

    private static boolean recentlyFailed(String key) {
        Long at = FAILED.get(key);
        if (at == null) return false;
        if (System.currentTimeMillis() - at < FAILED_TTL_MS) return true;
        FAILED.remove(key);
        return false;
    }

    private static Bitmap readDisk(File file, int width, int height) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / sample > Math.max(width, TARGET_WIDTH) * 2) sample *= 2;
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return null;
        return scale(bitmap, width, height);
    }

    private static Bitmap generate(Context context, String key, String url, File target) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap chosen = null;
        double bestLuma = -1;
        try {
            retriever.setDataSource(url, new HashMap<>());
            long duration = 0;
            try {
                String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (value != null) duration = Long.parseLong(value.trim());
            } catch (Throwable ignored) {
                duration = 0;
            }
            // 片头常是纯黑，按比例多试几个位置，取亮度最高的那帧；够亮就提前收工。
            double[] ratios = duration > 0
                    ? new double[]{0.15, 0.3, 0.5, 0.7, 0.05, 0.9}
                    : new double[]{-1};
            for (double ratio : ratios) {
                long timeUs = ratio < 0 ? -1 : (long) (duration * 1000 * ratio);
                Bitmap frame = null;
                try {
                    frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Throwable ignored) {
                    frame = null;
                }
                if (frame == null) continue;
                double luma = luma(frame);
                if (luma > bestLuma) {
                    if (chosen != null && chosen != frame) chosen.recycle();
                    chosen = frame;
                    bestLuma = luma;
                } else if (chosen != frame) {
                    frame.recycle();
                }
                if (bestLuma >= MIN_LUMA) break;
            }
            if (chosen == null) return null;
            Bitmap scaled = scale(chosen, TARGET_WIDTH, 0);
            if (scaled != chosen) chosen.recycle();
            write(scaled, target);
            trim(context);
            return scaled;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void write(Bitmap bitmap, File target) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
        } catch (Throwable ignored) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            // 目标可能已存在（并发生成），改名失败时直接覆盖。
            target.delete();
            if (!tmp.renameTo(target)) tmp.delete();
        }
    }

    private static Bitmap scale(Bitmap bitmap, int width, int height) {
        int currentWidth = bitmap.getWidth();
        int currentHeight = bitmap.getHeight();
        if (currentWidth <= 0 || currentHeight <= 0) return bitmap;
        int targetWidth = width > 0 ? width : TARGET_WIDTH;
        int targetHeight = height > 0 ? Math.round(targetWidth * (float) currentHeight / currentWidth) : 0;
        if (targetHeight <= 0) targetHeight = Math.max(1, Math.round(targetWidth * 9f / 16f));
        if (currentWidth <= targetWidth && (height <= 0 || currentHeight <= targetHeight)) return bitmap;
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    /** 0~255 的平均亮度：缩到 16x16 采样，黑场/纯色帧会明显偏低。 */
    private static double luma(Bitmap bitmap) {
        int width = Math.max(1, Math.min(16, bitmap.getWidth()));
        int height = Math.max(1, Math.min(16, bitmap.getHeight()));
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, width, height, false);
        double sum = 0;
        int count = 0;
        int stepX = Math.max(1, width / 8);
        int stepY = Math.max(1, height / 8);
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int color = sample.getPixel(x, y);
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
                count++;
            }
        }
        if (sample != bitmap) sample.recycle();
        return count == 0 ? 0 : sum / count;
    }

    /** 自管上限：超 128MB 按最早访问删到 96MB，并清掉 30 天没碰过的文件。 */
    private static void trim(Context context) {
        File dir = dir(context);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null || files.length == 0) return;
        long now = System.currentTimeMillis();
        long total = 0;
        List<File> alive = new ArrayList<>();
        for (File file : files) {
            if (now - file.lastModified() > MAX_AGE_MS) {
                file.delete();
                continue;
            }
            total += file.length();
            alive.add(file);
        }
        if (total <= MAX_BYTES) return;
        alive.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : alive) {
            if (total <= TRIM_TARGET_BYTES) break;
            total -= file.length();
            file.delete();
        }
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
