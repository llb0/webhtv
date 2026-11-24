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
    private static final int MAX_THUMBS = 512;
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
     * 由代理地址反查缩略图缓存键：播放中的地址只带 token，缩略图要按 host/share/path 落盘，
     * 播放时补图就靠这一步把两边对上。不是 SMB 代理地址时返回 null。
     */
    public static synchronized String keyForUrl(String url) {
        if (url == null) return null;
        String marker = "://";
        int scheme = url.indexOf(marker);
        int slash = url.indexOf('/', scheme + marker.length());
        if (slash < 0) return null;
        String rest = url.substring(slash + 1);
        int cut = rest.indexOf('/');
        if (cut <= 0) return null;
        String token = rest.substring(0, cut);
        FileRef ref = FILES.get(token);
        if (ref == null) ref = THUMBS.get(token);
        if (ref == null) return null;
        return SmbThumbnailCache.key(ref.host, ref.share, ref.path);
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

    private static long rangeWindowStart;
    private static int rangeWindowCount;
    private static long lastProblemAt;

    private static long streamWindowStart;
    private static int streamCount;
    private static long streamBytes;
    private static long streamMaxMs;
    private static int streamSlowCount;

    /** range 请求按窗口汇总，避免老容器逐块请求把日志刷爆。 */
    private static void logRange(long start, long end, boolean partial, String range) {
        if (!com.github.catvod.crawler.SpiderDebug.isEnabled()) return;
        long now = System.currentTimeMillis();
        synchronized (SmbPlaybackProxy.class) {
            rangeWindowCount++;
            if (rangeWindowStart == 0) rangeWindowStart = now;
            if (now - rangeWindowStart < 3000) return;
            int count = rangeWindowCount;
            rangeWindowCount = 0;
            rangeWindowStart = now;
            com.github.catvod.crawler.SpiderDebug.log("smb-proxy",
                    "range-window count=%d last=%d-%d partial=%s range=%s", count, start, end, partial, range);
        }
    }

    /**
     * 每条响应结束时记一次「服务端侧」的实际产出：发了多少字节、耗时多久。
     * 卡顿到底是播放器自己一小口一小口要（每次几 KB、我们毫秒级发完），
     * 还是我们这边响应本身就慢（几百毫秒），全看这组数。
     */
    private static void recordStream(long bytes, long elapsedMs) {
        if (!com.github.catvod.crawler.SpiderDebug.isEnabled()) return;
        long now = System.currentTimeMillis();
        synchronized (SmbPlaybackProxy.class) {
            streamCount++;
            streamBytes += Math.max(0, bytes);
            if (elapsedMs > streamMaxMs) streamMaxMs = elapsedMs;
            if (elapsedMs >= 300) streamSlowCount++;
            if (streamWindowStart == 0) streamWindowStart = now;
            if (now - streamWindowStart < 3000) return;
            int count = streamCount;
            long bytesTotal = streamBytes;
            long maxMs = streamMaxMs;
            int slow = streamSlowCount;
            streamCount = 0;
            streamBytes = 0;
            streamMaxMs = 0;
            streamSlowCount = 0;
            streamWindowStart = now;
            com.github.catvod.crawler.SpiderDebug.log("smb-proxy",
                    "stream-stat count=%d bytes=%d maxMs=%d slow=%d", count, bytesTotal, maxMs, slow);
        }
    }

    private static void logProblem(String message) {
        if (!com.github.catvod.crawler.SpiderDebug.isEnabled()) return;
        long now = System.currentTimeMillis();
        synchronized (SmbPlaybackProxy.class) {
            if (now - lastProblemAt < 2000) return;
            lastProblemAt = now;
            com.github.catvod.crawler.SpiderDebug.log("smb-proxy", "%s", message);
        }
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

            SmbStreamReader stream = null;
            try {
                stream = SmbStreamReader.open(token, ref.host, ref.share, ref.path,
                        ref.username, ref.password, ref.domain);
                long size = stream.length();
                String mime = mime(ref.path);
                String range = request.getHeaders().get("range");
                long start = 0;
                long end = Math.max(0, size - 1);
                boolean partial = false;
                boolean unsatisfiable = false;
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
                                if (start >= 0 && start < size && end >= start) {
                                    partial = true;
                                } else if (start >= size || end < start) {
                                    // 越界 range 以前被当成「没写 range」返回 200 整文件，播放器拿到从 0 开始的数据
                                    // 就会把进度重置成 0 再慢慢读一遍（拖动后时间归零、一直转圈）。这里如实回 416。
                                    unsatisfiable = true;
                                }
                            }
                        } catch (Exception ignored) {
                            start = 0;
                            end = size - 1;
                        }
                        if (!partial && !unsatisfiable) {
                            start = 0;
                            end = size - 1;
                        }
                    }
                }
                if (size <= 0) {
                    stream.close();
                    return newFixedLengthResponse(Response.Status.NO_CONTENT, mime, "");
                }
                if (unsatisfiable) {
                    stream.close();
                    Response rejected = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
                    rejected.addHeader("Content-Range", "bytes */" + size);
                    logProblem("range-416 range=" + range + " size=" + size);
                    return rejected;
                }
                if (request.getMethod() == Method.HEAD) {
                    stream.close();
                    // 不能用空字符串构造 fixed-length 响应：NanoHTTPD 会先把 Content-Length 设为 0，
                    // 后面再 addHeader 也不会覆盖，播放器因此认为文件时长为 0。
                    Response head = newFixedLengthResponse(Response.Status.OK, mime,
                            new ByteArrayInputStream(new byte[0]), size);
                    head.addHeader("Accept-Ranges", "bytes");
                    return head;
                }
                final SmbStreamReader streamReader = stream;
                final long streamStart = start;
                final long streamEnd = end;
                final long contentLength = end - start + 1;
                final InputStream input = new InputStream() {
                    private long position = streamStart;
                    private final long startedAt = System.currentTimeMillis();
                    private boolean reported;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        int count = read(one, 0, 1);
                        return count < 0 ? -1 : one[0] & 255;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (length == 0) return 0;
                        long remaining = streamEnd - position + 1;
                        if (remaining <= 0) return -1;
                        int wanted = (int) Math.min((long) length, remaining);
                        int count = streamReader.read(position, buffer, offset, wanted);
                        if (count <= 0) {
                            // 不该发生的提前结束：播放器会把这次响应当成截断，然后重新发起 range 请求，
                            // 在日志里就表现为「offset 每次只前进几百字节、反复 open」。记下来便于定位。
                            logProblem("premature-end pos=" + position + " end=" + streamEnd + " size=" + size);
                            throw new IOException("smb stream ended early at " + position);
                        }
                        position += count;
                        return count;
                    }

                    @Override
                    public void close() {
                        if (!reported) {
                            reported = true;
                            recordStream(position - streamStart, System.currentTimeMillis() - startedAt);
                        }
                        streamReader.close();
                    }
                };
                Response response = newFixedLengthResponse(
                        partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                        mime, input, contentLength);
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Cache-Control", "no-store");
                if (partial) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
                logRange(start, end, partial, range);
                return response;
            } catch (Exception e) {
                if (stream != null) stream.close();
                logProblem("serve-fail " + ref.path + " : " + e);
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
