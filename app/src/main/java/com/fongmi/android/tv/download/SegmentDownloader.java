package com.fongmi.android.tv.download;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单个分片下载器：支持 HTTP Range 断点续传 + 自定义 headers。 */
public class SegmentDownloader {

    public interface Listener {
        void onProgress(DownloadTask.FileSegment seg, long bytesDownloaded);
        void onComplete(DownloadTask.FileSegment seg);
        void onError(DownloadTask.FileSegment seg, String msg);
    }

    private static final int BUF = 64 * 1024;

    public static void download(DownloadTask.FileSegment seg, Map<String, String> headers,
                                AtomicBoolean cancelled, Listener listener) {
        try {
            // 断点续传：如果 .part 文件已存在，读取当前大小作为续传偏移
            File part = new File(seg.partFile);
            long resumeBytes = part.exists() ? part.length() : 0;
            long requestStart = seg.start + resumeBytes;
            long requestEnd = seg.end;

            HttpURLConnection conn = (HttpURLConnection) new URL(seg.url).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) {
                if (h.getKey() != null && h.getValue() != null) conn.setRequestProperty(h.getKey(), h.getValue());
            }

            // 直链分片: end >= start 时用两端 Range；hls 分片: end = -1，用开区间
            if (requestEnd >= requestStart) {
                conn.setRequestProperty("Range", "bytes=" + requestStart + "-" + requestEnd);
            } else if (resumeBytes > 0) {
                conn.setRequestProperty("Range", "bytes=" + requestStart + "-");
            }
            conn.setRequestProperty("Accept-Encoding", "identity");  // 禁止 gzip，保证字节数准确

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                // 服务器不支持 Range；已有残留 part 则丢弃重下
                if (resumeBytes > 0) { resumeBytes = 0; part.delete(); }
            } else if (code == 416) {
                // Range Not Satisfiable —— 分片已完整，跳过
                seg.downloaded = seg.end - seg.start + 1;
                seg.done = true;
                if (listener != null) listener.onComplete(seg);
                return;
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code);
            }

            long localDownloaded = resumeBytes;  // 本分片内已下载字节（不含全局起始偏移）
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(part, resumeBytes > 0);
                 BufferedOutputStream out = new BufferedOutputStream(fos, BUF)) {
                byte[] buf = new byte[BUF];
                int read;
                while ((read = in.read(buf)) != -1) {
                    if (cancelled != null && cancelled.get()) throw new IOException("Cancelled");
                    out.write(buf, 0, read);
                    localDownloaded += read;
                    seg.downloaded = localDownloaded;
                    if (listener != null) listener.onProgress(seg, localDownloaded);
                }
            }
            seg.downloaded = (seg.end >= 0) ? (seg.end - seg.start + 1) : localDownloaded;
            seg.done = true;
            if (listener != null) listener.onComplete(seg);
        } catch (Exception e) {
            if (seg.errorMsg == null) seg.errorMsg = e.getMessage();
            if (listener != null) listener.onError(seg, e.getMessage());
        }
    }
}
