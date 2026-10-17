package com.fongmi.android.tv.web;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.Source;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebHomeInlineVodStore {

    public static final String KEY = "webhome_inline";
    private static final String HLS_FORMAT = "application/x-mpegURL";
    // 持久化：ITEMS 只是内存 Map，进程被回收后（隔久了从历史点开）必然
    // 报 "WebHome inline VOD not found"。把条目写进 Prefers 才能跨进程存活。
    private static final String PREF_KEY = "webhome_inline_vods";
    private static final int MAX_PERSIST = 40;

    private static final Map<String, Entry> ITEMS = new ConcurrentHashMap<>();
    private static final Map<String, HeaderSpec> URL_HEADERS = new ConcurrentHashMap<>();
    private static final Map<String, EpisodeSpec> URL_EPISODES = new ConcurrentHashMap<>();

    public static String put(JsonObject payload) {
        return put(payload, null);
    }

    public static String put(JsonObject payload, Resolver resolver) {
        Vod vod = App.gson().fromJson(payload, Vod.class);
        if (vod == null) vod = new Vod();
        String id = first(vod.getId(), Json.safeString(payload, "vodId"), Json.safeString(payload, "id"), "webhome-" + UUID.randomUUID());
        Map<String, String> headers = HeaderPolicy.withDefaultUa(HeaderPolicy.parse(payload.get("headers")));
        HeaderSpec headerSpec = new HeaderSpec(headers, "include".equals(Json.safeString(payload, "credentials")));
        String playUrl = playUrl(payload.get("episodes"), headerSpec, resolver);
        vod.setId(id);
        if (vod.getName().isEmpty()) vod.setName(first(Json.safeString(payload, "vodName"), Json.safeString(payload, "title"), id));
        if (vod.getPic().isEmpty()) vod.setPic(first(Json.safeString(payload, "vodPic"), Json.safeString(payload, "pic")));
        if (vod.getPlayFrom().isEmpty()) vod.setPlayFrom(first(Json.safeString(payload, "vodPlayFrom"), Json.safeString(payload, "playFrom"), "WebHome"));
        if (vod.getPlayUrl().isEmpty()) vod.setPlayUrl(playUrl);
        ITEMS.put(id, new Entry(App.gson().toJson(vod), headerSpec, resolver));
        persist(id, ITEMS.get(id));
        return id;
    }

    public static Result detail(String id) {
        Entry entry = ITEMS.get(id);
        if (entry == null) entry = loadPersisted(id);
        if (entry == null || TextUtils.isEmpty(entry.vod)) return Result.error("WebHome inline VOD not found");
        SpiderDebug.log("webhome-inline", "detail id=%s found=%s", id, true);
        return Result.vod(Vod.objectFrom(entry.vod));
    }

    public static Result player(String flag, String id) throws Exception {
        Entry entry = ITEMS.get(id);
        if (entry == null) entry = loadPersisted(id);
        HeaderSpec headerSpec = URL_HEADERS.get(id);
        EpisodeSpec episodeSpec = URL_EPISODES.get(id);
        String url = id;
        String format = "";
        long start = System.currentTimeMillis();
        SpiderDebug.log("webhome-inline", "player start flag=%s id=%s entry=%s episode=%s", flag, id, entry != null, episodeSpec != null);
        if (episodeSpec != null) {
            ResolveResult resolved = resolve(entry, id, episodeSpec);
            url = resolved.url;
            format = resolved.format;
            headerSpec = resolved.headerSpec;
        }
        if (headerSpec == null && entry != null) headerSpec = entry.headerSpec;
        if (headerSpec == null) headerSpec = new HeaderSpec(new HashMap<>(), false);
        Result result = new Result();
        result.setUrl(url);
        result.setParse(0);
        result.setFlag(flag);
        result.setHeader(headers(url, headerSpec));
        if (!TextUtils.isEmpty(format)) result.setFormat(format);
        else if (isHls(url)) result.setFormat(HLS_FORMAT);
        SpiderDebug.log("webhome-inline", "player resolved cost=%sms id=%s url=%s format=%s headers=%s", System.currentTimeMillis() - start, id, url, result.getFormat(), result.getHeader().keySet());
        result.setUrl(Source.get().fetch(result));
        SpiderDebug.log("webhome-inline", "player fetch ok cost=%sms id=%s url=%s", System.currentTimeMillis() - start, id, result.getUrl().v());
        return result;
    }

    /** 写进 Prefers。Resolver 是接口实例，无法序列化，用不到就留空。 */
    private static void persist(String id, Entry entry) {
        if (entry == null || TextUtils.isEmpty(entry.vod)) return;
        try {
            JsonObject all = allPersisted();
            JsonObject one = new JsonObject();
            one.addProperty("vod", entry.vod);
            one.addProperty("headers", App.gson().toJson(entry.headerSpec.headers));
            one.addProperty("cookies", entry.headerSpec.includeCookies);
            one.addProperty("time", System.currentTimeMillis());
            all.add(id, one);
            trim(all);
            Prefers.put(PREF_KEY, all.toString());
        } catch (Throwable ignored) {
        }
    }

    private static Entry loadPersisted(String id) {
        try {
            JsonObject all = allPersisted();
            JsonElement e = all.get(id);
            if (e == null || !e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            String vod = Json.safeString(o, "vod");
            if (TextUtils.isEmpty(vod)) return null;
            Map<String, String> headers = new HashMap<>();
            String raw = Json.safeString(o, "headers");
            if (!TextUtils.isEmpty(raw)) {
                try {
                    java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, String>>() {
                    }.getType();
                    Map<String, String> parsed = App.gson().fromJson(raw, type);
                    if (parsed != null) headers = parsed;
                } catch (Throwable ignored) {
                }
            }
            Entry entry = new Entry(vod, new HeaderSpec(headers, o.has("cookies") && o.get("cookies").getAsBoolean()), null);
            ITEMS.put(id, entry);
            return entry;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject allPersisted() {
        String raw = Prefers.getString(PREF_KEY, "");
        if (TextUtils.isEmpty(raw)) return new JsonObject();
        try {
            JsonObject o = Json.parse(raw).getAsJsonObject();
            return o == null ? new JsonObject() : o;
        } catch (Throwable ignored) {
            return new JsonObject();
        }
    }

    /** 只保留最近 MAX_PERSIST 条，避免 Prefers 无限增长。 */
    private static void trim(JsonObject all) {
        if (all.size() <= MAX_PERSIST) return;
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String k : all.keySet()) keys.add(k);
        keys.sort((a, b) -> Long.compare(time(all, a), time(all, b)));
        while (keys.size() > MAX_PERSIST) all.remove(keys.remove(0));
    }

    private static long time(JsonObject all, String key) {
        try {
            JsonElement e = all.get(key);
            if (e != null && e.isJsonObject() && e.getAsJsonObject().has("time")) return e.getAsJsonObject().get("time").getAsLong();
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static Map<String, String> headers(String url, HeaderSpec headerSpec) {
        Map<String, String> result = HeaderPolicy.withDefaultUa(headerSpec.headers);
        boolean hasCookie = hasHeader(result, HttpHeaders.COOKIE);
        if (headerSpec.includeCookies && !hasCookie) {
            String cookie = CookieBridge.get(url);
            if (!TextUtils.isEmpty(cookie)) result.put("Cookie", cookie);
        }
        addBrowserCodeHeader(result);
        return result;
    }

    private static String playUrl(JsonElement element, HeaderSpec defaultSpec, Resolver resolver) {
        if (element == null || !element.isJsonArray()) return "";
        StringBuilder builder = new StringBuilder();
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement child = array.get(i);
            JsonObject object = child != null && child.isJsonObject() ? child.getAsJsonObject() : new JsonObject();
            String mediaUrl = Json.safeString(object, "mediaUrl");
            String pageUrl = first(Json.safeString(object, "pageUrl"), Json.safeString(object, "href"));
            String rawUrl = child != null && child.isJsonPrimitive() ? child.getAsString() : Json.safeString(object, "url");
            String url = first(mediaUrl, pageUrl, rawUrl);
            if (TextUtils.isEmpty(url)) continue;
            HeaderSpec episodeHeaders = episodeHeaders(object, defaultSpec);
            URL_HEADERS.put(url, episodeHeaders);
            if (child != null && child.isJsonObject()) {
                boolean resolve = bool(object, "resolve") || (!TextUtils.isEmpty(pageUrl) && TextUtils.isEmpty(mediaUrl));
                String format = first(Json.safeString(object, "format"), isHls(mediaUrl) ? HLS_FORMAT : "");
                URL_EPISODES.put(url, new EpisodeSpec(object.deepCopy(), episodeHeaders, resolver, resolve, mediaUrl, format));
                if (!TextUtils.isEmpty(mediaUrl)) URL_HEADERS.put(mediaUrl, episodeHeaders);
            }
            String name = first(Json.safeString(object, "name"), Json.safeString(object, "label"), Json.safeString(object, "title"), String.format("%02d", i + 1));
            if (builder.length() > 0) builder.append("#");
            builder.append(name.replace("$", " ").replace("#", " ")).append("$").append(url);
        }
        return builder.toString();
    }

    private static HeaderSpec episodeHeaders(JsonObject object, HeaderSpec defaultSpec) {
        Map<String, String> result = new HashMap<>(defaultSpec.headers);
        if (object != null && object.has("headers")) result.putAll(HeaderPolicy.parse(object.get("headers")));
        String referer = object == null ? "" : Json.safeString(object, "referer");
        if (!TextUtils.isEmpty(referer)) result.put(HttpHeaders.REFERER, referer);
        boolean includeCookies = defaultSpec.includeCookies || (object != null && "include".equals(Json.safeString(object, "credentials")));
        return new HeaderSpec(result, includeCookies);
    }

    private static ResolveResult resolve(Entry entry, String id, EpisodeSpec episodeSpec) throws Exception {
        if (!TextUtils.isEmpty(episodeSpec.mediaUrl)) {
            SpiderDebug.log("webhome-inline", "resolve cached id=%s url=%s", id, episodeSpec.mediaUrl);
            return new ResolveResult(episodeSpec.mediaUrl, episodeSpec.headerSpec, episodeSpec.format);
        }
        Resolver resolver = entry != null ? entry.resolver : episodeSpec.resolver;
        if (!episodeSpec.resolve || resolver == null) {
            String url = Json.safeString(episodeSpec.payload, "url");
            SpiderDebug.log("webhome-inline", "resolve direct id=%s url=%s resolve=%s resolver=%s", id, url, episodeSpec.resolve, resolver != null);
            return new ResolveResult(url, episodeSpec.headerSpec, episodeSpec.format);
        }
        long start = System.currentTimeMillis();
        SpiderDebug.log("webhome-inline", "resolve episode start id=%s page=%s", id, Json.safeString(episodeSpec.payload, "pageUrl"));
        JsonObject resolved = resolver.resolve(episodeSpec.payload.deepCopy());
        String url = Json.safeString(resolved, "url");
        if (TextUtils.isEmpty(url)) throw new IllegalStateException("WebHome inline episode resolve failed");
        HeaderSpec headerSpec = resolvedHeaders(resolved, episodeSpec.headerSpec);
        String format = first(Json.safeString(resolved, "format"), isHls(url) ? HLS_FORMAT : episodeSpec.format);
        URL_HEADERS.put(url, headerSpec);
        URL_EPISODES.put(id, new EpisodeSpec(episodeSpec.payload.deepCopy(), headerSpec, resolver, false, url, format));
        SpiderDebug.log("webhome-inline", "resolve episode ok cost=%sms id=%s url=%s", System.currentTimeMillis() - start, id, url);
        return new ResolveResult(url, headerSpec, format);
    }

    private static HeaderSpec resolvedHeaders(JsonObject resolved, HeaderSpec fallback) {
        Map<String, String> headers = new HashMap<>(fallback.headers);
        if (resolved != null && resolved.has("headers")) headers.putAll(HeaderPolicy.parse(resolved.get("headers")));
        String referer = resolved == null ? "" : Json.safeString(resolved, "referer");
        if (!TextUtils.isEmpty(referer)) headers.put(HttpHeaders.REFERER, referer);
        boolean includeCookies = fallback.includeCookies || (resolved != null && "include".equals(Json.safeString(resolved, "credentials")));
        return new HeaderSpec(headers, includeCookies);
    }

    private static void addBrowserCodeHeader(Map<String, String> headers) {
        if (hasHeader(headers, "browser-code")) return;
        String cookie = header(headers, HttpHeaders.COOKIE);
        if (TextUtils.isEmpty(cookie)) return;
        for (String part : cookie.split(";")) {
            String value = part.trim();
            if (!value.startsWith("browser-code=")) continue;
            headers.put("browser-code", value.substring("browser-code=".length()));
            return;
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return "";
    }

    private static boolean isHls(String url) {
        String value = url == null ? "" : url.toLowerCase();
        return value.contains(".m3u8") || value.contains("/playlist/");
    }

    private static boolean bool(JsonObject object, String name) {
        try {
            return object != null && object.has(name) && object.get(name).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String first(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value.trim();
        return "";
    }

    public interface Resolver {
        JsonObject resolve(JsonObject payload) throws Exception;
    }

    private record Entry(String vod, HeaderSpec headerSpec, Resolver resolver) {
    }

    private record HeaderSpec(Map<String, String> headers, boolean includeCookies) {
    }

    private record EpisodeSpec(JsonObject payload, HeaderSpec headerSpec, Resolver resolver, boolean resolve, String mediaUrl, String format) {
    }

    private record ResolveResult(String url, HeaderSpec headerSpec, String format) {
    }
}
