package com.fongmi.android.tv.smb;

/**
 * SMB2/SMB3 访问层：负责连接服务器、列出共享/目录、打开远程文件。
 *
 * <p>浏览和播放共用这一层。用户名留空时先尝试匿名会话，再回退 Guest；密码和域只保存在
 * 当前浏览会话的内存字段里，不写入 SharedPreferences。目录列表只返回子目录和可识别媒体，
 * 播放由 {@link SmbPlaybackProxy} 暴露成本机 HTTP 地址后交给播放器。</p>
 */

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;
import com.rapid7.client.dcerpc.mssrvs.ServerService;
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo0;
import com.rapid7.client.dcerpc.transport.RPCTransport;
import com.rapid7.client.dcerpc.transport.SMBTransportFactories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public final class SmbClient {

    private SmbClient() {
    }

    private static AuthenticationContext auth(String username, String password, String domain) {
        // 空用户名使用真正的 SMB 匿名会话；部分 NAS 会把 Guest 与 Anonymous 区分处理。
        if (username == null || username.trim().isEmpty()) return AuthenticationContext.anonymous();
        return new AuthenticationContext(username, (password == null ? "" : password).toCharArray(),
                domain == null || domain.isEmpty() ? null : domain);
    }

    private static Session authenticate(Connection connection, String username, String password, String domain) throws Exception {
        if (username == null || username.trim().isEmpty()) {
            try {
                return connection.authenticate(AuthenticationContext.anonymous());
            } catch (Exception anonymousError) {
                return connection.authenticate(AuthenticationContext.guest());
            }
        }
        return connection.authenticate(auth(username, password, domain));
    }

    public static List<String> listShares(String host, String username, String password, String domain) throws Exception {
        List<String> result = new ArrayList<>();
        try (SMBClient client = new SMBClient();
             Connection connection = client.connect(host);
             Session session = authenticate(connection, username, password, domain)) {
            RPCTransport transport = SMBTransportFactories.SRVSVC.getTransport(session);
            ServerService service = new ServerService(transport);
            for (NetShareInfo0 item : service.getShares0()) {
                String name = item.getNetName();
                if (name == null || name.isEmpty()) continue;
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                if ("IPC$".equals(upper) || "ADMIN$".equals(upper) || "PRINT$".equals(upper)
                        || (upper.length() == 2 && upper.charAt(1) == 36)) continue;
                result.add(name);
            }
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public static List<Entry> listFiles(String host, String shareName, String path,
                                        String username, String password, String domain) throws Exception {
        List<Entry> result = new ArrayList<>();
        try (SMBClient client = new SMBClient();
             Connection connection = client.connect(host);
             Session session = authenticate(connection, username, password, domain)) {
            Share remote = session.connectShare(shareName);
            if (!(remote instanceof DiskShare)) {
                remote.close();
                throw new IllegalStateException("Not a disk share");
            }
            try (DiskShare share = (DiskShare) remote) {
                String directory = path == null ? "" : path;
                for (FileIdBothDirectoryInformation item : share.list(directory)) {
                    String name = item.getFileName();
                    if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
                    boolean isDirectory = (item.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                    // 只显示目录及支持的媒体文件，其他普通文件在 SMB 列表层过滤。
                    if (!isDirectory && !isVideo(name) && !isAudio(name) && !isImage(name) && !isApk(name)) continue;
                    result.add(new Entry(name, isDirectory));
                }
            }
        }
        Collections.sort(result, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }


    public static boolean isVideo(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".mp4") || value.endsWith(".mkv") || value.endsWith(".avi")
                || value.endsWith(".mov") || value.endsWith(".m4v") || value.endsWith(".ts")
                || value.endsWith(".m2ts") || value.endsWith(".wmv") || value.endsWith(".flv")
                || value.endsWith(".webm") || value.endsWith(".mpg") || value.endsWith(".mpeg")
                || value.endsWith(".vob") || value.endsWith(".3gp") || value.endsWith(".3g2")
                || value.endsWith(".mts") || value.endsWith(".mxf") || value.endsWith(".ogv");
    }

    public static boolean isAudio(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".mp3") || value.endsWith(".flac") || value.endsWith(".m4a")
                || value.endsWith(".m4b") || value.endsWith(".aac") || value.endsWith(".wav")
                || value.endsWith(".ogg") || value.endsWith(".opus") || value.endsWith(".wma")
                || value.endsWith(".ape") || value.endsWith(".aiff") || value.endsWith(".aif")
                || value.endsWith(".amr") || value.endsWith(".mid") || value.endsWith(".midi")
                || value.endsWith(".mka") || value.endsWith(".ac3") || value.endsWith(".dts");
    }

    public static boolean isImage(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".jpe")
                || value.endsWith(".png") || value.endsWith(".gif") || value.endsWith(".bmp")
                || value.endsWith(".webp") || value.endsWith(".heic") || value.endsWith(".heif")
                || value.endsWith(".avif") || value.endsWith(".tif") || value.endsWith(".tiff");
    }

    public static boolean isApk(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk");
    }

    public static RemoteFile openFile(String host, String shareName, String path,
                                      String username, String password, String domain) throws Exception {
        SMBClient client = new SMBClient();
        Connection connection = null;
        Session session = null;
        DiskShare share = null;
        com.hierynomus.smbj.share.File file = null;
        try {
            connection = client.connect(host);
            session = authenticate(connection, username, password, domain);
            Share remote = session.connectShare(shareName);
            if (!(remote instanceof DiskShare)) throw new IllegalStateException("Not a disk share");
            share = (DiskShare) remote;
            file = share.openFile(path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
            return new RemoteFile(client, connection, session, share, file);
        } catch (Exception e) {
            closeQuietly(file);
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
            throw e;
        }
    }

    /**
     * 播放期的连接池：IJK/Exo 解老容器时会对同一个文件发成百上千次 range 请求，
     * 每次重新 connect + 认证 + tree connect 比真正读数据还贵。这里按
     * host+share+凭证 复用一条连接和 share，只把「打开文件」留给调用方。
     */
    private static final java.util.Map<String, Pooled> POOL = new java.util.HashMap<>();
    private static final int MAX_POOL = 4;
    private static final long POOL_IDLE_MS = 120_000L;

    public static final class SharedFile implements AutoCloseable {
        private final com.hierynomus.smbj.share.File file;
        private final long length;

        SharedFile(com.hierynomus.smbj.share.File file, long length) {
            this.file = file;
            this.length = length;
        }

        public long length() {
            return length;
        }

        public int read(byte[] buffer, long fileOffset, int offset, int length) {
            return file.read(buffer, fileOffset, offset, length);
        }

        @Override
        public void close() {
            closeQuietly(file);
        }
    }

    private static final class Pooled {
        final SMBClient client;
        final Connection connection;
        final Session session;
        final DiskShare share;
        long lastUsed;

        Pooled(SMBClient client, Connection connection, Session session, DiskShare share) {
            this.client = client;
            this.connection = connection;
            this.session = session;
            this.share = share;
            this.lastUsed = System.currentTimeMillis();
        }

        boolean alive(long now) {
            return now - lastUsed <= POOL_IDLE_MS && connection != null && connection.isConnected();
        }

        static Pooled create(String host, String shareName,
                             String username, String password, String domain) throws Exception {
            SMBClient client = new SMBClient();
            Connection connection = null;
            Session session = null;
            DiskShare share = null;
            try {
                connection = client.connect(host);
                session = authenticate(connection, username, password, domain);
                Share remote = session.connectShare(shareName);
                if (!(remote instanceof DiskShare)) {
                    remote.close();
                    throw new IllegalStateException("Not a disk share");
                }
                share = (DiskShare) remote;
                return new Pooled(client, connection, session, share);
            } catch (Exception e) {
                closeQuietly(share);
                closeQuietly(session);
                closeQuietly(connection);
                closeQuietly(client);
                throw e;
            }
        }

        void close() {
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
        }
    }

    private static Pooled acquirePooled(String host, String shareName,
                                        String username, String password, String domain) throws Exception {
        String key = host + "|" + shareName + "|" + (username == null ? "" : username)
                + "|" + (password == null ? "" : password) + "|" + (domain == null ? "" : domain);
        long now = System.currentTimeMillis();
        synchronized (POOL) {
            Pooled existing = POOL.get(key);
            if (existing != null) {
                if (existing.alive(now)) {
                    existing.lastUsed = now;
                    return existing;
                }
                POOL.remove(key);
                existing.close();
            }
            while (POOL.size() >= MAX_POOL) {
                String oldest = null;
                long oldestUsed = Long.MAX_VALUE;
                for (java.util.Map.Entry<String, Pooled> item : POOL.entrySet()) {
                    if (item.getValue().lastUsed < oldestUsed) {
                        oldestUsed = item.getValue().lastUsed;
                        oldest = item.getKey();
                    }
                }
                if (oldest == null) break;
                POOL.remove(oldest).close();
            }
            Pooled created = Pooled.create(host, shareName, username, password, domain);
            POOL.put(key, created);
            return created;
        }
    }

    private static void dropPooled(String host, String shareName,
                                   String username, String password, String domain) {
        String key = host + "|" + shareName + "|" + (username == null ? "" : username)
                + "|" + (password == null ? "" : password) + "|" + (domain == null ? "" : domain);
        synchronized (POOL) {
            Pooled removed = POOL.remove(key);
            if (removed != null) removed.close();
        }
    }

    /** 复用池里的连接打开远程文件；连接失效时丢掉重连一次。 */
    public static SharedFile openShared(String host, String shareName, String path,
                                        String username, String password, String domain) throws Exception {
        try {
            return openSharedOnce(host, shareName, path, username, password, domain);
        } catch (Exception e) {
            dropPooled(host, shareName, username, password, domain);
            return openSharedOnce(host, shareName, path, username, password, domain);
        }
    }

    private static SharedFile openSharedOnce(String host, String shareName, String path,
                                             String username, String password, String domain) throws Exception {
        Pooled pooled = acquirePooled(host, shareName, username, password, domain);
        com.hierynomus.smbj.share.File file = null;
        try {
            file = pooled.share.openFile(path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
            long length = file.getFileInformation(FileStandardInformation.class).getEndOfFile();
            pooled.lastUsed = System.currentTimeMillis();
            return new SharedFile(file, length);
        } catch (Exception e) {
            closeQuietly(file);
            throw e;
        }
    }

    private static void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception ignored) {
        }
    }

    public static final class Entry {
        public final String name;
        public final boolean directory;

        Entry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    public static final class RemoteFile implements AutoCloseable {
        private final SMBClient client;
        private final Connection connection;
        private final Session session;
        private final DiskShare share;
        private final com.hierynomus.smbj.share.File file;

        RemoteFile(SMBClient client, Connection connection, Session session,
                   DiskShare share, com.hierynomus.smbj.share.File file) {
            this.client = client;
            this.connection = connection;
            this.session = session;
            this.share = share;
            this.file = file;
        }

        public long length() throws Exception {
            return file.getFileInformation(FileStandardInformation.class).getEndOfFile();
        }

        public int read(byte[] buffer, long fileOffset, int offset, int length) {
            return file.read(buffer, fileOffset, offset, length);
        }

        @Override
        public void close() {
            closeQuietly(file);
            closeQuietly(share);
            closeQuietly(session);
            closeQuietly(connection);
            closeQuietly(client);
        }
    }
}
