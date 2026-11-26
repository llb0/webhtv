package com.fongmi.quickjs.method;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.UriUtil;
import com.orhanobut.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * iDuanJu idj1 插件的宿主桥：把插件 JS 里的 host.* 调用（经 __host(name, argsJson)）翻译成
 * webhtv 侧的原生实现。只服务 clan://idj/ 站点，其它 JS 站点不会走到这里。
 *
 * 设计约束：
 * - 与 idj1 规范保持一致：只能访问 manifest.permissions.http 里声明的域名；禁 IP 直连、禁非 80/443 端口；
 *   每次请求都校验跳转落点。
 * - 错误一律返回 {"__idjError":{code,message}}，由 idj-prelude.js 转成 JS 异常，避免原生异常穿透到 quickjs。
 * - 未实现的能力（rsa / codec.unpackPacker / image.* / cloudflare / login）在 host.has 里返回 false。
 */
public class IdjHost {

    private static final int MAX_HOPS = 5;
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final long STORAGE_LIMIT = 1024 * 1024;
    private static final Pattern IP_LITERAL = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$|^\\[?[0-9a-fA-F:]+\\]?$");

    private final File dir;
    private final String id;
    private final List<String> domains;
    private final JSONObject settings;
    private final JSONObject storage;
    private final Map<Integer, Element> nodes;
    private final Map<String, String> cookies;
    private final AtomicInteger nextNode;
    private final OkHttpClient client;
    private final Object lock = new Object();

    private IdjHost(File dir, String id, JSONArray domains, JSONObject settings) {
        this.dir = dir;
        this.id = id;
        this.domains = new ArrayList<>();
        this.nodes = new ConcurrentHashMap<>();
        this.cookies = new ConcurrentHashMap<>();
        this.nextNode = new AtomicInteger(1);
        this.client = OkHttp.client(false, 10000);
        for (int i = 0; i < domains.length(); i++) this.domains.add(domains.optString(i).toLowerCase());
        this.settings = settings == null ? new JSONObject() : settings;
        this.storage = readStorage();
    }

    /** 从插件目录（tvbox/idj/&lt;id&gt;）建宿主：manifest.json 提供域名白名单与 settings 默认值。 */
    public static IdjHost create(File dir) {
        JSONObject manifest = readJson(new File(dir, "manifest.json"));
        JSONObject permissions = manifest.optJSONObject("permissions");
        JSONArray http = permissions == null ? new JSONArray() : permissions.optJSONArray("http");
        return new IdjHost(dir, manifest.optString("id"), http == null ? new JSONArray() : http, readSettings(dir, manifest));
    }

    private static JSONObject readJson(File file) {
        try {
            String text = Path.read(file);
            return TextUtils.isEmpty(text) ? new JSONObject() : new JSONObject(text);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    private static JSONObject readSettings(File dir, JSONObject manifest) {
        JSONObject result = new JSONObject();
        JSONArray items = manifest.optJSONArray("settings");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String key = item.optString("key");
            if (TextUtils.isEmpty(key)) continue;
            try {
                result.put(key, item.opt("default"));
            } catch (Throwable ignored) {
            }
        }
        JSONObject override = readJson(new File(dir, "settings.json"));
        Iterator<String> keys = override.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                result.put(key, override.opt(key));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private JSONObject readStorage() {
        return readJson(new File(dir, "storage.json"));
    }

    public String call(String name, String argsJson) {
        try {
            JSONObject args = TextUtils.isEmpty(argsJson) || "null".equals(argsJson) ? new JSONObject() : new JSONObject(argsJson);
            Object result = dispatch(name, args);
            if (result == null) return "null";
            if (result instanceof String) return JSONObject.quote((String) result);
            return result.toString();
        } catch (HostError e) {
            return error(e.code, e.getMessage()).toString();
        } catch (Throwable e) {
            Logger.t("IdjHost").e(e, "%s", name);
            return error("E_PARSE", "宿主处理失败：" + name).toString();
        }
    }

    private static JSONObject error(String code, String message) {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            body.put("message", message);
        } catch (Throwable ignored) {
        }
        JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("__idjError", body);
        } catch (Throwable ignored) {
        }
        return wrapper;
    }

    private Object dispatch(String name, JSONObject a) throws Exception {
        switch (name) {
            case "host.version":
                return new JSONObject().put("app", "webhtv").put("spec", "idj1");
            case "host.has":
                return has(a.optString("cap"));
            case "http.request":
                return httpRequest(a);
            case "http.allowed":
                return allowed(a.optString("url"));
            case "html.parse":
                return htmlParse(a.optString("text"), a.optString("base"));
            case "html.select":
                return htmlSelect(a.optInt("id", 0), a.optString("q"), a.optBoolean("first", false));
            case "html.text":
                return node(a).text();
            case "html.html":
                return node(a).html();
            case "html.outer":
                return node(a).outerHtml();
            case "html.attr":
                return htmlAttr(node(a), a.optString("k"));
            case "url.resolve":
                return UriUtil.resolve(a.optString("base"), a.optString("rel"));
            case "crypto.hash":
                return cryptoHash(a);
            case "crypto.hmac":
                return cryptoHmac(a);
            case "crypto.aes":
                return cryptoAes(a);
            case "crypto.random":
                return randomHex(a.optInt("n", 16));
            case "codec.convert":
                return codecConvert(a);
            case "codec.gunzip":
                return gunzip(a.optString("data"));
            case "codec.unpack":
                throw new HostError("E_UNSUPPORTED", "webhtv 暂不支持 unpackPacker");
            case "m3u8.parse":
                return m3u8(a.optString("text"), a.optString("base"));
            case "storage.get":
                needStorage();
                synchronized (lock) {
                    return storage.opt(a.optString("k"));
                }
            case "storage.set":
                needStorage();
                storageWrite(a.optString("k"), a.opt("v"));
                return null;
            case "storage.remove":
                needStorage();
                storageWrite(a.optString("k"), null);
                return null;
            case "settings.all":
                return settings;
            case "log":
                Logger.t("Idj[" + id + "]").d(a.optString("m"));
                return null;
            default:
                throw new HostError("E_UNSUPPORTED", "未知宿主能力：" + name);
        }
    }

    private boolean has(String cap) {
        if (TextUtils.isEmpty(cap)) return false;
        switch (cap) {
            case "http":
            case "html":
            case "crypto.hash":
            case "crypto.hmac":
            case "crypto.aes":
            case "codec":
            case "m3u8":
            case "url":
                return true;
            case "storage":
                return manifestStorageAllowed();
            case "settings":
                return true;
            default:
                return false;
        }
    }

    private boolean manifestStorageAllowed() {
        JSONObject permissions = readJson(new File(dir, "manifest.json")).optJSONObject("permissions");
        return permissions != null && permissions.optBoolean("storage", false);
    }

    // ---------------------------------------------------------------- http

    private Object httpRequest(JSONObject a) throws Exception {
        String url = a.optString("url");
        check(url);
        String method = a.optString("method", "GET").toUpperCase();
        JSONObject headers = a.optJSONObject("headers");
        Object body = a.opt("body");
        int timeout = a.optInt("timeout", 10000);
        String contentType = headers == null ? "" : headers.optString("Content-Type", headers.optString("content-type", ""));
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            Request request = build(url, method, headers, body, contentType);
            OkHttpClient hopClient = timeout > 0 ? OkHttp.client(false, timeout) : client;
            try (Response response = hopClient.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location", "");
                    if (TextUtils.isEmpty(location)) return toResult(response, url);
                    url = UriUtil.resolve(url, location);
                    check(url);
                    method = "GET";
                    body = null;
                    continue;
                }
                return toResult(response, url);
            }
        }
        throw new HostError("E_HTTP", "跳转次数过多");
    }

    private Request build(String url, String method, JSONObject headers, Object body, String contentType) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headers.optString(key, "");
                if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) continue;
                builder.header(key, value);
            }
        }
        String cookie = headers != null && headers.has("Cookie") ? "" : cookieHeader(url);
        if (!TextUtils.isEmpty(cookie)) builder.header("Cookie", cookie);
        if (!"GET".equals(method)) {
            String text = body == null ? "" : body instanceof String ? (String) body : body.toString();
            MediaType type = MediaType.parse(TextUtils.isEmpty(contentType) ? ("POST".equals(method) ? "application/x-www-form-urlencoded" : "text/plain") : contentType);
            RequestBody requestBody = "POST".equals(method) ? RequestBody.create(text, type) : RequestBody.create(text, type);
            builder.method(method, requestBody);
        }
        return builder.build();
    }

    private Object toResult(Response response, String url) throws Exception {
        JSONObject headers = new JSONObject();
        Headers raw = response.headers();
        for (String name : raw.names()) headers.put(name.toLowerCase(), String.join(", ", raw.values(name)));
        saveCookies(url, raw);
        ResponseBody responseBody = response.body();
        byte[] bytes = responseBody == null ? new byte[0] : responseBody.bytes();
        if (bytes.length > MAX_BYTES) throw new HostError("E_HTTP", "响应超过 20MB");
        JSONObject result = new JSONObject();
        result.put("status", response.code());
        result.put("headers", headers);
        result.put("body", new String(bytes, StandardCharsets.UTF_8));
        result.put("url", url);
        return result;
    }

    private void check(String url) throws HostError {
        if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) throw new HostError("E_PERMISSION", "只允许 http/https：" + url);
        String host;
        String portText;
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getUserInfo() != null) throw new HostError("E_PERMISSION", "网址不可携带凭证");
            host = uri.getHost();
            portText = uri.getPort() == -1 ? (url.startsWith("https") ? "443" : "80") : String.valueOf(uri.getPort());
        } catch (HostError e) {
            throw e;
        } catch (Throwable e) {
            throw new HostError("E_NETWORK", "地址无效：" + url);
        }
        if (TextUtils.isEmpty(host)) throw new HostError("E_NETWORK", "地址无效：" + url);
        host = host.toLowerCase();
        if (IP_LITERAL.matcher(host).matches()) throw new HostError("E_PERMISSION", "不允许直接访问 IP：" + host);
        if (!"80".equals(portText) && !"443".equals(portText)) throw new HostError("E_PERMISSION", "不允许非标准端口：" + host + ":" + portText);
        if (!domains.contains(host)) throw new HostError("E_PERMISSION", "域名未授权：" + host);
        if (resolvesToPrivate(host)) throw new HostError("E_PERMISSION", host + " 解析到内网地址，已拦截");
    }

    private static boolean resolvesToPrivate(String host) {
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress();
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean allowed(String url) {
        try {
            check(url);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private String cookieHeader(String url) {
        if (cookies.isEmpty() || TextUtils.isEmpty(url)) return "";
        StringBuilder sb = new StringBuilder();
        synchronized (cookies) {
            for (Map.Entry<String, String> entry : cookies.entrySet()) if (url.startsWith(entry.getKey())) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(entry.getValue());
            }
        }
        return sb.toString();
    }

    private void saveCookies(String url, Headers headers) {
        List<String> values = headers.values("Set-Cookie");
        if (values.isEmpty() || TextUtils.isEmpty(url)) return;
        String origin = url;
        int scheme = url.indexOf("://");
        int path = scheme < 0 ? -1 : url.indexOf('/', scheme + 3);
        if (path > 0) origin = url.substring(0, path + 1);
        synchronized (cookies) {
            for (String value : values) {
                String pair = value.split(";")[0].trim();
                if (pair.indexOf('=') <= 0) continue;
                String name = pair.substring(0, pair.indexOf('='));
                String existing = cookies.get(origin);
                StringBuilder sb = new StringBuilder();
                if (!TextUtils.isEmpty(existing)) for (String part : existing.split("; ")) if (!part.startsWith(name + "=")) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(part);
                }
                if (sb.length() > 0) sb.append("; ");
                sb.append(pair);
                cookies.put(origin, sb.toString());
            }
        }
    }

    // ---------------------------------------------------------------- html

    private Element node(JSONObject a) throws HostError {
        Element element = nodes.get(a.optInt("id", 0));
        if (element == null) throw new HostError("E_PARSE", "无效的节点");
        return element;
    }

    private Object htmlParse(String text, String base) {
        Document document = Jsoup.parse(text == null ? "" : text, base == null ? "" : base);
        int nodeId = nextNode.getAndIncrement();
        nodes.put(nodeId, document);
        return nodeId;
    }

    private Object htmlSelect(int id, String query, boolean first) throws HostError {
        Element element = nodes.get(id);
        if (element == null) throw new HostError("E_PARSE", "无效的节点");
        Elements elements = element.select(query);
        JSONArray result = new JSONArray();
        for (int i = 0; i < elements.size(); i++) {
            if (first && i > 0) break;
            int nodeId = nextNode.getAndIncrement();
            nodes.put(nodeId, elements.get(i));
            result.put(nodeId);
        }
        return result;
    }

    private Object htmlAttr(Element element, String key) {
        if (key != null && key.startsWith("abs:")) return element.absUrl(key.substring(4));
        return element.attr(key == null ? "" : key);
    }

    // ---------------------------------------------------------------- crypto

    private Object cryptoHash(JSONObject a) throws Exception {
        byte[] data = decode(a.optString("data"), a.optString("in", "utf8"));
        String alg = a.optString("alg", "md5").toLowerCase();
        String javaAlg = "md5".equals(alg) ? "MD5" : "sha1".equals(alg) ? "SHA-1" : "sha256".equals(alg) ? "SHA-256" : "sha512".equals(alg) ? "SHA-512" : null;
        if (javaAlg == null) throw new HostError("E_UNSUPPORTED", "不支持的摘要算法：" + alg);
        byte[] digest = java.security.MessageDigest.getInstance(javaAlg).digest(data);
        return encode(digest, a.optString("out", "hex"));
    }

    private Object cryptoHmac(JSONObject a) throws Exception {
        String alg = a.optString("alg", "sha256").toLowerCase();
        String javaAlg = alg.startsWith("sha1") ? "HmacSHA1" : alg.startsWith("md5") ? "HmacMD5" : alg.startsWith("sha512") ? "HmacSHA512" : "HmacSHA256";
        Mac mac = Mac.getInstance(javaAlg);
        mac.init(new SecretKeySpec(decode(a.optString("key"), a.optString("keyEncoding", "utf8")), javaAlg));
        byte[] result = mac.doFinal(decode(a.optString("data"), a.optString("in", "utf8")));
        return encode(result, a.optString("out", "hex"));
    }

    private Object cryptoAes(JSONObject a) throws Exception {
        boolean encrypt = "encrypt".equals(a.optString("op", "decrypt"));
        String keyEncoding = a.optString("keyEncoding", "utf8");
        byte[] key = decode(a.optString("key"), keyEncoding);
        byte[] data = decode(a.optString("data"), a.optString("in", encrypt ? "utf8" : "base64"));
        String mode = a.optString("mode", "cbc").toLowerCase();
        String transformation = "ecb".equals(mode) ? "AES/ECB/" : "ctr".equals(mode) ? "AES/CTR/NoPadding" : "gcm".equals(mode) ? "AES/GCM/NoPadding" : "AES/CBC/";
        if (!"ctr".equals(mode) && !"gcm".equals(mode)) transformation += a.optString("padding", "pkcs7").equals("none") ? "NoPadding" : "PKCS5Padding";
        Cipher cipher = Cipher.getInstance(transformation);
        byte[] iv = TextUtils.isEmpty(a.optString("iv")) ? null : decode(a.optString("iv"), a.optString("ivEncoding", keyEncoding));
        if (iv == null && !"ecb".equals(mode)) throw new HostError("E_PARSE", "AES " + mode + " 需要 iv");
        if ("ecb".equals(mode)) cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        else if ("gcm".equals(mode)) cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        else cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return encode(cipher.doFinal(data), a.optString("out", encrypt ? "base64" : "utf8"));
    }

    private Object randomHex(int n) {
        int size = Math.max(1, Math.min(1024, n));
        byte[] bytes = new byte[size];
        new java.security.SecureRandom().nextBytes(bytes);
        return encode(bytes, "hex");
    }

    private Object codecConvert(JSONObject a) throws Exception {
        return encode(decode(a.optString("data"), a.optString("from", "utf8")), a.optString("to", "utf8"));
    }

    private Object gunzip(String base64) throws Exception {
        byte[] bytes = decode(base64, "base64");
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = gzip.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] decode(String text, String encoding) throws Exception {
        String from = TextUtils.isEmpty(encoding) ? "utf8" : encoding.toLowerCase();
        if ("hex".equals(from)) {
            String hex = text == null ? "" : text.replaceAll("\\s", "");
            byte[] result = new byte[hex.length() / 2];
            for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return result;
        }
        if ("base64".equals(from) || "base64url".equals(from)) {
            String value = text == null ? "" : text.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
            while (value.length() % 4 != 0) value += "=";
            return android.util.Base64.decode(value, android.util.Base64.NO_WRAP);
        }
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
    }

    private static Object encode(byte[] bytes, String encoding) {
        String to = TextUtils.isEmpty(encoding) ? "hex" : encoding.toLowerCase();
        if ("hex".equals(to)) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        }
        if ("base64".equals(to)) return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        if ("base64url".equals(to)) return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE).replace("=", "");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- m3u8

    private Object m3u8(String text, String base) throws Exception {
        List<Object> variants = new ArrayList<>();
        List<Object> segments = new ArrayList<>();
        JSONObject key = null;
        JSONObject pendingVariant = null;
        Double pendingDuration = null;
        String[] lines = (text == null ? "" : text).split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) pendingVariant = attributes(line.substring(18));
            else if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8);
                int comma = value.indexOf(',');
                if (comma >= 0) value = value.substring(0, comma);
                try {
                    pendingDuration = Double.parseDouble(value.trim());
                } catch (Throwable ignored) {
                    pendingDuration = 0.0;
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                JSONObject attrs = attributes(line.substring(11));
                key = new JSONObject();
                key.put("method", attrs.optString("METHOD"));
                key.put("uri", resolve(base, attrs.optString("URI")));
                key.put("iv", attrs.optString("IV"));
            } else if (!line.startsWith("#") && !line.isEmpty()) {
                if (pendingVariant != null) {
                    JSONObject variant = new JSONObject();
                    variant.put("url", resolve(base, line));
                    variant.put("bandwidth", parseInt(pendingVariant.optString("BANDWIDTH")));
                    variant.put("resolution", pendingVariant.optString("RESOLUTION"));
                    variant.put("name", pendingVariant.optString("NAME"));
                    variants.add(variant);
                    pendingVariant = null;
                } else {
                    JSONObject segment = new JSONObject();
                    segment.put("url", resolve(base, line));
                    segment.put("duration", pendingDuration == null ? 0 : pendingDuration);
                    segments.add(segment);
                    pendingDuration = null;
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("variants", new JSONArray(variants));
        result.put("segments", new JSONArray(segments));
        if (key != null) result.put("key", key);
        return result;
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String resolve(String base, String rel) {
        if (TextUtils.isEmpty(rel)) return "";
        if (TextUtils.isEmpty(base) || rel.startsWith("http")) return rel;
        try {
            return java.net.URI.create(base).resolve(rel).toString();
        } catch (Throwable e) {
            return rel;
        }
    }

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    private static JSONObject attributes(String line) {
        JSONObject result = new JSONObject();
        Matcher matcher = ATTR.matcher(line);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length() - 1);
            try {
                result.put(matcher.group(1), value);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- storage

    private void needStorage() throws HostError {
        if (!manifestStorageAllowed()) throw new HostError("E_PERMISSION", "插件未声明 permissions.storage");
    }

    private void storageWrite(String key, Object value) throws HostError {
        if (TextUtils.isEmpty(key)) throw new HostError("E_PARSE", "storage key 为空");
        synchronized (lock) {
            Object previous = storage.opt(key);
            try {
                if (value == null) storage.remove(key);
                else storage.put(key, value);
                String data = storage.toString();
                if (data.getBytes(StandardCharsets.UTF_8).length > STORAGE_LIMIT) {
                    if (previous == null) storage.remove(key);
                    else storage.put(key, previous);
                    throw new HostError("E_STORAGE_FULL", "插件存储超过 1MB");
                }
                Path.write(new File(dir, "storage.json"), data.getBytes(StandardCharsets.UTF_8));
            } catch (HostError e) {
                throw e;
            } catch (Throwable e) {
                throw new HostError("E_PARSE", "写入插件存储失败");
            }
        }
    }

    private static class HostError extends Exception {
        private final String code;

        private HostError(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
