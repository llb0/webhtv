package com.fongmi.android.tv.api.loader;

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
 * XBPQ 系（api=csp_XBPQ）的 jar 用 {@code private static XBPQ xbpq} 保存"当前站点环境"：
 * init 时把本实例的 rule / extend / oComand / homeUrl 等写进这个静态实例（XBPQ.java:393-395、:516-523），
 * WebView 渲染桥 {@code XBPQ.tool/getRV/getBL/setBL} 也全部从这个静态实例读（XBPQ.java:11917-11932、:8192-8205）。
 *
 * 也就是说：渲染能否拿到站点的规则字段（如 {@code 调用函数-js交互} 需要的 {@code js交互}），
 * 取决于"静态实例里装的是不是当前站点的环境"。webhtv 把 jar 下所有站点的 spider 长期缓存
 * （JarLoader 按 jar+siteKey），跨源搜索又会并发 init，静态环境常常停在别的站点上；此时进详情，
 * 渲染桥查不到本站点的规则字段，脚本解析失败、渲染结果为空，详情直接报错（日志：
 * "未定义js交互字段，无法调用函数！" → "渲染工具结果--> JSON解析失败"）。
 *
 * 修法与"从历史进入金牌无法播放"同源：使用前把本站点的环境重新准备好。这里在进入详情/播放前
 * 比对静态环境是否属于本站点，不属于就补一次 init（init 只是把本站点的 rule/oComand 重新写进
 * 静态实例），环境对上后照常走原来流程。
 */
public final class XbpqContext {

    private static final Object ABSENT = new Object();
    private static final Map<Class<?>, Object> CONTEXTS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Object> RULES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Object> LOCKS = new ConcurrentHashMap<>();

    private XbpqContext() {
    }

    /**
     * 进入详情/播放之前调用：保证 jar 内的静态站点环境就是 {@code spider} 这一站的。
     * 非 XBPQ 系（类里没有静态 {@code xbpq} 字段）直接返回，不改变原有行为。
     */
    public static void prepare(Site site, Spider spider) {
        if (spider == null) return;
        try {
            Object context = context(spider.getClass());
            if (context == null) return;
            if (belongsTo(context, spider)) return;
            synchronized (lock(spider.getClass())) {
                Object now = context(spider.getClass());
                if (now == null || belongsTo(now, spider)) return;
                SpiderDebug.log("xbpq-context", "detail reinit site=%s static=%s", site.getKey(), identity(now));
                spider.init(App.get(), site.getExt());
                SpiderDebug.log("xbpq-context", "detail reinit done site=%s static=%s", site.getKey(), identity(context(spider.getClass())));
            }
        } catch (Throwable e) {
            SpiderDebug.log("xbpq-context", "detail reinit error site=%s error=%s", site.getKey(), e);
        }
    }

    /** 静态实例（jar 里的 {@code private static XBPQ xbpq}）；类里没有该字段时返回 null。 */
    private static Object context(Class<?> type) throws Exception {
        Object cached = CONTEXTS.get(type);
        if (cached == null) {
            Field field = field(type, "xbpq");
            cached = field == null ? ABSENT : field;
            CONTEXTS.put(type, cached);
        }
        if (cached == ABSENT) return null;
        return ((Field) cached).get(null);
    }

    /** 静态环境是否已经属于这一站：比对 rule 里的站名与主页 url。 */
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

    private static Field field(Class<?> type, String name) {
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

    private static Object lock(Class<?> type) {
        return LOCKS.computeIfAbsent(type, key -> new Object());
    }
}
