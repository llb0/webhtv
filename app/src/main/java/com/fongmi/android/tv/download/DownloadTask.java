package com.fongmi.android.tv.download;

import java.util.ArrayList;
import java.util.List;

public class DownloadTask {

    public enum Type { DIRECT, HLS }
    public enum Status { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

    private String id;
    private String url;
    private String title;
    private String outputPath;
    private Type type;
    private Status status;
    private long totalSize;
    private long downloadedSize;
    private int threadCount = 4;
    private List<FileSegment> segments = new ArrayList<>();

    public DownloadTask(String url, String title, Type type) {
        this.url = url;
        this.title = title;
        this.type = type;
        this.status = Status.PENDING;
        this.id = String.valueOf(System.currentTimeMillis());
    }

    public String getId() { return id; }
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String p) { this.outputPath = p; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getTotalSize() { return totalSize; }
    public void setTotalSize(long size) { this.totalSize = size; }
    public long getDownloadedSize() { return downloadedSize; }
    public void addDownloaded(long delta) { this.downloadedSize += delta; }
    public int getThreadCount() { return threadCount; }
    public void setThreadCount(int n) { this.threadCount = n; }
    public List<FileSegment> getSegments() { return segments; }
    public void setSegments(List<FileSegment> segs) { this.segments = segs; }

    public int getProgress() {
        if (totalSize <= 0) return 0;
        return (int) (downloadedSize * 100 / totalSize);
    }

    public static class FileSegment {
        public int index;
        public long start;
        public long end;
        public long downloaded;
        public String tempFile;

        public FileSegment(int index, long start, long end, String tempFile) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.downloaded = start;
            this.tempFile = tempFile;
        }

        public long getSize() { return end - start + 1; }
        public boolean isComplete() { return downloaded >= end; }
    }
}
