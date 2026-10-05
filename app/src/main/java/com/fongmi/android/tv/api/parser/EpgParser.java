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
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import okhttp3.Request;
import okhttp3.Response;

public class EpgParser {

    private static final String TAG = "EpgParser";
    public static final long UPDATE_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    public static final int KEEP_DAYS = 7;
    public static final String INDEX_FILE_NAME = "merged_index.json";
    public static final String MERGED_META_NAME = "merged_meta.json";
    private static final Gson GSON = new GsonBuilder().create();
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
                                MergeMeta newMeta = loadMergeMeta();
                                newMeta.lastMergeRun = System.currentTimeMillis();
                                saveMergeMeta(newMeta);
                                loadMergedIndex(live, zoneId);
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

    /**
     * Gson流式写入，不再一次性生成完整json字符串，避免大索引OOM
     */
    private static void writeIndexFile(Map<String, List<Tv.Programme>> indexMap) throws Exception {
        File indexFile = getIndexFile();
        File tempFile = new File(indexFile.getParent(), INDEX_FILE_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             JsonWriter writer = new JsonWriter(osw)) {
            writer.beginObject();
            for (Map.Entry<String, List<Tv.Programme>> entry : indexMap.entrySet()) {
                writer.name(entry.getKey());
                writer.beginArray();
                List<Tv.Programme> progList = entry.getValue();
                for (Tv.Programme prog : progList) {
                    GSON.toJson(prog, Tv.Programme.class, writer);
                }
                writer.endArray();
            }
            writer.endObject();
            writer.flush();
        }
        if (tempFile.exists() && tempFile.length() > 0) {
            if (indexFile.exists()) indexFile.delete();
            tempFile.renameTo(indexFile);
        } else {
            if (tempFile.exists()) tempFile.delete();
            throw new Exception("写入索引临时文件为空");
        }
    }

    /**
     * 流式读取merged_index.json，返回 Map<String, List<Tv.Programme>>
     */
    private static Map<String, List<Tv.Programme>> readIndexStream(File indexFile) {
        Map<String, List<Tv.Programme>> result = new HashMap<>();
        if (!indexFile.exists()) return result;
        try (FileInputStream fis = new FileInputStream(indexFile);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String channelName = reader.nextName();
                List<Tv.Programme> progList = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) {
                    Tv.Programme prog = GSON.fromJson(reader, Tv.Programme.class);
                    progList.add(prog);
                }
                reader.endArray();
                result.put(channelName, progList);
            }
            reader.endObject();
        } catch (Exception e) {
            SpiderDebug.log(TAG, "流式读取索引文件失败 " + e.toString());
            result.clear();
        }
        return result;
    }

    private static void startBackgroundMerge(Live live, ZoneId zoneId) {
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                SpiderDebug.log(TAG, "延迟启动后台更新中断：" + e.toString());
                Thread.currentThread().interrupt();
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
                        loadMergedIndex(live, zoneId);
                        SpiderDebug.log(TAG, "远程Epg多源合并完成并持久化");
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
            mergedIndex = readIndexStream(indexFile);
        }

        SpiderDebug.log(TAG, "准备Epg远程更新...");
        for (String url : urls) {
            try {
                String urlMd5 = Util.md5(url);
                String remoteEtag = fetchRemoteTag(url);
                MergeMeta.SourceItem sourceItem = meta.sources.get(urlMd5);
                if (sourceItem != null && remoteEtag != null && remoteEtag.equals(sourceItem.etag)) {
                    SpiderDebug.log(TAG, "源未变化跳过：" + url);
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

        // 全部台统一执行7天过期过滤
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

    /**
     * SAX Handler 流式解析XMLTV，不加载完整文档进内存
     */
    private static class XmlTvSaxHandler extends DefaultHandler {
        private final Map<String, String> xmlIdToNormName = new HashMap<>();
        private final Map<String, List<Tv.Programme>> resultMap = new HashMap<>();

        private StringBuilder textBuf;
        private String currentChannelId;
        private Tv.Programme currentProg;
        private boolean inDisplayName;

        @Override
        public void startDocument() {
            textBuf = new StringBuilder();
            currentChannelId = null;
            currentProg = null;
            inDisplayName = false;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            textBuf.setLength(0);
            switch (qName) {
                case "channel":
                    currentChannelId = attributes.getValue("id");
                    break;
                case "display-name":
                    inDisplayName = true;
                    break;
                case "programme":
                    String chId = attributes.getValue("channel");
                    String start = attributes.getValue("start");
                    String stop = attributes.getValue("stop");
                    currentProg = new Tv.Programme();
                    currentProg.setChannel(chId);
                    currentProg.setStart(start);
                    currentProg.setStop(stop);
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            textBuf.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String text = textBuf.toString().trim();
            switch (qName) {
                case "display-name":
                    if (inDisplayName && currentChannelId != null && !text.isEmpty()) {
                        String norm = normalizeChannelName(text);
                        xmlIdToNormName.put(currentChannelId, norm);
                    }
                    inDisplayName = false;
                    break;
                case "programme":
                    if (currentProg != null) {
                        String chXmlId = currentProg.getChannel();
                        String bizName = xmlIdToNormName.get(chXmlId);
                        if (bizName != null) {
                            resultMap.computeIfAbsent(bizName, k -> new ArrayList<>()).add(currentProg);
                        }
                    }
                    currentProg = null;
                    break;
                case "title":
                    if (currentProg != null) {
                        currentProg.setTitle(text);
                    }
                    break;
            }
        }

        public Map<String, List<Tv.Programme>> getProgrammeMap() {
            return resultMap;
        }
    }

    /**
     * 读取缓存文件流，自动处理gz解压
     */
    private static InputStream openCacheStream(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[2];
            int read = fis.read(header);
            if (read >= 2 && (header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B) {
                File xml = new File(getEpgCacheDir(), file.getName() + ".xml");
                FileUtil.gzipDecompress(file, xml);
                return new FileInputStream(xml);
            }
        }
        return new FileInputStream(file);
    }

    private static void readSingleSourceToIndex(File cacheFile, String url, ZoneId zoneId, Map<String, List<Tv.Programme>> mergedIndex, boolean enableFilter) throws Exception {
        InputStream is = null;
        File tmpXml = null;
        try {
            is = openCacheStream(cacheFile);
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser parser = factory.newSAXParser();
            XmlTvSaxHandler handler = new XmlTvSaxHandler();
            parser.parse(new InputSource(is), handler);
            Map<String,List<Tv.Programme>> sourceByName = handler.getProgrammeMap();
            if (sourceByName == null || sourceByName.isEmpty()) return;

            if (enableFilter) {
                LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
                // 合并新源节目到现有索引（同台名做日期去重）
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
                //首次加载：直接覆盖写入
                mergedIndex.clear();
                mergedIndex.putAll(sourceByName);
            }
        } catch (SAXException e) {
            SpiderDebug.log(TAG, url + " SAX解析xml失败：" + e.getMessage());
        } finally {
            if (is != null) is.close();
            tmpXml = new File(getEpgCacheDir(), cacheFile.getName() + ".xml");
            if (tmpXml.exists()) tmpXml.delete();
        }
    }

    private static void collectByDate(List<Tv.Programme> programmes, ZoneId zoneId, LocalDate keepFrom,
                                       Set<String> seen, List<Tv.Programme> out, boolean takeExisting, String bizName) {
        for (Tv.Programme p : programmes) {
            OffsetDateTime start = parseFull(p.getStart(), zoneId);
            if (start.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) continue;
            LocalDate progDate = start.atZoneSameInstant(zoneId).toLocalDate();
            // 只丢弃7天之前，7天以及以内全部保留
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
        } catch (Exception e) {
            SpiderDebug.log(TAG, url + "获取etag出错：" + e.toString());
            return null;
        }
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
            progIndex = readIndexStream(indexFile);
            if (progIndex.isEmpty()) {
                SpiderDebug.log(TAG, "Epg索引解析为空");
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
            Tv tv = new org.simpleframework.xml.core.Persister().read(Tv.class, content, false);
            String rawDate = tv.getDate();
            String date = rawDate.isEmpty() ? LocalDate.now(zoneId).format(Formatters.DATE) : parseFull(rawDate, zoneId).atZoneSameInstant(zoneId).format(Formatters.DATE);
            Epg epg = Epg.create(key, date);
            for (Tv.Programme programme : tv.getProgramme()) {
                OffsetDateTime startDate = parseFull(programme.getStart(), zoneId);
                OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
                if (startDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC)) || endDate.isEqual(Instant.EPOCH.atOffset(ZoneOffset.UTC))) {
                    continue;
                }
                epg.getList().add(getEpgData(startDate, endDate, zoneId, programme));
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
}
