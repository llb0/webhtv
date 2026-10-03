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

    private static final String TAG = "EpgParser";
    public static final long UPDATE_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    public static final int KEEP_DAYS = 7;
    public static final String MERGED_FILE_NAME = "merged_epg.xml";
    public static final String MERGED_META_NAME = "merged_meta.json";
    private static final Gson GSON = new Gson();

    /** 全流程共享锁：防止首次合并与定时同步并发写 merged_epg.xml */
    public static final Object SYNC_LOCK = new Object();

    // ========== 新增：合并成品放在 files/epg，不受清缓存影响 ==========
    private static File getMergedEpgFile() {
        File dir = new File(Path.files(), "epg");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MERGED_FILE_NAME);
    }

    private static File getMergeMetaFile() {
        File dir = new File(Path.files(), "epg");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MERGED_META_NAME);
    }
    // ==============================================================

    public static void start(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        // 读取 meta
        loadMergeMeta();
        File mergedFile = getMergedEpgFile();
        if (!mergedFile.exists()) {
            // 首次：同步把第一个有效源直接建为总表（复制原始内容，不重新序列化）
            SpiderDebug.log(TAG, "没有Epg总表");

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
                            SpiderDebug.log(TAG, "后台合并Epg总表错误："+e.toString());
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
                String content = readCacheContent(cacheFile, url);
                if (content.isEmpty()) continue;
                // 原子写入，先写临时文件（临时文件也放在files目录）
                File tempFile = new File(getMergedEpgFile().getParent(), MERGED_FILE_NAME + ".tmp");
                writeFile(tempFile, content.getBytes(StandardCharsets.UTF_8));
                File mergedFile = getMergedEpgFile();
                if(tempFile.exists() && tempFile.length()>0){
                    if(mergedFile.exists()) mergedFile.delete();
                    tempFile.renameTo(mergedFile);
                }else{
                    SpiderDebug.log(TAG, "首次初始化临时文件写入失败");
                    if(tempFile.exists()) tempFile.delete();
                    continue;
                }
                // 记录该源已合并；lastMergeRun 保持0，让 start() 立即触发后台合并其余源
                MergeMeta.SourceItem item = new MergeMeta.SourceItem();
                item.fileMd5 = Util.md5(cacheFile);
                meta.sources.put(Util.md5(url), item);
                meta.lastMergeRun = 0;
                saveMergeMeta(meta);
                SpiderDebug.log(TAG, "首次使用，加载第一个可用Epg源, source=" + url);
                return;
            } catch (Exception e) {
                SpiderDebug.log(TAG, "首次使用时加载第一个可用Epg源错误 url=" + url+"："+e.toString());
            }
        }
    }

    // 加载合并好的总xml，组装EPG数据
    private static void loadMergedEpg(Live live, ZoneId zoneId) {
        File mergedFile = getMergedEpgFile();
        if (!mergedFile.exists()) return;
        try {
            String content = readCacheContent(mergedFile, "Epg总表");
            if (content.isEmpty()) {
                SpiderDebug.log(TAG, "Epg总表为空！");
                return;
            }
            Tv tv = parseTv(content);
            if (tv == null) return;

            // 构建节目索引，仅遍历一次节目，不做时间解析
            Map<String, List<Tv.Programme>> progIndex = new HashMap<>();
            for (Tv.Programme p : tv.getProgramme()) {
                progIndex.computeIfAbsent(p.getChannel(), k -> new ArrayList<>()).add(p);
            }
            Map<String, List<Tv.Channel>> xmlChannelMap = tv.getChannel().stream()
                    .collect(Collectors.groupingBy(Tv.Channel::getId));
            Map<String, Channel> liveChannelMap = prepareLiveChannels(live);

            // 遍历Live全部频道
            for (Group group : live.getGroups()) {
                for (Channel ch : group.getChannel()) {
                    // 沿用原来的频道匹配逻辑：tvgId / tvgName / displayName
                    Channel matchedXmlCh = findTargetChannel(ch.getTvgId(), liveChannelMap, xmlChannelMap);
                    if (matchedXmlCh == null) continue;
                    List<Tv.Programme> progList = progIndex.get(matchedXmlCh.getTvgId());
                    if (progList == null || progList.isEmpty()) continue;

                    // 复用原有getEpg/getEpgData解析逻辑
                    List<Epg> epgList = new ArrayList<>();
                    Map<String, Epg> dateGroup = new LinkedHashMap<>();
                    for (Tv.Programme programme : progList) {
                        OffsetDateTime startDate = parseFull(programme.getStart(), zoneId);
                        OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
                        if(startDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC)) || endDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))){
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
                    ch.setDataList(cleanOldDays(epgList));
                }
            }
        } catch (Exception e) {
            SpiderDebug.log(TAG, "加载Epg总表出错："+e.toString());
        }
    }

    /** 空闲任务调用：检测全部epg源，下载更新，增量合并到merged_epg.xml */
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
                SpiderDebug.log(TAG, "后台更新总表出错 url=" + url+"："+e.toString());
            }
        }
        if (needMerge) {
            meta.lastMergeRun = System.currentTimeMillis();
            saveMergeMeta(meta);
            SpiderDebug.log(TAG, "Epg后台更新完毕。");
        }
    }

    /** 增量合并：单个更新源 -> 合并进merged_epg.xml，合并完成统一清理7天外节目 */
    private static void incrementalMergeSource(String url, File sourceCacheFile, ZoneId zoneId) throws Exception {
        String sourceContent = readCacheContent(sourceCacheFile, url);
        if (sourceContent.isEmpty()) return;
        Tv sourceTv = parseTv(sourceContent);
        if (sourceTv == null) return;

        File mergedFile = getMergedEpgFile();
        Tv mergedTv;
        if (mergedFile.exists()) {
            String mergedContent = readCacheContent(mergedFile, "Epg总表");
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
            SpiderDebug.log(TAG, "合并结果为空，不覆盖已有Epg总表");
            return;
        }
        File tempFile = new File(getMergedEpgFile().getParent(), MERGED_FILE_NAME + ".tmp");
        writeTvToFile(mergedTv, new ArrayList<>(channelMap.values()), finalProgs, tempFile);
        if(tempFile.exists() && tempFile.length() > 0){
            if(mergedFile.exists()) mergedFile.delete();
            tempFile.renameTo(mergedFile);
            SpiderDebug.log(TAG, "增量合并完成，已替换总表");
        }else{
            SpiderDebug.log(TAG, "增量合并临时文件无效，不替换总表");
            if(tempFile.exists()) tempFile.delete();
            return;
        }
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
        File metaFile = getMergeMetaFile();
        MergeMeta meta = new MergeMeta();
        if (metaFile.exists()) {
            try {
                String json = Path.read(metaFile);
                // 增加TypeToken，告诉Gson完整泛型类型
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<MergeMeta>(){}.getType();
                meta = GSON.fromJson(json, type);
            } catch (Exception e) {
                SpiderDebug.log(TAG, "meta文件解析损坏，使用空meta："+e.toString());
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
            if(tempFile.exists() && tempFile.length()>0){
                if(metaFile.exists()) metaFile.delete();
                tempFile.renameTo(metaFile);
            }else{
                if(tempFile.exists()) tempFile.delete();
            }
        } catch (Exception e) {
            SpiderDebug.log(TAG, "保存meta失败："+e.toString());
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

    private static String readCacheContent(File file, String url) throws Exception {
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
            SpiderDebug.log(TAG, "获取Epg数据失败 key=" + key+"："+e.toString());
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
