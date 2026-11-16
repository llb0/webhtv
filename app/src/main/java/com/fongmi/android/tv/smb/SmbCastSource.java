package com.fongmi.android.tv.smb;

/**
 * 投屏用的 SMB 源描述符：投屏只传 SMB 的连接要素（主机、共享、路径、账号），
 * 由接收端自己启动 {@link SmbPlaybackProxy} 并按本机回环地址播放，
 * 完全不依赖发送端的进程、端口和局域网地址——发送端退出后接收端仍能继续播。
 *
 * <p>描述符形如 {@code smbcast://<host>/<share>/<path>?u=..&p=..&d=..}，每段按 UTF-8
 * 百分号编码，因此可以安全地放进 {@code History} 的 key（{@code siteKey@@@vodId@@@cid}），
 * 既不会被 "@@@" 切分破坏，也不会被 Gson 的 HTML 转义弄坏。</p>
 */

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

public final class SmbCastSource {

    public static final String SCHEME = "smbcast://";

    private SmbCastSource() {
    }

    /**
     * 发送端：本机代理地址 → 描述符。
     *
     * @return {@code null} 表示这条地址不是本机 SMB 代理地址，调用方按普通地址处理。
     */
    public static String describe(String localProxyUrl) {
        String[] ref = SmbPlaybackProxy.fileRefOf(localProxyUrl);
        if (ref == null) return null;
        return SCHEME + encode(ref[0]) + "/" + encode(ref[1]) + "/" + encode(ref[2])
                + "?u=" + encode(ref[3]) + "&p=" + encode(ref[4]) + "&d=" + encode(ref[5]);
    }

    public static boolean isDescriptor(String value) {
        return parse(value) != null;
    }

    /**
     * 接收端：描述符 → 本机代理地址（代理未启动时会在这一步启动）。
     *
     * @return {@code null} 表示不是描述符。
     */
    public static String toLocalUrl(String value) throws IOException {
        Parts parts = parse(value);
        if (parts == null) return null;
        return SmbPlaybackProxy.createUrl(parts.host, parts.share, parts.path,
                parts.username, parts.password, parts.domain);
    }

    /** 描述符里携带的文件名：接收端片名和 SMB 播放的命名都靠它，别把整串描述符当片名显示。 */
    public static String titleOf(String value) {
        Parts parts = parse(value);
        if (parts == null) return "";
        String path = parts.path;
        return path.substring(Math.max(path.lastIndexOf((char) 92), path.lastIndexOf('/')) + 1);
    }

    private static Parts parse(String value) {
        if (value == null || !value.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) return null;
        String body = value.substring(SCHEME.length());
        String query = "";
        int q = body.indexOf('?');
        if (q >= 0) {
            query = body.substring(q + 1);
            body = body.substring(0, q);
        }
        String[] segs = body.split("/", -1);
        if (segs.length < 3) return null;
        try {
            String host = decode(segs[0]);
            String share = decode(segs[1]);
            // 路径里的斜杠已被编码，正常只有 3 段；万一出现多余分段，按“第 3 段起合并”还原。
            StringBuilder path = new StringBuilder(decode(segs[2]));
            for (int i = 3; i < segs.length; i++) path.append('/').append(decode(segs[i]));
            if (host.isEmpty() || share.isEmpty() || path.length() == 0) return null;
            String username = "";
            String password = "";
            String domain = "";
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String key = pair.substring(0, eq);
                String val = decode(pair.substring(eq + 1));
                if ("u".equals(key)) username = val;
                else if ("p".equals(key)) password = val;
                else if ("d".equals(key)) domain = val;
            }
            return new Parts(host, share, path.toString(), username, password, domain);
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(String value) {
        try {
            // 不用 API 33 才有的 Charset 重载，Android 7/8 也能按 UTF-8 编码。
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required by the Java runtime", e);
        }
    }

    private static String decode(String value) throws UnsupportedEncodingException {
        return URLDecoder.decode(value == null ? "" : value, "UTF-8");
    }

    private static final class Parts {
        final String host;
        final String share;
        final String path;
        final String username;
        final String password;
        final String domain;

        Parts(String host, String share, String path, String username, String password, String domain) {
            this.host = host;
            this.share = share;
            this.path = path;
            this.username = username;
            this.password = password;
            this.domain = domain;
        }
    }
}
