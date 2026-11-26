package com.fongmi.android.tv.plugin.idj;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.utils.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * iDuanJu idj1 插件 → webhtv 站源的仓储层（movie 端 main 源集，双端共用）。
 *
 * 目录约定：
 * - tvbox/idj/xxx.idjplugin  用户放进来的插件包（单 JSON：{manifest, files}），配置加载时自动安装
 * - tvbox/idj/&lt;id&gt;/source.js   插件源码（entry 文件内容）
 * - tvbox/idj/&lt;id&gt;/manifest.json 清单副本（宿主读 permissions.http / settings / storage）
 * - tvbox/idj/&lt;id&gt;/settings.json 设置项默认值（用户可手改）
 * - tvbox/idj/&lt;id&gt;/storage.json  插件私有 KV（host.storage 写入）
 *
 * 只处理视频点播（video / shortdrama）；comic、live、成人内容不在本次范围内。
 */
public final class IdjPluginStore {

    public static final String DIR = "idj";
    public static final String SUFFIX = ".idjplugin";
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final List<String> TYPES = Arrays.asList("video", "shortdrama");

    private IdjPluginStore() {
    }

    public static File root() {
        return new File(Path.root(), "tvbox/" + DIR);
    }

    /** 扫描插件包并安装；单个包失败只跳过它，不影响其它站点。 */
    public static void sync() {
        File dir = root();
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) try {
            String id = idOf(readManifest(file));
            JSONObject installed = readJson(new File(new File(dir, id), "manifest.json"));
            String version = installed.optString("version");
            String incoming = readVersion(file);
            // 版本不同或尚未安装才重装，避免每次加载配置都写盘
            if (!new File(new File(dir, id), "source.js").exists() || !incoming.equals(version)) install(file);
        } catch (Throwable ignored) {
        }
    }

    public static void install(File file) throws Exception {
        if (file == null || !file.exists()) throw new IllegalArgumentException("插件包不存在");
        JSONObject json = new JSONObject(Path.read(file));
        JSONObject manifest = json.optJSONObject("manifest");
        JSONObject files = json.optJSONObject("files");
        if (manifest == null || files == null) throw new IllegalArgumentException("不是 idjplugin 包");
        if (manifest.optInt("schema", 0) != 1) throw new IllegalArgumentException("只支持 schema 1（idj1）");
        String id = manifest.optString("id");
        if (!ID.matcher(id == null ? "" : id).matches()) throw new IllegalArgumentException("插件 id 非法");
        if (!TYPES.contains(manifest.optString("type"))) throw new IllegalArgumentException("只支持视频点播插件（video / shortdrama）");

        boolean adult = manifest.optBoolean("adult", false);
        if (adult && !Setting.isIdjAdultAllowed()) throw new IllegalArgumentException("成人插件已在来源屏蔽里被关掉");
        String entry = manifest.optString("entry");
        if (TextUtils.isEmpty(entry) || !files.has(entry)) throw new IllegalArgumentException("缺少入口文件");
        String source = files.optString(entry);
        if (TextUtils.isEmpty(source)) throw new IllegalArgumentException("入口文件为空");
        JSONArray capabilities = manifest.optJSONArray("capabilities");
        List<String> caps = new ArrayList<>();
        if (capabilities != null) for (int i = 0; i < capabilities.length(); i++) caps.add(capabilities.optString(i));
        if (!caps.contains("detail") || !caps.contains("play")) throw new IllegalArgumentException("插件必须实现 detail + play");
        verify(manifest, entry, source);
        File dir = new File(root(), id);
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalArgumentException("无法创建插件目录");
        Path.write(new File(dir, "source.js"), source.getBytes(StandardCharsets.UTF_8));
        Path.write(new File(dir, "manifest.json"), manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        File settings = new File(dir, "settings.json");
        if (!settings.exists()) Path.write(settings, defaults(manifest).getBytes(StandardCharsets.UTF_8));
        Module.get().clear();
    }

    private static String defaults(JSONObject manifest) {
        JSONObject result = new JSONObject();
        JSONArray settings = manifest.optJSONArray("settings");
        if (settings != null) for (int i = 0; i < settings.length(); i++) {
            JSONObject item = settings.optJSONObject(i);
            if (item == null || TextUtils.isEmpty(item.optString("key"))) continue;
            try {
                result.put(item.optString("key"), item.opt("default"));
            } catch (Throwable ignored) {
            }
        }
        try {
            return result.toString(2);
        } catch (Throwable e) {
            return result.toString();
        }
    }

    private static void verify(JSONObject manifest, String entry, String source) {
        JSONObject integrity = manifest.optJSONObject("integrity");
        JSONObject files = integrity == null ? null : integrity.optJSONObject("files");
        String expected = files == null ? "" : files.optString(entry);
        if (TextUtils.isEmpty(expected) || !expected.startsWith("sha256-")) return;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            String actual = "sha256-" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
            if (!actual.equals(expected)) throw new IllegalArgumentException("插件包完整性校验失败");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalArgumentException("插件包完整性校验失败");
        }
    }

    private static String readVersion(File file) {
        try {
            JSONObject json = new JSONObject(Path.read(file));
            JSONObject manifest = json.optJSONObject("manifest");
            return manifest == null ? "" : manifest.optString("version");
        } catch (Throwable e) {
            return "";
        }
    }

    private static String readManifest(File file) {
        try {
            JSONObject json = new JSONObject(Path.read(file));
            JSONObject manifest = json.optJSONObject("manifest");
            return manifest == null ? "" : manifest.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String idOf(String manifest) {
        try {
            return new JSONObject(manifest).optString("id");
        } catch (Throwable e) {
            return "";
        }
    }

    private static JSONObject readJson(File file) {
        try {
            String text = Path.read(file);
            return TextUtils.isEmpty(text) ? new JSONObject() : new JSONObject(text);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    /** 生成站点：api 指向 tvbox/idj/&lt;id&gt;/source.js，由 quickjs Spider 走 idj1 适配器。 */
    public static List<Site> sites(String globalSpider) {
        List<Site> result = new ArrayList<>();
        if (!Setting.isIdjSitesEnabled()) return result;
        File dir = root();
        if (!dir.exists() || !dir.isDirectory()) return result;
        sync();
        File[] files = dir.listFiles(File::isDirectory);
        if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File pluginDir : files) {
            JSONObject manifest = readJson(new File(pluginDir, "manifest.json"));
            if (!new File(pluginDir, "source.js").exists()) continue;
            if (TextUtils.isEmpty(manifest.optString("name"))) continue;
            if (!TYPES.contains(manifest.optString("type"))) continue;
            boolean adult = manifest.optBoolean("adult", false);
            JSONArray capabilities = manifest.optJSONArray("capabilities");
            boolean search = false;
            if (capabilities != null) for (int i = 0; i < capabilities.length(); i++) if ("search".equals(capabilities.optString(i))) search = true;
            Site site = Site.get("IDJ_" + pluginDir.getName(), manifest.optString("name"));
            site.setType(3);
            site.setApi("clan://" + DIR + "/" + pluginDir.getName() + "/source.js");
            site.setJar(globalSpider);
            site.setName(manifest.optString("name") + (adult ? " 🔞" : "") + " | IDJ");
            site.setSearchable(search);
            site.setChangeable(true);
            result.add(site);
        }
        return result;
    }

    /** 供未来的导入入口使用：把用户选择的 idjplugin 安装到 tvbox/idj 下。 */
    public static boolean importPackage(File file) {
        try {
            install(file);
            File target = new File(root(), new JSONObject(Path.read(file)).optJSONObject("manifest").optString("id") + SUFFIX);
            Path.copy(file, target);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
