package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进入详情/播放前的环境准备（影图的逻辑）：只要来源不是当前正在浏览的源，就先按该源的 ext
 * 重新 init 一遍 spider，环境备好再走后续请求；来源就是当前源时保持原样，不打断它的页面状态。
 *
 * 为什么必须做：站源 spider 的"环境"不只活在这一个对象实例上。XBPQ 系（api=csp_XBPQ）在 jar 里
 * 用 {@code private static XBPQ xbpq} 存站点环境，init 时把本实例的 rule/extend/oComand/homeUrl
 * 写进静态实例（XBPQ.java:393-395、:516-523），WebView 渲染桥 {@code XBPQ.tool/getRV/getBL/setBL}
 * 也全从这个静态实例读（XBPQ.java:11917-11932、:8192-8205）。webhtv 把 jar 下所有站点的 spider
 * 长期缓存（JarLoader 按 jar+siteKey），跨源搜索又会并发 init，静态环境常常停在别的站点上；
 * 此时进详情，渲染桥查不到本站点的规则字段（如 {@code 调用函数-js交互} 要的 {@code js交互}），
 * 脚本解析失败、渲染结果为空，详情直接报错。js/py 同样有自己的解释器/脚本状态，跨源用前也要
 * 按该源的 ext 重新准备。
 */
public final class SiteContext {

    private static final Object ABSENT = new Object();
    private static final Map<Class<?>, Object> CONTEXTS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Object> RULES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Object> LOCKS = new ConcurrentHashMap<>();

    /** 当前正在浏览（首页/分类）的源，detail/player 据此判断要不要重新初始化。 */
    private static volatile String browsing = "";

    private SiteContext() {
    }

    /** 首页/分类取数据时记录"当前源"。 */
    public static void markBrowse(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        browsing = site.getKey();
    }

    /** 进入详情/播放之前调用：来源不是当前源，就先把该源的环境重新备好。 */
    public static void prepare(Site site, Spider spider) {
        if (site == null || spider == null) return;
        try {
            if (TextUtils.equals(browsing, site.getKey()) && !foreignContext(spider)) return;
            synchronized (LOCKS.computeIfAbsent(spider.getClass(), key -> new Object())) {
                SpiderDebug.log("site-context", "reinit site=%s current=%s", site.getKey(), browsing);
                spider.init(App.get(), site.getExt());
            }
            browsing = site.getKey();
            SpiderDebug.log("site-context", "reinit done site=%s", site.getKey());
        } catch (Throwable e) {
            SpiderDebug.log("site-context", "reinit error site=%s error=%s", site.getKey(), e);
        }
    }

    /**
     * 是否是 XBPQ 系且静态环境已经不属于这一站。
     * 非 XBPQ（类里没有静态 {@code xbpq} 字段）返回 false，走"当前源"判断。
     */
    private static boolean foreignContext(Spider spider) {
        try {
            Object context = context(spider.getClass());
            if (context == null) return false;
            return !belongsTo(context, spider);
        } catch (Throwable e) {
            return false;
        }
    }

    private static Object context(Class<?> type) throws Exception {
        Object cached = CONTEXTS.get(type);
        if (cached == null) {
            Field field = staticField(type, "xbpq");
            cached = field == null ? ABSENT : field;
            CONTEXTS.put(type, cached);
        }
        if (cached == ABSENT) return null;
        return ((Field) cached).get(null);
    }

    private static boolean belongsTo(Object context, Spider spider) {
        String want = identity(spider);
        String got = identity(context);
        return !want.isEmpty() && want.equals(got);
    }

    private static String identity(Object target) {
        JSONObject rule = rule(target);
        if (rule == null) return "";
        String name = rule.optString("站名").trim();
        String home = rule.optString("主页url").trim();
        if (name.isEmpty() && home.isEmpty()) return "";
        return name + "|" + home;
    }

    private static JSONObject rule(Object target) {
        if (target == null) return null;
        try {
            Object cached = RULES.get(target.getClass());
            if (cached == null) {
                Field field = instanceField(target.getClass(), "rule", JSONObject.class);
                cached = field == null ? ABSENT : field;
                RULES.put(target.getClass(), cached);
            }
            if (cached == ABSENT) return null;
            Object value = ((Field) cached).get(target);
            return value instanceof JSONObject ? (JSONObject) value : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Field staticField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!name.equals(field.getName())) continue;
                if (!field.getType().isAssignableFrom(type)) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                return field;
            }
        }
        return null;
    }

    private static Field instanceField(Class<?> type, String name, Class<?> wanted) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!name.equals(field.getName())) continue;
                if (!wanted.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                return field;
            }
        }
        return null;
    }
}
