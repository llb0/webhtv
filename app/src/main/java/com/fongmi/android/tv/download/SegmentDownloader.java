package com.fongmi.android.tv.download;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SegmentDownloader implements Runnable {

    private final DownloadTask task;
    private final DownloadTask.FileSegment segment;
    private final Map<String, String> headers;
    private final AtomicBoolean cancelFlag;
    private final SegmentListener listener;

    public interface SegmentListener {
        void onProgress(long delta);
        void onSegmentComplete(DownloadTask.FileSegment seg);
        void onError(Exception e);
    }

    public SegmentDownloader(DownloadTask task, DownloadTask.FileSegment seg,
                             Map<String, String> headers, AtomicBoolean cancelFlag,
                             SegmentListener listener) {
        this.task = task;
        this.segment = seg;
        this.headers = headers;
        this.cancelFlag = cancelFlag;
        this.listener = listener;
    }

    @Override
    public void run() {
        File temp = new File(segment.tempFile);
        try {
            long already = 0;
            if (temp.exists()) already = temp.length();

            HttpURLConnection conn = (HttpURLConnection) new URL(task.getUrl()).openConnection();
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                conn.setRequestProperty(e.getKey(), e.getValue());
            long rangeStart = segment.start + already;
            long rangeEnd = segment.end;
            if (rangeStart <= rangeEnd) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-" + rangeEnd);
            }
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.connect();

            int code = conn.getResponseCode();
            InputStream in;
            if (code == 206) {
                in = conn.getInputStream();
            } else if (code == 200 && already == 0) {
                // server doesn't support range — re-download whole segment
                segment.downloaded = segment.start;
                in = conn.getInputStream();
            } else if (code == 416) {
                // segment already fully downloaded
                listener.onSegmentComplete(segment);
                return;
            } else {
                throw new IOException("HTTP " + code);
            }

            long delta = 0;
            try (FileOutputStream fos = new FileOutputStream(temp, true)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelFlag.get()) return;
                    fos.write(buf, 0, n);
                    segment.downloaded += n;
                    delta += n;
                    if (listener != null) listener.onProgress(n);
                }
            }
            if (listener != null) listener.onSegmentComplete(segment);

        } catch (Exception e) {
            if (!cancelFlag.get() && listener != null) listener.onError(e);
        }
    }
}
