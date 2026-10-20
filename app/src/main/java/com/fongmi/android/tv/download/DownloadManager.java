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

/** 全局下载管理器：单例，队列管理，4 线程并发，整合直链多线程分片 + HLS 分片下载。 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static final int DEFAULT_PARALLEL = 4;

    private static class Holder { static final DownloadManager INSTANCE = new DownloadManager(); }
    public static DownloadManager get() { return Holder.INSTANCE; }

    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService segmentPool = Executors.newFixedThreadPool(DEFAULT_PARALLEL);
    private volatile Listener listener;
    private final AtomicInteger activeCount = new AtomicInteger(0);

    public interface Listener {
        void onTaskStarted(DownloadTask task);
        void onTaskProgress(DownloadTask task, long bytes, long total);
        void onTaskCompleted(DownloadTask task, File output);
        void onTaskFailed(DownloadTask task, String msg);
        void onTaskCancelled(DownloadTask task);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 创建任务，outputDir 可传 null（此时使用 Setting 中配置的下载目录）。 */
    public DownloadTask addTask(String name, String url, Map<String, String> headers, File outputDir) {
        File dir = outputDir;
        if (dir == null) {
            dir = new File(Setting.getDownloadDir());
            dir.mkdirs();
        }
        DownloadTask task = new DownloadTask();
        task.id = UUID.randomUUID().toString();
        task.name = sanitizeFilename(name);
        task.url = url;
        task.headers = headers != null ? new java.util.HashMap<>(headers) : new java.util.HashMap<>();
        task.type = looksLikeHls(url) ? DownloadTask.Type.HLS : DownloadTask.Type.DIRECT;
        task.ext = task.type == DownloadTask.Type.HLS ? "ts" : guessExt(url);
        tasks.put(task.id, task);
        cancelFlags.put(task.id, new AtomicBoolean(false));

        // 异步初始化（解析 m3u8 / 获取直链大小 / 分片规划）
        final File finalDir = dir;
        taskExecutor.submit(() -> initAndStart(task, finalDir));
        return task;
    }

    private void initAndStart(DownloadTask task, File outputDir) {
        try {
            task.partDirPath = partDir(task, outputDir).getAbsolutePath();
            if (task.type == DownloadTask.Type.HLS) {
                initHls(task, outputDir);
            } else {
                initDirect(task, outputDir);
            }
            task.status = DownloadTask.Status.RUNNING;
            activeCount.incrementAndGet();
            if (listener != null) listener.onTaskStarted(task);
            runSegments(task, outputDir);
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            task.status = DownloadTask.Status.FAILED;
            task.errorMsg = e.getMessage();
            cancelFlags.remove(task.id);
            if (listener != null) listener.onTaskFailed(task, e.getMessage());
        }
    }

    private void initHls(DownloadTask task, File outputDir) throws Exception {
        HlsParser.Result result = HlsParser.parse(task.url, task.headers);
        List<DownloadTask.FileSegment> segs = new ArrayList<>();
        // fMP4（EXT-X-MAP）：初始化分片必须排在所有媒体分片之前，否则合并结果无法播放
        if (result.initUrl != null) {
            DownloadTask.FileSegment init = new DownloadTask.FileSegment();
            init.index = -1;
            init.url = result.initUrl;
            init.start = 0;
            init.end = -1;
            init.partFile = new File(partDir(task, outputDir), "seg_init.part").getAbsolutePath();
            segs.add(init);
        }
        Map<String, byte[]> keyCache = new HashMap<>();
        for (int i = 0; i < result.segmentUrls.size(); i++) {
            DownloadTask.FileSegment s = new DownloadTask.FileSegment();
            s.index = i;
            s.url = result.segmentUrls.get(i);
            s.start = 0;
            s.end = -1;
            s.partFile = new File(partDir(task, outputDir), "seg_" + i + ".part").getAbsolutePath();
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
        task.totalSize = -1;  // hls 总大小未知，靠分片实际下载量累加
        // fMP4 合并后就是标准 mp4；纯 TS 保持 .ts（TS 无法在无 ffmpeg 的情况下重封装为 mp4）
        task.ext = result.initUrl != null ? "mp4" : "ts";
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

    private void initDirect(DownloadTask task, File outputDir) throws Exception {
        Probe p = probe(task.url, task.headers);
        task.totalSize = p.length;  // -1 表示大小未知
        // 仅当服务器支持 Range 且总大小已知时才多线程分片；否则单线程整文件下载
        boolean canSplit = p.range && p.length > 0;
        if (!canSplit) {
            task.segments = new DownloadTask.FileSegment[]{newSegment(task, outputDir, 0, 0, -1)};
            task.totalSegments = 1;
        } else {
            int n = DEFAULT_PARALLEL;
            long perSeg = (p.length + n - 1) / n;
            DownloadTask.FileSegment[] arr = new DownloadTask.FileSegment[n];
            for (int i = 0; i < n; i++) {
                long start = i * perSeg;
                long end = Math.min(p.length - 1, (i + 1) * perSeg - 1);
                arr[i] = newSegment(task, outputDir, i, start, end);
            }
            task.segments = arr;
            task.totalSegments = n;
        }
    }

    private DownloadTask.FileSegment newSegment(DownloadTask task, File outputDir, int index, long start, long end) {
        DownloadTask.FileSegment s = new DownloadTask.FileSegment();
        s.index = index;
        s.url = task.url;
        s.start = start;
        s.end = end;
        s.partFile = new File(partDir(task, outputDir), "seg_" + index + ".part").getAbsolutePath();
        return s;
    }

    private void runSegments(DownloadTask task, File outputDir) {
        AtomicBoolean cancelled = cancelFlags.computeIfAbsent(task.id, k -> new AtomicBoolean(false));
        final AtomicInteger finished = new AtomicInteger(0);   // 完成 + 失败总数，用于触发合并
        final AtomicInteger succeeded = new AtomicInteger(0);  // 成功数，用于进度展示
        for (final DownloadTask.FileSegment seg : task.segments) {
            segmentPool.submit(() -> SegmentDownloader.download(seg, task.headers, cancelled,
                    new SegmentDownloader.Listener() {
                        @Override public void onProgress(DownloadTask.FileSegment s, long bytesDownloaded) {
                            long total = 0;
                            for (DownloadTask.FileSegment x : task.segments) total += x.downloaded;
                            task.downloadedBytes = total;
                            if (listener != null) listener.onTaskProgress(task, total, task.totalSize);
                        }
                        @Override public void onComplete(DownloadTask.FileSegment s) {
                            task.completedSegments = succeeded.incrementAndGet();
                            // hls 场景: 记录实际分片大小
                            if (task.type == DownloadTask.Type.HLS) {
                                try { s.end = new File(s.partFile).length(); } catch (Exception ignored) {}
                            }
                            if (finished.incrementAndGet() >= task.totalSegments) {
                                activeCount.decrementAndGet();
                                mergeSegments(task, outputDir);
                            }
                        }
                        @Override public void onError(DownloadTask.FileSegment s, String msg) {
                            // 单分片失败不立即放弃；等所有分片结束再统一判定（避免任务卡死）
                            if (finished.incrementAndGet() >= task.totalSegments) {
                                activeCount.decrementAndGet();
                                mergeSegments(task, outputDir);
                            }
                        }
                    }));
        }
    }

    private void mergeSegments(DownloadTask task, File outputDir) {
        if (task.status == DownloadTask.Status.CANCELLED) return;  // 已取消，不再上报结果
        boolean allDone = true;
        for (DownloadTask.FileSegment s : task.segments) if (!s.done) { allDone = false; break; }
        if (!allDone) {
            task.status = DownloadTask.Status.FAILED;
            task.errorMsg = "Some segments failed";
            cancelFlags.remove(task.id);
            if (listener != null) listener.onTaskFailed(task, task.errorMsg);
            return;
        }
        File output = new File(outputDir, task.name + "." + task.ext);
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
            // 清理 partDir
            partDir(task, outputDir).delete();
            task.status = DownloadTask.Status.COMPLETED;
            cancelFlags.remove(task.id);
            if (listener != null) listener.onTaskCompleted(task, output);
        } catch (IOException e) {
            task.status = DownloadTask.Status.FAILED;
            task.errorMsg = e.getMessage();
            cancelFlags.remove(task.id);
            if (listener != null) listener.onTaskFailed(task, e.getMessage());
        }
    }

    public void cancelTask(String taskId) {
        DownloadTask t = tasks.remove(taskId);
        if (t == null) return;
        t.status = DownloadTask.Status.CANCELLED;
        // 通知各分片线程尽快退出读取循环
        AtomicBoolean cancelled = cancelFlags.remove(taskId);
        if (cancelled != null) cancelled.set(true);
        // 清理 part 文件（使用任务自己的分片目录，兼容自定义下载路径）
        File partDir = t.partDirPath != null ? new File(t.partDirPath) : partDir(t, new File(Setting.getDownloadDir()));
        File[] files = partDir.listFiles();
        if (files != null) for (File f : files) if (f != null) f.delete();
        partDir.delete();
        if (listener != null) listener.onTaskCancelled(t);
    }

    public DownloadTask getTask(String id) { return tasks.get(id); }
    public List<DownloadTask> getAllTasks() { return new ArrayList<>(tasks.values()); }

    /** 从列表移除任务（已完成/失败/取消后的删除），不触碰正在下载的临时文件。 */
    public void removeTask(String taskId) {
        DownloadTask t = tasks.remove(taskId);
        if (t == null) return;
        cancelFlags.remove(taskId);
        if (t.partDirPath != null) {
            File dir = new File(t.partDirPath);
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) if (f != null) f.delete();
            dir.delete();
        }
    }

    public int getActiveCount() { return activeCount.get(); }
    public void shutdown() { segmentPool.shutdown(); taskExecutor.shutdown(); }

    // ---------- 工具 ----------
    private File partDir(DownloadTask task, File outputDir) {
        File d = new File(outputDir, ".parts_" + task.id);
        if (!d.exists()) d.mkdirs();
        return d;
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
