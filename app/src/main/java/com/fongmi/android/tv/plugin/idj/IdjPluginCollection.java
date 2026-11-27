package com.fongmi.android.tv.plugin.idj;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
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
