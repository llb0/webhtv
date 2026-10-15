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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * WebHome 网页广告过滤（原生层）。
 *
 * 对应海阔视界的 AdblockHolder：在 shouldInterceptRequest 里做 URL 匹配，
 * 命中的请求直接返回空响应，广告资源根本不会发出网络请求。
 *
 * 这是 JS 层 hook 做不到的——JS 只能拦 fetch / XHR，
 * 而 <script src>、<img src>、<iframe src> 这些是 WebView 直接发起的。
 *
 * 规则语法为 Adblock Plus 子集：
 *   ||domain^            域名及子域，任意协议
 *   |url                 精确前缀
 *   text                 子串匹配
 *   @@rule               白名单（优先级最高）
 *   *                    通配符
 *   ^                    分隔符（非字母数字 _ - . % 或结尾）
 *   !                    注释
 */
public class WebHomeAdBlocker {

    private static volatile WebHomeAdBlocker instance;

    // 内置规则：覆盖最常见的广告 / 统计 / 追踪域名与路径
    // 更细的元素隐藏规则由 JS 层 webhome-adblock.js 负责
    private static final String[] DEFAULT_RULES = {
            // 白名单：本地网关与自身
            "@@||127.0.0.1^",
            "@@||localhost^",
            "@@/webResource",

            // Google 广告 / 统计
            "||google-analytics.com^",
            "||googletagmanager.com^",
            "||googlesyndication.com^",
            "||googleadservices.com^",
            "||doubleclick.net^",
            "||adservice.google.com^",

            // 国际广告平台
            "||amazon-adsystem.com^",
            "||criteo.com^",
            "||pubmatic.com^",
            "||rubiconproject.com^",
            "||openx.net^",
            "||adnxs.com^",
            "||casalemedia.com^",
            "||appnexus.com^",
            "||quantserve.com^",
            "||scorecardresearch.com^",
            "||outbrain.com^",
            "||taboola.com^",
            "||mgid.com^",
            "||revcontent.com^",
            "||popads.net^",
            "||popcash.net^",
            "||propellerads.com^",
            "||adsterra.com^",
            "||juicyads.com^",
            "||exoclick.com^",
            "||hilltopads.net^",
            "||clickadu.com^",
            "||trafficjunky.net^",

            // 国内广告 / 统计
            "||hm.baidu.com^",
            "||baidustatic.com^",
            "||pos.baidu.com^",
            "||cbjs.baidu.com^",
            "||cpro.baidustatic.com^",
            "||cb.baidu.com^",
            "||tanx.com^",
            "||alimama.com^",
            "||mmstat.com^",
            "||union.sogou.com^",
            "||cnzz.com^",
            "||51.la^",
            "||web.51.la^",
            "||js.users.51.la^",
            "||talkingdata.com^",
            "||umeng.com^",
            "||sensorsdata.cn^",
            "||qstat.net^",
            "||union.jd.com^",
            "||gclick.cn^",
            "||adsame.com^",
            "||max.mobad.cn^",
            "||sdk.mobad.cn^",

            // 广告路径 / 关键词（子串兜底）
            "/ad.js",
            "/ads.js",
            "/ads/",
            "/adview",
            "/adserver",
            "/ad-banner",
            "/ad-script",
            "/adsbygoogle",
            "/google_ad",
            "/popunder",
            "/popupad",
            "/floatad",
            "/guanggao",
            "/gg.js",
            "/advert",
            "/advertise",
            "adclient=",
            "adsbygoogle",
            "google_ad_client",
            "google_ad_slot",
            "utm_source=ad"
    };

    private final List<Rule> rules = new ArrayList<>();
    private final Set<String> logOnce = new HashSet<>();
    private volatile String signature = "";

    private WebHomeAdBlocker() {
        load(Arrays.asList(DEFAULT_RULES));
    }

    public static WebHomeAdBlocker get() {
        if (instance == null) {
            synchronized (WebHomeAdBlocker.class) {
                if (instance == null) instance = new WebHomeAdBlocker();
            }
        }
        return instance;
    }

    private static final class Rule {
        final Pattern pattern;
        final boolean except;

        Rule(Pattern pattern, boolean except) {
            this.pattern = pattern;
            this.except = except;
        }
    }

    /**
     * ABP 规则 -> 正则（子集实现）。
     */
    private static Pattern compile(String raw) {
        String p = raw;
        boolean domainAnchor = false;
        boolean anchoredStart = false;
        boolean anchoredEnd = false;

        if (p.startsWith("||")) {
            p = p.substring(2);
            domainAnchor = true;
        } else if (p.startsWith("|")) {
            p = p.substring(1);
            anchoredStart = true;
        }
        if (p.endsWith("|") && !domainAnchor) {
            p = p.substring(0, p.length() - 1);
            anchoredEnd = true;
        }

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') body.append("[\\s\\S]*");
            else if (c == '^') body.append("(?:[^\\w\\-.%]|$)");
            else if (".+?${}()|[]\\".indexOf(c) >= 0) body.append('\\').append(c);
            else body.append(c);
        }

        String re;
        if (domainAnchor) {
            String d = p.startsWith("www.") ? p.substring(4) : p;
            StringBuilder db = new StringBuilder();
            for (int i = 0; i < d.length(); i++) {
                char c = d.charAt(i);
                if (c == '*') db.append("[\\s\\S]*");
                else if (c == '^') db.append("(?:[^\\w\\-.%]|$)");
                else if (".+?${}()|[]\\".indexOf(c) >= 0) db.append('\\').append(c);
                else db.append(c);
            }
            re = "^https?://(?:[\\w-]+\\.)*" + db;
        } else if (anchoredStart) {
            re = "^" + body;
        } else {
            re = body.toString();
        }
        if (anchoredEnd) re = re + "$";
        try {
            return Pattern.compile(re, Pattern.CASE_INSENSITIVE);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 加载规则（内置 + 自定义）。自定义规则可来自配置层或用户设置。
     */
    public synchronized void load(List<String> list) {
        rules.clear();
        if (list == null) return;
        for (String line : list) {
            if (TextUtils.isEmpty(line)) continue;
            String raw = line.trim();
            if (raw.isEmpty() || raw.startsWith("!")) continue;
            if (raw.startsWith("##") || raw.startsWith("#@#") || raw.contains("##")) continue; // 元素隐藏交给 JS
            boolean except = raw.startsWith("@@");
            String body = except ? raw.substring(2) : raw;
            int dollar = body.indexOf('$');
            if (dollar >= 0) body = body.substring(0, dollar); // 暂不支持 $ 选项，直接忽略
            if (body.isEmpty()) continue;
            Pattern pattern = compile(body);
            if (pattern != null) rules.add(new Rule(pattern, except));
        }
        signature = String.valueOf(list.size()) + "@" + System.currentTimeMillis();
        SpiderDebug.log("webhome-adblock", "loaded rules=%d", rules.size());
    }

    /**
     * 追加自定义规则（保留内置规则）。
     */
    public synchronized void addRules(List<String> extra) {
        List<String> merged = new ArrayList<>(Arrays.asList(DEFAULT_RULES));
        if (extra != null) merged.addAll(extra);
        load(merged);
    }

    /**
     * 判断 URL 是否应被拦截。白名单优先级最高。
     */
    public boolean matches(String url) {
        if (TextUtils.isEmpty(url)) return false;
        boolean blocked = false;
        List<Rule> snapshot = this.rules;
        for (int i = 0; i < snapshot.size(); i++) {
            Rule rule = snapshot.get(i);
            if (!rule.pattern.matcher(url).find()) continue;
            if (rule.except) return false;
            blocked = true;
        }
        return blocked;
    }

    /**
     * 在 shouldInterceptRequest 里调用。返回 null 表示放行。
     */
    public WebResourceResponse intercept(WebResourceRequest request) {
        if (!Setting.isWebHomeAdblock()) return null;
        if (request == null || request.getUrl() == null) return null;
        String url = request.getUrl().toString();
        if (!matches(url)) return null;
        if (logOnce.add(url)) SpiderDebug.log("webhome-adblock", "blocked url=%s", url);
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    public String getSignature() {
        return signature;
    }
}
