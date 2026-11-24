package com.fongmi.android.tv.smb;

/**
 * SMB 播放/取帧的顺序预读缓存。
 *
 * <p>IJK 解 AVI 这类老容器时不会一口气把连接读完，而是「读一小段就换一次 range 请求」。
 * 如果每个请求都去 SMB 重新 connect + 认证 + tree connect + open，握手开销会把带宽彻底吃掉
 * （实测 95 秒内 2400 次 open，每次只前进 1~4KB，缓冲 5 秒就卡住）。这里做两件事：</p>
 *
 * <p>1. 把 {@link SmbClient.SharedFile} 挂在这个 token 上复用，连接与句柄只在播放开始时建一次；<br>
 * 2. 按 offset 顺序预读一整块到内存，后续小请求直接内存命中，SMB 只看到顺序大块读。</p>
 *
 * <p>空闲 {@link #IDLE_MS} 后由下一次 acquire 顺手回收；最多同时保留 {@link #MAX_ENTRIES} 条，
 * 每条一块 {@link #CHUNK_BYTES} 缓冲，内存上限约 16MB。</p>
 */
final class SmbReadAheadCache {

    private static final long CHUNK_BYTES = 4L * 1024 * 1024;
    private static final long IDLE_MS = 120_000L;
    private static final int MAX_ENTRIES = 4;

    private static final java.util.Map<String, SmbReadAheadCache> ENTRIES = new java.util.LinkedHashMap<>();

    private final String host;
    private final String share;
    private final String path;
    private final String username;
    private final String password;
    private final String domain;

    private SmbClient.SharedFile file;
    private long length = -1;
    private byte[] buffer;
    private long bufferStart;
    private int bufferLength;
    private long lastUsed = System.currentTimeMillis();

    private SmbReadAheadCache(String host, String share, String path,
                              String username, String password, String domain) {
        this.host = host;
        this.share = share;
        this.path = path;
        this.username = username;
        this.password = password;
        this.domain = domain;
    }

    static SmbReadAheadCache acquire(String key, String host, String share, String path,
                                     String username, String password, String domain) {
        long now = System.currentTimeMillis();
        synchronized (ENTRIES) {
            sweep(now);
            SmbReadAheadCache cache = ENTRIES.get(key);
            if (cache != null) {
                cache.lastUsed = now;
                return cache;
            }
            while (ENTRIES.size() >= MAX_ENTRIES) {
                String oldest = null;
                long oldestUsed = Long.MAX_VALUE;
                for (java.util.Map.Entry<String, SmbReadAheadCache> item : ENTRIES.entrySet()) {
                    if (item.getValue().lastUsed < oldestUsed) {
                        oldestUsed = item.getValue().lastUsed;
                        oldest = item.getKey();
                    }
                }
                if (oldest == null) break;
                ENTRIES.remove(oldest).close();
            }
            SmbReadAheadCache created = new SmbReadAheadCache(host, share, path, username, password, domain);
            ENTRIES.put(key, created);
            return created;
        }
    }

    /** 只回收空闲条目：正在播放的那条连接不能被抽帧或其它播放打断。 */
    private static void sweep(long now) {
        java.util.Iterator<java.util.Map.Entry<String, SmbReadAheadCache>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            SmbReadAheadCache value = it.next().getValue();
            if (now - value.lastUsed > IDLE_MS) {
                it.remove();
                value.close();
            }
        }
    }

    synchronized long length() throws IOException {
        lastUsed = System.currentTimeMillis();
        ensureFile();
        return length;
    }

    /**
     * 从 position 读最多 len 字节。只有越过文件末尾才返回 -1；中间读失败抛 IOException，
     * 让代理层能把「提前截断」记进日志，而不是悄悄当成 EOF。
     */
    synchronized int read(long position, byte[] target, int offset, int len) throws IOException {
        if (len <= 0) return 0;
        lastUsed = System.currentTimeMillis();
        ensureFile();
        if (position < 0) throw new IOException("negative offset " + position);
        if (position >= length) return -1;
        if (buffer == null || position < bufferStart || position >= bufferStart + bufferLength) fill(position);
        if (bufferLength <= 0) throw new IOException("smb read returned 0 bytes at " + position);
        long available = bufferStart + bufferLength - position;
        int count = (int) Math.min((long) len, available);
        count = (int) Math.min((long) count, length - position);
        System.arraycopy(buffer, (int) (position - bufferStart), target, offset, count);
        return count;
    }

    private void fill(long position) throws IOException {
        if (buffer == null) buffer = new byte[(int) CHUNK_BYTES];
        long want = Math.min((long) buffer.length, length - position);
        if (want <= 0) {
            bufferStart = position;
            bufferLength = 0;
            return;
        }
        int filled = 0;
        boolean retried = false;
        while (filled < want) {
            int read;
            try {
                read = file.read(buffer, position + filled, filled, (int) (want - filled));
            } catch (RuntimeException e) {
                if (retried) throw new IOException("smb read failed at " + (position + filled) + ": " + e, e);
                retried = true;
                reopen();
                continue;
            }
            if (read <= 0) {
                if (retried || filled > 0) break;
                retried = true;
                reopen();
                continue;
            }
            filled += read;
        }
        bufferStart = position;
        bufferLength = filled;
    }

    private void ensureFile() throws IOException {
        if (file != null) return;
        try {
            file = SmbClient.openShared(host, share, path, username, password, domain);
            length = file.length();
        } catch (Exception e) {
            file = null;
            throw new IOException("open " + host + "/" + share + "/" + path + " failed: " + e, e);
        }
    }

    private void reopen() throws IOException {
        closeFile();
        ensureFile();
        if (buffer == null) buffer = new byte[(int) CHUNK_BYTES];
    }

    private void closeFile() {
        if (file != null) {
            try {
                file.close();
            } catch (Exception ignored) {
            }
            file = null;
        }
    }

    synchronized void close() {
        closeFile();
        buffer = null;
        bufferLength = 0;
        bufferStart = 0;
        length = -1;
    }
}
