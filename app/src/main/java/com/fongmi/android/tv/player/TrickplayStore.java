package com.fongmi.android.tv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
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
    /** 每部片子的显示宽高比（w/h），预览条按片源比例排版，不再写死 16:9。 */
    private static final Map<String, Float> ASPECT = new ConcurrentHashMap<>();
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Window> PENDING = new ConcurrentHashMap<>();
    private static final Set<String> ATTEMPTED = ConcurrentHashMap.newKeySet();
    /** 打不开的片源：同一部片子 5 分钟内不再反复重开（预览每次拖动都会重新请求）。 */
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    /**
     * 网络源不在起播瞬间开抽，等播放真的稳住（不处于缓冲）再开始；
     * 不再固定等 20 秒——播放稳定了立刻抽，还在缓冲就每 2 秒再看一次。
     */
    private static final long NETWORK_GRACE_MS = 1500L;
    private static final long BUSY_RETRY_MS = 2000L;
    private static Runnable backgroundPending;
    private static final long FAILED_RETRY_MS = 5L * 60 * 1000;
    /** 拿不到时长时（部分 HLS 首帧前 duration=0）最多抽到这里，约 6 小时。 */
    private static final long UNKNOWN_DURATION_CAP_MS = 6L * 60 * 60 * 1000;
    private static final AtomicLong LAST_TRIM = new AtomicLong(0);
    /** 播放忙时最多让路这么久，超过就这轮不抽了，别一直挂着打开的文件会话。 */
    private static final long BUSY_GIVEUP_MS = 2000;

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
            long startedAt = SystemClock.elapsedRealtime();
            this.handle = TrickplayNative.ensureLoaded(this.context)
                    ? TrickplayNative.open(media, this.headers, TrickplayNative.caFile(this.context))
                    : 0;
            long cost = SystemClock.elapsedRealtime() - startedAt;
            if (handle == 0) {
                traceOnce("open fail cost=%dms error=%s headers=%d keys=%s url=%s", cost,
                        TrickplayNative.lastError(), headerCount(), headerKeys(), media);
                if (!TrickplayNative.isReady()) return;
            } else {
                traceOnce("open ok cost=%dms aspect=%.3f url=%s", cost,
                        TrickplayNative.nativeAspect(handle), media);
            }
            if (handle != 0) {
                rememberAspect(TrickplayStore.keyFor(media), TrickplayNative.nativeAspect(handle));
            }
        }

        public boolean isValid() {
            return handle != 0 && !closed;
        }

        int headerCount() {
            return headers == null ? 0 : headers.length / 2;
        }

        /** 只记键名，不记值（值里可能带 auth/cookie）。 */
        String headerKeys() {
            if (headers == null || headers.length == 0) return "none";
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (int i = 0; i < headers.length; i += 2) {
                if (headers[i] != null) keys.add(headers[i]);
            }
            return keys.isEmpty() ? "none" : String.join(",", keys);
        }

        public synchronized Bitmap grab(long positionMs, int width) {
            if (!isValid()) return null;
            long startedAt = SystemClock.elapsedRealtime();
            int[] frame = TrickplayNative.grab(handle, Math.max(0, positionMs), width);
            Bitmap bitmap = TrickplayNative.bitmapOf(frame);
            trace("grab at=%s cost=%dms ok=%b error=%s", time(positionMs),
                    SystemClock.elapsedRealtime() - startedAt, bitmap != null, TrickplayNative.lastError());
            return bitmap;
        }

        /** 顺序模式：先定位一次，之后用 {@link #advance} 一路往前，避免逐格 seek 把盘扫死。 */
        public synchronized boolean restart(long positionMs) {
            if (!isValid()) return false;
            boolean ok = TrickplayNative.restart(handle, Math.max(0, positionMs));
            if (!ok) traceOnce("restart fail at=%s error=%s", time(positionMs), TrickplayNative.lastError());
            return ok;
        }

        public synchronized Bitmap advance(long targetMs, int width) {
            if (!isValid()) return null;
            long startedAt = SystemClock.elapsedRealtime();
            int[] frame = TrickplayNative.advance(handle, Math.max(0, targetMs), width);
            Bitmap bitmap = TrickplayNative.bitmapOf(frame);
            trace("advance at=%s cost=%dms ok=%b error=%s", time(targetMs),
                    SystemClock.elapsedRealtime() - startedAt, bitmap != null, TrickplayNative.lastError());
            if (bitmap != null) rememberAspect(TrickplayStore.keyFor(media), bitmap.getWidth() / (float) bitmap.getHeight());
            return bitmap;
        }

        @Override
        public synchronized void close() {
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
        String key = keyFor(media);
        Long failedAt = FAILED_AT.get(key);
        if (failedAt != null) {
            if (SystemClock.elapsedRealtime() - failedAt < FAILED_RETRY_MS) {
                traceOnce("open skip reason=prev-failed key=%s error=%s", key.substring(0, 8),
                        TrickplayNative.lastError());
                return null;
            }
            FAILED_AT.remove(key);
        }
        String source = resolveSource(media);
        Extractor extractor = new Extractor(context, source, source.equals(media) ? headers : null);
        if (extractor.isValid()) FAILED_AT.remove(key);
        else FAILED_AT.put(key, SystemClock.elapsedRealtime());
        return extractor;
    }

    /**
     * 本地文件播放走的是应用内的 http 服务（http://127.0.0.1:port/file/<path>），
     * 抽帧再开一条连接去抢同一个服务会把播放和抽帧一起卡住（日志里 15 秒读不到任何数据就是它）。
     * 这里把这种 URL 直接还原成真实文件路径，绕开本地服务。
     */
    static String resolveSource(String media) {
        if (media == null) return media;
        String lower = media.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return media;
        // 必须是本机/局域网地址：公网直链里也可能有 /file/，那不能当本地文件读盘。
        if (!isLocalHost(lower)) return media;
        int index = media.indexOf("/file/");
        if (index < 0) return media;
        String target = media.substring(index + 5);
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        int hash = target.indexOf('#');
        if (hash >= 0) target = target.substring(0, hash);
        String decoded = target;
        try {
            decoded = java.net.URLDecoder.decode(target, "UTF-8");
        } catch (Throwable ignored) {
        }
        try {
            File file = com.github.catvod.utils.Path.local(decoded);
            if (file != null && file.isFile()) {
                traceOnce("source local-file name=%s size=%d", file.getName(), file.length());
                return file.getAbsolutePath();
            }
            traceOnce("source local-file missing path=%s", decoded);
        } catch (Throwable e) {
            traceOnce("source local-file fail path=%s error=%s", decoded, e);
        }
        return media;
    }

    /**
     * 播放一开始就在后台把整部片子的缩略图抽出来，不用等第一次快进。
     * 抽一格写一格，前面几格几秒内就能用。缓冲时由 {@link #setPlayerBusy(boolean)} 挂起。
     */
    public static void startBackground(Context context, String media, Map<String, String> headers, long durationMs) {
        if (context == null || TextUtils.isEmpty(media)) return;
        if (!supports(media)) {
            // 网络源不抽：同一 auth_key 再拉一路既费流量，也容易被 CDN 限速/封。
            traceOnce("bg skip reason=network-source url=%s", media);
            return;
        }
        if (isSmbProxy(media)) {
            // SMB 就一条连接、实测上限 20MB/s 左右，而整片顺序抽帧（2:33 的片子要抽 900 多格）
            // 会把这 20MB/s 全吃掉，播放器缓冲一路掉到 0——卡顿就是这么来的。
            // 播放期间不抽，等用户长按快进/快退时按需补当前那几格（SeekPreviewStrip 那条路）。
            traceOnce("bg skip reason=smb-proxy url=%s", media);
            return;
        }
        final String key = keyFor(media);
        if (key.equals(backgroundKey)) {
            if (backgroundThread != null && backgroundThread.isAlive()) return;
            if (backgroundPending != null) return;
        }
        stopBackground();
        backgroundKey = key;
        final Context app = context.getApplicationContext();
        if (isNetworkSource(media)) {
            traceOnce("bg wait key=%s reason=network-source（等播放稳定）", key.substring(0, 8));
            schedule(app, media, headers, durationMs, key, NETWORK_GRACE_MS);
            return;
        }
        launch(app, media, headers, durationMs, key);
    }

    private static void launch(Context context, String media, Map<String, String> headers, long durationMs, String key) {
        Thread thread = new Thread(() -> runBackground(context, media, headers, durationMs, key), "trickplay-bg");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        backgroundThread = thread;
        thread.start();
    }

    /** 延后开抽；期间还在缓冲就再等一轮，别在播放最需要带宽的时候插进去。 */
    private static void schedule(Context context, String media, Map<String, String> headers,
                                 long durationMs, String key, long delayMs) {
        Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            if (backgroundPending != holder[0]) return;
            if (!key.equals(backgroundKey)) return;
            if (playerBusy) {
                // 还在缓冲/卡顿，先别插进去，过两秒再看一眼。
                schedule(context, media, headers, durationMs, key, BUSY_RETRY_MS);
                return;
            }
            backgroundPending = null;
            traceOnce("bg start-stable key=%s", key.substring(0, 8));
            launch(context, media, headers, durationMs, key);
        };
        backgroundPending = holder[0];
        MAIN.postDelayed(holder[0], delayMs);
    }

    /**
     * 只对能随机读、且不走外网的东西抽帧：本地文件、SMB。
     * HLS/直链这类网络源一律不抽（已明确撤销）。
     */
    public static boolean supports(String media) {
        return media != null && !TextUtils.isEmpty(media) && !isNetworkSource(media);
    }

    /** 网络源（HLS/直链）；本地文件和本地服务代理都不算。 */
    private static boolean isNetworkSource(String media) {
        if (media == null) return false;
        String source = resolveSource(media);
        if (!source.equals(media)) return false; // 本地服务 URL 已还原成本地路径
        String lower = source.toLowerCase(Locale.ROOT);
        // 本机/局域网地址上的本地服务路由才算本地：
        // SMB 代理（…?smb=1）和应用内 /file/ 都只是把本地文件包成 http，不是外网源；
        // 公网直链即使路径里出现 /file/ 也一律按网络源撤掉，避免被封。
        if (isLocalHost(lower) && (lower.contains("/file/") || lower.contains("smb=1"))) return false;
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rtmp");
    }

    /** SMB 播放代理（本机 http 服务上的 ?smb=1）：带宽只有一条，不能拿来抽帧。 */
    private static boolean isSmbProxy(String media) {
        if (media == null) return false;
        String lower = media.toLowerCase(Locale.ROOT);
        return lower.contains("smb=1") && isLocalHost(lower);
    }

    /** 地址的主机部分是不是本机 / 局域网（127.0.0.1、localhost、192.168.x.x、10.x.x.x、172.16~31.x.x、169.254.x.x）。 */
    private static boolean isLocalHost(String lowerUrl) {
        int start = lowerUrl.indexOf("://");
        if (start < 0) return false;
        int slash = lowerUrl.indexOf('/', start + 3);
        String hostPort = slash < 0 ? lowerUrl.substring(start + 3) : lowerUrl.substring(start + 3, slash);
        int colon = hostPort.lastIndexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close > 0 ? host.substring(1, close) : host;
        }
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0") || host.equals("::1")) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10 || a == 127) return true;
            if (a == 192 && b == 168) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 169 && b == 254) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 停止后台抽帧。这里只发停止信号并唤醒线程，句柄由抽帧线程自己在 finally 里关，
     * 避免主线程 nativeClose 掉正在 nativeGrab 的句柄（那是之前退出 SMB 崩溃的原因）。
     */
    public static void stopBackground() {
        backgroundKey = null;
        Runnable pending = backgroundPending;
        backgroundPending = null;
        if (pending != null) MAIN.removeCallbacks(pending);
        Thread thread = backgroundThread;
        backgroundThread = null;
        if (thread != null) thread.interrupt();
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
        // 有些 HLS/直播型片源首帧前拿不到时长；照样抽，用上限收口，别静默不动。
        long limit = durationMs > 0 ? durationMs : UNKNOWN_DURATION_CAP_MS;
        Extractor extractor = null;
        try {
            TrickplayNative.resetError();
            int index = lastCompleteIndex(context, key) + 1;
            trace("bg start key=%s from=%s duration=%s%s", key.substring(0, 8), time((index - 1) * STEP_MS),
                    time(limit), durationMs > 0 ? "" : "（未知，按上限跑）");
            if ((index - 1) * STEP_MS >= limit) {
                traceOnce("bg skip reason=already-complete");
                return;
            }
            extractor = open(context, media, headers);
            if (extractor == null || !extractor.isValid()) {
                traceOnce("bg fail reason=open error=%s", TrickplayNative.lastError());
                return;
            }
            backgroundExtractor = extractor;
            int consecutiveFailures = 0;
            while (key.equals(backgroundKey)) {
                long target = (index - 1) * STEP_MS;
                if (target >= limit) {
                    traceOnce("bg done index=%d at=%s", index, time(target));
                    return;
                }
                if (playerBusy || RUNNING.contains(key)) {
                    // 缓冲 / 前台窗口在抽帧：先让路，位置不动，回来继续。
                    trace("bg pause index=%d busy=%b window=%b", index, playerBusy, RUNNING.contains(key));
                    sleep(400);
                    continue;
                }
                Bitmap bitmap = extractor.grab(target, FRAME_WIDTH);
                if (!key.equals(backgroundKey)) {
                    traceOnce("bg stop reason=key-changed index=%d", index);
                    return;
                }
                if (bitmap == null) {
                    // 单格取不到（损坏包、慢盘、定位失败）就跳过这一格继续往后，
                    // 不再让一个坏点把整部片的缩略图全都掐掉。
                    trace("bg gap index=%d at=%s error=%s", index, time(target), TrickplayNative.lastError());
                    if (++consecutiveFailures >= 8) {
                        traceOnce("bg stop reason=too-many-gaps index=%d", index);
                        return;
                    }
                    index++;
                    continue;
                }
                consecutiveFailures = 0;
                File file = frameFile(context, key, index);
                if (!file.isFile() || file.length() == 0) writeFrame(file, bitmap);
                else bitmap.recycle();
                index++;
                trim(context);
                // 轻让一下 CPU，给播放解码留出余量。
                sleep(20);
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

    /** 关键步骤与耗时进应用自带调试日志（设置-调试日志，播放流程分类）。 */
    private static void trace(String format, Object... args) {
        TrickplayLog.log(TrickplayLog.keyOf(format), format, args);
    }

    /** 失败/关键节点：同类内容 5 秒内只记一次。 */
    private static void traceOnce(String format, Object... args) {
        TrickplayLog.once(TrickplayLog.keyOf(format), format, args);
    }

    private static String time(long millis) {
        long total = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    public static float aspect(Context context, String media) {
        if (TextUtils.isEmpty(media)) return 16f / 9f;
        Float value = ASPECT.get(keyFor(media));
        return value == null || value <= 0 ? 16f / 9f : value;
    }

    private static void rememberAspect(String key, float ratio) {
        if (TextUtils.isEmpty(key) || ratio <= 0.1f || ratio > 4f) return;
        ASPECT.put(key, ratio);
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
        if (bitmap != null) {
            MEMORY.put(memKey, bitmap);
            rememberAspect(key, bitmap.getWidth() / (float) bitmap.getHeight());
        }
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
     * 补指定位置：预览条要哪几格就抽哪几格（不再按 10 秒网格补一整窗），
     * 一格抽完就落盘并回调一次，界面可以边抽边贴，不用等整排补完。
     */
    public static void ensureFrames(Context context, String media, Map<String, String> headers,
                                    java.util.List<Long> positions, Runnable onFrame, Runnable onDone) {
        if (context == null || TextUtils.isEmpty(media) || positions == null || positions.isEmpty()) {
            if (onDone != null) MAIN.post(onDone);
            return;
        }
        String key = keyFor(media);
        trace("window request key=%s count=%d first=%s last=%s",
                key.substring(0, 8), positions.size(), time(positions.get(0)), time(positions.get(positions.size() - 1)));
        String token = key + "#" + positions.hashCode();
        if (!ATTEMPTED.add(token)) {
            if (onDone != null) MAIN.post(onDone);
            return;
        }
        Window window = new Window(context.getApplicationContext(), media, headers, positions, onFrame, onDone);
        window.startedAt = SystemClock.elapsedRealtime();
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
            int missing = 0;
            for (Long position : window.positions) {
                File file = frameFile(window.context, window.key, frameIndex(position));
                if (!file.isFile() || file.length() == 0) missing++;
            }
            traceOnce("window done key=%s cost=%dms missing=%d/%d error=%s",
                    window.key.substring(0, 8), SystemClock.elapsedRealtime() - window.startedAt,
                    missing, window.positions.size(), TrickplayNative.lastError());
            MAIN.post(() -> {
                if (window.onDone != null) window.onDone.run();
            });
            ATTEMPTED.remove(window.key + "#" + window.positions.hashCode());
            RUNNING.remove(window.key);
            Window next = PENDING.remove(window.key);
            if (next != null && RUNNING.add(window.key)) IO.execute(() -> runWindow(next));
        }
    }

    private static void executeWindow(Window window) {
        if (window.positions.isEmpty()) return;
        // 播放端正在缓冲/正在真正跳转时不要开第二路读同一个文件：这盘 avi（OpenDML）
        // 每次 open 都要回读片尾索引几 MB，和真正那次 seek 抢同一条 SMB 连接，
        // 会把 seek 拖到播放器超时放弃（表现就是屏显到了、画面还在原位）。
        if (waitWhileBusy()) {
            traceOnce("window skip reason=player-busy key=%s", window.key.substring(0, 8));
            return;
        }
        try (Extractor extractor = open(window.context, window.media, window.headers)) {
            if (extractor == null || !extractor.isValid()) {
                logFailure(window.context, "抽帧会话打不开：" + TrickplayNative.lastError());
                return;
            }
            // 逐格定位：本地/SMB 都能随机读，一次 seek 只解一两帧就拿到图；
            // 顺序推进一旦碰上损坏包或慢盘，会把整条窗口卡死（之前就是这样整排空图）。
            for (Long position : window.positions) {
                if (!RUNNING.contains(window.key)) break;
                // 补帧途中播放又卡住了：等一会儿再继续，但**不整窗放弃**——
                // 以前这里一 break，后面的格子就永远是空白（用户看到的就是后几张老不出图）。
                if (waitWhileBusy()) {
                    traceOnce("window pause reason=player-busy key=%s", window.key.substring(0, 8));
                    sleep(CELL_PAUSE_MS);
                }
                // 每格之间让一拍，别把播放的连接/带宽全占了。
                sleep(CELL_PAUSE_MS);
                int index = frameIndex(position);
                File file = frameFile(window.context, window.key, index);
                if (file.isFile() && file.length() > 0) continue;
                Bitmap bitmap = extractor.grab((index - 1) * STEP_MS, FRAME_WIDTH);
                if (bitmap == null) {
                    logFailure(window.context, "抽帧失败：" + TrickplayNative.lastError());
                    continue;
                }
                writeFrame(file, bitmap);
                if (window.onFrame != null) MAIN.post(window.onFrame);
            }
        } finally {
            trim(window.context);
        }
    }

    /** 播放忙（缓冲/跳转）时等一小会儿；仍忙就返回 true，表示这次补帧应该让路。 */
    /** 相邻两格之间让一拍，降低和播放抢读的压力。 */
    private static final long CELL_PAUSE_MS = 120L;

    private static boolean waitWhileBusy() {
        long waited = 0;
        while (playerBusy && waited < BUSY_GIVEUP_MS) {
            sleep(300);
            waited += 300;
        }
        return playerBusy;
    }

    /** 关键信息只走调试日志，不落文件。 */
    private static void logFailure(Context context, String message) {
        Log.w("Trickplay", message);
        traceOnce("fail %s", message);
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
        if (!supports(media)) return null; // 网络源不抽，交给原来的兜底路径
        TrickplayNative.resetError();
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
        String source = resolveSource(media);
        return TrickplayNative.grab(context, source, source.equals(media) ? headerArray(headers) : null,
                Math.max(0, positionMs), Math.max(64, width));
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
        final java.util.List<Long> positions;
        final Runnable onFrame;
        final Runnable onDone;
        long startedAt;

        Window(Context context, String media, Map<String, String> headers,
               java.util.List<Long> positions, Runnable onFrame, Runnable onDone) {
            this.context = context;
            this.media = media;
            this.headers = headers;
            this.key = keyFor(media);
            this.positions = new java.util.ArrayList<>(positions);
            this.onFrame = onFrame;
            this.onDone = onDone;
        }
    }
}
