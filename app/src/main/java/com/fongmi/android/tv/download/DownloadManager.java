package com.fongmi.android.tv.download;

import android.util.Log;

import com.fongmi.android.tv.setting.Setting;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 全局下载管理器：单例，队列管理，4 线程并发，整合直链多线程分片 + HLS 分片下载。
 *  支持 暂停 / 继续 / 取消 / 失败重试（重试优先续传）。 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static final int DEFAULT_PARALLEL = 4;

    private static class Holder { static final DownloadManager INSTANCE = new DownloadManager(); }
    public static DownloadManager get() { return Holder.INSTANCE; }

    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    /** 每轮调度的代号：暂停/取消后旧一轮还在收尾的线程不再影响新一轮结果 */
    private final Map<String, AtomicInteger> runGenerations = new ConcurrentHashMap<>();
    /** 每代独立的中断标记：避免 resume 重置共享标记导致旧线程继续下载并吞掉 onProgress */
    private final Map<String, Map<Integer, AtomicBoolean>> genCancelled = new ConcurrentHashMap<>();
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService segmentPool = Executors.newFixedThreadPool(DEFAULT_PARALLEL);
    private volatile Listener listener;

    public interface Listener {
        void onTaskStarted(DownloadTask task);
        void onTaskProgress(DownloadTask task, long bytes, long total);
        void onTaskCompleted(DownloadTask task, File output);
        void onTaskFailed(DownloadTask task, String msg);
        void onTaskPaused(DownloadTask task);
        void onTaskResumed(DownloadTask task);
        void onTaskCancelled(DownloadTask task);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 创建任务，outputDir 可传 null（此时使用 Setting 中配置的下载目录）。
     *  输出目录在创建时固化到任务上，之后修改全局设置不会再影响该任务。 */
    public DownloadTask addTask(String name, String url, Map<String, String> headers, File outputDir) {
        File dir = outputDir != null ? outputDir : new File(Setting.getDownloadDir());
        dir.mkdirs();
        DownloadTask task = new DownloadTask();
        task.id = UUID.randomUUID().toString();
        task.name = sanitizeFilename(name);
        task.url = url;
        task.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        task.type = looksLikeHls(url) ? DownloadTask.Type.HLS : DownloadTask.Type.DIRECT;
        task.ext = task.type == DownloadTask.Type.HLS ? "mp4" : guessExt(url);
        task.outputDir = dir.getAbsolutePath();
        tasks.put(task.id, task);

        // 异步初始化（解析 m3u8 / 获取直链大小 / 分片规划）
        taskExecutor.submit(() -> initAndStart(task));
        return task;
    }

    private void initAndStart(DownloadTask task) {
        try {
            new File(task.outputDir).mkdirs();
            task.partDirPath = partDir(task).getAbsolutePath();
            if (task.type == DownloadTask.Type.HLS) {
                initHls(task);
            } else {
                initDirect(task);
            }
            task.status = DownloadTask.Status.RUNNING;
            if (listener != null) listener.onTaskStarted(task);
            runSegments(task);
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            fail(task, e.getMessage());
        }
    }

    private void initHls(DownloadTask task) throws Exception {
        HlsParser.Result result = HlsParser.parse(task.url, task.headers);
        List<DownloadTask.FileSegment> segs = new ArrayList<>();
        // fMP4（EXT-X-MAP）：初始化分片必须排在所有媒体分片之前，否则合并结果无法播放
        if (result.initUrl != null) {
            DownloadTask.FileSegment init = new DownloadTask.FileSegment();
            init.index = -1;
            init.url = result.initUrl;
            init.start = 0;
            init.end = -1;
            init.partFile = new File(partDir(task), "seg_init.part").getAbsolutePath();
            segs.add(init);
        }
        Map<String, byte[]> keyCache = new HashMap<>();
        for (int i = 0; i < result.segmentUrls.size(); i++) {
            DownloadTask.FileSegment s = new DownloadTask.FileSegment();
            s.index = i;
            s.url = result.segmentUrls.get(i);
            s.start = 0;
            s.end = -1;
            s.partFile = new File(partDir(task), "seg_" + i + ".part").getAbsolutePath();
            String keyUrl = result.segmentKeyUrls.get(i);
            if (keyUrl != null) {
                byte[] key = keyCache.get(keyUrl);
                if (key == null) {
                    key = HlsParser.fetchBytes(keyUrl, task.headers);
                    keyCache.put(keyUrl, key);
                }
                if (key != null && key.length > 0) {
                    s.keyBytes = key;
                    // 未显式给 IV 时，按 RFC 8216 用媒体序号推导 IV
                    byte[] iv = result.segmentKeyIvs.get(i);
                    s.keyIv = iv != null && iv.length == 16 ? iv : sequenceIv(result.mediaSequence + i);
                }
            }
            segs.add(s);
        }
        task.segments = segs.toArray(new DownloadTask.FileSegment[0]);
        task.totalSegments = segs.size();
        task.totalSize = -1;   // hls 总大小未知，靠分片实际下载量累加
        task.ext = "mp4";      // 分片合并结果统一使用 .mp4 扩展名
    }

    /** 由媒体序号推导 AES-128 IV（16 字节大端）。 */
    private static byte[] sequenceIv(long seq) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 0 && seq != 0; i--) {
            iv[i] = (byte) (seq & 0xFF);
            seq >>>= 8;
        }
        return iv;
    }

    private void initDirect(DownloadTask task) throws Exception {
        Probe p = probe(task.url, task.headers);
        task.totalSize = p.length;  // -1 表示大小未知
        // 仅当服务器支持 Range 且总大小已知时才多线程分片；否则单线程整文件下载
        boolean canSplit = p.range && p.length > 0;
        if (!canSplit) {
            task.segments = new DownloadTask.FileSegment[]{newSegment(task, 0, 0, -1)};
            task.totalSegments = 1;
        } else {
            int n = DEFAULT_PARALLEL;
            long perSeg = (p.length + n - 1) / n;
            DownloadTask.FileSegment[] arr = new DownloadTask.FileSegment[n];
            for (int i = 0; i < n; i++) {
                long start = i * perSeg;
                long end = Math.min(p.length - 1, (i + 1) * perSeg - 1);
                arr[i] = newSegment(task, i, start, end);
            }
            task.segments = arr;
            task.totalSegments = n;
        }
    }

    private DownloadTask.FileSegment newSegment(DownloadTask task, int index, long start, long end) {
        DownloadTask.FileSegment s = new DownloadTask.FileSegment();
        s.index = index;
        s.url = task.url;
        s.start = start;
        s.end = end;
        s.partFile = new File(partDir(task), "seg_" + index + ".part").getAbsolutePath();
        return s;
    }

    /** 调度一轮分片下载：已完成分片直接跳过，未完成的按已有 .part 续传。 */
    private void runSegments(DownloadTask task) {
        final int gen = nextGeneration(task.id);
        // 每代独立的中断标记：旧线程持有的旧标记仍为 true，不会因 resume 被重置为 false 而继续下载
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        genCancelled.computeIfAbsent(task.id, k -> new ConcurrentHashMap<>()).put(gen, cancelled);
        final AtomicInteger finished = new AtomicInteger(0);
        final AtomicInteger succeeded = new AtomicInteger(0);
        for (DownloadTask.FileSegment seg : task.segments) {
            if (seg.done) {
                finished.incrementAndGet();
                succeeded.incrementAndGet();
            }
        }
        task.completedSegments = succeeded.get();
        // 新一轮开始：立即上报一次当前已下载量，避免 resume 后直到首个分片回调才刷新进度
        long initialTotal = 0;
        for (DownloadTask.FileSegment x : task.segments) initialTotal += x.downloaded;
        task.downloadedBytes = initialTotal;
        if (listener != null) listener.onTaskProgress(task, initialTotal, task.totalSize);
        if (finished.get() >= task.totalSegments) {
            mergeSegments(task, gen);
            return;
        }
        for (final DownloadTask.FileSegment seg : task.segments) {
            if (seg.done) continue;
            segmentPool.submit(() -> SegmentDownloader.download(seg, task.headers, cancelled,
                    new SegmentDownloader.Listener() {
                        @Override public void onProgress(DownloadTask.FileSegment s, long bytesDownloaded) {
                            if (!isCurrent(task.id, gen)) return;
                            long total = 0;
                            for (DownloadTask.FileSegment x : task.segments) total += x.downloaded;
                            task.downloadedBytes = total;
                            if (listener != null) listener.onTaskProgress(task, total, task.totalSize);
                        }
                        @Override public void onComplete(DownloadTask.FileSegment s) {
                            if (!isCurrent(task.id, gen)) return;
                            task.completedSegments = succeeded.incrementAndGet();
                            // hls 场景: 记录实际分片大小
                            if (task.type == DownloadTask.Type.HLS) {
                                try { s.end = new File(s.partFile).length(); } catch (Exception ignored) {}
                            }
                            // 分片完成（含 416 已完整路径）也需刷新已下载总量，否则进度会卡住
                            long total = 0;
                            for (DownloadTask.FileSegment x : task.segments) total += x.downloaded;
                            task.downloadedBytes = total;
                            if (listener != null) listener.onTaskProgress(task, total, task.totalSize);
                            if (finished.incrementAndGet() >= task.totalSegments) mergeSegments(task, gen);
                        }
                        @Override public void onError(DownloadTask.FileSegment s, String msg) {
                            if (!isCurrent(task.id, gen)) return;
                            // 单分片失败不立即放弃；等所有分片结束再统一判定（避免任务卡死）
                            if (finished.incrementAndGet() >= task.totalSegments) mergeSegments(task, gen);
                        }
                    }));
        }
    }

    private void mergeSegments(DownloadTask task, int gen) {
        if (!isCurrent(task.id, gen)) return;   // 旧一轮的收尾回调，忽略
        if (task.status == DownloadTask.Status.CANCELLED || task.status == DownloadTask.Status.PAUSED) return;
        boolean allDone = true;
        for (DownloadTask.FileSegment s : task.segments) if (!s.done) { allDone = false; break; }
        if (!allDone) {
            // 失败：保留 .part 文件与任务记录，便于「重试」优先续传
            fail(task, "Some segments failed");
            return;
        }
        File output = new File(new File(task.outputDir), task.name + "." + task.ext);
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output), 65536)) {
            for (DownloadTask.FileSegment seg : task.segments) {
                File part = new File(seg.partFile);
                if (!part.exists()) throw new IOException("Missing segment: " + seg.partFile);
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(part), 65536)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                part.delete();
            }
            deleteDir(partDir(task));
            task.status = DownloadTask.Status.COMPLETED;
            if (listener != null) listener.onTaskCompleted(task, output);
        } catch (IOException e) {
            fail(task, e.getMessage());
        }
    }

    /** 暂停：中断在途分片读取，保留已下载的 .part 文件，任务留在列表中。 */
    public void pauseTask(String taskId) {
        DownloadTask t = tasks.get(taskId);
        if (t == null || t.status != DownloadTask.Status.RUNNING) return;
        t.status = DownloadTask.Status.PAUSED;
        // 仅中断当前这一代的分片线程；旧标记不动，避免误影响已收尾的线程
        AtomicBoolean cancelled = currentCancelled(taskId);
        if (cancelled != null) cancelled.set(true);
        if (listener != null) listener.onTaskPaused(t);
    }

    /** 继续：从已有 .part 断点续传未完成的分片。 */
    public void resumeTask(String taskId) {
        DownloadTask t = tasks.get(taskId);
        if (t == null || t.status != DownloadTask.Status.PAUSED) return;
        t.status = DownloadTask.Status.RUNNING;
        clearSegmentErrors(t);
        if (listener != null) listener.onTaskResumed(t);
        taskExecutor.submit(() -> restart(t));
    }

    /** 失败重试：优先续传（保留 .part），仅对未完成分片重新请求。 */
    public void retryTask(String taskId) {
        DownloadTask t = tasks.get(taskId);
        if (t == null || t.status != DownloadTask.Status.FAILED) return;
        t.errorMsg = null;
        t.status = DownloadTask.Status.RUNNING;
        clearSegmentErrors(t);
        if (listener != null) listener.onTaskResumed(t);
        taskExecutor.submit(() -> restart(t));
    }

    private void restart(DownloadTask task) {
        try {
            if (task.segments == null || task.segments.length == 0) initAndStart(task);
            else runSegments(task);
        } catch (Exception e) {
            Log.e(TAG, "resume failed", e);
            fail(task, e.getMessage());
        }
    }

    private void clearSegmentErrors(DownloadTask task) {
        if (task.segments != null) for (DownloadTask.FileSegment s : task.segments) s.errorMsg = null;
    }

    private void fail(DownloadTask task, String msg) {
        task.status = DownloadTask.Status.FAILED;
        task.errorMsg = msg;
        if (listener != null) listener.onTaskFailed(task, msg);
    }

    public void cancelTask(String taskId) {
        DownloadTask t = tasks.remove(taskId);
        if (t == null) return;
        t.status = DownloadTask.Status.CANCELLED;
        // 通知当前这一代分片线程尽快退出读取循环
        AtomicBoolean cancelled = currentCancelled(taskId);
        if (cancelled != null) cancelled.set(true);
        runGenerations.remove(taskId);
        genCancelled.remove(taskId);
        deleteDir(partDir(t));   // 使用任务自己的分片目录，与全局设置无关
        if (listener != null) listener.onTaskCancelled(t);
    }

    public DownloadTask getTask(String id) { return tasks.get(id); }
    public List<DownloadTask> getAllTasks() { return new ArrayList<>(tasks.values()); }

    /** 从列表移除任务：清理分片临时目录 + 已产出的成品文件。 */
    public void removeTask(String taskId) {
        DownloadTask t = tasks.remove(taskId);
        if (t == null) return;
        runGenerations.remove(taskId);
        genCancelled.remove(taskId);
        deleteDir(partDir(t));
        // 成品文件的扩展名必须用任务自己的 ext（HLS 现为 mp4），否则会删不到 / 删错文件
        File output = new File(new File(t.outputDir), t.name + "." + t.ext);
        if (output.exists()) output.delete();
    }

    /** 正在下载（RUNNING）的任务数。 */
    public int getActiveCount() {
        int count = 0;
        for (DownloadTask t : tasks.values()) if (t.status == DownloadTask.Status.RUNNING) count++;
        return count;
    }

    /** 是否还有未结束的任务（RUNNING/PAUSED/PENDING）；服务停不停自用它判断。 */
    public boolean hasTaskWork() {
        for (DownloadTask t : tasks.values()) {
            if (t.status == DownloadTask.Status.RUNNING || t.status == DownloadTask.Status.PAUSED || t.status == DownloadTask.Status.PENDING) return true;
        }
        return false;
    }

    public void shutdown() { segmentPool.shutdown(); taskExecutor.shutdown(); }

    // ---------- 工具 ----------
    private int nextGeneration(String taskId) {
        return runGenerations.computeIfAbsent(taskId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private boolean isCurrent(String taskId, int gen) {
        AtomicInteger g = runGenerations.get(taskId);
        return g != null && g.get() == gen;
    }

    /** 当前这一代的中断标记；暂停/取消时只置位它，不影响旧线程持有的旧标记。 */
    private AtomicBoolean currentCancelled(String taskId) {
        AtomicInteger g = runGenerations.get(taskId);
        if (g == null) return null;
        Map<Integer, AtomicBoolean> map = genCancelled.get(taskId);
        return map != null ? map.get(g.get()) : null;
    }
 
    private File partDir(DownloadTask task) {
        File d = new File(new File(task.outputDir), ".parts_" + task.id);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f != null) f.delete();
        dir.delete();
    }

    private boolean looksLikeHls(String url) {
        String lower = url.toLowerCase();
        return lower.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("m3u8");
    }

    private String guessExt(String url) {
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 5) return base.substring(dot + 1).toLowerCase();
        return "mp4";
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) return "video_" + System.currentTimeMillis();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** 探测文件总大小 + 是否支持 HTTP Range（用 GET + Range: bytes=0-0，比 HEAD 兼容性好）。 */
    private Probe probe(String url, Map<String, String> headers) {
        Probe p = new Probe();
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) {
                if (h.getKey() != null && h.getValue() != null) conn.setRequestProperty(h.getKey(), h.getValue());
            }
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            if (code == 206) {
                p.range = true;
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null) {
                    // 形如 bytes 0-0/1234567
                    int slash = cr.indexOf('/');
                    if (slash > 0) try { p.length = Long.parseLong(cr.substring(slash + 1).trim()); } catch (Exception ignored) {}
                }
            } else if (code == 200) {
                String cl = conn.getHeaderField("Content-Length");
                if (cl != null) try { p.length = Long.parseLong(cl.trim()); } catch (Exception ignored) {}
            }
            conn.disconnect();
        } catch (Exception ignored) {}
        return p;
    }

    private static final class Probe {
        long length = -1;  // 文件总大小，-1 未知
        boolean range;     // 服务器是否支持 HTTP Range（返回 206）
    }
}
