package com.fongmi.android.tv.smb;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;

/**
 * SMB 目录剧集的“身份证”。
 *
 * <p>以前点一个 SMB 目录里的视频，生成的剧集 vod 直接拿 {@link SmbPlaybackProxy} 的临时直链当
 * vod id：那个 token 是进程内存里的 UUID，进程一重启就失效，历史里既恢复不出剧集表，也打不开
 * 原来的文件，只剩“目录名 + 点的那一集”。这里改用稳定的目录描述符
 * {@code smb://host/share/dir?n=当前文件} 作为 vod id，历史点开时再由
 * {@link com.fongmi.android.tv.api.SiteApi#detailContent} 按描述符重新列目录、重建整张剧集表。</p>
 */
public final class SmbFolder {

    private static final String SCHEME = "smb://";
    /** 描述符里记录“点开时是哪一集”，只用于排序与选中，不是地址。 */
    private static final String CURRENT = "?n=";

    private SmbFolder() {
    }

    /**
     * @param host   可能是 {@code 192.168.0.5} 或 {@code 192.168.0.5:4450}（自家分享带端口）
     * @param folder 目录在共享内的相对路径，用 {@code \} 或 {@code /} 都行，空串表示共享根
     * @param current 点开的那一集文件名，用于重建成剧集后排在第一位
     */
    public static String encode(String host, String share, String folder, String current) {
        StringBuilder sb = new StringBuilder(SCHEME);
        sb.append(host).append('/').append(share);
        String dir = folder == null ? "" : folder.replace('\\', '/').trim();
        while (dir.startsWith("/")) dir = dir.substring(1);
        while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        if (!dir.isEmpty()) sb.append('/').append(dir);
        if (!TextUtils.isEmpty(current)) sb.append(CURRENT).append(UriCodec.encode(current));
        return sb.toString();
    }

    public static boolean isDescriptor(String value) {
        return value != null && value.startsWith(SCHEME) && !value.startsWith(SCHEME + "/");
    }

    /** 解析描述符；不是描述符时返回 null。 */
    public static Parts parse(String value) {
        if (!isDescriptor(value)) return null;
        String body = value.substring(SCHEME.length());
        String current = "";
        int mark = body.indexOf(CURRENT);
        if (mark >= 0) {
            current = UriCodec.decode(body.substring(mark + CURRENT.length()));
            body = body.substring(0, mark);
        }
        int slash = body.indexOf('/');
        if (slash <= 0) return null;
        String host = body.substring(0, slash);
        String rest = body.substring(slash + 1);
        if (host.isEmpty() || rest.isEmpty()) return null;
        String share;
        String folder;
        int second = rest.indexOf('/');
        if (second < 0) {
            share = rest;
            folder = "";
        } else {
            share = rest.substring(0, second);
            folder = rest.substring(second + 1);
        }
        if (share.isEmpty()) return null;
        return new Parts(host, share, folder.replace('/', '\\'), current);
    }

    /**
     * 按描述符重建整张剧集表：列目录 → 生成代理直链 → 点开的那一集排第一。
     * 目录读不到（离线、口令变了）时回退成单集，至少不挡住播放。
     */
    public static Vod detail(String id, String name) throws Exception {
        Parts parts = parse(partsOf(id));
        Vod vod = new Vod();
        vod.setId(id);
        // 片名优先用描述符里记的那一集（文件名本身），目录名只当兜底：
        // 以前历史里那一条的标题就是目录名，跟实际在放的东西对不上。
        String title = !parts.current.isEmpty() ? stripExtension(parts.current) : "";
        if (title.isEmpty()) title = TextUtils.isEmpty(name) ? folderName(parts.folder) : name;
        vod.setName(title);
        vod.setPlayFrom("SMB");
        List<SmbClient.Entry> files = SmbClient.listFiles(parts.host, parts.share, parts.folder,
                parts.username, parts.password, parts.domain);
        List<SmbClient.Entry> videos = new ArrayList<>();
        for (SmbClient.Entry entry : files) {
            if (!entry.directory && SmbClient.isVideo(entry.name)) videos.add(entry);
        }
        videos.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        List<SmbClient.Entry> ordered = new ArrayList<>();
        for (SmbClient.Entry entry : videos) {
            if (parts.current.equals(entry.name)) ordered.add(0, entry);
            else ordered.add(entry);
        }
        // 直链池只有 24 条，超了会把最早注册的那条（正好是当前集）挤掉。
        while (ordered.size() > 24) ordered.remove(ordered.size() - 1);
        if (ordered.isEmpty()) {
            vod.setPlayUrl("");
            return vod;
        }
        StringBuilder urls = new StringBuilder();
        String playUrl = null;
        for (SmbClient.Entry entry : ordered) {
            String remote = parts.folder.isEmpty() ? entry.name : parts.folder + "\\" + entry.name;
            String url = SmbPlaybackProxy.createUrl(parts.host, parts.share, remote,
                    parts.username, parts.password, parts.domain);
            if (playUrl == null) playUrl = url;
            if (urls.length() > 0) urls.append('#');
            urls.append(stripExtension(entry.name)).append('$').append(url);
        }
        vod.setPlayUrl(playUrl);
        Flag flag = Flag.create("SMB", urls.toString());
        for (com.fongmi.android.tv.bean.Episode episode : flag.getEpisodes()) {
            if (playUrl.equals(episode.getUrl())) episode.setSelected(true);
        }
        vod.setFlags(new ArrayList<>());
        vod.getFlags().add(flag);
        SpiderDebug.log("smb-folder", "rebuild id=%s episodes=%d", id, ordered.size());
        return vod;
    }

    private static String partsOf(String id) {
        int mark = id.lastIndexOf('|');
        return mark > 0 ? id.substring(0, mark) : id;
    }

    private static String folderName(String folder) {
        if (TextUtils.isEmpty(folder)) return "SMB";
        int cut = folder.lastIndexOf('\\');
        return cut < 0 ? folder : folder.substring(cut + 1);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static final class Parts {

        public final String host;
        public final String share;
        public final String folder;
        public final String current;
        public final String username;
        public final String password;
        public final String domain;

        private Parts(String host, String share, String folder, String current) {
            this.host = host;
            this.share = share;
            this.folder = folder;
            this.current = current;
            SmbCredentialStore.Stored stored = SmbCredentialStore.load(App.get(), host);
            this.username = stored == null ? "" : stored.username;
            this.password = stored == null ? "" : stored.password;
            this.domain = stored == null ? "" : stored.domain;
        }
    }

    /** 描述符里带的是文件名，不走 URL 编码器（那边会把空格变成 +），只做最小转义。 */
    private static final class UriCodec {

        static String encode(String value) {
            try {
                return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
            } catch (Exception e) {
                return value;
            }
        }

        static String decode(String value) {
            try {
                return java.net.URLDecoder.decode(value, "UTF-8");
            } catch (Exception e) {
                return value;
            }
        }
    }
}
