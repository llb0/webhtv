package com.fongmi.android.tv.api.parser;

import android.util.Log;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Tv;
import com.fongmi.android.tv.setting.LiveEpgSetting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Formatters;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;
import com.google.gson.Gson;

import org.simpleframework.xml.core.Persister;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import okhttp3.Request;
import okhttp3.Response;

public class EpgParser {

    private static final String TAG = EpgParser.class.getSimpleName();
    public static final long UPDATE_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    public static final int KEEP_DAYS = 7;
    public static final String MERGED_FILE_NAME = "merged_epg.xml";
    public static final String MERGED_META_NAME = "merged_meta.json";
    private static final Gson GSON = new Gson();

    /** 全流程共享锁：防止首次合并与定时同步并发写 merged_epg.xml */
    public static final Object SYNC_LOCK = new Object();

    public static void start(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        // 读取 meta
        loadMergeMeta();
        File mergedFile = Path.epg(MERGED_FILE_NAME);
        if (!mergedFile.exists()) {
            // 首次：同步把第一个有效源直接建为总表（复制原始内容，不重新序列化）
            buildInitialMerged(live, zoneId);
        }
        if (mergedFile.exists()) {
            loadMergedEpg(live, zoneId);
            // 后台做增量合并（仅距离上次合并超过6小时才跑）
            MergeMeta meta = loadMergeMeta();
            long age = System.currentTimeMillis() - meta.lastMergeRun;
            if (meta.lastMergeRun == 0 || age > UPDATE_INTERVAL_MS) {
                new Thread(() -> {
                    synchronized (SYNC_LOCK) {
                        try {
                            syncEpgSources(live);
                        } catch (Exception e) {
                            Log.w(TAG, "background merge error:" + e.getMessage());
                        }
                    }
                }).start();
            }
        }
    }

    /** 首次使用：直接复制第一个有效源的原始内容为总表，格式与源完全一致 */
    private static void buildInitialMerged(Live live, ZoneId zoneId) {
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return;
        MergeMeta meta = loadMergeMeta();
        for (String url : urls) {
            try {
                File cacheFile = ensureCache(url);
                if (cacheFile == null || !cacheFile.exists()) continue;
                String content = readCacheContent(cacheFile);
                if (content.isEmpty()) continue;
                // 直接写原始内容，不重新序列化（保证 XML 结构与源完全一致，避免解析错位）
                writeFile(Path.epg(MERGED_FILE_NAME), content.getBytes(StandardCharsets.UTF_8));
                // 记录该源已合并；lastMergeRun 保持0，让 start() 立即触发后台合并其余源
                MergeMeta.SourceItem item = new MergeMeta.SourceItem();
                item.fileMd5 = Util.md5(cacheFile);
                meta.sources.put(Util.md5(url), item);
                meta.lastMergeRun = 0;
                saveMergeMeta(meta);
                Log.i(TAG, "buildInitialMerged done, source=" + url);
                return;
            } catch (Exception e) {
                Log.w(TAG, "buildInitialMerged url=" + url + " err:" + e.getMessage());
            }
        }
    }

    // 加载合并好的总xml，组装EPG数据（EpgParser只干这件事）
    private static void loadMergedEpg(Live live, ZoneId zoneId) {
        File mergedFile = Path.epg(MERGED_FILE_NAME);
        if (!mergedFile.exists()) return;
        try {
            String content = readCacheContent(mergedFile);
            if (content.isEmpty()) return;
            Tv tv = parseTv(content);
            if (tv == null) return;
            Map<String, Channel> liveChannelMap = prepareLiveChannels(live);
            Map<String, List<Tv.Channel>> xmlChannelMap = tv.getChannel().stream()
                    .collect(Collectors.groupingBy(Tv.Channel::getId));
            Map<String, Map<String, Epg>> sourceMap = buildSourceMap(tv, liveChannelMap, xmlChannelMap, zoneId);
            mergeIntoLive(live, sourceMap);
        } catch (Exception e) {
            Log.w(TAG, "loadMergedEpg error:" + e.getMessage());
        }
    }

    public static class MergeMeta {
        public long lastMergeRun;
        public Map<String, SourceItem> sources = new HashMap<>();
        public static class SourceItem {
            public String fileMd5;
        }
    }

    /** 空闲任务调用：检测全部epg源，下载更新，增量合并到merged_epg.xml */
    public static void syncEpgSources(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return;
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        MergeMeta meta = loadMergeMeta();
        boolean needMerge = false;
        for (String url : urls) {
            try {
                File cacheFile = ensureCache(url);
                if (cacheFile == null || !cacheFile.exists()) continue;
                String fileMd5 = Util.md5(cacheFile);
                String urlMd5 = Util.md5(url);
                MergeMeta.SourceItem sourceItem = meta.sources.get(urlMd5);
                if (sourceItem != null && fileMd5.equals(sourceItem.fileMd5)) {
                    continue;
                }
                // 当前源文件发生变更，执行增量合并
                incrementalMergeSource(url, cacheFile, zoneId);
                // 更新meta记录该源md5
                if (sourceItem == null) sourceItem = new MergeMeta.SourceItem();
                sourceItem.fileMd5 = fileMd5;
                meta.sources.put(urlMd5, sourceItem);
                needMerge = true;
            } catch (Exception e) {
                Log.w(TAG, "syncEpgSources url=" + url + " err:" + e.getMessage());
            }
        }
        if (needMerge) {
            meta.lastMergeRun = System.currentTimeMillis();
            saveMergeMeta(meta);
        }
    }

    /** 增量合并：单个更新源 -> 合并进merged_epg.xml，合并完成统一清理7天外节目 */
    private static void incrementalMergeSource(String url, File sourceCacheFile, ZoneId zoneId) throws Exception {
        String sourceContent = readCacheContent(sourceCacheFile);
        if (sourceContent.isEmpty()) return;
        Tv sourceTv = parseTv(sourceContent);
        if (sourceTv == null) return;

        File mergedFile = Path.epg(MERGED_FILE_NAME);
        Tv mergedTv;
        if (mergedFile.exists()) {
            String mergedContent = readCacheContent(mergedFile);
            mergedTv = mergedContent.isEmpty() ? new Tv() : parseTv(mergedContent);
        } else {
            mergedTv = new Tv();
        }

        // 1.合并channel，按id去重（保留首次出现）
        Map<String, Tv.Channel> channelMap = new LinkedHashMap<>();
        for (Tv.Channel ch : mergedTv.getChannel()) channelMap.putIfAbsent(ch.getId(), ch);
        for (Tv.Channel ch : sourceTv.getChannel()) channelMap.putIfAbsent(ch.getId(), ch);

        // 2.按 频道+日期 去重：同频道同一天只采纳一个源的全天节目单；
        //   不做时间重叠扫描、不排序（原始数据本身已按时间排序），同一天内多条节目全部保留
        LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
        List<Tv.Programme> finalProgs = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        collectByDate(mergedTv.getProgramme(), zoneId, keepFrom, seen, finalProgs, true);
        collectByDate(sourceTv.getProgramme(), zoneId, keepFrom, seen, finalProgs, false);

        // 3.合并结果为空则不覆盖已有总表（避免合并失败把好数据冲掉）
        if (finalProgs.isEmpty()) {
            Log.w(TAG, "incrementalMergeSource: merged programmes empty, skip writing");
            return;
        }
        writeTvToFile(mergedTv, new ArrayList<>(channelMap.values()), finalProgs, mergedFile);
    }

    /** 反射设置 Tv 的 channel/programme 字段，用 SimpleFramework 写出（保证 XML 结构与源一致） */
    private static void writeTvToFile(Tv tv, List<Tv.Channel> channels, List<Tv.Programme> programmes, File file) throws Exception {
        java.lang.reflect.Field chField = Tv.class.getDeclaredField("channel");
        chField.setAccessible(true);
        chField.set(tv, channels);
        java.lang.reflect.Field progField = Tv.class.getDeclaredField("programme");
        progField.setAccessible(true);
        progField.set(tv, programmes);
        new Persister().write(tv, file);
    }

    /**
     * 遍历节目，按 (频道|日期) 归集。
     * @param takeExisting true=已合并数据：全部保留并登记日期；false=新源：仅加入未登记日期的节目
     */
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
                // 该(频道|日期)未被已合并数据占用 → 保留当天全部节目（早中晚多条）；
                // 已被占用 → 整个当天跳过（多源同一天的重复节目单）
                if (!seen.contains(key)) out.add(p);
            }
        }
    }

    private static MergeMeta loadMergeMeta() {
        File metaFile = Path.epg(MERGED_META_NAME);
        MergeMeta meta = new MergeMeta();
        if (metaFile.exists()) {
            try {
                String json = Path.read(metaFile);
                meta = GSON.fromJson(json, MergeMeta.class);
            } catch (Exception e) {
                meta = new MergeMeta();
            }
        }
        return meta;
    }

    private static void saveMergeMeta(MergeMeta meta) {
        try {
            File metaFile = Path.epg(MERGED_META_NAME);
            String json = GSON.toJson(meta);
            writeFile(metaFile, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "saveMergeMeta error: " + e.getMessage());
        }
    }

    /** 标准 Java IO 写文件：确保父目录存在，不吞异常 */
    private static void writeFile(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
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
            String storedTag = readTag(url);
            String remoteTag = fetchRemoteTag(url);
            if (remoteTag == null) return;
            if (storedTag != null && storedTag.equals(remoteTag)) return;
            if (download(url, cacheFile)) writeTag(url, remoteTag);
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

    private static String readTag(String url) {
        try {
            File tagFile = Path.epg(Util.md5(url) + ".tag");
            if (!tagFile.exists()) return null;
            return Path.read(tagFile);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeTag(String url, String tag) {
        try {
            File tagFile = Path.epg(Util.md5(url) + ".tag");
            java.nio.file.Files.write(tagFile.toPath(), tag.getBytes());
        } catch (Exception ignored) {
        }
    }

    private static String readCacheContent(File file) throws Exception {
        byte[] bytes = Path.readToByte(file);
        // 修复gzip魔数判断BUG
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            File xml = Path.epg(file.getName() + ".xml");
            FileUtil.gzipDecompress(file, xml);
            bytes = Path.readToByte(xml);
            // 新增：解压后校验非空
            if(bytes == null || bytes.length == 0){
                return "";
            }
        }
        if (bytes.length == 0) return "";
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.isEmpty()) return "";
        if (content.charAt(0) == '\uFEFF') content = content.substring(1);
        String head = content.trim();
        if (head.startsWith("<!DOCTYPE html") || head.startsWith("<html") || head.startsWith("<HTML")) {
            Log.w(TAG, "readCacheContent: HTML content skipped");
            return "";
        }
        return content;
    }

    private static Tv parseTv(String content) throws Exception {
        return new Persister().read(Tv.class, content, false);
    }

    private static Map<String, Map<String, Epg>> buildSourceMap(Tv tv, Map<String, Channel> liveChannelMap,
                                                                  Map<String, List<Tv.Channel>> xmlChannelMap, ZoneId zoneId) {
        Map<String, Map<String, Epg>> result = new HashMap<>();
        Map<String, Channel> channelCache = new HashMap<>();
        Set<String> channelMiss = new LinkedHashSet<>();
        for (Tv.Programme programme : tv.getProgramme()) {
            String xmlChannelId = programme.getChannel();
            Channel target;
            if (channelCache.containsKey(xmlChannelId)) target = channelCache.get(xmlChannelId);
            else if (channelMiss.contains(xmlChannelId)) target = null;
            else {
                target = findTargetChannel(xmlChannelId, liveChannelMap, xmlChannelMap);
                if (target != null) channelCache.put(xmlChannelId, target);
                else channelMiss.add(xmlChannelId);
            }
            if (target == null) continue;
            String tvgId = target.getTvgId();
            OffsetDateTime startDate = parseFull(programme.getStart(), zoneId);
            // 跳过无效时间节目
            if(startDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) continue;
            OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
            if(endDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) continue;
            String date = startDate.atZoneSameInstant(zoneId).format(Formatters.DATE);
            result.computeIfAbsent(tvgId, k -> new HashMap<>())
                    .computeIfAbsent(date, d -> Epg.create(tvgId, d))
                    .getList().add(getEpgData(startDate, endDate, zoneId, programme));
        }
        return result;
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

    private static Channel findTargetChannel(String xmlChannelId, Map<String, Channel> liveChannelMap,
                                              Map<String, List<Tv.Channel>> xmlChannelMap) {
        Channel target = liveChannelMap.get(xmlChannelId);
        if (target != null) return target;
        List<Tv.Channel> channels = xmlChannelMap.get(xmlChannelId);
        if (channels == null) return null;
        for (Tv.Channel ch : channels) {
            for (Tv.DisplayName dn : ch.getDisplayName()) {
                String name = dn.getText();
                if (!name.isEmpty() && liveChannelMap.containsKey(name)) return liveChannelMap.get(name);
            }
        }
        return null;
    }

    private static void mergeIntoLive(Live live, Map<String, Map<String, Epg>> sourceMap) {
        for (Group group : live.getGroups()) {
            for (Channel channel : group.getChannel()) {
                Map<String, Epg> dateMap = findDateMap(channel, sourceMap);
                if (dateMap == null) continue;
                List<Epg> existing = new ArrayList<>(channel.getDataList());
                Set<String> seenDates = new LinkedHashSet<>();
                for (Epg epg : existing) seenDates.add(epg.getDate());
                for (Epg incoming : dateMap.values()) {
                    if (seenDates.add(incoming.getDate())) existing.add(incoming);
                }
                channel.setDataList(cleanOldDays(existing));
            }
        }
    }

    private static Map<String, Epg> findDateMap(Channel channel, Map<String, Map<String, Epg>> sourceMap) {
        Map<String, Epg> map = sourceMap.get(channel.getTvgId());
        if (map != null) return map;
        map = sourceMap.get(channel.getTvgName());
        if (map != null) return map;
        return sourceMap.get(channel.getName());
    }

    private static List<Epg> cleanOldDays(List<Epg> list) {
        if (list == null || list.isEmpty()) return new ArrayList<>();
        List<Epg> result = new ArrayList<>();
        for (Epg epg : list) {
            try {
                LocalDate date = LocalDate.parse(epg.getDate(), Formatters.DATE);
                if (!date.isBefore(LocalDate.now().minusDays(KEEP_DAYS))) result.add(epg);
            } catch (Exception e) {
                result.add(epg);
            }
        }
        result.sort((a, b) -> a.getDate().compareTo(b.getDate()));
        return result;
    }

    public static Epg getEpg(String xml, String key, ZoneId zoneId) {
        try {
            String content = sanitizeXml(xml);
            if (content.isEmpty()) return new Epg();
            Tv tv = parseTv(content);
            String rawDate = tv.getDate();
            String date = rawDate.isEmpty() ? LocalDate.now(zoneId).format(Formatters.DATE) : parseFull(rawDate, zoneId).atZoneSameInstant(zoneId).format(Formatters.DATE);
            Epg epg = Epg.create(key, date);
            for (Tv.Programme programme : tv.getProgramme()){
                OffsetDateTime startDate = parseFull(programme.getStart(), zoneId);
                OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
                if(startDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC)) || endDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))){
                    continue;
                }
                epg.getList().add(getEpgData(programme, zoneId));
            }
            return epg;
        } catch (Exception e) {
            Log.w(TAG, "getEpg parse failed key=" + key + ": " + e.getMessage());
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
            return LocalDateTime.parse(len > 14 ? s.substring(0, 14) : s, Formatters.EPG_FULL_NO_TZ).atZone(zoneId).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        }
    }
}
