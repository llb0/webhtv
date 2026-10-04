package com.fongmi.android.tv.api.parser;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.MergeMeta;
import com.fongmi.android.tv.bean.Tv;
import com.fongmi.android.tv.setting.LiveEpgSetting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Formatters;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.simpleframework.xml.core.Persister;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

public class EpgParser {

    private static final String TAG = "EpgParser";
    public static final long UPDATE_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    public static final int KEEP_DAYS = 7;
    public static final String INDEX_FILE_NAME = "merged_index.json";
    public static final String MERGED_META_NAME = "merged_meta.json";
    private static final Gson GSON = new Gson();

    public static final Object SYNC_LOCK = new Object();

    private static File getIndexFile() {
        File dir = new File(Path.files(), "epg");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, INDEX_FILE_NAME);
    }

    private static File getMergeMetaFile() {
        File dir = new File(Path.files(), "epg");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MERGED_META_NAME);
    }

    private static File getEpgCacheDir() {
        return new File(Path.files(), "epg");
    }

    private static File getCacheFile(String url) {
        return new File(getEpgCacheDir(), Util.md5(url) + ".xml");
    }

    public static void start(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        MergeMeta meta = loadMergeMeta();
        File indexFile = getIndexFile();
        if (!indexFile.exists()) {
            SpiderDebug.log(TAG, "没有Epg持久索引，进入首次快速加载并持久化");
            buildInitialEpgAndSave(live, zoneId);
            startBackgroundMerge(live, zoneId);
        } else {
            SpiderDebug.log(TAG, "Epg持久化索引存在，正常加载");
            loadMergedIndex(live, zoneId);
            long age = System.currentTimeMillis() - meta.lastMergeRun;
            if (meta.lastMergeRun == 0 || age > UPDATE_INTERVAL_MS) {
                new Thread(() -> {
                    synchronized (SYNC_LOCK) {
                        try {
                            syncEpgSources(live, zoneId);
                        } catch (Exception e) {
                            SpiderDebug.log(TAG, "后台合并Epg索引错误：" + e.toString());
                        }
                    }
                }).start();
            }
        }
    }

    /**
     * 首次快速加载：仅下载源、生成索引并持久化，移除独立内存组装逻辑
     * 全部EPG绑定逻辑交给loadMergedIndex，和后台合并流程完全统一
     */
    private static void buildInitialEpgAndSave(Live live, ZoneId zoneId) {
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return;
        for (String url : urls) {
            try {
                SpiderDebug.log(TAG, "首次尝试源：" + url);
                File cacheFile = getCacheFile(url);
                // 首次场景强制下载第一个源，不复用任何本地残留缓存
                if (!download(url, cacheFile)) {
                    SpiderDebug.log(TAG, "首次下载源失败：" + url);
                    continue;
                }
                String content = readCacheContent(cacheFile, url);
                if (content.isEmpty()) {
                    SpiderDebug.log(TAG, "源读取内容为空：" + url);
                    continue;
                }
                Tv tv = parseTv(content);
                if (tv == null) continue;

                // 单源，不做任何过滤、不去重，直接构建索引用于持久化
                Map<String, List<Tv.Programme>> tempProgIndex = new HashMap<>();
                for (Tv.Programme origin : tv.getProgramme()) {
                    tempProgIndex.computeIfAbsent(origin.getChannel(), k -> new ArrayList<>()).add(origin);
                }

                // 持久化本次第一个源的索引
                writeIndexFile(tempProgIndex);
                // 写入meta，记录当前源ETag
                MergeMeta meta = new MergeMeta();
                String urlMd5 = Util.md5(url);
                MergeMeta.SourceItem sourceItem = new MergeMeta.SourceItem();
                String currentEtag = fetchRemoteTag(url);
                sourceItem.etag = currentEtag;
                meta.sources.put(urlMd5, sourceItem);
                meta.lastMergeRun = System.currentTimeMillis();
                saveMergeMeta(meta);

                // 统一走loadMergedIndex加载磁盘索引填充channel EPG，对齐后台合并逻辑
                loadMergedIndex(live, zoneId);
                SpiderDebug.log(TAG, "首次加载Epg成功并持久化, source=" + url);
                return;
            } catch (Exception e) {
                SpiderDebug.log(TAG, "首次加载源失败 url=" + url + "：" + e.toString());
            }
        }
    }

    private static void writeIndexFile(Map<String, List<Tv.Programme>> indexMap) throws Exception {
        File indexFile = getIndexFile();
        File tempFile = new File(indexFile.getParent(), INDEX_FILE_NAME + ".tmp");
        String jsonOutput = GSON.toJson(indexMap);
        writeFile(tempFile, jsonOutput.getBytes(StandardCharsets.UTF_8));
        if (tempFile.exists() && tempFile.length() > 0) {
            if (indexFile.exists()) indexFile.delete();
            tempFile.renameTo(indexFile);
        } else {
            if (tempFile.exists()) tempFile.delete();
            throw new Exception("写入索引临时文件失败");
        }
    }

    private static void startBackgroundMerge(Live live, ZoneId zoneId) {
        new Thread(() -> {
            synchronized (SYNC_LOCK) {
                try {
                    syncEpgSources(live, zoneId);
                } catch (Exception e) {
                    SpiderDebug.log(TAG, "首次触发后台合并失败：" + e.toString());
                }
            }
        }).start();
    }

    /**
     * 加载持久化json索引，直接组装EPG
     */
    private static void loadMergedIndex(Live live, ZoneId zoneId) {
        File indexFile = getIndexFile();
        if (!indexFile.exists()) return;
        try {
            String json = Path.read(indexFile);
            TypeToken<Map<String, List<Tv.Programme>>> typeToken = new TypeToken<Map<String, List<Tv.Programme>>>() {};
            Map<String, List<Tv.Programme>> progIndex = GSON.fromJson(json, typeToken.getType());
            if (progIndex == null) {
                SpiderDebug.log(TAG, "Epg索引解析为空，进入首次逻辑");
                buildInitialEpgAndSave(live, zoneId);
                startBackgroundMerge(live, zoneId);
                return;
            }
            Map<String, Channel> liveChannelMap = prepareLiveChannels(live);
            for (Group group : live.getGroups()) {
                for (Channel ch : group.getChannel()) {
                    List<Tv.Programme> progList = progIndex.get(ch.getTvgId());
                    if (progList == null || progList.isEmpty()) {
                        progList = progIndex.get(ch.getTvgName());
                    }
                    if (progList == null || progList.isEmpty()) continue;
                    List<Epg> epgList = new ArrayList<>();
                    Map<String, Epg> dateGroup = new LinkedHashMap<>();
                    for (Tv.Programme programme : progList) {
                        OffsetDateTime startDate = parseFull(programme.getStart(), zoneId);
                        OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
                        if (startDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC)) || endDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) {
                            continue;
                        }
                        String dateStr = startDate.atZoneSameInstant(zoneId).format(Formatters.DATE);
                        Epg epg = dateGroup.get(dateStr);
                        if (epg == null) {
                            epg = Epg.create(ch.getTvgId(), dateStr);
                            dateGroup.put(dateStr, epg);
                        }
                        epg.getList().add(getEpgData(programme, zoneId));
                    }
                    epgList.addAll(dateGroup.values());
                    ch.setDataList(epgList);
                }
            }
        } catch (Exception e) {
            SpiderDebug.log(TAG, "加载持久Epg索引出错，进入首次逻辑：" + e.toString());
            buildInitialEpgAndSave(live, zoneId);
            startBackgroundMerge(live, zoneId);
        }
    }

    public static void syncEpgSources(Live live) {
        syncEpgSources(live, null);
    }

    public static void syncEpgSources(Live live, ZoneId zoneId) {
        if (live == null || live.getGroups().isEmpty()) {
            SpiderDebug.log(TAG, "Epg远程更新时Live为空！");
            return;
        }
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return;
        MergeMeta meta = loadMergeMeta();
        boolean needMerge = false;
        SpiderDebug.log(TAG, "准备Epg远程更新...");
        for (String url : urls) {
            try {
                String urlMd5 = Util.md5(url);
                String remoteEtag = fetchRemoteTag(url);
                MergeMeta.SourceItem sourceItem = meta.sources.get(urlMd5);
                if (sourceItem != null && remoteEtag != null && remoteEtag.equals(sourceItem.etag)) {
                    continue;
                }
                SpiderDebug.log(TAG, "远程Epg有变化：" + url);
                File cacheFile = getCacheFile(url);
                // Etag不一致才下载
                if (!download(url, cacheFile)) continue;

                incrementalMergeSource(url, cacheFile, zoneId);
                if (sourceItem == null) sourceItem = new MergeMeta.SourceItem();
                sourceItem.etag = remoteEtag;
                meta.sources.put(urlMd5, sourceItem);
                needMerge = true;
                //合并完成删除本次缓存
                if(cacheFile.exists()) cacheFile.delete();
            } catch (Exception e) {
                SpiderDebug.log(TAG, "后台更新Epg出错 url=" + url + "：" + e.toString());
            }
        }
        if (needMerge) {
            meta.lastMergeRun = System.currentTimeMillis();
            saveMergeMeta(meta);
            if (zoneId != null) loadMergedIndex(live, zoneId);
            SpiderDebug.log(TAG, "远程Epg数据已更新至本地。");
        } else {
            SpiderDebug.log(TAG, "远程Epg数据无变化，未更新。");
        }
        // 合并完成，清理无效缓存
        cleanStaleEpgCache(meta, urls);
    }

    /**
     * 多源合并，合并后生成索引json持久化，保留7天过滤，多源去重，防止数据无限增大
     */
    private static void incrementalMergeSource(String url, File sourceCacheFile, ZoneId zoneId) throws Exception {
        String sourceContent = readCacheContent(sourceCacheFile, url);
        if (sourceContent.isEmpty()) return;
        Tv sourceTv = parseTv(sourceContent);
        if (sourceTv == null) return;

        Map<String, List<Tv.Programme>> mergedIndex;
        File indexFile = getIndexFile();
        if (indexFile.exists()) {
            String json = Path.read(indexFile);
            TypeToken<Map<String, List<Tv.Programme>>> typeToken = new TypeToken<Map<String, List<Tv.Programme>>>() {};
            mergedIndex = GSON.fromJson(json, typeToken.getType());
        } else {
            mergedIndex = new HashMap<>();
        }

        LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
        List<Tv.Programme> finalProgs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (List<Tv.Programme> list : mergedIndex.values()) {
            collectByDate(list, zoneId, keepFrom, seen, finalProgs, true);
        }
        collectByDate(sourceTv.getProgramme(), zoneId, keepFrom, seen, finalProgs, false);

        Map<String, List<Tv.Programme>> newIndex = new HashMap<>();
        for (Tv.Programme p : finalProgs) {
            newIndex.computeIfAbsent(p.getChannel(), k -> new ArrayList<>()).add(p);
        }
        if (newIndex.isEmpty()) {
            SpiderDebug.log(TAG, "合并索引结果为空，不覆盖");
            return;
        }
        writeIndexFile(newIndex);
    }

    /**
     * 清理失效缓存：不在当前url列表里的缓存文件全部删除
     */
    private static void cleanStaleEpgCache(MergeMeta meta, List<String> validUrls) {
        File cacheDir = getEpgCacheDir();
        File[] allFiles = cacheDir.listFiles();
        if (allFiles == null) return;
        Set<String> validUrlMd5Set = new HashSet<>();
        for(String url : validUrls){
            validUrlMd5Set.add(Util.md5(url));
        }
        for (File f : allFiles) {
            String name = f.getName();
            if (name.equals(INDEX_FILE_NAME) || name.equals(MERGED_META_NAME) || name.endsWith(".tmp")) {
                continue;
            }
            //文件名前缀是urlMd5
            boolean keep = false;
            for(String md5 : validUrlMd5Set){
                if(name.startsWith(md5)){
                    keep = true;
                    break;
                }
            }
            if(!keep){
                boolean del = f.delete();
                SpiderDebug.log(TAG, "清理过期EPG缓存:" + f.getName() + " del=" + del);
            }
        }
    }

    private static void collectByDate(List<Tv.Programme> programmes, ZoneId zoneId, LocalDate keepFrom,
                                       Set<String> seen, List<Tv.Programme> out, boolean takeExisting) {
        for (Tv.Programme p : programmes) {
            OffsetDateTime start = parseFull(p.getStart(), zoneId);
            if (start.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) continue;
            LocalDate progDate = start.atZoneSameInstant(zoneId).toLocalDate();
            if (progDate.isBefore(keepFrom)) continue;
            String key = p.getChannel() + "|" + progDate.format(Formatters.DATE);
            if (takeExisting) {
                out.add(p);
                seen.add(key);
            } else {
                if (!seen.contains(key)) out.add(p);
            }
        }
    }

    private static MergeMeta loadMergeMeta() {
        File metaFile = getMergeMetaFile();
        MergeMeta meta = new MergeMeta();
        if (metaFile.exists()) {
            try {
                String json = Path.read(metaFile);
                TypeToken<MergeMeta> typeToken = new TypeToken<MergeMeta>() {};
                meta = GSON.fromJson(json, typeToken.getType());
            } catch (Exception e) {
                SpiderDebug.log(TAG, "meta文件解析损坏，使用空meta：" + e.toString());
            }
        }
        return meta;
    }

    private static void saveMergeMeta(MergeMeta meta) {
        try {
            File metaFile = getMergeMetaFile();
            File tempFile = new File(metaFile.getParent(), MERGED_META_NAME + ".tmp");
            String json = GSON.toJson(meta);
            writeFile(tempFile, json.getBytes(StandardCharsets.UTF_8));
            if (tempFile.exists() && tempFile.length() > 0) {
                if (metaFile.exists()) metaFile.delete();
                tempFile.renameTo(metaFile);
            } else {
                if (tempFile.exists()) tempFile.delete();
            }
        } catch (Exception e) {
            SpiderDebug.log(TAG, "保存meta失败：" + e.toString());
        }
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    private static boolean download(String url, File file) {
        try {
            com.fongmi.android.tv.utils.Download.create(url, file).get();
            return file.exists() && file.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String fetchRemoteTag(String url) {
        try {
            Request request = new Request.Builder().url(url).head().build();
            try (Response res = OkHttp.client().newCall(request).execute()) {
                if (!res.isSuccessful()) return null;
                String etag = res.header(HttpHeaders.ETAG);
                if (etag != null && !etag.isEmpty()) return etag;
                String lastModified = res.header(HttpHeaders.LAST_MODIFIED);
                if (lastModified != null && !lastModified.isEmpty()) return lastModified;
                String contentLength = res.header(HttpHeaders.CONTENT_LENGTH);
                return contentLength != null ? contentLength : String.valueOf(System.currentTimeMillis());
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String readCacheContent(File file, String url) throws Exception {
        byte[] bytes = Path.readToByte(file);
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            File xml = new File(getEpgCacheDir(), file.getName() + ".xml");
            try {
                FileUtil.gzipDecompress(file, xml);
                bytes = Path.readToByte(xml);
                if (bytes == null || bytes.length == 0) {
                    return "";
                }
            } finally {
                xml.delete();
            }
        }
        if (bytes.length == 0) return "";
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.isEmpty()) return "";
        if (content.charAt(0) == '\uFEFF') content = content.substring(1);
        String head = content.trim();
        if (head.startsWith("<!DOCTYPE html") || head.startsWith("<html") || head.startsWith("<HTML")) {
            SpiderDebug.log(TAG, url + "缓存内容是html，弃用");
            return "";
        }
        return content;
    }

    private static Tv parseTv(String content) throws Exception {
        return new Persister().read(Tv.class, content, false);
    }

    private static Map<String, Channel> prepareLiveChannels(Live live) {
        Map<String, Channel> map = new HashMap<>();
        for (Group group : live.getGroups()) {
            for (Channel channel : group.getChannel()) {
                if (!channel.getTvgId().isEmpty()) map.putIfAbsent(channel.getTvgId(), channel);
                if (!channel.getTvgName().isEmpty()) map.putIfAbsent(channel.getTvgName(), channel);
                if (!channel.getName().isEmpty()) map.putIfAbsent(channel.getName(), channel);
            }
        }
        return map;
    }

    public static Epg getEpg(String xml, String key, ZoneId zoneId) {
        try {
            String content = sanitizeXml(xml);
            if (content.isEmpty()) return new Epg();
            Tv tv = parseTv(content);
            String rawDate = tv.getDate();
            String date = rawDate.isEmpty() ? LocalDate.now(zoneId).format(Formatters.DATE) : parseFull(rawDate, zoneId).atZoneSameInstant(zoneId).format(Formatters.DATE);
            Epg epg = Epg.create(key, date);
            for (Tv.Programme programme : tv.getProgramme()) {
                OffsetDateTime startDate = parseFull(programme.getStart(), zoneId);
                OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
                if (startDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC)) || endDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) {
                    continue;
                }
                epg.getList().add(getEpgData(programme, zoneId));
            }
            return epg;
        } catch (Exception e) {
            SpiderDebug.log(TAG, "获取Epg数据失败 key=" + key + "：" + e.toString());
            return new Epg();
        }
    }

    private static String sanitizeXml(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        String s = xml;
        if (s.charAt(0) == '\uFEFF') s = s.substring(1);
        String head = s.trim();
        if (head.startsWith("<!DOCTYPE html") || head.startsWith("<html") || head.startsWith("<HTML")) return "";
        return s;
    }

    private static EpgData getEpgData(Tv.Programme programme, ZoneId zoneId) {
        OffsetDateTime startDate = parseFull(programme.getStart(), zoneId);
        OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
        return getEpgData(startDate, endDate, zoneId, programme);
    }

    private static EpgData getEpgData(OffsetDateTime startDate, OffsetDateTime endDate, ZoneId zoneId, Tv.Programme programme) {
        try {
            EpgData data = new EpgData();
            data.setTitle(programme.getTitle());
            data.setStart(startDate.atZoneSameInstant(zoneId).format(Formatters.TIME));
            data.setEnd(endDate.atZoneSameInstant(zoneId).format(Formatters.TIME));
            data.setStartTime(startDate.toInstant().toEpochMilli());
            data.setEndTime(endDate.toInstant().toEpochMilli());
            data.trans();
            return data;
        } catch (Exception e) {
            return new EpgData();
        }
    }

    private static ZoneId zoneIdOf(String tz) {
        if (tz == null || tz.isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(tz);
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }

    private static OffsetDateTime parseFull(String source, ZoneId zoneId) {
        String s = source.trim();
        int len = s.length();
        try {
            if (len >= 20)
                return OffsetDateTime.parse(s, s.charAt(len - 3) == ':' ? Formatters.EPG_FULL_COLON : Formatters.EPG_FULL);
            return java.time.LocalDateTime.parse(len > 14 ? s.substring(0, 14) : s, Formatters.EPG_FULL_NO_TZ).atZone(zoneId).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        }
    }
}
