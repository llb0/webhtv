package com.fongmi.android.tv.web;

import android.text.TextUtils;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebHome 网页广告过滤（原生层）。
 *
 * 【跨域的正确含义】
 * 指的是「点击后打开的新页面属于别的站点」，即 &lt;a href&gt; 指向外站，
 * 不是图片资源跨域。影视站的广告几乎必然是外跳链接。
 * JS 层（webhome-adblock.js）负责隐藏这些 &lt;a&gt; 及其容器，
 * 原生层负责 JS 挡不住的部分：location.href= 强制跳转、window.open 弹窗、
 * 以及按域名清单直接断掉广告资源请求。
 *
 * 三层分工：
 *   1. shouldOverrideUrlLoading —— 拦外跳导航（JS 阻止不了的重定向）
 *   2. onCreateWindow           —— 拦弹窗（广告最常用的手段）
 *   3. shouldInterceptRequest   —— 按广告域名清单断资源（含 &lt;img&gt;/&lt;script&gt;/&lt;iframe&gt;，
 *                                  这些是 WebView 直接发的，JS 完全看不到）
 *
 * 参考 hikerView FloatWebView：
 *   - 广告域名清单（实测 40 个国内影视站域名）
 *   - getCoreDomainFromUrl 核心域名算法（去 www/m/wap 前缀 + 复合后缀）
 */
public class WebHomeAdBlocker {

    private static volatile WebHomeAdBlocker instance;

    // ---------------- 广告域名清单 ----------------
    // 前 40 个来自 hikerView FloatWebView 的实测清单，覆盖国内影视站；
    // 后面补充通用广告/统计域名。
    // 判定用 contains，因此写 "googleads." 这种带点的前缀即可覆盖其子域。
    private static final String[] AD_DOMAINS = {
            // —— hikerView FloatWebView 实测清单 ——
            "doubleclick.net", "googleads.", "adnxs.com", "tencentads.", "baiduads.", "sinaads.",
            "m3u8.xiangodt.com", "6080kan.cc", "mimg.0c1q0l.cn", "www.googletagmanager.com",
            "mc.usihnbcq.cn", "mg.g1mm3d.cn", "mscs.svaeuzh.cn", "cnzz.hhttm.top", "tp.vinuxhome.com",
            "cnzz.mmstat.com", "www.baihuillq.com", "stj.v1vo.top", "img.mosflower.cn", "tips.gamevvip.com",
            "ehwe.yhdtns.com", "xdn.cqqc3.com", "www.jixunkyy.cn", "sp.chemacid.cn", "um.cavuc.com",
            "mav.mavuz.com", "wofwk.aoidf3.com", "xc.hubeijieshikj.cn", "tj.tianwenhu.com", "xg.gars57.cn",
            "k.jinxiuzhilv.com", "ppl.xunzhuo123.com", "xomk.jiangjunmh.top", "img.xunzhuo123.com",
            "xg.huataisangao.cn", "thy.dacedsfa.cn", "xg.whsbpw.cn", "f5w.as12df.top", "www.92424.cn",
            "k.wudejia.com", "vivovip.mmszxc.top", "qiu.xixiqiu.com", "cdnjs.hnfenxun.com", "cms.qdwght.com",

            // —— 通用广告 / 统计 / 追踪 ——
            "google-analytics.com", "googlesyndication.com", "googleadservices.com",
            "amazon-adsystem.com", "criteo.com", "pubmatic.com", "rubiconproject.com",
            "openx.net", "casalemedia.com", "appnexus.com", "quantserve.com",
            "scorecardresearch.com", "outbrain.com", "taboola.com", "mgid.com",
            "revcontent.com", "popads.net", "popcash.net", "propellerads.com",
            "adsterra.com", "juicyads.com", "exoclick.com", "hilltopads.net",
            "clickadu.com", "trafficjunky.net",
            "hm.baidu.com", "pos.baidu.com", "cbjs.baidu.com", "cpro.baidustatic.com",
            "cb.baidu.com", "tanx.com", "alimama.com", "mmstat.com",
            "union.sogou.com", "cnzz.com", "51.la", "talkingdata.com",
            "umeng.com", "sensorsdata.cn", "qstat.net", "union.jd.com",
            "gclick.cn", "adsame.com", "max.mobad.cn", "sdk.mobad.cn"
    };

    // 指向这些的文件直链不是外跳（海报大图 / 播放地址），不能拦
    private static final String[] MEDIA_EXT = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg", ".avif",
            ".mp4", ".m3u8", ".mpd", ".mkv", ".flv", ".webm", ".m4a", ".mp3",
            ".ts", ".avi", ".mov", ".rmvb"
    };

    // 跳板特征：同域但明显是中转
    private static final String[] REDIRECT_HINT = {
            "?url=", "&url=", "?u=http", "&u=http", "?to=http", "&to=http",
            "?goto=", "&goto=", "?go=http", "&go=http", "?redirect=", "&redirect=",
            "/go.php", "/jump.php", "/out.php", "/link.php", "/redirect.php", "/click.php"
    };

    private static final String[] COMMON_PREFIX = {
            "www.", "m.", "wap.", "mobile.", "wap2.", "www2.", "www3.",
            "tv.", "v.", "cdn.", "img.", "static.", "s.", "pic.", "res."
    };

    private static final String[] SPECIAL_SUFFIX = {
            ".com.cn", ".net.cn", ".org.cn", ".gov.cn", ".edu.cn", ".ac.cn",
            ".com.hk", ".com.mo", ".com.tw", ".net.tw", ".co.uk", ".org.uk",
            ".gov.uk", ".ac.uk", ".co.jp", ".ne.jp", ".or.jp", ".go.jp",
            ".co.au", ".com.au", ".net.au", ".org.au", ".co.ca", ".com.ca",
            ".org.ca", ".co.in", ".com.in", ".net.in", ".co.kr", ".com.sg",
            ".co.sg", ".com.my", ".co.my"
    };

    private final List<String> adDomains = new ArrayList<>(Arrays.asList(AD_DOMAINS));
    private final Set<String> whitelist = new HashSet<>();   // 用户/运行时加白
    private final Set<String> logOnce = new HashSet<>();

    private WebHomeAdBlocker() {
        // 默认白名单：本地网关与嗅探播放必须放行
        whitelist.add("127.0.0.1");
        whitelist.add("localhost");
    }

    public static WebHomeAdBlocker get() {
        if (instance == null) {
            synchronized (WebHomeAdBlocker.class) {
                if (instance == null) instance = new WebHomeAdBlocker();
            }
        }
        return instance;
    }

    // ---------------- 配置 ----------------

    /** 追加广告域名（保留内置清单）。 */
    public synchronized void addAdDomains(List<String> extra) {
        if (extra == null) return;
        for (String s : extra) {
            if (!TextUtils.isEmpty(s) && !adDomains.contains(s)) adDomains.add(s.trim());
        }
    }

    /** 加白：核心域名或完整 host。 */
    public synchronized void whitelist(String domain) {
        if (!TextUtils.isEmpty(domain)) whitelist.add(domain.toLowerCase(Locale.ROOT));
    }

    public synchronized void removeWhitelist(String domain) {
        if (!TextUtils.isEmpty(domain)) whitelist.remove(domain.toLowerCase(Locale.ROOT));
    }

    public synchronized List<String> getAdDomains() {
        return new CopyOnWriteArrayList<>(adDomains);
    }

    // ---------------- 工具 ----------------

    /** 提取核心域名（移植自 FloatWebView.getCoreDomainFromUrl）。 */
    public static String coreDomain(String url) {
        String host = hostOf(url);
        if (TextUtils.isEmpty(host)) return "";
        String hostname = host.toLowerCase(Locale.ROOT);
        // 纯 IP / localhost 原样返回
        if (hostname.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return hostname;
        if ("localhost".equals(hostname)) return hostname;
        for (String p : COMMON_PREFIX) {
            if (hostname.startsWith(p)) {
                hostname = hostname.substring(p.length());
                break;
            }
        }
        String core = null;
        for (String suf : SPECIAL_SUFFIX) {
            if (hostname.length() > suf.length() && hostname.endsWith(suf)) {
                String prefix = hostname.substring(0, hostname.length() - suf.length());
                String[] pp = prefix.split("\\.");
                core = pp[pp.length - 1];
                break;
            }
        }
        if (core == null) {
            String[] parts = hostname.split("\\.");
            core = parts.length >= 2 ? parts[parts.length - 2] : hostname;
        }
        return core;
    }

    private static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            String s = url.trim();
            int scheme = s.indexOf("://");
            if (scheme < 0) return "";
            int start = scheme + 3;
            int end = s.length();
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '/' || c == '?' || c == '#' || c == ':') { end = i; break; }
            }
            return s.substring(start, end);
        } catch (Throwable e) {
            return "";
        }
    }

    private boolean isWhitelisted(String url) {
        if (TextUtils.isEmpty(url)) return true;
        String host = hostOf(url).toLowerCase(Locale.ROOT);
        String core = coreDomain(url);
        if (whitelist.contains(host)) return true;
        return !TextUtils.isEmpty(core) && whitelist.contains(core);
    }

    /** 是否图片/视频直链。 */
    public static boolean isMediaUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String s = url.toLowerCase(Locale.ROOT);
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h > 0) s = s.substring(0, h);
        for (String ext : MEDIA_EXT) {
            if (s.endsWith(ext)) return true;
        }
        return false;
    }

    /** 是否跳板（同域中转外站）。 */
    public static boolean isRedirectUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String s = url.toLowerCase(Locale.ROOT);
        for (String hint : REDIRECT_HINT) {
            if (s.contains(hint)) return true;
        }
        return false;
    }

    /** 是否命中广告域名清单。 */
    public synchronized boolean isAdDomain(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String s = url.toLowerCase(Locale.ROOT);
        for (String ad : adDomains) {
            if (s.contains(ad)) return true;
        }
        return false;
    }

    /**
     * 是否「外跳链接」——点了会离开当前站。
     * 这是广告的核心判据，与 JS 层 isOutboundLink 保持一致。
     */
    public synchronized boolean isOutbound(String pageUrl, String targetUrl) {
        if (TextUtils.isEmpty(targetUrl)) return false;
        String s = targetUrl.trim();
        if (s.startsWith("#") || s.startsWith("javascript:")) return false;
        if (s.startsWith("data:") || s.startsWith("blob:")) return false;
        if (isWhitelisted(s)) return false;
        // 图片/视频直链是海报或播放地址，不是外跳
        if (isMediaUrl(s)) return false;
        // 命中广告域名清单：高置信
        if (isAdDomain(s)) return true;
        // 跳板
        if (isRedirectUrl(s)) return true;
        // 跨域
        String pc = coreDomain(pageUrl);
        String tc = coreDomain(s);
        return !TextUtils.isEmpty(pc) && !TextUtils.isEmpty(tc) && !pc.equals(tc);
    }

    // ---------------- 拦截入口 ----------------

    /**
     * 资源层拦截：shouldInterceptRequest 里调用。
     * 只按广告域名清单断请求，不做"所有跨域图片"一刀切——
     * 海报常放在 CDN 上，一刀切会误杀（这点 JS 层同理）。
     */
    public WebResourceResponse intercept(WebResourceRequest request) {
        if (!Setting.isWebHomeAdblock()) return null;
        if (request == null || request.getUrl() == null) return null;
        String url = request.getUrl().toString();
        if (!isAdDomain(url)) return null;
        if (isWhitelisted(url)) return null;
        if (logOnce.add(url)) SpiderDebug.log("webhome-adblock", "blocked resource=%s", url);
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    /**
     * 导航层拦截：shouldOverrideUrlLoading 里调用。返回 true 表示已拦截（不跳转）。
     *
     * JS 能隐藏 &lt;a&gt;，但挡不住 location.href= / meta refresh / 脚本触发的重定向，
     * 这一层专门兜底。
     *
     * 为避免误伤"点播放跳到外站 CDN"这类正常行为，
     * 默认只拦【命中广告域名清单】或【跳板】的高置信目标；
     * 想连普通跨域外跳一起拦，打开 Setting.isWebHomeAdblockNav()。
     */
    public boolean shouldBlockNavigation(String pageUrl, String targetUrl) {
        if (!Setting.isWebHomeAdblock()) return false;
        if (TextUtils.isEmpty(targetUrl)) return false;
        if (isWhitelisted(targetUrl)) return false;
        if (isMediaUrl(targetUrl)) return false;

        boolean confirmed = isAdDomain(targetUrl) || isRedirectUrl(targetUrl);
        if (confirmed) {
            if (logOnce.add(targetUrl)) SpiderDebug.log("webhome-adblock", "blocked nav=%s", targetUrl);
            return true;
        }
        // 激进模式：所有跨域外跳都拦（可能拦掉跳外站播放的正常跳转）
        if (Setting.isWebHomeAdblockNav()) {
            if (isOutbound(pageUrl, targetUrl)) {
                if (logOnce.add(targetUrl)) SpiderDebug.log("webhome-adblock", "blocked nav(cross)=%s", targetUrl);
                return true;
            }
        }
        return false;
    }

    /**
     * 弹窗拦截：WebChromeClient.onCreateWindow 里调用。
     * 返回 true 表示拦截该弹窗。广告弹窗目标几乎必然是外站。
     */
    public boolean shouldBlockPopup(String pageUrl, String targetUrl) {
        if (!Setting.isWebHomeAdblock()) return false;
        if (isWhitelisted(targetUrl)) return false;
        if (isMediaUrl(targetUrl)) return false;
        boolean hit = isAdDomain(targetUrl) || isRedirectUrl(targetUrl) || isOutbound(pageUrl, targetUrl);
        if (hit && logOnce.add("popup:" + targetUrl)) {
            SpiderDebug.log("webhome-adblock", "blocked popup=%s", targetUrl);
        }
        return hit;
    }
}
