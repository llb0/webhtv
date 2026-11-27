package com.fongmi.android.tv.plugin.idj;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * iDuanJu「插件合集」接口：一个 JSON 清单，plugins[] 每项给出插件 id / 名称 / manifest 路径，
 * 以及 type、adult、version 等元信息。
 * <p>
 * 本类只负责把清单里的 .idjplugin 包拉到 tvbox/idj/，安装仍交给 {@link IdjPluginStore}：
 * 包的 schema、capabilities、integrity、adult 开关都在安装阶段校验，下载阶段不重复判定。
 */
public final class IdjPluginCollection {

    public interface Progress {
        void onProgress(String stage, int current, int total);
    }

    /** 最近一次按合集接口加载/安装时使用的清单地址，用于让「接口下载」不再对合集重复下载。 */
    private static volatile String knownCollectionUrl = "";

    private IdjPluginCollection() {
    }

    /** 这条地址是不是刚刚被当作 iDuanJu 插件合集加载过。 */
    public static boolean isKnownCollection(String url) {
        return !TextUtils.isEmpty(url) && url.equals(knownCollectionUrl);
    }

    public static boolean isCollection(JsonObject root) {
        if (root == null || !root.has("plugins")) return false;
        JsonElement plugins = root.get("plugins");
        return plugins != null && plugins.isJsonArray();
    }

    /** 一次合集安装的结果：新增、更新、内容未变、失败。 */
    public static class Result {
        public int added;
        public int updated;
        public int unchanged;
        public int skipped;
        public int failed;
    }

    /**
     * 拉取并安装清单里的插件包；本地已装过的包会与远端逐字节比对，远端变了就地覆盖并重装，
     * 内容没变则完全跳过（不写盘、不重装，保留用户 settings.json / storage.json）。
     *
     * @param baseUrl  清单自身的地址，manifest 里的相对路径按它解析
     * @param root     清单根对象
     * @param progress 进度回调（调用方负责切主线程）
     * @return 本次新增/更新的统计
     */
    public static Result install(String baseUrl, JsonObject root, Progress progress) throws Exception {
        JsonArray plugins = root.getAsJsonArray("plugins");
        knownCollectionUrl = baseUrl;
        File dir = IdjPluginStore.root();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 tvbox/idj 目录");
        int total = plugins.size();
        Result result = new Result();
        for (int i = 0; i < total; i++) {
            JsonObject plugin = plugins.get(i) != null && plugins.get(i).isJsonObject() ? plugins.get(i).getAsJsonObject() : null;
            if (plugin == null) continue;
            String manifest = text(plugin.get("manifest"), 4096);
            if (TextUtils.isEmpty(manifest)) continue;
            String fileName = fileName(manifest);
            if (!fileName.endsWith(IdjPluginStore.SUFFIX)) fileName = fileName + IdjPluginStore.SUFFIX;
            String source = absolute(baseUrl, manifest);
            String body = fetch(source);
            // 只收 JSON 包，避免把 404 页面写成插件文件后再被安装阶段静默跳过
            if (TextUtils.isEmpty(body) || !body.contains("\"manifest\"")) {
                result.failed++;
                continue;
            }
            File target = new File(dir, fileName);
            boolean exists = target.exists();
            // 另一个合集接口可能给出同一个 id 的旧版本：版本更低就整包跳过，
            // 连包都不落盘（否则 sync() 见到版本差异会把旧包装回去，等于降级）。
            if (isOlderThanLocal(dir, target, body)) {
                result.skipped++;
                continue;
            }
            // 远端没变就别动：既不覆盖包，也不触发重装，用户的 settings/storage 原样保留
            if (exists && body.equals(readText(target))) {
                result.unchanged++;
                continue;
            }
            if (progress != null) progress.onProgress((exists ? "更新 " : "下载 ") + fileName, i, total);
            Path.write(target, body.getBytes(StandardCharsets.UTF_8));
            try {
                // 逐包直接装：sync() 只在 version 变化时重装，同版本静默改包会漏掉
                IdjPluginStore.install(target);
                if (exists) result.updated++; else result.added++;
            } catch (Throwable e) {
                result.failed++;
            }
        }
        IdjPluginStore.sync();
        if (progress != null) progress.onProgress("安装插件", total, total);
        return result;
    }

    /**
     * 远端包是否比本地已装的版本更低。本地以 &lt;id&gt;/manifest.json 的版本为准，
     * 还没装过时才看同名包里的版本；任一侧版本取不到就不判，交给内容比对决定。
     */
    private static boolean isOlderThanLocal(File dir, File target, String body) {
        try {
            JSONObject manifest = new JSONObject(body).optJSONObject("manifest");
            if (manifest == null) return false;
            String remote = manifest.optString("version").trim();
            String id = manifest.optString("id").trim();
            if (TextUtils.isEmpty(remote) || TextUtils.isEmpty(id)) return false;
            String local = versionOf(new File(new File(dir, id), "manifest.json"));
            if (TextUtils.isEmpty(local)) local = versionOf(target);
            if (TextUtils.isEmpty(local)) return false;
            return compareVersion(remote, local) < 0;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String versionOf(File file) {
        try {
            if (!file.exists()) return "";
            JSONObject root = new JSONObject(readText(file));
            JSONObject manifest = root.optJSONObject("manifest");
            return manifest == null ? "" : manifest.optString("version").trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 按数字段逐段比较版本号，返回负数表示 a &lt; b；任一侧没有数字段则视为相同。 */
    private static int compareVersion(String a, String b) {
        String[] left = a.split("[^0-9]+");
        String[] right = b.split("[^0-9]+");
        int len = Math.max(left.length, right.length);
        for (int i = 0; i < len; i++) {
            String l = i < left.length ? left[i] : "";
            String r = i < right.length ? right[i] : "";
            long lv = number(l);
            long rv = number(r);
            if (lv < 0 || rv < 0) continue;
            if (lv != rv) return lv < rv ? -1 : 1;
        }
        return 0;
    }

    private static long number(String value) {
        if (TextUtils.isEmpty(value) || value.length() > 18) return -1;
        try {
            return Long.parseLong(value);
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String readText(File file) {
        try {
            return Path.read(file);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String fetch(String url) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) return OkHttp.string(url);
            File local = Path.local(url);
            return local != null && local.exists() ? Path.read(local) : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private static String text(JsonElement element, int max) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        String value = element.getAsString().trim();
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String fileName(String manifest) {
        String path = manifest;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return TextUtils.isEmpty(name) ? "plugin" : name;
    }

    private static String absolute(String baseUrl, String manifest) {
        if (manifest.startsWith("http://") || manifest.startsWith("https://")) return manifest;
        try {
            return URI.create(baseUrl).resolve(manifest).toString();
        } catch (Throwable e) {
            return manifest;
        }
    }

    private static class IOException extends Exception {
        IOException(String message) {
            super(message);
        }
    }
}
