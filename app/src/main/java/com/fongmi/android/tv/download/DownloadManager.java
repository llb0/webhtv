package com.fongmi.android.tv.download;

import android.util.Log;

import com.github.catvod.utils.Path;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadManager {

    private static final String TAG = "DownloadManager";
    private static final DownloadManager INSTANCE = new DownloadManager();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public static DownloadManager get() { return INSTANCE; }

    public interface ProgressListener {
        void onProgress(String taskId, int percent, long downloaded, long total);
        void onComplete(String taskId);
        void onFailed(String taskId, String reason);
    }

    private ProgressListener listener;
    public void setListener(ProgressListener l) { this.listener = l; }

    public DownloadTask enqueue(String url, String title, Map<String, String> headers) {
        DownloadTask.Type type = url.toLowerCase().contains(".m3u8")
                ? DownloadTask.Type.HLS : DownloadTask.Type.DIRECT;
        DownloadTask task = new DownloadTask(url, title, type);
        tasks.put(task.getId(), task);
        start(task, headers);
        return task;
    }

    public void cancel(String taskId) {
        AtomicBoolean flag = cancelFlags.remove(taskId);
        if (flag != null) flag.set(true);
        DownloadTask t = tasks.get(taskId);
        if (t != null) t.setStatus(DownloadTask.Status.CANCELLED);
    }

    private void start(DownloadTask task, Map<String, String> headers) {
        task.setStatus(DownloadTask.Status.RUNNING);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(task.getId(), cancelFlag);

        executor.submit(() -> {
            try {
                if (task.getType() == DownloadTask.Type.HLS) {
                    runHls(task, headers, cancelFlag);
                } else {
                    runDirect(task, headers, cancelFlag);
                }
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                task.setStatus(DownloadTask.Status.FAILED);
                if (listener != null) listener.onFailed(task.getId(), e.getMessage());
            }
        });
    }

    // ========== DIRECT ==========
    private void runDirect(DownloadTask task, Map<String, String> headers, AtomicBoolean cancelFlag) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(task.getUrl()).openConnection();
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
            conn.setRequestProperty(e.getKey(), e.getValue());
        conn.setRequestProperty("Range", "bytes=0-");
        conn.connect();

        long contentLength = conn.getContentLengthLong();
        if (contentLength <= 0) contentLength = conn.getHeaderFieldLong("Content-Length", 0);
        task.setTotalSize(contentLength);

        String ext = guessExt(task.getUrl(), conn);
        String outName = safeName(task.getTitle()) + ext;
        File outDir = Path.download();
        File outFile = new File(outDir, outName);
        task.setOutputPath(outFile.getAbsolutePath());

        // split into N segments
        int n = task.getThreadCount();
        if (contentLength > 0) {
            long segSize = contentLength / n;
            List<DownloadTask.FileSegment> segs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                long start = i * segSize;
                long end = (i == n - 1) ? contentLength - 1 : start + segSize - 1;
                String temp = new File(outDir, outName + ".part" + i).getAbsolutePath();
                segs.add(new DownloadTask.FileSegment(i, start, end, temp));
            }
            task.setSegments(segs);
        } else {
            // unknown size — single segment
            String temp = new File(outDir, outName + ".part0").getAbsolutePath();
            DownloadTask.FileSegment seg = new DownloadTask.FileSegment(0, 0, Long.MAX_VALUE, temp);
            seg.downloaded = 0;
            task.setSegments(Collections.singletonList(seg));
        }

        // download all segments in parallel
        CountDownLatch latch = new CountDownLatch(task.getSegments().size());
        SegmentDownloader.SegmentListener segListener = new SegmentDownloader.SegmentListener() {
            @Override public void onProgress(long delta) {
                task.addDownloaded(delta);
                if (listener != null) listener.onProgress(task.getId(), task.getProgress(),
                        task.getDownloadedSize(), task.getTotalSize());
            }
            @Override public void onSegmentComplete(DownloadTask.FileSegment seg) { latch.countDown(); }
            @Override public void onError(Exception e) { latch.countDown(); }
        };

        for (DownloadTask.FileSegment seg : task.getSegments()) {
            executor.submit(new SegmentDownloader(task, seg, headers, cancelFlag, segListener));
        }
        latch.await();

        if (cancelFlag.get()) return;

        // merge
        mergeParts(task, outFile);
        if (listener != null) listener.onComplete(task.getId());
        task.setStatus(DownloadTask.Status.COMPLETED);
        tasks.remove(task.getId());
        cancelFlags.remove(task.getId());
    }

    // ========== HLS ==========
    private void runHls(DownloadTask task, Map<String, String> headers, AtomicBoolean cancelFlag) throws Exception {
        HlsParser.ParseResult result = HlsParser.parse(task.getUrl(), headers);
        if (!result.isHls || result.segments.isEmpty()) {
            throw new IllegalStateException("No TS segments found");
        }

        String outName = safeName(task.getTitle()) + ".ts";
        File outDir = Path.download();
        File outFile = new File(outDir, outName);
        task.setOutputPath(outFile.getAbsolutePath());

        List<DownloadTask.FileSegment> segs = new ArrayList<>();
        for (int i = 0; i < result.segments.size(); i++) {
            String temp = new File(outDir, outName + ".seg" + i).getAbsolutePath();
            // for HLS, we don't split each TS further; each TS is a "segment" as a whole file
            segs.add(new DownloadTask.FileSegment(i, 0, 0, temp));
            // store actual URL in a side-channel by reusing tempFile path name convention
            // simpler: stash in a Map
            segUrls.put(temp, result.segments.get(i));
        }
        task.setSegments(segs);

        CountDownLatch latch = new CountDownLatch(result.segments.size());
        SegmentDownloader.SegmentListener segListener = new SegmentDownloader.SegmentListener() {
            @Override public void onProgress(long delta) {
                task.addDownloaded(delta);
                if (listener != null) listener.onProgress(task.getId(), -1,
                        task.getDownloadedSize(), task.getTotalSize());
            }
            @Override public void onSegmentComplete(DownloadTask.FileSegment seg) { latch.countDown(); }
            @Override public void onError(Exception e) { latch.countDown(); }
        };

        // Download TS segments directly (no Range splitting for HLS)
        for (int i = 0; i < result.segments.size(); i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    if (cancelFlag.get()) return;
                    String tsUrl = result.segments.get(idx);
                    HttpURLConnection c = (HttpURLConnection) new URL(tsUrl).openConnection();
                    if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                        c.setRequestProperty(e.getKey(), e.getValue());
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.connect();
                    File segFile = new File(segs.get(idx).tempFile);
                    try (InputStream in = c.getInputStream();
                         FileOutputStream fos = new FileOutputStream(segFile)) {
                        byte[] buf = new byte[65536];
                        int n;
                        long localDelta = 0;
                        while ((n = in.read(buf)) > 0) {
                            if (cancelFlag.get()) return;
                            fos.write(buf, 0, n);
                            localDelta += n;
                        }
                    }
                    segListener.onProgress(new File(segs.get(idx).tempFile).length());
                    segListener.onSegmentComplete(segs.get(idx));
                } catch (Exception e) {
                    segListener.onError(e);
                }
            });
        }
        latch.await();

        if (cancelFlag.get()) return;

        // concatenate all TS
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            for (DownloadTask.FileSegment seg : segs) {
                File f = new File(seg.tempFile);
                if (!f.exists()) continue;
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                }
                f.delete();
            }
        }

        if (listener != null) listener.onComplete(task.getId());
        task.setStatus(DownloadTask.Status.COMPLETED);
        tasks.remove(task.getId());
        cancelFlags.remove(task.getId());
    }

    private final Map<String, String> segUrls = new HashMap<>(); // unused, kept for future

    private void mergeParts(DownloadTask task, File outFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            for (DownloadTask.FileSegment seg : task.getSegments()) {
                File f = new File(seg.tempFile);
                if (!f.exists()) continue;
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                }
                f.delete();
            }
        }
    }

    private String guessExt(String url, HttpURLConnection conn) {
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        int dot = path.lastIndexOf('.');
        if (dot > 0 && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase();
            if (ext.length() <= 6) return "." + ext;
        }
        String ct = conn.getContentType();
        if (ct != null) {
            if (ct.contains("mp4")) return ".mp4";
            if (ct.contains("mpeg") || ct.contains("ts")) return ".ts";
            if (ct.contains("webm")) return ".webm";
            if (ct.contains("x-matroska")) return ".mkv";
        }
        return ".mp4";
    }

    private String safeName(String name) {
        if (name == null || name.isEmpty()) name = "download";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public void shutdown() { executor.shutdown(); }
}
