package com.fongmi.android.tv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.SessionState;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
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
 * trickplay 缩略图仓库：用 ffmpeg(-kit) 直接从片源抽关键帧，按「10 秒一格」落盘成 jpg，
 * 预览条只做读盘 + 裁剪，不再每次现场开一个解码器解一帧。
 *
 * <p>与 Plex / Jellyfin 服务端 trickplay 的思路一致（{@code -skip_frame nokey} 只解关键帧、
 * 缩到 320 宽、10 秒一格），区别是生成放在设备端按需进行：预览条打开时只补当前那一窗（
 * 8~12 格，一次 ffmpeg 调用），已经抽过的位置永久命中磁盘缓存。</p>
 *
 * <p>目录挂在 {@code cache/trickplay}，参与缓存统计与自动清理，上限自管。</p>
 */
public final class TrickplayStore {

    public static final String DIR = "trickplay";

    /** 一格 = 10 秒，和快进步进一致，也贴近 Jellyfin 默认。 */
    public static final long STEP_MS = 10_000L;
    /** 预览图宽度：320 足够一排小格，单张 jpg 约 7KB。 */
    private static final int FRAME_WIDTH = 320;
    private static final int FRAME_QUALITY = 4;
    private static final int COVER_WIDTH = 480;
    private static final long DISK_MAX_BYTES = 256L * 1024 * 1024;
    private static final long DISK_TRIM_TARGET_BYTES = 200L * 1024 * 1024;
    private static final long DISK_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MEMORY_BYTES = 8 * 1024 * 1024;
    /** 找不到精确格时往前后各找几格（黄金时刻还没抽到时就近顶替）。 */
    private static final int NEIGHBORS = 5;
    private static final double MIN_COVER_LUMA = 36;

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

    private static final long STREAM_POLL_MS = 500L;
    private static final int MAX_STREAM_FAILURES = 2;

    private static volatile boolean playerBusy;
    private static volatile String backgroundKey;
    private static volatile Thread backgroundThread;
    /** 正在跑的那条流式抽帧会话，暂停/离开播放页时按 id 取消。 */
    private static volatile long streamSessionId = -1;

    private TrickplayStore() {
    }

    public static ExecutorService io() {
        return IO;
    }

    /**
     * 播放一开始就在后台把整部片子的缩略图抽出来，不用等第一次快进。
     * 只在播放器 READY 时推进；缓冲时由 {@link #setPlayerBusy(boolean)} 挂起，
     * 有前台窗口在抽帧时也会让路。
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
        cancelStream();
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
        int failures = 0;
        while (key.equals(backgroundKey)) {
            if (!waitWhileBusy(key)) return;
            int next = lastCompleteIndex(context, key) + 1;
            if ((next - 1) * STEP_MS >= durationMs) return;
            String pattern = new File(dir(context, key), "%06d.jpg").getAbsolutePath();
            String[] args = streamArgs(media, headers, next, pattern);
            FFmpegSession session;
            try {
                session = FFmpegKit.executeWithArgumentsAsync(args, (FFmpegSessionCompleteCallback) result -> {
                });
            } catch (Throwable error) {
                return;
            }
            if (session == null) return;
            streamSessionId = sessionIdOf(session);
            boolean cancelled = false;
            // 流式抽帧一边解一边落盘，帧是一格一格冒出来的；读取端有多少读多少。
            while (session.getState() == SessionState.RUNNING) {
                if (!key.equals(backgroundKey)) {
                    cancelStream();
                    return;
                }
                // 播放卡了/在缓冲：先停下，等 READY 再从断点续跑，别和播放抢带宽。
                if (playerBusy) {
                    cancelled = true;
                    cancelStream();
                }
                sleep(STREAM_POLL_MS);
            }
            streamSessionId = -1;
            if (cancelled) {
                // 缓冲挂起导致的中断不算失败，等 READY 后从断点续跑。
                failures = 0;
                sleep(STREAM_POLL_MS);
                continue;
            }
            boolean success = session.getReturnCode() != null
                    && ReturnCode.isSuccess(session.getReturnCode());
            if (success) return;
            int written = lastCompleteIndex(context, key);
            if (written >= next) {
                failures = 0;
            } else if (++failures >= MAX_STREAM_FAILURES) {
                return;
            } else {
                sleep(2_000L);
            }
        }
    }

    private static long sessionIdOf(FFmpegSession session) {
        try {
            return session.getSessionId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 缓冲中或有前台窗口在抽帧时歇一会儿；返回 false 表示任务已被取消。 */
    private static boolean waitWhileBusy(String key) {
        while (playerBusy || RUNNING.contains(key)) {
            if (!key.equals(backgroundKey)) return false;
            sleep(500);
        }
        return key.equals(backgroundKey);
    }

    private static void cancelStream() {
        long id = streamSessionId;
        if (id <= 0) return;
        try {
            FFmpegKit.cancel(id);
        } catch (Throwable ignored) {
        }
    }

    /** 流式抽帧参数：从第 {@code firstIndex} 格对应的时间点开始，一路解到片尾。 */
    private static String[] streamArgs(String media, Map<String, String> headers, int firstIndex, String pattern) {
        String headerLine = headerLine(headers);
        String[] base = new String[]{
                "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, firstIndex - 1) * STEP_MS / 1000.0),
                "-skip_frame", "nokey",
                "-i", media,
                "-vf", String.format(Locale.ROOT, "fps=1/%d,scale=%d:-2", STEP_MS / 1000, FRAME_WIDTH),
                "-q:v", String.valueOf(FRAME_QUALITY),
                "-f", "image2",
                "-start_number", String.valueOf(firstIndex),
                pattern
        };
        if (TextUtils.isEmpty(headerLine)) return base;
        String[] withHeaders = new String[base.length + 2];
        withHeaders[0] = "-y";
        withHeaders[1] = "-ss";
        withHeaders[2] = base[2];
        withHeaders[3] = "-headers";
        withHeaders[4] = headerLine;
        System.arraycopy(base, 3, withHeaders, 5, base.length - 3);
        return withHeaders;
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

    /** 某位置对应的帧文件序号（1 起，和 ffmpeg image2 的 %06d 对齐）。 */
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

    /** 读一帧：内存 → 磁盘（精确格，其次前后邻格）。返回 null 表示这一窗还没抽到。 */
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
     * 已经在跑的窗口会被后来的请求替换成最新位置。
     */
    public static void prepareWindow(Context context, String media, Map<String, String> headers,
                                     long firstMs, int count, Runnable onDone) {
        if (context == null || TextUtils.isEmpty(media) || count <= 0) {
            if (onDone != null) MAIN.post(onDone);
            return;
        }
        String key = keyFor(media);
        int start = (int) (Math.max(0, firstMs) / STEP_MS) + 1;
        String token = key + "@" + start + "+" + count;
        if (!ATTEMPTED.add(token)) {
            // 这个窗口已经试过（成功或失败），不再重复跑；再由调用方决定兜底。
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
        File outDir = dir(window.context, window.key);
        String pattern = new File(outDir, "%06d.jpg").getAbsolutePath();
        String[] args = windowArgs(window, pattern);
        try {
            FFmpegSession session = FFmpegKit.executeWithArguments(args);
            if (session == null || !ReturnCode.isSuccess(session.getReturnCode())) {
                // 失败时清掉半成品，避免把黑帧/空文件当命中。
                for (int i = 0; i < window.count; i++) {
                    File file = frameFile(window.context, window.key, window.start + i);
                    if (file.isFile() && file.length() == 0) file.delete();
                }
            }
        } catch (Throwable ignored) {
        }
        trim(window.context);
    }

    private static String[] windowArgs(Window window, String pattern) {
        double startSec = Math.max(0, (window.start - 1)) * STEP_MS / 1000.0;
        String[] base = new String[]{
                "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", startSec),
                "-skip_frame", "nokey",
                "-i", window.media,
                "-vf", String.format(Locale.ROOT, "fps=1/%d,scale=%d:-2", STEP_MS / 1000, FRAME_WIDTH),
                "-frames:v", String.valueOf(window.count),
                "-q:v", String.valueOf(FRAME_QUALITY),
                "-f", "image2",
                "-start_number", String.valueOf(window.start),
                pattern
        };
        String headerLine = headerLine(window.headers);
        if (TextUtils.isEmpty(headerLine)) return base;
        String[] withHeaders = new String[base.length + 2];
        withHeaders[0] = "-y";
        withHeaders[1] = "-ss";
        withHeaders[2] = base[2];
        withHeaders[3] = "-headers";
        withHeaders[4] = headerLine;
        // base 去掉前三个（-y/-ss/值）后接上
        System.arraycopy(base, 3, withHeaders, 5, base.length - 3);
        return withHeaders;
    }

    /** 把播放器用的请求头转成 ffmpeg 的 -headers 格式（CRLF 分隔）。 */
    private static String headerLine(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (TextUtils.isEmpty(name) || value == null) continue;
            if ("Range".equalsIgnoreCase(name)) continue;
            builder.append(name).append(": ").append(value).append("\r\n");
        }
        return builder.length() == 0 ? null : builder.toString();
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
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(tmp)) {
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

    /** 抽一张：{@code -ss} 放输入端（关键帧快速定位），只解一帧。 */
    public static Bitmap extractOne(Context context, String media, Map<String, String> headers,
                                    long positionMs, int width) {
        if (context == null || TextUtils.isEmpty(media)) return null;
        File temp = new File(context.getCacheDir(), "trickplay_cover.jpg");
        File parent = temp.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        temp.delete();
        String[] args;
        String headerLine = headerLine(headers);
        if (TextUtils.isEmpty(headerLine)) {
            args = new String[]{
                    "-y",
                    "-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, positionMs) / 1000.0),
                    "-skip_frame", "nokey",
                    "-i", media,
                    "-frames:v", "1",
                    "-vf", String.format(Locale.ROOT, "scale=%d:-2", Math.max(64, width)),
                    "-q:v", "3",
                    "-f", "image2",
                    temp.getAbsolutePath()
            };
        } else {
            args = new String[]{
                    "-y",
                    "-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, positionMs) / 1000.0),
                    "-headers", headerLine,
                    "-skip_frame", "nokey",
                    "-i", media,
                    "-frames:v", "1",
                    "-vf", String.format(Locale.ROOT, "scale=%d:-2", Math.max(64, width)),
                    "-q:v", "3",
                    "-f", "image2",
                    temp.getAbsolutePath()
            };
        }
        try {
            FFmpegSession session = FFmpegKit.executeWithArguments(args);
            if (session == null || !ReturnCode.isSuccess(session.getReturnCode())) return null;
            if (!temp.isFile() || temp.length() == 0) return null;
            Bitmap bitmap = BitmapFactory.decodeFile(temp.getAbsolutePath());
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        } finally {
            temp.delete();
        }
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
