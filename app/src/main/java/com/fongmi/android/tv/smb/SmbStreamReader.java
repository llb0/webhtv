package com.fongmi.android.tv.smb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SMB 播放/取帧用的读取器：跨请求共享「最近的几块数据」，每条 HTTP 流自己拿一个文件句柄。
 *
 * <p>背景：IJK 解 AVI 这类老容器时会发成百上千个小 range 请求（读一点就换一次连接）。
 * 如果每个请求都去 SMB 重新 connect + 认证 + tree connect + open，握手开销会把带宽彻底吃掉
 * （实测 95 秒内 2400 次打开、每次只前进 1~4KB）。所以这里：</p>
 *
 * <p>1. 连接由 {@link SmbClient} 的连接池复用，每个请求只做一次「打开文件」；<br>
 * 2. 某个 offset 第一次被读时顺序预读一整块（{@link #CHUNK_BYTES}）挂在 token 上，后续
 * 小请求（哪怕来自新的 HTTP 连接）直接命中内存，SMB 只看到顺序大块读；<br>
 * 3. 缓冲按窗口保留最近 {@link #MAX_WINDOWS} 块而不是单块：拖动时新的 seek 位置和旧流位置
 * 能各自命中自己的窗口，不会互相挤掉来回重读。</p>
 *
 * <p>句柄按流独立，避免多路并发读同一个 smbj File（它并不保证线程安全），也避免 seek 的首字节
 * 被另一条流的预读锁住、超时后播放器从 0 重新开始。</p>
 */
final class SmbStreamReader {

    private static final long CHUNK_BYTES = 2L * 1024 * 1024;
    private static final int MAX_WINDOWS = 2;
    private static final long IDLE_MS = 120_000L;
    private static final int MAX_ENTRIES = 8;

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

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

    private static final class Entry {
        final String host;
        final String share;
        final String path;
        final String username;
        final String password;
        final String domain;
        final List<Window> windows = new ArrayList<>();
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
    }

    private final Entry entry;
    private SmbClient.SharedFile file;
    private long length = -1;
    private boolean closed;

    private SmbStreamReader(Entry entry) {
        this.entry = entry;
    }

    /** 按 token 复用同一个缓存条目：同一场播放里的所有 range 请求共享已读的数据块。 */
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
                        // 有活动流的条目优先保留：正在播的那一路不能因为我们腾地方而被丢掉缓冲。
                        if (value.active > 0) continue;
                        if (value.lastUsed < victimUsed) {
                            victimUsed = value.lastUsed;
                            victim = item.getKey();
                        }
                    }
                    if (victim == null) break;
                    ENTRIES.remove(victim);
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
            if (value.active == 0 && now - value.lastUsed > IDLE_MS) it.remove();
        }
    }

    long length() throws IOException {
        ensureFile();
        return length;
    }

    /**
     * 从 position 读最多 len 字节。只有越过文件末尾才返回 -1；中途读失败抛 IOException，
     * 让代理层能把「提前截断」记进日志，而不是悄悄当成 EOF。
     */
    int read(long position, byte[] target, int offset, int len) throws IOException {
        if (len <= 0) return 0;
        ensureFile();
        if (position < 0) throw new IOException("negative offset " + position);
        if (position >= length) return -1;
        int count = (int) Math.min((long) len, length - position);
        Window window = find(position, count);
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

    /** 顺序读完一整块再挂到条目上；读不满也照实际长度挂，避免重复读同一段。 */
    private Window fill(long position) throws IOException {
        int want = (int) Math.min(CHUNK_BYTES, length - position);
        byte[] data = new byte[Math.max(want, 1)];
        int filled = 0;
        boolean retried = false;
        while (filled < want) {
            int read;
            try {
                read = file.read(data, position + filled, filled, want - filled);
            } catch (RuntimeException e) {
                // 句柄被服务器回收或连接中断：重开一次再读。
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
        Window window = new Window(position, data, filled);
        synchronized (entry) {
            entry.lastUsed = System.currentTimeMillis();
            // 同一位置重复预读（并发请求）时复用先挂上的那块。
            for (Window existing : entry.windows) {
                if (existing.start == position && existing.length >= window.length) return existing;
            }
            entry.windows.add(window);
            while (entry.windows.size() > MAX_WINDOWS) entry.windows.remove(0);
        }
        return window;
    }

    private void ensureFile() throws IOException {
        if (file != null) return;
        if (closed) throw new IOException("reader closed");
        try {
            file = SmbClient.openShared(entry.host, entry.share, entry.path,
                    entry.username, entry.password, entry.domain);
            length = file.length();
        } catch (Exception e) {
            file = null;
            throw new IOException("open " + entry.host + "/" + entry.share + "/" + entry.path + " failed: " + e, e);
        }
    }

    private void reopen() throws IOException {
        closeFile();
        ensureFile();
    }

    /** 关闭这条流的句柄；预读数据留给同一场播放的后续请求继续用。 */
    void close() {
        if (closed) return;
        closed = true;
        closeFile();
        synchronized (ENTRIES) {
            if (entry.active > 0) entry.active--;
            entry.lastUsed = System.currentTimeMillis();
        }
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
}
