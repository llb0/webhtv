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
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
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
import java.util.zip.GZIPInputStream;

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

    /**
     * 台名归一化：移除横杠、空白，转大写，解决 CCTV‑1 / CCTV1 匹配失败
     */
    private static String normalizeChannelName(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.trim().replaceAll("[-\\s]+", "").toUpperCase();
    }

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
                    Map<String, List<Tv.Programme>> memoryIndex = syncEpgSourcesInternal(live, zoneId);
                    if (memoryIndex != null) {
                        synchronized (SYNC_LOCK) {
                            try {
                                writeIndexFile(memoryIndex);
                                saveMergeMeta(loadMergeMeta());
                                if (zoneId != null) loadMergedIndex(live, zoneId);
                            } catch (Exception e) {
                                SpiderDebug.log(TAG, "写入epg总表文件异常" + e.toString());
                            }
                        }
                    }
                }).start();
            }
        }
    }

    private static void buildInitialEpgAndSave(Live live, ZoneId zoneId) {
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return;
        for (String url : urls) {
            try {
                SpiderDebug.log(TAG, "首次尝试源：" + url);
                File cacheFile = getCacheFile(url);
                if (!download(url, cacheFile)) {
                    SpiderDebug.log(TAG, "首次下载源失败：" + url);
                    continue;
                }
                Map<String, List<Tv.Programme>> indexMap = new HashMap<>();
                readSingleSourceToIndex(cacheFile, url, zoneId, indexMap, false);

                synchronized (SYNC_LOCK) {
                    writeIndexFile(indexMap);
                    MergeMeta meta = loadMergeMeta();
                    String urlMd5 = Util.md5(url);
                    String remoteEtag = fetchRemoteTag(url);
                    MergeMeta.SourceItem sourceItem = new MergeMeta.SourceItem();
                    sourceItem.etag = remoteEtag;
                    meta.sources.put(urlMd5, sourceItem);
                    meta.lastMergeRun = System.currentTimeMillis();
                    saveMergeMeta(meta);
                }

                loadMergedIndex(live, zoneId);
                SpiderDebug.log(TAG, "首次加载Epg成功并持久化, source=" + url);
                break;
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
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                SpiderDebug.log(TAG, "迟延启动后台更新出错：" + e.toString());
                return;
            }
            Map<String, List<Tv.Programme>> memoryIndex = syncEpgSourcesInternal(live, zoneId);
            if (memoryIndex != null) {
                synchronized (SYNC_LOCK) {
                    try {
                        writeIndexFile(memoryIndex);
                        MergeMeta meta = loadMergeMeta();
                        meta.lastMergeRun = System.currentTimeMillis();
                        saveMergeMeta(meta);
                        if (zoneId != null) loadMergedIndex(live, zoneId);
                        SpiderDebug.log(TAG, "远程Epg数据已更新至本地。");
                    } catch (Exception e) {
                        SpiderDebug.log(TAG, "写入epg总表文件异常" + e.toString());
                    }
                }
            }
        }).start();
    }

    private static Map<String, List<Tv.Programme>> syncEpgSourcesInternal(Live live, ZoneId zoneId) {
        if (live == null || live.getGroups().isEmpty()) {
            SpiderDebug.log(TAG, "Epg远程更新时Live为空！");
            return null;
        }
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return null;
        MergeMeta meta = loadMergeMeta();
        boolean needMerge = false;
        Map<String, List<Tv.Programme>> mergedIndex = new HashMap<>();

        File indexFile = getIndexFile();
        if (indexFile.exists()) {
            try {
                String json = Path.read(indexFile);
                TypeToken<Map<String, List<Tv.Programme>>> typeToken = new TypeToken<Map<String, List<Tv.Programme>>>() {};
                mergedIndex = GSON.fromJson(json, typeToken.getType());
                if (mergedIndex == null) mergedIndex = new HashMap<>();
            } catch (Exception e) {
                SpiderDebug.log(TAG, "读取旧索引到内存失败，新建内存索引" + e.toString());
                mergedIndex = new HashMap<>();
            }
        }

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
                if (!download(url, cacheFile)) continue;

                readSingleSourceToIndex(cacheFile, url, zoneId, mergedIndex, true);
                if (sourceItem == null) sourceItem = new MergeMeta.SourceItem();
                sourceItem.etag = remoteEtag;
                meta.sources.put(urlMd5, sourceItem);
                needMerge = true;
                if(cacheFile.exists()) cacheFile.delete();
            } catch (Exception e) {
                SpiderDebug.log(TAG, "后台更新Epg出错 url=" + url + "：" + e.toString());
            }
        }

        // ==========修复：全部台统一执行7天过期过滤，不仅仅新源出现过的台==========
        if(needMerge){
            LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
            Map<String,List<Tv.Programme>> cleanedIndex = new HashMap<>();
            for(Map.Entry<String,List<Tv.Programme>> entry : mergedIndex.entrySet()){
                String bizName = entry.getKey();
                List<Tv.Programme> oldList = entry.getValue();
                Set<String> seen = new HashSet<>();
                List<Tv.Programme> outList = new ArrayList<>();
                collectByDate(oldList, zoneId, keepFrom, seen, outList, true, bizName);
                if(!outList.isEmpty()){
                    cleanedIndex.put(bizName, outList);
                }
            }
            mergedIndex.clear();
            mergedIndex.putAll(cleanedIndex);
        }

        if (!needMerge) {
            SpiderDebug.log(TAG, "远程Epg数据无变化，未更新");
            cleanStaleEpgCache(meta, urls);
            return null;
        }
        cleanStaleEpgCache(meta, urls);
        return mergedIndex;
    }

    private static void readSingleSourceToIndex(File cacheFile, String url, ZoneId zoneId, Map<String, List<Tv.Programme>> mergedIndex, boolean enableFilter) throws Exception {
        InputStream rawIn = null;
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        try {
            rawIn = new FileInputStream(cacheFile);
            byte[] header = new byte[2];
            if (rawIn.read(header) == 2 && (header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B) {
                rawIn.close();
                rawIn = new GZIPInputStream(new FileInputStream(cacheFile));
            } else {
                rawIn.reset();
            }
            parser.setInput(rawIn, "UTF-8");

            Tv tv = new Tv();
            setField(tv, "channel", new ArrayList<>());
            setField(tv, "programme", new ArrayList<>());

            Tv.Channel currentChannel = null;
            Tv.DisplayName currentDisplayName = null;
            Tv.Programme currentProg = null;
            Tv.Title currentTitle = null;
            StringBuilder textBuf = new StringBuilder();
            int eventType = parser.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        textBuf.setLength(0);
                        if ("tv".equals(tagName)) {
                            String dateAttr = parser.getAttributeValue(null, "date");
                            setField(tv, "date", dateAttr);
                        } else if ("channel".equals(tagName)) {
                            currentChannel = new Tv.Channel();
                            setField(currentChannel, "id", parser.getAttributeValue(null, "id"));
                            setField(currentChannel, "displayName", new ArrayList<>());
                        } else if ("display-name".equals(tagName)) {
                            currentDisplayName = new Tv.DisplayName();
                        } else if ("programme".equals(tagName)) {
                            currentProg = new Tv.Programme();
                            setField(currentProg, "start", parser.getAttributeValue(null, "start"));
                            setField(currentProg, "stop", parser.getAttributeValue(null, "stop"));
                            setField(currentProg, "channel", parser.getAttributeValue(null, "channel"));
                            setField(currentProg, "title", new ArrayList<>());
                        } else if ("title".equals(tagName)) {
                            currentTitle = new Tv.Title();
                        }
                        break;
                    case XmlPullParser.TEXT:
                        textBuf.append(parser.getText());
                        break;
                    case XmlPullParser.END_TAG:
                        String text = textBuf.toString().trim();
                        if ("display-name".equals(tagName) && currentChannel != null && currentDisplayName != null) {
                            setField(currentDisplayName, "text", text);
                            currentChannel.getDisplayName().add(currentDisplayName);
                            currentDisplayName = null;
                        } else if ("channel".equals(tagName) && tv != null && currentChannel != null) {
                            tv.getChannel().add(currentChannel);
                            currentChannel = null;
                        } else if ("title".equals(tagName) && currentProg != null && currentTitle != null) {
                            setField(currentTitle, "text", text);
                            currentProg.getTitle().add(currentTitle);
                            currentTitle = null;
                        } else if ("programme".equals(tagName) && tv != null && currentProg != null) {
                            tv.getProgramme().add(currentProg);
                            currentProg = null;
                        }
                        break;
                }
                eventType = parser.next();
            }

            Map<String,String> localXmlToBizName = new HashMap<>();
            for(Tv.Channel ch : tv.getChannel()){
                String xmlChId = ch.getId();
                String rawBizName = null;
                for(Tv.DisplayName dn : ch.getDisplayName()){
                    String txt = dn.getText().trim();
                    if(!txt.isEmpty()){
                        rawBizName = txt;
                        break;
                    }
                }
                if(rawBizName != null && !rawBizName.isEmpty()){
                    String normName = normalizeChannelName(rawBizName);
                    localXmlToBizName.put(xmlChId, normName);
                }
            }

            Map<String,List<Tv.Programme>> sourceByName = new HashMap<>();
            for(Tv.Programme p : tv.getProgramme()){
                String xmlId = p.getChannel();
                String bizName = localXmlToBizName.get(xmlId);
                if(bizName == null) continue;
                sourceByName.computeIfAbsent(bizName, k->new ArrayList<>()).add(p);
            }

            if (enableFilter) {
                LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
                for(Map.Entry<String,List<Tv.Programme>> entry : sourceByName.entrySet()){
                    String bizName = entry.getKey();
                    List<Tv.Programme> newProgs = entry.getValue();
                    List<Tv.Programme> oldProgs = mergedIndex.getOrDefault(bizName, new ArrayList<>());

                    Set<String> seen = new HashSet<>();
                    List<Tv.Programme> finalProgs = new ArrayList<>();
                    collectByDate(oldProgs, zoneId, keepFrom, seen, finalProgs, true, bizName);
                    collectByDate(newProgs, zoneId, keepFrom, seen, finalProgs, false, bizName);

                    if(finalProgs.isEmpty()){
                        mergedIndex.remove(bizName);
                    }else{
                        mergedIndex.put(bizName, finalProgs);
                    }
                }
            } else {
                mergedIndex.clear();
                for(Map.Entry<String,List<Tv.Programme>> entry : sourceByName.entrySet()){
                    mergedIndex.put(entry.getKey(), entry.getValue());
                }
            }
        } finally {
            if(rawIn != null) rawIn.close();
        }
    }

    private static void collectByDate(List<Tv.Programme> programmes, ZoneId zoneId, LocalDate keepFrom,
                                       Set<String> seen, List<Tv.Programme> out, boolean takeExisting, String bizName) {
        for (Tv.Programme p : programmes) {
            OffsetDateTime start = parseFull(p.getStart(), zoneId);
            if (start.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) continue;
            LocalDate progDate = start.atZoneSameInstant(zoneId).toLocalDate();
            if (progDate.isBefore(keepFrom)) continue;
            String key = bizName + "|" + progDate.format(Formatters.DATE);
            if (takeExisting) {
                out.add(p);
                seen.add(key);
            } else {
                if (!seen.contains(key)) out.add(p);
            }
        }
    }

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
            SpiderDebug.log(TAG, "保存meta出错：" + e.toString());
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
            SpiderDebug.log(TAG, url + "下载出错：" + e.toString());
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
        } catch (InterruptedIOException e) {
            SpiderDebug.log(TAG, url + "获取etag被中断：" + e.getMessage());
            Thread.interrupted();
            return null;
        } catch (Exception e) {
            SpiderDebug.log(TAG, url + "获取etag出错：" + e.toString());
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

    @Deprecated
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

    private static void loadMergedIndex(Live live, ZoneId zoneId) {
        Map<String, List<Tv.Programme>> progIndex = null;
        synchronized (SYNC_LOCK) {
            File indexFile = getIndexFile();
            if (!indexFile.exists()) return;
            try {
                String json = Path.read(indexFile);
                TypeToken<Map<String, List<Tv.Programme>>> typeToken = new TypeToken<Map<String, List<Tv.Programme>>>() {};
                progIndex = GSON.fromJson(json, typeToken.getType());
                if (progIndex == null) {
                    SpiderDebug.log(TAG, "Epg索引解析为空");
                    return;
                }
            } catch (Exception e) {
                SpiderDebug.log(TAG, "加载持久Epg索引出错：" + e.toString());
                return;
            }
        }
        int bindCount = 0;
        for (Group group : live.getGroups()) {
            for (Channel ch : group.getChannel()) {
                String lookupKey = ch.getTvgName();
                if (lookupKey == null || lookupKey.isEmpty()) {
                    lookupKey = ch.getName();
                }
                if (lookupKey == null || lookupKey.isEmpty()) continue;
                String rawLookup = lookupKey.trim();
                String normLookup = normalizeChannelName(rawLookup);

                List<Tv.Programme> progList = progIndex.get(normLookup);
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
                        epg = Epg.create(normLookup, dateStr);
                        dateGroup.put(dateStr, epg);
                    }
                    epg.getList().add(getEpgData(programme, zoneId));
                }
                epgList.addAll(dateGroup.values());
                ch.setDataList(epgList);
                bindCount++;
            }
        }
        SpiderDebug.log(TAG, "loadMergedIndex完成，绑定EPG频道数量：" + bindCount);
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
            SpiderDebug.log(TAG, "getEpgData出错：" + e.toString());
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
            SpiderDebug.log(TAG, "parseFull出错：" + e.toString());
            return OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
