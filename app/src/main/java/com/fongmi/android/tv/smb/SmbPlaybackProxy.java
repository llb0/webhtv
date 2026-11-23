package com.fongmi.android.tv.smb;

/**
 * 本机 SMB 播放代理：把一个 SMB 文件登记成短期 token，通过 127.0.0.1 上的
 * NanoHTTPD 暴露为 HTTP 地址，供 Exo/IJK 等播放器按普通网络地址播放，并支持 Range 请求。
 *
 * <p>token 只保存在进程内存，最多保留 24 个；不落盘。账号密码默认只在内存，若用户在登录页
 * 勾选保存，则由 {@link SmbCredentialStore} 按主机加密存储，与本代理无关。代理随播放器请求
 * 打开远程文件，播放结束或进程退出后失效。SMB 播放统一走这里，不要让播放器直接接收
 * smb:// 地址。</p>
 */

import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SmbPlaybackProxy {

    private static final Map<String, FileRef> FILES = new LinkedHashMap<>();
    /** 缩略图专用 token 池：目录里逐行取帧不该把播放中的直链从 FILES 里挤掉。 */
    private static final Map<String, FileRef> THUMBS = new LinkedHashMap<>();
    private static final int MAX_FILES = 24;
    private static final int MAX_THUMBS = 8;
    private static ProxyServer server;

    private SmbPlaybackProxy() {
    }

    public static synchronized String createUrl(String host, String share, String path,
                                                String username, String password, String domain) throws IOException {
        ensureServer();
        String token = UUID.randomUUID().toString().replace("-", "");
        FILES.put(token, new FileRef(host, share, path, username, password, domain));
        while (FILES.size() > MAX_FILES) {
            String first = FILES.keySet().iterator().next();
            FILES.remove(first);
        }
        // 末尾保留真实扩展名：播放器/推送解析器会据此判断这是视频直链而不是网页，
        // 否则 127.0.0.1 的代理地址会被当作待嗅探页面，解析超时后报“播放地址解析失败”。
        String name = path == null ? "" : path.substring(path.lastIndexOf((char) 92) + 1);
        if (name.isEmpty()) name = "media";
        // 保留真实后缀（AVI/WMV 也照原样），用 smb=1 明确告诉嗅探器这是直链。
        // 以前给未知后缀补 .mp4 会让 Exo 按 MP4 解析旧 AVI/WMV，外调播放器也会误判格式。
        return "http://127.0.0.1:" + server.getListeningPort() + "/" + token + "/"
                + encodePathSegment(name) + "?smb=1";
    }

    /**
     * 缩略图取帧专用地址：同一文件给固定 token（重复取帧复用同一条），
     * 并且走独立的 {@link #THUMBS} 池，避免逐行取帧把播放中的直链挤出 {@link #FILES}。
     */
    public static synchronized String createThumbUrl(String host, String share, String path,
                                                     String username, String password, String domain) throws IOException {
        ensureServer();
        String token = "t" + digest(host + "|" + share + "|" + path);
        THUMBS.put(token, new FileRef(host, share, path, username, password, domain));
        while (THUMBS.size() > MAX_THUMBS) {
            String first = THUMBS.keySet().iterator().next();
            THUMBS.remove(first);
        }
        String name = path == null ? "" : path.substring(path.lastIndexOf((char) 92) + 1);
        if (name.isEmpty()) name = "media";
        return "http://127.0.0.1:" + server.getListeningPort() + "/" + token + "/"
                + encodePathSegment(name) + "?smb=1&thumb=1";
    }

    /**
     * 外调播放器专用地址：内部 URL 已保留真实文件名后缀并带 smb=1；
     * 这里从 token 反查真实文件名，保证外部播放器按 AVI/WMV 等原后缀识别格式，
     * 不再出现补过 .mp4 导致打不开的问题。路径仍由代理按 SMB 原文件读取。
     */
    public static synchronized String externalUrl(String internalUrl) {
        if (internalUrl == null || internalUrl.isEmpty() || server == null) return internalUrl;
        // 外调播放器运行在同一台设备上，但部分系统播放器会拒绝回环地址；
        // 代理已监听 0.0.0.0，这里改用局域网地址，仍使用同一个 token 和真实文件名。
        String host = com.github.catvod.utils.Util.getIp();
        if (host == null || host.isEmpty()) host = "127.0.0.1";
        String prefix = "http://" + host + ":" + server.getListeningPort() + "/";
        String internalPrefix = "http://127.0.0.1:" + server.getListeningPort() + "/";
        if (!internalUrl.startsWith(internalPrefix)) return internalUrl;
        internalUrl = internalPrefix + internalUrl.substring(internalPrefix.length());
        String rest = internalUrl.substring(internalPrefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return internalUrl;
        String token = rest.substring(0, slash);
        FileRef ref = FILES.get(token);
        if (ref == null) return internalUrl;
        String name = ref.path == null ? "" : ref.path.substring(ref.path.lastIndexOf((char) 92) + 1);
        if (name.isEmpty()) name = "media";
        return prefix + token + "/" + encodePathSegment(name) + "?smb=1";
    }

    /**
     * 内部：本机代理地址 → SMB 文件要素 {host, share, path, username, password, domain}。
     * 投屏只传这些要素，由接收端自己启动代理（见 {@link SmbCastSource}）。
     * 不是本代理生成的地址时返回 {@code null}。
     */
    static synchronized String[] fileRefOf(String internalUrl) {
        if (internalUrl == null || internalUrl.isEmpty() || server == null) return null;
        int port = server.getListeningPort();
        if (port <= 0) return null;
        String internalPrefix = "http://127.0.0.1:" + port + "/";
        if (!internalUrl.startsWith(internalPrefix)) return null;
        String rest = internalUrl.substring(internalPrefix.length());
        int slash = rest.indexOf('/');
        String token = slash < 0 ? rest : rest.substring(0, slash);
        FileRef ref = FILES.get(token);
        if (ref == null) return null;
        return new String[]{ref.host, ref.share, ref.path, ref.username, ref.password, ref.domain};
    }

    private static String digest(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString().substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String encodePathSegment(String value) {
        try {
            // 不用 API 33 才有的 Charset 重载，保证 Android 7/8 设备也能按 UTF-8 编码。
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required by the Java runtime", e);
        }
    }

    private static void ensureServer() throws IOException {
        if (server != null && server.getListeningPort() > 0) return;
        server = new ProxyServer();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    private static final class ProxyServer extends NanoHTTPD {
        ProxyServer() {
            // 投屏/推送会把 127.0.0.1 换成局域网地址再发给接收端；只绑回环地址时
            // 接收端会连到发送端的 127.0.0.1 而不是代理，表现为“网络读取失败”。
            // 监听全部网卡，本机播放仍走 127.0.0.1，局域网接收端也能访问同一个 token。
            super("0.0.0.0", 0);
        }

        @Override
        public Response serve(IHTTPSession request) {
            String uri = request.getUri();
            if (uri != null && uri.indexOf('?') >= 0) uri = uri.substring(0, uri.indexOf('?'));
            String token = uri == null ? "" : uri.replaceFirst("^/", "");
            // URL 形如 /<token>/<文件名>；token 只取第一段，文件名仅用于让播放器识别扩展名。
            int slash = token.indexOf('/');
            if (slash >= 0) token = token.substring(0, slash);
            FileRef ref;
            synchronized (SmbPlaybackProxy.class) {
                ref = FILES.get(token);
                if (ref == null) ref = THUMBS.get(token);
            }
            if (ref == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");

            SmbClient.RemoteFile remote = null;
            try {
                remote = SmbClient.openFile(ref.host, ref.share, ref.path,
                        ref.username, ref.password, ref.domain);
                long size = remote.length();
                String mime = mime(ref.path);
                String range = request.getHeaders().get("range");
                long start = 0;
                long end = Math.max(0, size - 1);
                boolean partial = false;
                if (range != null && range.startsWith("bytes=") && size > 0) {
                    String value = range.substring(6).trim();
                    int dash = value.indexOf('-');
                    if (dash >= 0) {
                        try {
                            String head = value.substring(0, dash).trim();
                            String tail = value.substring(dash + 1).trim();
                            if (head.isEmpty()) {
                                // AVI 的索引常在文件尾部，部分播放器会用 bytes=-N 读最后 N 字节；
                                // 这种后缀 Range 必须返回 206 + 正确的 Content-Range，返回 200 整文件会让解复用器拿不到索引。
                                long suffix = Long.parseLong(tail);
                                if (suffix > 0) {
                                    start = Math.max(0, size - suffix);
                                    end = size - 1;
                                    partial = true;
                                }
                            } else {
                                start = Long.parseLong(head);
                                if (!tail.isEmpty()) end = Math.min(size - 1, Long.parseLong(tail));
                                if (start >= 0 && start < size && end >= start) partial = true;
                            }
                        } catch (Exception ignored) {
                            start = 0;
                            end = size - 1;
                        }
                        if (!partial) {
                            start = 0;
                            end = size - 1;
                        }
                    }
                }
                if (size <= 0) {
                    remote.close();
                    return newFixedLengthResponse(Response.Status.NO_CONTENT, mime, "");
                }
                if (request.getMethod() == Method.HEAD) {
                    remote.close();
                    // 不能用空字符串构造 fixed-length 响应：NanoHTTPD 会先把 Content-Length 设为 0，
                    // 后面再 addHeader 也不会覆盖，播放器因此认为文件时长为 0。
                    Response head = newFixedLengthResponse(Response.Status.OK, mime,
                            new ByteArrayInputStream(new byte[0]), size);
                    head.addHeader("Accept-Ranges", "bytes");
                    return head;
                }
                final SmbClient.RemoteFile streamFile = remote;
                final long streamStart = start;
                final long streamEnd = end;
                final long contentLength = end - start + 1;
                final InputStream input = new InputStream() {
                    private final byte[] cache = new byte[64 * 1024];
                    private long remotePosition = streamStart;
                    private int cachePosition;
                    private int cacheLength;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        int count = read(one, 0, 1);
                        return count < 0 ? -1 : one[0] & 255;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (length == 0) return 0;
                        if (cachePosition >= cacheLength) {
                            long remaining = streamEnd - remotePosition + 1;
                            if (remaining <= 0) return -1;
                            int wanted = (int) Math.min((long) cache.length, remaining);
                            try {
                                int count = streamFile.read(cache, remotePosition, 0, wanted);
                                if (count <= 0) return -1;
                                remotePosition += count;
                                cachePosition = 0;
                                cacheLength = count;
                            } catch (RuntimeException e) {
                                throw new IOException(e);
                            }
                        }
                        int count = Math.min(length, cacheLength - cachePosition);
                        System.arraycopy(cache, cachePosition, buffer, offset, count);
                        cachePosition += count;
                        return count;
                    }

                    @Override
                    public void close() {
                        streamFile.close();
                    }
                };
                Response response = newFixedLengthResponse(
                        partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                        mime, input, contentLength);
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Cache-Control", "no-store");
                if (partial) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
                return response;
            } catch (Exception e) {
                if (remote != null) remote.close();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                        "SMB read failed: " + e.getClass().getSimpleName());
            }
        }
    }

    private static String mime(String path) {
        String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".mp4") || value.endsWith(".m4v")) return "video/mp4";
        if (value.endsWith(".mkv")) return "video/x-matroska";
        if (value.endsWith(".ts") || value.endsWith(".m2ts")) return "video/mp2t";
        if (value.endsWith(".avi")) return "video/x-msvideo";
        if (value.endsWith(".wmv") || value.endsWith(".asf")) return "video/x-ms-wmv";
        if (value.endsWith(".rmvb") || value.endsWith(".rm")) return "application/vnd.rn-realmedia";
        if (value.endsWith(".vob")) return "video/dvd";
        if (value.endsWith(".webm")) return "video/webm";
        if (value.endsWith(".mov")) return "video/quicktime";
        if (value.endsWith(".mpg") || value.endsWith(".mpeg")) return "video/mpeg";
        if (value.endsWith(".3gp") || value.endsWith(".3g2")) return "video/3gpp";
        if (value.endsWith(".mxf")) return "application/mxf";
        if (value.endsWith(".ogv")) return "video/ogg";
        if (value.endsWith(".mp3")) return "audio/mpeg";
        if (value.endsWith(".flac")) return "audio/flac";
        if (value.endsWith(".m4a") || value.endsWith(".m4b")) return "audio/mp4";
        if (value.endsWith(".aac")) return "audio/aac";
        if (value.endsWith(".wav")) return "audio/wav";
        if (value.endsWith(".ogg") || value.endsWith(".opus")) return "audio/ogg";
        if (value.endsWith(".wma")) return "audio/x-ms-wma";
        if (value.endsWith(".ape")) return "audio/x-ape";
        if (value.endsWith(".aiff") || value.endsWith(".aif")) return "audio/aiff";
        if (value.endsWith(".amr")) return "audio/amr";
        if (value.endsWith(".mid") || value.endsWith(".midi")) return "audio/midi";
        if (value.endsWith(".mka")) return "audio/x-matroska";
        if (value.endsWith(".ac3")) return "audio/ac3";
        if (value.endsWith(".dts")) return "audio/vnd.dts";
        if (value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".jpe")) return "image/jpeg";
        if (value.endsWith(".png")) return "image/png";
        if (value.endsWith(".gif")) return "image/gif";
        if (value.endsWith(".bmp")) return "image/bmp";
        if (value.endsWith(".webp")) return "image/webp";
        if (value.endsWith(".heic")) return "image/heic";
        if (value.endsWith(".heif")) return "image/heif";
        if (value.endsWith(".avif")) return "image/avif";
        if (value.endsWith(".tif") || value.endsWith(".tiff")) return "image/tiff";
        return "application/octet-stream";
    }

    private static final class FileRef {
        final String host;
        final String share;
        final String path;
        final String username;
        final String password;
        final String domain;

        FileRef(String host, String share, String path, String username, String password, String domain) {
            this.host = host;
            this.share = share;
            this.path = path;
            this.username = username;
            this.password = password;
            this.domain = domain;
        }
    }
}
