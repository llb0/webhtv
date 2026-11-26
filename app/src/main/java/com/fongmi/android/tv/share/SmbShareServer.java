package com.fongmi.android.tv.share;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 只读 SMB2 服务端（原型）：把本机的一个目录以共享 {@link #SHARE_NAME} 暴露出去，
 * 让同一局域网里的另一台 webhtv（电视端/手机端都行）用现有的 SMB 浏览器直接浏览、播放。
 *
 * <p>为什么不用 JFileServer / Samba：JFileServer 只有 SMB1，而本项目的 SMB 客户端是
 * smbj（只支持 SMB2/3），协议对不上；Samba 要整棵源码 cross build，APK 里塞不下。
 * 这里按 MS-SMB2 只实现客户端真正用到的那几条命令：协商 → 会话（匿名/不校验口令）→
 * 树连接 → 打开 → 读 → 枚举目录 → 查询属性 → 关闭。写操作一律返回不支持（原型只读）。</p>
 *
 * <p>端口：非 root 绑不了 445，默认 4450；客户端地址写 {@code host:4450} 即可。</p>
 */
public final class SmbShareServer {

    public static final String SHARE_NAME = "webhtv";
    public static final int DEFAULT_PORT = 4450;

    private static final int CMD_NEGOTIATE = 0;
    private static final int CMD_SESSION_SETUP = 1;
    private static final int CMD_LOGOFF = 2;
    private static final int CMD_TREE_CONNECT = 3;
    private static final int CMD_TREE_DISCONNECT = 4;
    private static final int CMD_CREATE = 5;
    private static final int CMD_CLOSE = 6;
    private static final int CMD_FLUSH = 7;
    private static final int CMD_READ = 8;
    private static final int CMD_WRITE = 9;
    private static final int CMD_IOCTL = 11;
    private static final int CMD_ECHO = 13;
    private static final int CMD_QUERY_DIRECTORY = 14;
    private static final int CMD_QUERY_INFO = 16;
    private static final int CMD_SET_INFO = 17;

    private static final int STATUS_SUCCESS = 0x00000000;
    private static final int STATUS_BUFFER_OVERFLOW = 0x80000005;
    private static final int STATUS_MORE_PROCESSING_REQUIRED = 0xC0000016;  // NTSTATUS，不是 0x80000016
    private static final int STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034;
    private static final int STATUS_ACCESS_DENIED = 0xC0000022;
    private static final int STATUS_NOT_SUPPORTED = 0xC00000BB;
    private static final int STATUS_INVALID_INFO_CLASS = 0xC0000003;
    private static final int STATUS_LOGON_FAILURE = 0xC000006D;

    private static final int MAX_READ = 8 * 1024 * 1024;

    private final Object lock = new Object();
    private final Set<Socket> clients = new HashSet<>();
    private volatile boolean running;
    private ServerSocket server;
    private File root;
    private int port = DEFAULT_PORT;
    /** 账号密码都为空表示匿名开放全部；一旦设了账号密码，匿名访问直接拒绝。 */
    private volatile String username = "";
    private volatile String password = "";

    public synchronized boolean start(File root, int port) {
        return start(root, port, "", "");
    }

    /** 账号密码任一为空就按匿名开放处理；两者都设了才要求账号密码，且匿名会被拒绝。 */
    public synchronized boolean start(File root, int port, String username, String password) {
        stop();
        if (root == null || !root.isDirectory()) return false;
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            // 运行中改设置要先 stop 再 start，旧连接的 TIME_WAIT 可能让端口短暂不可用，重试几次再认失败。
            bindWithRetry(socket, port);
            this.server = socket;
            this.root = root;
            this.port = port;
            this.username = username == null ? "" : username.trim();
            this.password = password == null ? "" : password;
            this.running = true;
            Thread thread = new Thread(this::acceptLoop, "smb-share-accept");
            thread.setDaemon(true);
            thread.start();
            log("start port=" + port + " root=" + root.getAbsolutePath()
                    + " auth=" + (hasAccount() ? this.username : "anonymous"));
            return true;
        } catch (Exception error) {
            log("start failed port=" + port + " error=" + error);
            this.root = null;
            this.running = false;
            closeQuietly(server);
            this.server = null;
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(server);
        server = null;
        synchronized (lock) {
            for (Socket socket : new ArrayList<>(clients)) closeQuietly(socket);
            clients.clear();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** 端口可能被刚断开的连接占在 TIME_WAIT 里，短暂重试几次。 */
    private static void bindWithRetry(ServerSocket socket, int port) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                socket.bind(new InetSocketAddress(port));
                return;
            } catch (BindException busy) {
                if (attempt >= 8) throw busy;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw busy;
                }
            }
        }
    }

    public int getPort() {
        return port;
    }

    public File getRoot() {
        return root;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /** 设了账号（且给了口令）就必须校验；否则匿名开放全部。 */
    private boolean hasAccount() {
        return !username.isEmpty() && !password.isEmpty();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                synchronized (lock) {
                    clients.add(socket);
                }
                Thread worker = new Thread(new Connection(socket, root), "smb-share-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception error) {
                if (!running) return;
                log("accept error=" + error);
            }
        }
    }

    /** 一条 TCP 连接：NetBIOS 会话头 + 若干 SMB2 请求（可能是复合请求）。 */
    private final class Connection implements Runnable {

        private final Socket socket;
        private final File root;
        private final Map<Long, Handle> handles = new LinkedHashMap<>();
        private final AtomicLong nextHandle = new AtomicLong(1);
        private long sessionId;
        private int treeId;
        /** 本次 Type2 下发的 8 字节 server challenge，校验 Type3 的 NTProofStr 要用。 */
        private byte[] challenge;

        Connection(Socket socket, File root) {
            this.socket = socket;
            this.root = root;
        }

        @Override
        public void run() {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 OutputStream out = socket.getOutputStream()) {
                while (running) {
                    byte[] packet = readPacket(in);
                    if (packet == null) return;
                    byte[] reply = dispatch(packet);
                    if (reply == null) continue;
                    out.write(netbios(reply));
                    out.flush();
                }
            } catch (Exception error) {
                log("conn end error=" + error);
            } finally {
                for (Handle handle : handles.values()) handle.close();
                handles.clear();
                synchronized (lock) {
                    clients.remove(socket);
                }
                closeQuietly(socket);
            }
        }

        /** NetBIOS 会话消息：1 字节类型 0x00 + 3 字节长度。 */
        private byte[] readPacket(DataInputStream in) throws IOException {
            int type = in.read();
            if (type < 0) return null;
            int length = (in.read() << 16) | (in.read() << 8) | in.read();
            if (length <= 0 || length > 16 * 1024 * 1024) return null;
            byte[] data = new byte[length];
            in.readFully(data);
            return data;
        }

        private byte[] netbios(byte[] payload) {
            byte[] frame = new byte[payload.length + 4];
            frame[1] = (byte) (payload.length >> 16);
            frame[2] = (byte) (payload.length >> 8);
            frame[3] = (byte) payload.length;
            System.arraycopy(payload, 0, frame, 4, payload.length);
            return frame;
        }

        /**
         * 一个报文里可能串着多条命令（NextCommand），逐条处理，再把响应按同样的链式结构拼回去。
         */
        private byte[] dispatch(byte[] packet) {
            List<byte[]> replies = new ArrayList<>();
            int offset = 0;
            while (offset + 64 <= packet.length) {
                int command = le16(packet, offset + 12);
                int next = le32(packet, offset + 20);
                long messageId = le64(packet, offset + 24);
                int requestTree = le32(packet, offset + 36);
                int body = offset + 64;
                int bodyLength = packet.length - body;
                Reply reply = handle(command, packet, body, bodyLength);
                int tree = reply.treeId != 0 ? reply.treeId : requestTree;
                replies.add(reply.toBytes(command, messageId, tree, sessionId));
                if (next <= 0) break;
                offset += next;
            }
            if (replies.isEmpty()) return null;
            ByteArrayOutputStream total = new ByteArrayOutputStream();
            for (int i = 0; i < replies.size(); i++) {
                byte[] bytes = replies.get(i);
                if (i < replies.size() - 1) putLe32(bytes, 20, bytes.length);
                total.write(bytes, 0, bytes.length);
            }
            return total.toByteArray();
        }

        private Reply handle(int command, byte[] packet, int body, int length) {
            switch (command) {
                case CMD_NEGOTIATE:
                    return negotiate();
                case CMD_SESSION_SETUP:
                    return sessionSetup(packet, body);
                case CMD_LOGOFF:
                    return fixed(4, 2);
                case CMD_TREE_CONNECT:
                    return treeConnect(packet, body, length);
                case CMD_TREE_DISCONNECT:
                    treeId = 0;
                    return fixed(4, 2);
                case CMD_CREATE:
                    return create(packet, body, length);
                case CMD_READ:
                    return read(packet, body, length);
                case CMD_QUERY_DIRECTORY:
                    return queryDirectory(packet, body, length);
                case CMD_QUERY_INFO:
                    return queryInfo(packet, body, length);
                case CMD_CLOSE:
                    return closeHandle(packet, body);
                case CMD_FLUSH:
                    return fixed(24, 22);
                case CMD_ECHO:
                    return fixed(4, 2);
                default:
                    return simple(STATUS_NOT_SUPPORTED, 9);
            }
        }

        // ---------------------------------------------------------------- NEGOTIATE

        private Reply negotiate() {
            Reply reply = new Reply(STATUS_SUCCESS);
            reply.u16(65);                 // StructureSize
            reply.u16(0x0001);             // SecurityMode：允许签名但不要求
            reply.u16(0x0202);             // Dialect：SMB 2.0.2，省掉 validate-negotiate 那一套
            reply.u16(0);                  // Reserved
            byte[] guid = new byte[16];
            new SecureRandom().nextBytes(guid);
            reply.raw(guid);               // ServerGuid
            reply.u32(0);                  // Capabilities
            reply.u32(8 * 1024 * 1024);    // MaxTransactSize
            reply.u32(MAX_READ);           // MaxReadSize
            reply.u32(8 * 1024 * 1024);    // MaxWriteSize
            long now = time(System.currentTimeMillis());
            reply.u64(now);                // SystemTime
            reply.u64(now);                // ServerStartTime
            reply.u16(0);                  // SecurityBufferOffset
            reply.u16(0);                  // SecurityBufferLength
            reply.u32(0);                  // NegotiateContextOffset
            return reply;
        }

        // ------------------------------------------------------------ SESSION SETUP

        private Reply sessionSetup(byte[] packet, int body) {
            // 这几个 Offset 都是相对 SMB2 报文头起始，不是相对 body，别再叠加 body。
            int securityOffset = le16(packet, body + 12);
            int securityLength = le16(packet, body + 14);
            byte[] buffer = slice(packet, securityOffset, securityLength);
            if (buffer.length == 0) {
                // 空安全缓冲：smbj 的匿名/guest 也是这个起手式，但它紧接着要把回包当
                // SPNEGO NegTokenTarg 解析，所以这里不能直接 SUCCESS，要先给一轮挑战。
                return sessionReply(STATUS_MORE_PROCESSING_REQUIRED, spnegoTarg(1, ntlmChallenge()));
            }
            boolean spnego = (buffer[0] & 0xFF) == 0x60;
            int type = ntlmType(buffer);
            if (type == 1) {
                byte[] challenge = ntlmChallenge();
                return sessionReply(STATUS_MORE_PROCESSING_REQUIRED,
                        spnego ? spnegoTarg(1, challenge) : challenge);
            }
            // Type 3（带账号的口令响应）：设了账号密码就按 NTLMv2 校验（账号密码都要对），否则放行。
            if (hasAccount() && !NtlmV2Verifier.verify(buffer, challenge, username, password)) {
                log("session setup denied: bad account");
                return sessionReply(STATUS_LOGON_FAILURE, null);
            }
            if (sessionId == 0) sessionId = newSessionId();
            return sessionReply(STATUS_SUCCESS, spnego ? spnegoTarg(0, null) : null);
        }

        private Reply sessionReply(int status, byte[] security) {
            Reply reply = new Reply(status);
            reply.u16(9);
            reply.u16(0);                       // SessionFlags
            if (security == null || security.length == 0) {
                reply.u16(0);
                reply.u16(0);
            } else {
                reply.u16(72);                  // SecurityBufferOffset：64 头 + 8
                reply.u16(security.length);
                reply.raw(security);
            }
            return reply;
        }

        private long newSessionId() {
            long value = System.nanoTime();
            return value == 0 ? 1 : value;
        }

        /** NTLM 消息类型；smbj 的匿名/guest 走 SPNEGO（0x60 包一层）而不是裸 NTLMSSP。 */
        private int ntlmType(byte[] buffer) {
            int at = indexOfNtlm(buffer);
            if (at < 0 || at + 12 > buffer.length) return -1;
            return le32(buffer, at + 8);
        }

        private int indexOfNtlm(byte[] buffer) {
            if (buffer == null) return -1;
            for (int i = 0; i + 8 <= buffer.length; i++) {
                if (buffer[i] != 'N' || buffer[i + 1] != 'T' || buffer[i + 2] != 'L'
                        || buffer[i + 3] != 'M' || buffer[i + 4] != 'S' || buffer[i + 5] != 'S'
                        || buffer[i + 6] != 'P' || buffer[i + 7] != 0) continue;
                return i;
            }
            return -1;
        }

        /**
         * SPNEGO NegTokenTarg：{@code a1/30/a0(negState)/a2(04 responseToken)}。
         * negState=1 表示还要一轮（带 Type2 挑战），0 表示认证完成。
         */
        private byte[] spnegoTarg(int state, byte[] response) {
            byte[] negState = { (byte) 0xA0, 0x03, 0x0A, 0x01, (byte) state };
            byte[] token = new byte[0];
            if (response != null && response.length > 0) {
                byte[] octet = new byte[2 + response.length];
                octet[0] = 0x04;
                octet[1] = (byte) response.length;
                System.arraycopy(response, 0, octet, 2, response.length);
                token = new byte[2 + octet.length];
                token[0] = (byte) 0xA2;
                token[1] = (byte) octet.length;
                System.arraycopy(octet, 0, token, 2, octet.length);
            }
            byte[] inner = new byte[negState.length + token.length];
            System.arraycopy(negState, 0, inner, 0, negState.length);
            System.arraycopy(token, 0, inner, negState.length, token.length);
            byte[] out = new byte[4 + inner.length];
            out[0] = (byte) 0xA1;
            out[1] = (byte) (2 + inner.length);
            out[2] = 0x30;
            out[3] = (byte) inner.length;
            System.arraycopy(inner, 0, out, 4, inner.length);
            return out;
        }

        /** NTLM Type2：只为了让客户端能算出 Type3；challenge 随机生成并留作后续校验。 */
        private byte[] ntlmChallenge() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII));
            writeLe32(out, 2);                   // Type 2
            writeLe16(out, 0);                   // TargetNameLen
            writeLe16(out, 0);                   // TargetNameMaxLen
            writeLe32(out, 0);                   // TargetNameOffset
            // 必须带上 128 位加密与扩展会话安全：smbj 的 NTLM 客户端缺了这两项会直接拒绝。
            writeLe32(out, 0x20880205);          // UNICODE|REQUEST_TARGET|NTLM|EXT_SESSION|128|TARGET_INFO
            challenge = new byte[8];
            new SecureRandom().nextBytes(challenge);
            out.writeBytes(challenge);
            out.writeBytes(new byte[8]);
            writeLe16(out, 0);                   // TargetInfoLen
            writeLe16(out, 0);                   // TargetInfoMaxLen
            writeLe32(out, 0);                   // TargetInfoOffset
            // smbj 的 NtlmChallenge.read 在无 VERSION 标志时也会 skip(8)，尾部必须留够字节。
            out.writeBytes(new byte[8]);
            return out.toByteArray();
        }

        // ----------------------------------------------------------- TREE CONNECT

        private Reply treeConnect(byte[] packet, int body, int length) {
            int pathOffset = le16(packet, body + 4);  // StructureSize(2)+Reserved(2) 之后
            int pathLength = le16(packet, body + 6);
            String path = new String(slice(packet, pathOffset, pathLength), StandardCharsets.UTF_16LE);
            String share = path;
            int cut = Math.max(share.lastIndexOf('\\'), share.lastIndexOf('/'));
            if (cut >= 0) share = share.substring(cut + 1);
            if (TextUtils.isEmpty(share) || !share.equalsIgnoreCase(SHARE_NAME)) {
                return simple(STATUS_OBJECT_NAME_NOT_FOUND, 9);
            }
            int id = (int) (System.nanoTime() & 0x7fffffff);
            treeId = id == 0 ? 1 : id;
            Reply reply = new Reply(STATUS_SUCCESS);
            reply.treeId = treeId;
            reply.u16(16);
            reply.u8(1);            // ShareType：DISK
            reply.u8(0);            // Reserved
            reply.u32(0);           // ShareFlags
            reply.u32(0);           // Capabilities
            reply.u32(0x001F01FF);  // MaximalAccess
            return reply;
        }

        // ---------------------------------------------------------------- CREATE

        private Reply create(byte[] packet, int body, int length) {
            int nameOffset = le16(packet, body + 44);
            int nameLength = le16(packet, body + 46);
            String name = nameLength > 0
                    ? new String(slice(packet, nameOffset, nameLength), StandardCharsets.UTF_16LE) : "";
            name = name.replace('\\', '/').replace("\u0000", "");
            while (name.startsWith("/")) name = name.substring(1);
            int createOptions = le32(packet, body + 40);
            boolean wantDirectory = (createOptions & 0x00000001) != 0;
            File target;
            try {
                target = resolve(name);
            } catch (IOException denied) {
                return simple(STATUS_ACCESS_DENIED, 9);
            }
            if (target == null || !target.exists()) return simple(STATUS_OBJECT_NAME_NOT_FOUND, 9);
            if (wantDirectory && !target.isDirectory()) return simple(STATUS_OBJECT_NAME_NOT_FOUND, 9);
            long id;
            synchronized (handles) {
                id = nextHandle.getAndIncrement();
                handles.put(id, new Handle(target));
            }
            Reply reply = new Reply(STATUS_SUCCESS);
            reply.u16(89);
            reply.u8(0);            // OplockLevel：none
            reply.u8(0);            // Flags
            reply.u32(1);           // CreateAction：FILE_OPENED
            long modified = time(target.lastModified());
            reply.u64(modified);
            reply.u64(modified);
            reply.u64(modified);
            reply.u64(modified);
            long size = target.isFile() ? target.length() : 0;
            reply.u64(size);
            reply.u64(size);
            reply.u32(attributes(target));
            reply.u32(0);           // Reserved2
            reply.u64(id);          // FileId.Persistent
            reply.u64(0);           // FileId.Volatile
            reply.u32(0);           // CreateContextsOffset
            reply.u32(0);           // CreateContextsLength
            return reply;
        }

        private File resolve(String name) throws IOException {
            File base = root;
            if (TextUtils.isEmpty(name)) return base.getCanonicalFile();
            File target = new File(base, name);
            String basePath = base.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.equals(basePath) && !targetPath.startsWith(basePath + File.separator)) {
                throw new IOException("outside share");
            }
            return target;
        }

        // ------------------------------------------------------------------ READ

        private Reply read(byte[] packet, int body, int length) {
            int readLength = le32(packet, body + 4);
            long offset = le64(packet, body + 8);
            long id = le64(packet, body + 16);      // FileId.Persistent
            Handle handle = handleOf(id);
            if (handle == null || handle.file.isDirectory()) return simple(STATUS_ACCESS_DENIED, 17);
            byte[] data;
            try {
                long size = handle.length();
                if (offset >= size) {
                    data = new byte[0];
                } else {
                    int want = (int) Math.min((long) Math.max(1, readLength), size - offset);
                    data = new byte[Math.min(want, MAX_READ)];
                    RandomAccessFile reader = handle.file();
                    reader.seek(offset);
                    reader.readFully(data);
                }
            } catch (Exception error) {
                log("read error=" + error);
                return simple(STATUS_ACCESS_DENIED, 17);
            }
            Reply reply = new Reply(STATUS_SUCCESS);
            reply.u16(17);
            reply.u8(80);           // DataOffset：64 头 + 16
            reply.u8(0);
            reply.u32(data.length);
            reply.u32(0);           // DataRemaining
            reply.u32(0);           // Reserved2
            reply.raw(data);
            return reply;
        }

        // ------------------------------------------------------- QUERY DIRECTORY

        private Reply queryDirectory(byte[] packet, int body, int length) {
            int infoClass = packet[body + 2] & 0xFF;
            long index = le32(packet, body + 4);
            long id = le64(packet, body + 8);       // FileId.Persistent
            int outputLength = le32(packet, body + 28);
            Handle handle = handleOf(id);
            if (handle == null || !handle.file.isDirectory()) return simple(STATUS_ACCESS_DENIED, 9);
            File[] children = handle.file.listFiles();
            if (children == null) return simple(STATUS_ACCESS_DENIED, 9);
            // 先排子项，再拼「.」「..」：否则占位项会被一起排序，名字和属性整体错位。
            List<File> sorted = new ArrayList<>(java.util.Arrays.asList(children));
            sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            List<File> items = new ArrayList<>();
            items.add(handle.file);                                  // "."
            items.add(handle.file);                                  // ".."（原型不区分父目录）
            items.addAll(sorted);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<byte[]> entries = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            boolean overflow = false;
            for (int position = (int) Math.max(0, index); position < items.size(); position++) {
                String name;
                if (position == 0) name = ".";
                else if (position == 1) name = "..";
                else name = items.get(position).getName();
                byte[] entry = directoryEntry(name, items.get(position), position);
                if (out.size() + entry.length > outputLength) {
                    if (entries.isEmpty()) return simple(STATUS_BUFFER_OVERFLOW, 9);
                    overflow = true;
                    break;
                }
                entries.add(entry);
                sizes.add(entry.length);
                out.writeBytes(entry);
            }
            // NextEntryOffset：每条指向下一条的起始（相对本条），最后一条必须为 0。
            ByteArrayOutputStream padded = new ByteArrayOutputStream();
            for (int i = 0; i < entries.size(); i++) {
                byte[] entry = entries.get(i);
                putLe32(entry, 0, i < entries.size() - 1 ? sizes.get(i) : 0);
                padded.writeBytes(entry);
            }
            Reply reply = new Reply(overflow ? STATUS_BUFFER_OVERFLOW : STATUS_SUCCESS);
            reply.u16(9);
            reply.u16(72);          // OutputBufferOffset
            reply.u32(padded.size());
            reply.raw(padded.toByteArray());
            return reply;
        }

        /**
         * FileIdBothDirectoryInformation（0x25）：smbj 默认就要这一种。
         * 每条按 8 字节对齐，NextEntryOffset 由调用方最后补。
         */
        private byte[] directoryEntry(String name, File item, int index) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeLe32(out, 0);                       // NextEntryOffset：整排收尾时统一补
            writeLe32(out, index);                   // FileIndex：客户端续读时按这个跳
            long modified = time(item.lastModified());
            writeLe64(out, modified);
            writeLe64(out, modified);
            writeLe64(out, modified);
            writeLe64(out, modified);
            writeLe64(out, item.isFile() ? item.length() : 0);
            writeLe64(out, item.isFile() ? item.length() : 0);
            writeLe32(out, attributes(item));
            writeLe32(out, nameBytes.length);
            writeLe32(out, 0);                       // EaSize
            out.write(0);                            // ShortNameLength
            out.write(0);                            // Reserved1
            out.writeBytes(new byte[24]);            // ShortName
            writeLe16(out, 0);                       // Reserved2
            writeLe64(out, item.getAbsolutePath().hashCode());  // FileId
            out.writeBytes(nameBytes);
            byte[] bytes = out.toByteArray();
            int padded = (bytes.length + 7) & ~7;
            byte[] result = new byte[padded];
            System.arraycopy(bytes, 0, result, 0, bytes.length);
            return result;
        }

        // ------------------------------------------------------------ QUERY INFO

        private Reply queryInfo(byte[] packet, int body, int length) {
            int infoType = packet[body + 2] & 0xFF;
            int infoClass = packet[body + 3] & 0xFF;
            int outputLength = le32(packet, body + 4);
            long id = le64(packet, body + 24);      // FileId.Persistent
            Handle handle = handleOf(id);
            if (handle == null) return simple(STATUS_ACCESS_DENIED, 9);
            if (infoType != 1) return simple(STATUS_NOT_SUPPORTED, 9);
            byte[] data;
            switch (infoClass) {
                case 4:
                    data = basicInfo(handle.file);
                    break;
                case 5:
                    data = standardInfo(handle.file);
                    break;
                case 18:
                    data = allInfo(handle.file);
                    break;
                default:
                    return simple(STATUS_INVALID_INFO_CLASS, 9);
            }
            if (data.length > outputLength) return simple(STATUS_BUFFER_OVERFLOW, 9);
            Reply reply = new Reply(STATUS_SUCCESS);
            reply.u16(9);
            reply.u16(72);
            reply.u32(data.length);
            reply.raw(data);
            return reply;
        }

        private byte[] basicInfo(File file) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long modified = time(file.lastModified());
            writeLe64(out, modified);
            writeLe64(out, modified);
            writeLe64(out, modified);
            writeLe64(out, modified);
            writeLe32(out, attributes(file));
            writeLe32(out, 0);                       // Reserved
            return out.toByteArray();
        }

        private byte[] standardInfo(File file) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long size = file.isFile() ? file.length() : 0;
            writeLe64(out, size);
            writeLe64(out, size);
            writeLe32(out, 1);                       // NumberOfLinks
            out.write(0);                            // DeletePending
            out.write(file.isDirectory() ? 1 : 0);   // Directory
            writeLe16(out, 0);                       // Reserved（缺了客户端解析会越界）
            return out.toByteArray();
        }

        private byte[] allInfo(File file) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(basicInfo(file));
            out.writeBytes(standardInfo(file));
            writeLe64(out, 0);                       // FileInternalInformation
            writeLe32(out, 0);                       // FileEaInformation
            writeLe32(out, 0x001F01FF);              // FileAccessInformation
            writeLe64(out, 0);                       // FilePositionInformation
            writeLe32(out, 0);                       // FileModeInformation
            writeLe32(out, 0);                       // FileAlignmentInformation
            byte[] name = file.getName().getBytes(StandardCharsets.UTF_16LE);
            writeLe32(out, name.length);             // FileNameInformation
            writeLe32(out, 0);
            out.writeBytes(name);
            return out.toByteArray();
        }

        // ----------------------------------------------------------------- CLOSE

        private Reply closeHandle(byte[] packet, int body) {
            long id = le64(packet, body + 8);        // FileId.Persistent
            Handle handle;
            synchronized (handles) {
                handle = handles.remove(id);
            }
            long size = 0;
            long modified = 0;
            int attrs = 0;
            if (handle != null) {
                size = handle.file.isFile() ? handle.file.length() : 0;
                modified = handle.file.lastModified();
                attrs = attributes(handle.file);
                handle.close();
            }
            Reply reply = new Reply(STATUS_SUCCESS);
            reply.u16(60);
            reply.u16(0);
            reply.u32(0);
            long stamp = time(modified);
            reply.u64(stamp);
            reply.u64(stamp);
            reply.u64(stamp);
            reply.u64(stamp);
            reply.u64(size);
            reply.u64(size);
            reply.u32(attrs);
            return reply;
        }

        private Handle handleOf(long id) {
            synchronized (handles) {
                return handles.get(id);
            }
        }

        private void log(String message) {
            SmbShareServer.log(message);
        }

    }

    // ------------------------------------------------------------------ 静态工具

    private static void log(String message) {
        SpiderDebug.log("smbshare", message);
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length <= 0 || offset + length > data.length) return new byte[0];
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    private static int le16(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 2 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 4 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long le64(byte[] data, int offset) {
        long low = le32(data, offset) & 0xFFFFFFFFL;
        long high = le32(data, offset + 4) & 0xFFFFFFFFL;
        return low | (high << 32);
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeLe64(ByteArrayOutputStream out, long value) {
        writeLe32(out, (int) (value & 0xFFFFFFFFL));
        writeLe32(out, (int) ((value >> 32) & 0xFFFFFFFFL));
    }

    private static void putLe32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
        data[offset + 2] = (byte) (value >> 16);
        data[offset + 3] = (byte) (value >> 24);
    }

    private static void putLe64(byte[] data, int offset, long value) {
        putLe32(data, offset, (int) (value & 0xFFFFFFFFL));
        putLe32(data, offset + 4, (int) ((value >> 32) & 0xFFFFFFFFL));
    }

    /** Windows FILETIME：100ns 刻度，起点 1601-01-01。 */
    private static long time(long epochMillis) {
        if (epochMillis <= 0) return 0;
        return epochMillis * 10_000L + 116444736000000000L;
    }

    private static int attributes(File file) {
        int value = file.isDirectory() ? 0x10 : 0x80;
        if (file.isHidden()) value |= 0x02;
        return value;
    }

    /** 定长空响应：StructureSize 之后还有 {@code extra} 字节保留位，缺了客户端会解析越界。 */
    private static Reply fixed(int structureSize, int extra) {
        Reply reply = new Reply(STATUS_SUCCESS);
        reply.u16(structureSize);
        for (int i = 0; i < extra; i++) reply.u8(0);
        return reply;
    }

    private static Reply simple(int status, int structureSize) {
        Reply reply = new Reply(status);
        reply.u16(structureSize);
        return reply;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    /** 打开中的文件；读句柄按需打开。 */
    private static final class Handle {
        private final File file;
        private RandomAccessFile reader;

        Handle(File file) {
            this.file = file;
        }

        RandomAccessFile file() throws IOException {
            if (reader == null) reader = new RandomAccessFile(file, "r");
            return reader;
        }

        long length() throws IOException {
            if (file.isDirectory()) return 0;
            return file().length();
        }

        void close() {
            closeQuietly(reader);
            reader = null;
        }
    }

    /** 响应体拼装：内部统一小端，最后再套 SMB2 头。 */
    private static final class Reply {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final int status;
        private int treeId;

        Reply(int status) {
            this.status = status;
        }

        void u8(int value) {
            body.write(value & 0xFF);
        }

        void u16(int value) {
            writeLe16(body, value);
        }

        void u32(int value) {
            writeLe32(body, value);
        }

        void u64(long value) {
            writeLe64(body, value);
        }

        void raw(byte[] data) {
            body.writeBytes(data);
        }

        byte[] toBytes(int command, long messageId, int tree, long session) {
            byte[] payload = body.toByteArray();
            byte[] out = new byte[64 + payload.length];
            out[0] = (byte) 0xFE;
            out[1] = 'S';
            out[2] = 'M';
            out[3] = 'B';
            out[4] = 64;        // StructureSize
            out[6] = 1;         // CreditCharge
            putLe32(out, 8, status);
            out[12] = (byte) command;
            out[13] = (byte) (command >> 8);
            out[14] = 1;        // CreditResponse
            out[16] = 1;        // Flags：SERVER_TO_REDIR
            putLe64(out, 24, messageId);
            putLe32(out, 36, tree);
            putLe64(out, 40, session);
            System.arraycopy(payload, 0, out, 64, payload.length);
            return out;
        }
    }
}
