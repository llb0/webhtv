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
import java.util.stream.Collectors;

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

    public static void start(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        MergeMeta meta = loadMergeMeta();
        File indexFile = getIndexFile();
        if (!indexFile.exists()) {
            SpiderDebug.log(TAG, "没有Epg持久索引，进入首次快速加载并持久化");
            buildInitialEpgAndSave(live, zoneId);
            startBackgroundMerge(live);
        } else {
            loadMergedIndex(live, zoneId);
            long age = System.currentTimeMillis() - meta.lastMergeRun;
            if (meta.lastMergeRun == 0 || age > UPDATE_INTERVAL_MS) {
                new Thread(() -> {
                    synchronized (SYNC_LOCK) {
                        try {
                            syncEpgSources(live);
                        } catch (Exception e) {
                            SpiderDebug.log(TAG, "后台合并Epg索引错误：" + e.toString());
                        }
                    }
                }).start();
            }
        }
    }

    /**
     * 首次快速加载：取第一个可用源，【不做7天过滤】，同步填充live原始channel，并且持久化索引+meta
     * 解决：第一次打开直播页面，start返回前就把dataList写入原始channel，上层UI直接读到EPG
     */
    private static void buildInitialEpgAndSave(Live live, ZoneId zoneId) {
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return;
        for (String url : urls) {
            try {
                File cacheFile = ensureCache(url);
                if (cacheFile == null || !cacheFile.exists()) continue;
                String content = readCacheContent(cacheFile, url);
                if (content.isEmpty()) continue;
                Tv tv = parseTv(content);
                if (tv == null) continue;
                Map<String, List<Tv.Programme>> tempProgIndex = new HashMap<>();
                for (Tv.Programme p : tv.getProgramme()) {
                    OffsetDateTime start = parseFull(p.getStart(), zoneId);
                    if (start.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) continue;
                    tempProgIndex.computeIfAbsent(p.getChannel(), k -> new ArrayList<>()).add(p);
                }
                // 填充原始live的channel，直接修改live内Channel实例，无副本问题
                Map<String, Channel> liveChannelMap = prepareLiveChannels(live);
                for (Group group : live.getGroups()) {
                    for (Channel ch : group.getChannel()) {
                        List<Tv.Programme> progList = tempProgIndex.get(ch.getTvgId());
                        if (progList == null || progList.isEmpty()) {
                            progList = tempProgIndex.get(ch.getTvgName());
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

    private static void startBackgroundMerge(Live live) {
        new Thread(() -> {
            synchronized (SYNC_LOCK) {
                try {
                    syncEpgSources(live);
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
                startBackgroundMerge(live);
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
            startBackgroundMerge(live);
        }
    }

    public static void syncEpgSources(Live live) {
        if (live == null || live.getGroups().isEmpty()) {
            SpiderDebug.log(TAG, "Epg后台更新时Live为空！");
            return;
        }
        SpiderDebug.log(TAG, "进入Epg后台更新。");
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return;
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        MergeMeta meta = loadMergeMeta();
        boolean needMerge = false;
        for (String url : urls) {
            try {
                String urlMd5 = Util.md5(url);
                String remoteEtag = fetchRemoteTag(url);
                MergeMeta.SourceItem sourceItem = meta.sources.get(urlMd5);
                if (sourceItem != null && remoteEtag != null && remoteEtag.equals(sourceItem.etag)) {
                    SpiderDebug.log(TAG, "源ETag无变化，跳过：" + url);
                    continue;
                }
                File cacheFile = ensureCache(url);
                if (cacheFile == null || !cacheFile.exists()) continue;

                incrementalMergeSource(url, cacheFile, zoneId);
                if (sourceItem == null) sourceItem = new MergeMeta.SourceItem();
                sourceItem.etag = remoteEtag;
                meta.sources.put(urlMd5, sourceItem);
                needMerge = true;
                //合并完成删除本次缓存
                if(cacheFile.exists()) cacheFile.delete();
            } catch (Exception e) {
                SpiderDebug.log(TAG, "后台更新索引出错 url=" + url + "：" + e.toString());
            }
        }
        if (needMerge) {
            meta.lastMergeRun = System.currentTimeMillis();
            saveMergeMeta(meta);
            SpiderDebug.log(TAG, "Epg后台更新索引完毕。");
        }
        // 合并完成，清理无效缓存
        cleanStaleEpgCache(meta, urls);
    }

    /**
     * 多源合并，合并后生成索引json持久化，保留7天过滤
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

    private static File ensureCache(String url) {
        File cacheFile = Path.epg(Util.md5(url) + ".xml");
        if (!cacheFile.exists()) {
            if (!download(url, cacheFile)) return null;
            return cacheFile;
        }
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > UPDATE_INTERVAL_MS) refreshIfChanged(url, cacheFile);
        return cacheFile;
    }

    private static void refreshIfChanged(String url, File cacheFile) {
        try {
            String remoteTag = fetchRemoteTag(url);
            MergeMeta meta = loadMergeMeta();
            String urlMd5 = Util.md5(url);
            MergeMeta.SourceItem item = meta.sources.get(urlMd5);
            String storedTag = (item != null) ? item.etag : null;
            if (remoteTag == null) return;
            if (storedTag != null && storedTag.equals(remoteTag)) return;
            if (download(url, cacheFile)) {
                item = new MergeMeta.SourceItem();
                item.etag = remoteTag;
                meta.sources.put(urlMd5, item);
                saveMergeMeta(meta);
            }
        } catch (Exception ignored) {
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

    // 删除readTag、writeTag 独立tag文件相关方法

    private static String readCacheContent(File file, String url) throws Exception {
        byte[] bytes = Path.readToByte(file);
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            File xml = Path.epg(file.getName() + ".xml");
            FileUtil.gzipDecompress(file, xml);
            bytes = Path.readToByte(xml);
            if (bytes == null || bytes.length == 0) {
                return "";
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
