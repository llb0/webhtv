package com.fongmi.android.tv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * trickplay 缩略图仓库：用应用自带的 FFmpeg（{@link TrickplayNative}，JNI 抽帧）直接从片源
 * 抽关键帧，按「10 秒一格」落盘成 jpg，预览条只做读盘 + 裁剪。
 *
 * <p>与 Plex / Jellyfin 服务端 trickplay 思路一致（关键帧、320 宽、10 秒一格），区别是生成放在
 * 设备端按需进行：起播就跑后台流式抽帧，抽一格写一格，预览条读多少用多少；预览打开时优先补
 * 当前那一窗。</p>
 *
 * <p>目录挂在 {@code cache/trickplay}，参与缓存统计与自动清理，上限自管。</p>
 */
public final class TrickplayStore {

    public static final String DIR = "trickplay";

    /** 一格 = 10 秒，和快进步进一致，也贴近 Jellyfin 默认。 */
    public static final long STEP_MS = 10_000L;
    /** 预览图宽度：320 足够一排小格，单张 jpg 约 7KB。 */
    private static final int FRAME_WIDTH = 320;
    private static final int FRAME_JPEG_QUALITY = 84;
    private static final int COVER_WIDTH = 480;
    private static final long DISK_MAX_BYTES = 256L * 1024 * 1024;
    private static final long DISK_TRIM_TARGET_BYTES = 200L * 1024 * 1024;
    private static final long DISK_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MEMORY_BYTES = 8 * 1024 * 1024;
    /** 找不到精确格时往前后各找几格（黄金时刻还没抽到时就近顶替）。 */
    private static final int NEIGHBORS = 5;
    private static final double MIN_COVER_LUMA = 36;
    private static final long STREAM_POLL_MS = 300L;
    private static final int MAX_STREAM_FAILURES = 3;

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "trickplay");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(MEMORY_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null || value.isRecycled() ? 0 : value.getByteCount();
        }
    };
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Window> PENDING = new ConcurrentHashMap<>();
    private static final Set<String> ATTEMPTED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong LAST_TRIM = new AtomicLong(0);

    private static volatile boolean playerBusy;
    private static volatile String backgroundKey;
    private static volatile Thread backgroundThread;
    /** 正在跑的后台流式抽帧会话，暂停/离开播放页时关掉它，让 IO 立刻让出来。 */
    private static volatile Extractor backgroundExtractor;

    private TrickplayStore() {
    }

    public static ExecutorService io() {
        return IO;
    }

    /**
     * 抽帧会话：一个片源打开一次（探测 + 建解码器），之后每格只 seek+解一帧。
     * 不是线程安全的，只在单个抽帧线程里用。
     */
    public static final class Extractor implements AutoCloseable {
        private final Context context;
        private final String media;
        private final String[] headers;
        private long handle;
        private volatile boolean closed;

        private Extractor(Context context, String media, Map<String, String> headers) {
            this.context = context.getApplicationContext();
            this.media = media;
            this.headers = headerArray(headers);
            this.handle = TrickplayNative.ensureLoaded(this.context)
                    ? TrickplayNative.nativeOpen(media, this.headers) : 0;
        }

        public boolean isValid() {
            return handle != 0 && !closed;
        }

        public Bitmap grab(long positionMs, int width) {
            if (!isValid()) return null;
            int[] frame = TrickplayNative.nativeGrab(handle, Math.max(0, positionMs), width);
            return TrickplayNative.bitmapOf(frame);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            long value = handle;
            handle = 0;
            if (value != 0) {
                try {
                    TrickplayNative.nativeClose(value);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static Extractor open(Context context, String media, Map<String, String> headers) {
        if (context == null || TextUtils.isEmpty(media)) return null;
        return new Extractor(context, media, headers);
    }

    /**
     * 播放一开始就在后台把整部片子的缩略图抽出来，不用等第一次快进。
     * 抽一格写一格，前面几格几秒内就能用。缓冲时由 {@link #setPlayerBusy(boolean)} 挂起。
     */
    public static void startBackground(Context context, String media, Map<String, String> headers, long durationMs) {
        if (context == null || TextUtils.isEmpty(media)) return;
        final String key = keyFor(media);
        if (key.equals(backgroundKey) && backgroundThread != null && backgroundThread.isAlive()) return;
        stopBackground();
        backgroundKey = key;
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> runBackground(app, media, headers, durationMs, key), "trickplay-bg");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        backgroundThread = thread;
        thread.start();
    }

    public static void stopBackground() {
        backgroundKey = null;
        backgroundThread = null;
        closeBackgroundExtractor();
    }

    private static void closeBackgroundExtractor() {
        Extractor extractor = backgroundExtractor;
        backgroundExtractor = null;
        if (extractor != null) extractor.close();
    }

    /** 播放器缓冲 / 卡顿时挂起后台抽帧，READY 后继续。 */
    public static void setPlayerBusy(boolean busy) {
        playerBusy = busy;
    }

    private static void runBackground(Context context, String media, Map<String, String> headers,
                                      long durationMs, String key) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignored) {
        }
        if (durationMs <= 0) return;
        Extractor extractor = null;
        int failures = 0;
        try {
            while (key.equals(backgroundKey)) {
                if (playerBusy || RUNNING.contains(key)) {
                    sleep(500);
                    continue;
                }
                int index = lastCompleteIndex(context, key) + 1;
                if ((index - 1) * STEP_MS >= durationMs) return;
                if (extractor == null) {
                    extractor = open(context, media, headers);
                    backgroundExtractor = extractor;
                    if (extractor == null || !extractor.isValid()) return;
                }
                Bitmap bitmap = extractor.grab((index - 1) * STEP_MS, FRAME_WIDTH);
                if (!key.equals(backgroundKey)) return;
                if (bitmap == null) {
                    // 网络抖动之类：同一格重试几次，仍然不行就放弃后台，别空转。
                    if (++failures >= MAX_STREAM_FAILURES) return;
                    sleep(1_000L);
                    continue;
                }
                failures = 0;
                writeFrame(frameFile(context, key, index), bitmap);
                trim(context);
            }
        } finally {
            if (extractor != null) extractor.close();
            if (backgroundExtractor == extractor) backgroundExtractor = null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String keyFor(String media) {
        return digest(media == null ? "" : media);
    }

    public static File dir(Context context, String key) {
        File dir = new File(context.getCacheDir(), DIR + File.separator + key);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 某位置对应的帧文件序号（1 起，第 1 格 = 片头 0 秒）。 */
    private static int frameIndex(long positionMs) {
        return (int) (Math.max(0, positionMs) / STEP_MS) + 1;
    }

    private static File frameFile(Context context, String key, int index) {
        return new File(dir(context, key), String.format(Locale.ROOT, "%06d.jpg", index));
    }

    public static void clearMemory() {
        MEMORY.evictAll();
        ATTEMPTED.clear();
    }

    /** 读一帧：内存 → 磁盘（精确格，其次前后邻格）。返回 null 表示这一格还没抽到。 */
    public static Bitmap read(Context context, String media, long positionMs, int width, int height) {
        if (context == null || TextUtils.isEmpty(media)) return null;
        String key = keyFor(media);
        int index = frameIndex(positionMs);
        String memKey = key + "#" + index;
        Bitmap cached = MEMORY.get(memKey);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = findFile(context, key, index);
        if (file == null) return null;
        Bitmap bitmap = decode(file, width, height);
        if (bitmap != null) MEMORY.put(memKey, bitmap);
        return bitmap;
    }

    private static File findFile(Context context, String key, int index) {
        File exact = frameFile(context, key, index);
        if (exact.isFile() && exact.length() > 0) return exact;
        for (int step = 1; step <= NEIGHBORS; step++) {
            File before = frameFile(context, key, index - step);
            if (before.isFile() && before.length() > 0) return before;
            File after = frameFile(context, key, index + step);
            if (after.isFile() && after.length() > 0) return after;
        }
        return null;
    }

    /**
     * 补一窗：{@code count} 格，从 {@code firstMs} 起。同一次预览重复打开只跑一次，
     * 已经在跑的窗口会被后来的请求替换成最新位置。每抽到一格就落盘，调用方随时可读。
     */
    public static void prepareWindow(Context context, String media, Map<String, String> headers,
                                     long firstMs, int count, Runnable onDone) {
        if (context == null || TextUtils.isEmpty(media) || count <= 0) {
            if (onDone != null) MAIN.post(onDone);
            return;
        }
        String key = keyFor(media);
        int start = frameIndex(firstMs);
        String token = key + "@" + start + "+" + count;
        if (!ATTEMPTED.add(token)) {
            if (onDone != null) MAIN.post(onDone);
            return;
        }
        Window window = new Window(context.getApplicationContext(), media, headers, start, count, onDone);
        if (RUNNING.add(key)) {
            IO.execute(() -> runWindow(window));
        } else {
            PENDING.put(key, window);
        }
    }

    private static void runWindow(Window window) {
        try {
            executeWindow(window);
        } finally {
            MAIN.post(() -> {
                if (window.onDone != null) window.onDone.run();
            });
            RUNNING.remove(window.key);
            Window next = PENDING.remove(window.key);
            if (next != null && RUNNING.add(window.key)) IO.execute(() -> runWindow(next));
        }
    }

    private static void executeWindow(Window window) {
        if (window.count <= 0) return;
        try (Extractor extractor = open(window.context, window.media, window.headers)) {
            if (extractor == null || !extractor.isValid()) return;
            for (int i = 0; i < window.count; i++) {
                int index = window.start + i;
                File file = frameFile(window.context, window.key, index);
                if (file.isFile() && file.length() > 0) continue;
                Bitmap bitmap = extractor.grab((index - 1) * STEP_MS, FRAME_WIDTH);
                if (bitmap == null) continue;
                writeFrame(file, bitmap);
            }
        } finally {
            trim(window.context);
        }
    }

    /** 写一格 jpg：先写 .tmp 再改名，读端永远不会看到半张图。 */
    private static boolean writeFrame(File target, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            bitmap.recycle();
            return false;
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        boolean ok = false;
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            ok = bitmap.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, output);
        } catch (Throwable ignored) {
        } finally {
            bitmap.recycle();
        }
        if (!ok) {
            tmp.delete();
            return false;
        }
        if (tmp.renameTo(target)) return true;
        target.delete();
        return tmp.renameTo(target);
    }

    /** 已经写完整（长度大于 0）的最大格号；半截文件顺手删掉，续跑时重写这一格。 */
    private static int lastCompleteIndex(Context context, String key) {
        File dir = dir(context, key);
        String[] names = dir.list();
        if (names == null) return 0;
        int max = 0;
        for (String name : names) {
            if (!name.endsWith(".jpg") || name.length() < 6) continue;
            try {
                int index = Integer.parseInt(name.substring(0, 6));
                File file = new File(dir, name);
                if (file.length() > 0) max = Math.max(max, index);
                else file.delete();
            } catch (Throwable ignored) {
            }
        }
        return max;
    }

    /** 把播放器用的请求头转成 [name, value, name, value...]，交给 JNI 拼 ffmpeg headers。 */
    private static String[] headerArray(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        java.util.List<String> values = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (TextUtils.isEmpty(name) || value == null) continue;
            if ("Range".equalsIgnoreCase(name)) continue;
            values.add(name);
            values.add(value);
        }
        return values.isEmpty() ? null : values.toArray(new String[0]);
    }

    /**
     * 封面兜底：给列表缩略图 / 无图历史抽一张画面。
     * 依次试几个常见时间点挑最亮的（片头黑场跳过），片短时后面的点自然抽不到，失败返回 null。
     */
    public static Bitmap extractCover(Context context, String media, Map<String, String> headers) {
        if (context == null || TextUtils.isEmpty(media)) return null;
        long[] positions = new long[]{60_000L, 15_000L, 180_000L, 600_000L, 1_500_000L};
        Bitmap chosen = null;
        double bestLuma = -1;
        for (long timeMs : positions) {
            Bitmap frame = extractOne(context, media, headers, timeMs, COVER_WIDTH);
            if (frame == null) continue;
            double luma = luma(frame);
            if (luma > bestLuma) {
                if (chosen != null && chosen != frame) chosen.recycle();
                chosen = frame;
                bestLuma = luma;
            } else if (chosen != frame) {
                frame.recycle();
            }
            if (bestLuma >= MIN_COVER_LUMA) break;
        }
        return chosen;
    }

    /** 抽一张并写到指定文件（历史兜底封面用），成功返回 true。 */
    public static boolean saveCover(Context context, String media, Map<String, String> headers, File target) {
        Bitmap bitmap = extractCover(context, media, headers);
        if (bitmap == null || bitmap.isRecycled()) return false;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            bitmap.recycle();
            return false;
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                tmp.delete();
                return false;
            }
        } catch (Throwable error) {
            tmp.delete();
            return false;
        } finally {
            bitmap.recycle();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            return tmp.renameTo(target);
        }
        return true;
    }

    /** 抽一张：一次打开 + 一次 seek + 解一帧（复用同一套 JNI）。 */
    public static Bitmap extractOne(Context context, String media, Map<String, String> headers,
                                    long positionMs, int width) {
        if (context == null || TextUtils.isEmpty(media)) return null;
        return TrickplayNative.grab(context, media, headerArray(headers), Math.max(0, positionMs),
                Math.max(64, width));
    }

    private static Bitmap decode(File file, int width, int height) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            int target = Math.max(FRAME_WIDTH, Math.max(width, height));
            while (bounds.outWidth / sample > target * 2) sample *= 2;
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return null;
            if (width > 0 && bitmap.getWidth() > width) {
                int h = Math.max(1, Math.round(width * bitmap.getHeight() / (float) bitmap.getWidth()));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, h, true);
                if (scaled != bitmap) bitmap.recycle();
                return scaled;
            }
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 0~255 平均亮度，检测片头黑场。 */
    private static double luma(Bitmap bitmap) {
        if (bitmap == null) return 0;
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
                sum += 0.2126 * ((color >> 16) & 0xFF) + 0.7152 * ((color >> 8) & 0xFF) + 0.0722 * (color & 0xFF);
                count++;
            }
        }
        if (sample != bitmap) sample.recycle();
        return count == 0 ? 0 : sum / count;
    }

    /** 自管上限：256MB / 30 天，超过按最旧访问删到 200MB。 */
    public static void trim(Context context) {
        long now = System.currentTimeMillis();
        if (now - LAST_TRIM.get() < 60_000L) return;
        if (!LAST_TRIM.compareAndSet(LAST_TRIM.get(), now)) return;
        File root = new File(context.getCacheDir(), DIR);
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        long total = 0;
        Map<File, Long> alive = new HashMap<>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                dir.delete();
                continue;
            }
            File[] files = dir.listFiles();
            if (files == null) continue;
            long dirTotal = 0;
            long newest = 0;
            for (File file : files) {
                if (now - file.lastModified() > DISK_MAX_AGE_MS) {
                    file.delete();
                    continue;
                }
                dirTotal += file.length();
                newest = Math.max(newest, file.lastModified());
            }
            if (dirTotal == 0) {
                dir.delete();
                continue;
            }
            total += dirTotal;
            alive.put(dir, newest);
        }
        if (total <= DISK_MAX_BYTES) return;
        java.util.List<Map.Entry<File, Long>> entries = new java.util.ArrayList<>(alive.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue(), b.getValue()));
        for (Map.Entry<File, Long> entry : entries) {
            if (total <= DISK_TRIM_TARGET_BYTES) break;
            File[] files = entry.getKey().listFiles();
            if (files != null) for (File file : files) total -= file.length();
            deleteTree(entry.getKey());
            ATTEMPTED.clear();
        }
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
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static final class Window {
        final Context context;
        final String media;
        final Map<String, String> headers;
        final String key;
        final int start;
        final int count;
        final Runnable onDone;

        Window(Context context, String media, Map<String, String> headers, int start, int count, Runnable onDone) {
            this.context = context;
            this.media = media;
            this.headers = headers;
            this.key = keyFor(media);
            this.start = start;
            this.count = count;
            this.onDone = onDone;
        }
    }
}
