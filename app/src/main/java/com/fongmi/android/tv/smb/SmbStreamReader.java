package com.fongmi.android.tv.smb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SMB 播放/取帧用的读取器：句柄池 + 跨请求共享的数据块。
 *
 * <p>背景：IJK 解 AVI 这类老容器时会发成百上千个小 range 请求（读一点就换一次连接，
 * 实测 95 秒内 2400 次打开、每次只前进 1~4KB）。如果每个请求都去 SMB 重新
 * connect + 认证 + tree connect + open，光握手就会把时间吃光，带宽再高也用不上。</p>
 *
 * <p>所以这里分两层：</p>
 * <p>1. 连接由 {@link SmbClient} 的连接池复用；打开的文件句柄也按 token 留 {@link #MAX_HANDLES}
 * 个在池里反复借还，不再每个请求 CREATE/CLOSE 一轮；<br>
 * 2. 某个 offset 第一次被读时顺序预读一整块（{@link #CHUNK_BYTES}）挂在 token 上，保留最近
 * {@link #MAX_WINDOWS} 块，后续小请求（哪怕来自新的 HTTP 连接）直接命中内存。</p>
 *
 * <p>多块窗口而不是单块：老容器同时会读片头、正文和片尾索引，只有一块会互相挤掉来回重读。
 * 每个句柄同一时刻只借给一条流，避免并发读同一个 smbj File（它不保证线程安全）。</p>
 */
final class SmbStreamReader {

    private static final long CHUNK_BYTES = 1024L * 1024;
    private static final int MAX_WINDOWS = 8;
    private static final int MAX_HANDLES = 3;
    private static final long IDLE_MS = 120_000L;
    private static final int MAX_ENTRIES = 4;

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    /** 预读命中/未命中统计：卡顿到底是我们每次未命中就去 SMB 读，还是播放器自己要得太碎。 */
    private static long statWindowStart;
    private static int statFill;
    private static int statHit;
    private static long statFillBytes;

    private static final class Window {
        final long start;
        final byte[] data;
        final int length;

        Window(long start, byte[] data, int length) {
            this.start = start;
            this.data = data;
            this.length = length;
        }

        boolean covers(long position, int count) {
            return position >= start && position + count <= start + length;
        }
    }

    /** 借出的句柄：pooled 为 true 时归还到池里，否则用完就关（池满时的临时句柄）。 */
    private static final class Lease {
        final SmbClient.SharedFile file;
        final boolean pooled;

        Lease(SmbClient.SharedFile file, boolean pooled) {
            this.file = file;
            this.pooled = pooled;
        }
    }

    private static final class Entry {
        final String host;
        final String share;
        final String path;
        final String username;
        final String password;
        final String domain;
        final List<Window> windows = new ArrayList<>();
        final List<SmbClient.SharedFile> idle = new ArrayList<>();
        long length = -1;
        boolean closed;
        int handles;
        int active;
        long lastUsed = System.currentTimeMillis();

        Entry(String host, String share, String path, String username, String password, String domain) {
            this.host = host;
            this.share = share;
            this.path = path;
            this.username = username;
            this.password = password;
            this.domain = domain;
        }

        SmbClient.SharedFile open() throws IOException {
            try {
                SmbClient.SharedFile file = SmbClient.openShared(host, share, path, username, password, domain);
                length = file.length();
                return file;
            } catch (Exception e) {
                throw new IOException("open " + host + "/" + share + "/" + path + " failed: " + e, e);
            }
        }

        synchronized Lease borrow() throws IOException {
            if (closed) throw new IOException("smb cache entry closed");
            lastUsed = System.currentTimeMillis();
            if (!idle.isEmpty()) return new Lease(idle.remove(idle.size() - 1), true);
            if (handles < MAX_HANDLES) {
                SmbClient.SharedFile file = open();
                handles++;
                return new Lease(file, true);
            }
            // 池已满：临时开一个，归还时直接关掉，避免把池撑大。
            return new Lease(open(), false);
        }

        synchronized void giveBack(Lease lease) {
            lastUsed = System.currentTimeMillis();
            if (!closed && lease.pooled && idle.size() < MAX_HANDLES) idle.add(lease.file);
            else closeQuietly(lease.file);
        }

        void discard(Lease lease) {
            synchronized (this) {
                if (lease.pooled) handles--;
            }
            closeQuietly(lease.file);
        }

        synchronized void closeIdle() {
            closed = true;
            for (SmbClient.SharedFile file : idle) closeQuietly(file);
            idle.clear();
            handles = 0;
            windows.clear();
            length = -1;
        }
    }

    private final Entry entry;
    private Lease lease;
    private boolean closed;

    private SmbStreamReader(Entry entry) {
        this.entry = entry;
    }

    /** 按 token 复用同一份缓存条目：同一场播放里的所有 range 请求共享已读的数据块与句柄。 */
    static SmbStreamReader open(String key, String host, String share, String path,
                                String username, String password, String domain) {
        long now = System.currentTimeMillis();
        synchronized (ENTRIES) {
            sweep(now);
            Entry entry = ENTRIES.get(key);
            if (entry == null) {
                while (ENTRIES.size() >= MAX_ENTRIES) {
                    String victim = null;
                    long victimUsed = Long.MAX_VALUE;
                    for (Map.Entry<String, Entry> item : ENTRIES.entrySet()) {
                        Entry value = item.getValue();
                        // 有活动流的条目优先保留：正在播的那一路不能被我们腾地方。
                        if (value.active > 0) continue;
                        if (value.lastUsed < victimUsed) {
                            victimUsed = value.lastUsed;
                            victim = item.getKey();
                        }
                    }
                    if (victim == null) break;
                    ENTRIES.remove(victim).closeIdle();
                }
                entry = new Entry(host, share, path, username, password, domain);
                ENTRIES.put(key, entry);
            }
            entry.active++;
            entry.lastUsed = now;
            return new SmbStreamReader(entry);
        }
    }

    private static void sweep(long now) {
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            Entry value = it.next().getValue();
            // 活动流每次读都会刷新 lastUsed，真正空闲才回收；这样句柄/内存不会因为
            // 某个请求异常退出没归还而长期泄漏。
            if (now - value.lastUsed > IDLE_MS) {
                it.remove();
                value.closeIdle();
            }
        }
    }

    long length() throws IOException {
        ensureLease();
        return entry.length;
    }

    /**
     * 从 position 读最多 len 字节。只有越过文件末尾才返回 -1；中途读失败抛 IOException，
     * 让代理层能把「提前截断」记进日志，而不是悄悄当成 EOF。
     */
    int read(long position, byte[] target, int offset, int len) throws IOException {
        if (len <= 0) return 0;
        ensureLease();
        if (position < 0) throw new IOException("negative offset " + position);
        if (position >= entry.length) return -1;
        int count = (int) Math.min((long) len, entry.length - position);
        Window window = find(position, count);
        if (window != null) recordStat(false, 0);
        if (window == null) {
            window = fill(position);
            if (window.length <= 0) throw new IOException("smb read returned 0 bytes at " + position);
            if (!window.covers(position, count)) count = (int) Math.min((long) count, window.length);
        }
        System.arraycopy(window.data, (int) (position - window.start), target, offset, count);
        return count;
    }

    private Window find(long position, int count) {
        synchronized (entry) {
            for (Window window : entry.windows) {
                if (window.covers(position, count)) {
                    entry.lastUsed = System.currentTimeMillis();
                    return window;
                }
            }
            return null;
        }
    }

    /** 顺序读一整块挂到条目上；读不满也照实际长度挂，避免反复重读同一段。 */
    private Window fill(long position) throws IOException {
        int want = (int) Math.min(CHUNK_BYTES, entry.length - position);
        byte[] data = new byte[Math.max(want, 1)];
        int filled = 0;
        boolean retried = false;
        while (filled < want) {
            int read;
            try {
                read = lease.file.read(data, position + filled, filled, want - filled);
            } catch (RuntimeException e) {
                // 句柄被服务器回收或连接中断：换一个句柄再读。
                if (retried) throw new IOException("smb read failed at " + (position + filled) + ": " + e, e);
                retried = true;
                recycleLease();
                continue;
            }
            if (read <= 0) {
                if (retried || filled > 0) break;
                retried = true;
                recycleLease();
                continue;
            }
            filled += read;
        }
        recordStat(true, filled);
        Window window = new Window(position, data, filled);
        synchronized (entry) {
            entry.lastUsed = System.currentTimeMillis();
            // 同一位置重复预读（并发请求）时复用先挂上的那块。
            for (Window existing : entry.windows) {
                if (existing.start == position && existing.length >= window.length) return existing;
            }
            entry.windows.add(window);
            while (entry.windows.size() > MAX_WINDOWS) {
                // 片头那块（start=0）常驻：老容器每读一段都要回跳片头取索引/关键帧头部，
                // 让它参与 LRU 会被正文反复挤掉，等于每次都重读一遍。
                int victim = -1;
                for (int i = 0; i < entry.windows.size(); i++) {
                    if (entry.windows.get(i).start != 0) {
                        victim = i;
                        break;
                    }
                }
                if (victim < 0) break;
                entry.windows.remove(victim);
            }
        }
        return window;
    }

    private static void recordStat(boolean fill, int bytes) {
        if (!com.github.catvod.crawler.SpiderDebug.isEnabled()) return;
        long now = System.currentTimeMillis();
        synchronized (SmbStreamReader.class) {
            if (fill) {
                statFill++;
                statFillBytes += bytes;
            } else {
                statHit++;
            }
            if (statWindowStart == 0) statWindowStart = now;
            if (now - statWindowStart < 3000) return;
            int fills = statFill;
            int hits = statHit;
            long bytesTotal = statFillBytes;
            statFill = 0;
            statHit = 0;
            statFillBytes = 0;
            statWindowStart = now;
            com.github.catvod.crawler.SpiderDebug.log("smb-proxy",
                    "reader-stat fill=%d bytes=%d hit=%d", fills, bytesTotal, hits);
        }
    }

    private void ensureLease() throws IOException {
        if (lease != null) return;
        if (closed) throw new IOException("reader closed");
        lease = entry.borrow();
    }

    private void recycleLease() throws IOException {
        if (lease != null) {
            entry.discard(lease);
            lease = null;
        }
        ensureLease();
    }

    /** 归还句柄，并把这条流计入结束；预读数据留给同一场播放的后续请求继续用。 */
    void close() {
        if (closed) return;
        closed = true;
        if (lease != null) {
            entry.giveBack(lease);
            lease = null;
        }
        synchronized (ENTRIES) {
            if (entry.active > 0) entry.active--;
            entry.lastUsed = System.currentTimeMillis();
        }
    }

    private static void closeQuietly(SmbClient.SharedFile file) {
        if (file == null) return;
        try {
            file.close();
        } catch (Exception ignored) {
        }
    }
}
