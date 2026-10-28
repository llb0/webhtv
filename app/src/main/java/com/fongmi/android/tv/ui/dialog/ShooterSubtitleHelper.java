package com.fongmi.android.tv.ui.dialog;
 
import android.util.Log;
 
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
 
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
 
import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;
 
/**
 * 射手字幕 API 客户端。
 * 按官方协议：先算文件特征哈希 → POST search → 拿候选 → 下载直链。
 * 支持两类输入：
 *   - 本地文件路径（直接 RandomAccessFile 读四段算哈希）
 *   - HTTP(S) URL（发 HEAD 或 Range 请求拿文件大小，再按四段偏移各发一次 Range GET 算哈希）
 * 典型场景：file:///sdcard/... 转成 http://127.0.0.1:9978/file/... 代理 URL 时也能用。
 */
public final class ShooterSubtitleHelper {
 
    private static final String TAG = "ShooterSub";
    private static final String API = "https://www.shooter.cn/api/subapi.php";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    private static final long MIN_FILE_SIZE = 12L * 1024; // 射手要求文件至少 12KB
    private static final long RANGE_CHUNK = 4L * 1024;
 
    public static class Candidate {
        public final String displayName;   // 显示名，如 "filename | 射手 | srt"
        public final String language;      // zh / eng
        public final String format;        // srt / ass / ssa / vtt
        public final String downloadUrl;   // 射手返回的下载直链
 
        Candidate(String name, String lang, String fmt, String url) {
            this.displayName = name;
            this.language = lang;
            this.format = fmt;
            this.downloadUrl = url;
        }
 
        @Override public String toString() { return displayName; }
    }
 
    /** 计算射手的特征哈希。返回 null 表示文件不满足条件。 */
    public static String computeHash(File file) {
        if (file == null || !file.isFile()) return null;
        long size = file.length();
        if (size < MIN_FILE_SIZE) return null;
        long[] offsets = new long[]{4 * 1024L, size / 3 * 2, size / 3, size - 8 * 1024L};
        // 修正最后一个 offset（避免 size < 8KB 时变成负数）
        offsets[3] = Math.max(offsets[3], 0L);
        byte[] buffer = new byte[4 * 1024];
        StringBuilder sb = new StringBuilder();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (long offset : offsets) {
                raf.seek(offset);
                int read = raf.read(buffer);
                if (read <= 0) continue;
                md.reset();
                md.update(buffer, 0, read);
                if (sb.length() > 0) sb.append(';');
                sb.append(hex(md.digest()));
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "computeHash failed", e);
            return null;
        }
    }
 
    /** 用本地文件在射手搜字幕候选。 */
    public static List<Candidate> search(File file) throws Exception {
        List<Candidate> empty = new ArrayList<>();
        if (file == null || !file.isFile()) return empty;
        String hash = computeHash(file);
        if (hash == null) {
            Log.i(TAG, "skip: invalid file or too small " + file.getName());
            return empty;
        }
        return postSearch(hash, file.getAbsolutePath(), file.getName());
    }
 
    /** 通用入口：file:/// → 本地路径；http(s):// → HTTP Range；其他 → 尝试 file 处理。 */
    public static List<Candidate> searchUrl(String url) throws Exception {
        List<Candidate> empty = new ArrayList<>();
        if (url == null || url.isEmpty()) return empty;
        String clean = url.trim();
        String scheme;
        String pathinfo;
        String fileName;
        String hash;
        try {
            URI uri = URI.create(clean);
            scheme = uri.getScheme();
            pathinfo = clean;
            fileName = extractFileName(uri.getPath());
        } catch (Exception e) {
            return empty;
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            hash = computeHttpRangeHash(clean);
            if (hash == null) return empty;
            return postSearch(hash, clean, fileName);
        }
        if ("file".equalsIgnoreCase(scheme)) {
            String p = uri.getPath();
            // 去掉开头多余的一个 /（file://sdcard/... → /sdcard/...）
            if (p != null && p.startsWith("//")) p = p.substring(1);
            File file = new File(p == null ? clean : p);
            return search(file);
        }
        // 兜底：直接按文件路径试
        File f = new File(clean);
        return search(f);
    }
 
    /** 对 HTTP(S) URL 发 Range 请求取文件四段 4KB 算射手哈希。 */
    public static String computeHttpRangeHash(String url) {
        try {
            long size = probeContentLength(url);
            if (size < MIN_FILE_SIZE) {
                Log.i(TAG, "skip http: too small " + url + " size=" + size);
                return null;
            }
            long[] offsets = new long[]{4 * 1024L, size / 3 * 2, size / 3, Math.max(size - 8 * 1024L, 0L)};
            byte[] buffer = new byte[(int) RANGE_CHUNK];
            StringBuilder sb = new StringBuilder();
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (long offset : offsets) {
                Request req = new Request.Builder().url(url)
                        .header("User-Agent", UA)
                        .header("Range", "bytes=" + offset + "-" + (offset + RANGE_CHUNK - 1))
                        .get().build();
                try (Response resp = OkHttp.client().newCall(req).execute()) {
                    if (resp.code() != 206 || resp.body() == null) {
                        Log.w(TAG, "range fail code=" + resp.code() + " offset=" + offset + " url=" + url);
                        return null;
                    }
                    InputStream in = resp.body().byteStream();
                    int read = 0;
                    int n;
                    while (read < buffer.length && (n = in.read(buffer, read, buffer.length - read)) > 0) {
                        read += n;
                    }
                    if (read <= 0) continue;
                    md.reset();
                    md.update(buffer, 0, read);
                    if (sb.length() > 0) sb.append(';');
                    sb.append(hex(md.digest()));
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "computeHttpRangeHash fail " + url, e);
            return null;
        }
    }
 
    /** 对 URL 发 HEAD 或 Range 请求拿 Content-Length。 */
    private static long probeContentLength(String url) {
        // 先 HEAD
        try {
            Request head = new Request.Builder().url(url)
                    .header("User-Agent", UA).head().build();
            try (Response resp = OkHttp.client().newCall(head).execute()) {
                String cl = resp.header("Content-Length");
                if (cl != null) return Long.parseLong(cl);
            }
        } catch (Exception ignored) {}
        // HEAD 不支持时（有些代理对 HEAD 返回 405），发 Range: bytes=0-0 看 Content-Range
        try {
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Range", "bytes=0-0").get().build();
            try (Response resp = OkHttp.client().newCall(req).execute()) {
                String cr = resp.header("Content-Range");
                if (cr != null) {
                    // bytes 0-0/12345
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0) return Long.parseLong(cr.substring(slash + 1));
                }
                String cl = resp.header("Content-Length");
                if (cl != null) return Long.parseLong(cl);
            }
        } catch (Exception ignored) {}
        return 0;
    }
 
    /** 真正向射手 POST 请求。文件信息用于 pathinfo（仅用于搜索命中率，射手不做权限校验）。 */
    private static List<Candidate> postSearch(String hash, String pathinfo, String fileName) throws Exception {
        FormBody body = new FormBody.Builder()
                .add("filehash", hash)
                .add("pathinfo", pathinfo)
                .add("format", "json")
                .add("lang", "chn")
                .build();
        Request request = new Request.Builder().url(API).header("User-Agent", UA).post(body).build();
        try (Response response = OkHttp.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "search http fail code=" + response.code());
                return new ArrayList<>();
            }
            String raw = response.body().string();
            if (raw == null || raw.isEmpty()) return new ArrayList<>();
            return parse(raw, fileName == null || fileName.isEmpty() ? pathinfo : fileName);
        }
    }
 
    private static List<Candidate> parse(String json, String fileName) {
        List<Candidate> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return out;
            for (JsonElement group : root.getAsJsonArray()) {
                if (!group.isJsonObject()) continue;
                JsonArray files = group.getAsJsonObject().has("Files")
                        && group.getAsJsonObject().get("Files").isJsonArray()
                        ? group.getAsJsonObject().getAsJsonArray("Files") : new JsonArray();
                for (JsonElement el : files) {
                    if (!el.isJsonObject()) continue;
                    JsonObject f = el.getAsJsonObject();
                    String link = f.has("Link") && !f.get("Link").isJsonNull()
                            ? f.get("Link").getAsString() : "";
                    if (link.isEmpty()) continue;
                    String ext = f.has("Ext") && !f.get("Ext").isJsonNull()
                            ? f.get("Ext").getAsString() : "";
                    String format = detectFormat(ext, link);
                    out.add(new Candidate(
                            fileName + " | 射手 | " + format,
                            "zh", format, link));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse failed", e);
        }
        return out;
    }
 
    /** 把候选字幕下载到本地。返回最终 .srt/.ass/.vtt 文件。 */
    public static File download(Candidate candidate, File destDir) throws Exception {
        if (candidate == null || destDir == null) throw new IllegalArgumentException();
        if (!destDir.exists()) destDir.mkdirs();
        // 用时间戳避免冲突
        String safeName = "shooter_" + System.currentTimeMillis() + "." + candidate.format;
        File target = new File(destDir, safeName);
        Request request = new Request.Builder().url(candidate.downloadUrl)
                .header("User-Agent", UA).get().build();
        try (Response response = OkHttp.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("download code=" + response.code());
            }
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
        return target;
    }
 
    private static String detectFormat(String ext, String url) {
        String raw = (ext == null || ext.isEmpty()) ? extFrom(url) : ext;
        if (raw != null) raw = raw.toLowerCase(Locale.ROOT).replace(".", "");
        if ("ass".equals(raw) || "ssa".equals(raw) || "srt".equals(raw) || "vtt".equals(raw)) return raw;
        return "srt";
    }
 
    private static String extFrom(String url) {
        if (url == null) return "";
        int i = url.lastIndexOf('.');
        int q = url.indexOf('?', i);
        if (i < 0) return "";
        if (q < 0) return url.substring(i + 1);
        return url.substring(i + 1, q);
    }
 
    private static String extractFileName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
 
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }
}
