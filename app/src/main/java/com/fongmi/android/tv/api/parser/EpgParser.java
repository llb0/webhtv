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
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.simpleframework.xml.core.Persister;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
    public static final String SHARDS_DIR_NAME = "shards";
    private static final Gson GSON = new Gson();

    public static final Object SYNC_LOCK = new Object();

    /**
     * 台名归一化：移除横杠、空白，转大写，解决 CCTV‑1 / CCTV1 匹配失败
     */
    private static String normalizeChannelName(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.trim().replaceAll("[-\\s]+", "").toUpperCase();
    }

    private static File getEpgCacheDir() {
        return new File(Path.files(), "epg");
    }

    private static File getShardsDir() {
        File dir = new File(getEpgCacheDir(), SHARDS_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 按完整频道名（归一化后）定位分片文件：每个频道一个分片。
     * 文件名用频道名的 md5，保证文件系统安全且可按频道名直接定位。
     */
    private static File getShardFile(String channelName) {
        return new File(getShardsDir(), Util.md5(channelName) + ".json");
    }

    private static File getMergeMetaFile() {
        File dir = new File(Path.files(), "epg");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MERGED_META_NAME);
    }

    private static File getOldIndexFile() {
        return new File(getEpgCacheDir(), INDEX_FILE_NAME);
    }

    private static File getCacheFile(String url) {
        return new File(getEpgCacheDir(), Util.md5(url) + ".xml");
    }

    public static void start(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        MergeMeta meta = loadMergeMeta();
        // 兼容旧版单文件总索引：若存在旧 merged_index.json 且无分片目录，则迁移为分片
        File oldIndex = getOldIndexFile();
        if (oldIndex.exists() && !hasAnyShard()) {
            migrateOldIndexToShards();
        }
        if (!hasAnyShard()) {
            SpiderDebug.log(TAG, "没有Epg持久索引，进入首次快速加载并持久化");
            buildInitialEpgAndSave(live, zoneId);
            startBackgroundMerge(live, zoneId);
        } else {
            SpiderDebug.log(TAG, "Epg持久化索引存在，正常加载");
            loadMergedIndex(live, zoneId);
            long age = System.currentTimeMillis() - meta.lastMergeRun;
            if (meta.lastMergeRun == 0 || age > UPDATE_INTERVAL_MS) {
                startBackgroundMerge(live, zoneId);
            }
        }
    }

    private static boolean hasAnyShard() {
        File dir = getShardsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        return files != null && files.length > 0;
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
                Map<String, List<Tv.Programme>> indexMap = parseSourceByName(cacheFile, zoneId);

                synchronized (SYNC_LOCK) {
                    writeShardedIndex(indexMap);
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
     * 全量写出索引（首次加载场景）：逐频道写分片，不构造超大中间字符串。
     */
    private static void writeShardedIndex(Map<String, List<Tv.Programme>> indexMap) throws Exception {
        for (Map.Entry<String, List<Tv.Programme>> entry : indexMap.entrySet()) {
            writeChannelShard(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 流式写入单个频道分片文件：内容为该频道的节目单数组。
     * tmp 文件名带线程 ID 后缀，避免多线程并发写同一 tmp 文件导致内容交错损坏。
     */
    private static void writeChannelShard(String channelName, List<Tv.Programme> programmes) throws Exception {
        File shardFile = getShardFile(channelName);
        File tempFile = new File(shardFile.getParent(), Util.md5(channelName) + ".json.tmp." + Thread.currentThread().getId());
        File parent = tempFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8);
             JsonWriter writer = new JsonWriter(osw)) {
            writer.setIndent("");
            writer.beginArray();
            for (Tv.Programme p : programmes) {
                GSON.toJson(p, Tv.Programme.class, writer);
            }
            writer.endArray();
            writer.flush();
        }
        if (tempFile.exists() && tempFile.length() > 0) {
            if (shardFile.exists()) shardFile.delete();
            tempFile.renameTo(shardFile);
        } else {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    /**
     * 删除单个频道分片（清理空频道时用）。
     */
    private static void deleteChannelShard(String channelName) {
        File shardFile = getShardFile(channelName);
        if (shardFile.exists()) shardFile.delete();
    }

    /**
     * 流式读取单个频道分片，返回该频道的节目单列表；不存在返回空列表。
     */
    private static List<Tv.Programme> readChannelShard(String channelName) throws Exception {
        File shardFile = getShardFile(channelName);
        List<Tv.Programme> list = new ArrayList<>();
        if (!shardFile.exists() || shardFile.length() == 0) return list;
        try (FileInputStream fis = new FileInputStream(shardFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             InputStreamReader isr = new InputStreamReader(bis, StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            reader.beginArray();
            while (reader.hasNext()) {
                list.add(GSON.fromJson(reader, Tv.Programme.class));
            }
            reader.endArray();
        }
        return list;
    }

    /**
     * 将旧版单文件总索引迁移为分片索引：流式边读边写，内存峰值 = 单个频道。
     * 写分片时用 SYNC_LOCK 保护，防止与合并/清理操作并发写同一分片。
     */
    private static void migrateOldIndexToShards() {
        File oldIndex = getOldIndexFile();
        if (!oldIndex.exists()) return;
        int count = 0;
        try (FileInputStream fis = new FileInputStream(oldIndex);
             BufferedInputStream bis = new BufferedInputStream(fis);
             InputStreamReader isr = new InputStreamReader(bis, StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            SpiderDebug.log(TAG, "检测到旧版总索引，开始迁移为分片...");
            reader.beginObject();
            while (reader.hasNext()) {
                String channelName = reader.nextName();
                List<Tv.Programme> progs = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) {
                    progs.add(GSON.fromJson(reader, Tv.Programme.class));
                }
                reader.endArray();
                if (!progs.isEmpty()) {
                    synchronized (SYNC_LOCK) {
                        writeChannelShard(channelName, progs);
                    }
                    count++;
                }
            }
            reader.endObject();
            if (oldIndex.delete()) {
                SpiderDebug.log(TAG, "旧索引迁移完成，共 " + count + " 个频道，已删除旧文件");
            }
        } catch (Exception e) {
            SpiderDebug.log(TAG, "旧索引迁移失败：" + e.toString());
        }
    }

    private static void startBackgroundMerge(Live live, ZoneId zoneId) {
        new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                SpiderDebug.log(TAG, "启动后台更新延迟出错：" + e.toString());
                return;
            }
            boolean updated = syncEpgSourcesInternal(live, zoneId);
            if (updated) {
                synchronized (SYNC_LOCK) {
                    try {
                        MergeMeta meta = loadMergeMeta();
                        meta.lastMergeRun = System.currentTimeMillis();
                        saveMergeMeta(meta);
                        SpiderDebug.log(TAG, "远程Epg数据已更新至本地。");
                    } catch (Exception e) {
                        SpiderDebug.log(TAG, "更新epg分片异常" + e.toString());
                    }
                }
            }
        }).start();
    }

    private static boolean syncEpgSourcesInternal(Live live, ZoneId zoneId) {
        if (live == null || live.getGroups().isEmpty()) {
            SpiderDebug.log(TAG, "Epg远程更新时Live为空！");
            return false;
        }
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return false;
        MergeMeta meta = loadMergeMeta();
        boolean needMerge = false;

        // 兼容：仍存在旧单文件索引时，逐频道流式迁移为分片后删除旧文件
        File oldIndex = getOldIndexFile();
        if (oldIndex.exists()) {
            try {
                migrateOldIndexToShards();
            } catch (Exception e) {
                SpiderDebug.log(TAG, "迁移旧索引失败：" + e.toString());
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

                // 解析单个源，得到 频道名→节目单（仅该源的数据，内存峰值=单个源）
                Map<String, List<Tv.Programme>> sourceByName = parseSourceByName(cacheFile, zoneId);
                // 逐频道合并到分片：只读该频道旧分片，合并后写回
                mergeSourceToShards(sourceByName, zoneId);

                if (sourceItem == null) sourceItem = new MergeMeta.SourceItem();
                sourceItem.etag = remoteEtag;
                meta.sources.put(urlMd5, sourceItem);
                needMerge = true;
                if(cacheFile.exists()) cacheFile.delete();
            } catch (Exception e) {
                SpiderDebug.log(TAG, "后台更新Epg出错 url=" + url + "：" + e.toString());
            }
        }

        if (!needMerge) {
            SpiderDebug.log(TAG, "远程Epg数据无变化，未更新");
            cleanStaleEpgCache(meta, urls);
            return false;
        }
        // 全部分片统一执行7天过期过滤（逐文件，内存只占单个频道）
        cleanAllShards(zoneId);
        cleanStaleEpgCache(meta, urls);
        return true;
    }

    /**
     * 解析单个 EPG 源文件，得到 归一化频道名→该频道节目单列表。
     * 仅持有该源的数据，内存峰值 = 单个源。
     */
    private static Map<String, List<Tv.Programme>> parseSourceByName(File cacheFile, ZoneId zoneId) throws Exception {
        InputStream rawIn = null;
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        try {
            rawIn = new BufferedInputStream(new FileInputStream(cacheFile));
            byte[] header = new byte[2];
            rawIn.mark(2);
            int readCnt = rawIn.read(header);
            if (readCnt == 2 && (header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B) {
                rawIn.close();
                rawIn = new GZIPInputStream(new BufferedInputStream(new FileInputStream(cacheFile)));
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
                            Field dnField = currentChannel.getClass().getDeclaredField("displayName");
                            dnField.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            List<Tv.DisplayName> dnList = (List<Tv.DisplayName>) dnField.get(currentChannel);
                            dnList.add(currentDisplayName);
                            currentDisplayName = null;
                        } else if ("channel".equals(tagName) && tv != null && currentChannel != null) {
                            tv.getChannel().add(currentChannel);
                            currentChannel = null;
                        } else if ("title".equals(tagName) && currentProg != null && currentTitle != null) {
                            setField(currentTitle, "text", text);
                            Field titleField = currentProg.getClass().getDeclaredField("title");
                            titleField.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            List<Tv.Title> titleList = (List<Tv.Title>) titleField.get(currentProg);
                            titleList.add(currentTitle);
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
                Field dnField = ch.getClass().getDeclaredField("displayName");
                dnField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Tv.DisplayName> dnList = (List<Tv.DisplayName>) dnField.get(ch);
                for(Tv.DisplayName dn : dnList){
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
            return sourceByName;
        } finally {
            if(rawIn != null) rawIn.close();
        }
    }

    /**
     * 逐频道将单个源的数据合并写入分片：只读该频道旧分片，7天过滤后写回。
     * 内存峰值 = 单个频道的节目单列表。
     * 每个频道的 read-modify-write 用 SYNC_LOCK 保护，防止并发合并导致数据丢失。
     */
    private static void mergeSourceToShards(Map<String, List<Tv.Programme>> sourceByName, ZoneId zoneId) throws Exception {
        LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
        for (Map.Entry<String, List<Tv.Programme>> entry : sourceByName.entrySet()) {
            String bizName = entry.getKey();
            List<Tv.Programme> newProgs = entry.getValue();
            synchronized (SYNC_LOCK) {
                List<Tv.Programme> oldProgs = readChannelShard(bizName);
 
                Set<String> seen = new HashSet<>();
                List<Tv.Programme> finalProgs = new ArrayList<>();
                collectByDate(oldProgs, zoneId, keepFrom, seen, finalProgs, true, bizName);
                collectByDate(newProgs, zoneId, keepFrom, seen, finalProgs, false, bizName);

                if (finalProgs.isEmpty()) {
                    deleteChannelShard(bizName);
                } else {
                    writeChannelShard(bizName, finalProgs);
                }
            }
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

    /**
     * 遍历全部分片，逐个执行7天过期过滤并写回；空分片删除。
     * 内存峰值 = 单个频道的节目单列表，不加载全量索引。
     * 每个分片的 read-modify-write 用 SYNC_LOCK 保护，防止与合并操作并发导致数据覆盖。
     */
    private static void cleanAllShards(ZoneId zoneId) {
        LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
        File[] files = getShardsDir().listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try {
                synchronized (SYNC_LOCK) {
                    List<Tv.Programme> progs = readChannelShardFile(f);
                    Set<String> seen = new HashSet<>();
                    List<Tv.Programme> outList = new ArrayList<>();
                    collectByDate(progs, zoneId, keepFrom, seen, outList, true, f.getName());
                    if (outList.isEmpty()) {
                        f.delete();
                    } else if (outList.size() != progs.size()) {
                        writeChannelShardFile(f, outList);
                    }
                }
            } catch (Exception e) {
                SpiderDebug.log(TAG, "清理分片失败 " + f.getName() + "：" + e.toString());
            }
        }
    }

    /**
     * 从指定文件流式读取单个频道分片（供 cleanAllShards 遍历文件时使用）。
     */
    private static List<Tv.Programme> readChannelShardFile(File shardFile) throws Exception {
        List<Tv.Programme> list = new ArrayList<>();
        if (!shardFile.exists() || shardFile.length() == 0) return list;
        try (FileInputStream fis = new FileInputStream(shardFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             InputStreamReader isr = new InputStreamReader(bis, StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            reader.beginArray();
            while (reader.hasNext()) {
                list.add(GSON.fromJson(reader, Tv.Programme.class));
            }
            reader.endArray();
        }
        return list;
    }

    /**
     * 流式写入指定分片文件（供 cleanAllShards 原地写回时使用）。
     * tmp 文件名带线程 ID 后缀，避免多线程并发写同一 tmp 文件导致内容交错损坏。
     */
    private static void writeChannelShardFile(File shardFile, List<Tv.Programme> programmes) throws Exception {
        File tempFile = new File(shardFile.getParent(), shardFile.getName() + ".tmp." + Thread.currentThread().getId());
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8);
             JsonWriter writer = new JsonWriter(osw)) {
            writer.setIndent("");
            writer.beginArray();
            for (Tv.Programme p : programmes) {
                GSON.toJson(p, Tv.Programme.class, writer);
            }
            writer.endArray();
            writer.flush();
        }
        if (tempFile.exists() && tempFile.length() > 0) {
            if (shardFile.exists()) shardFile.delete();
            tempFile.renameTo(shardFile);
        } else {
            if (tempFile.exists()) tempFile.delete();
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
            // 跳过分片目录、meta 文件、旧总索引文件及临时文件
            if (name.equals(SHARDS_DIR_NAME) || name.equals(MERGED_META_NAME) || name.equals(INDEX_FILE_NAME) || name.endsWith(".tmp")) {
                continue;
            }
            if (f.isDirectory()) continue;
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
            synchronized (SYNC_LOCK) {
                try (FileInputStream fis = new FileInputStream(metaFile);
                     BufferedInputStream bis = new BufferedInputStream(fis);
                     InputStreamReader isr = new InputStreamReader(bis, StandardCharsets.UTF_8);
                     JsonReader reader = new JsonReader(isr)) {
                    meta = GSON.fromJson(reader, MergeMeta.class);
                } catch (Exception e) {
                    SpiderDebug.log(TAG, "meta文件解析损坏，使用空meta：" + e.toString());
                }
            }
        }
        return meta;
    }

    private static void saveMergeMeta(MergeMeta meta) {
        synchronized (SYNC_LOCK) {
            try {
                File metaFile = getMergeMetaFile();
                File tempFile = new File(metaFile.getParent(), MERGED_META_NAME + ".tmp");
                File parent = tempFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(tempFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos);
                     OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8);
                     JsonWriter writer = new JsonWriter(osw)) {
                    GSON.toJson(meta, MergeMeta.class, writer);
                    writer.flush();
                }
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
        if (!hasAnyShard()) {
            // 兼容：若只有旧版单文件索引，先迁移
            if (getOldIndexFile().exists()) {
                migrateOldIndexToShards();
            } else {
                return;
            }
        }
        // 按频道名直接读取对应分片，按需组装；缓存避免同频道重复读文件
        Map<String, List<Tv.Programme>> shardCache = new HashMap<>();
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
                if (normLookup.isEmpty()) continue;

                List<Tv.Programme> progList = shardCache.get(normLookup);
                if (progList == null) {
                    progList = new ArrayList<>();
                    synchronized (SYNC_LOCK) {
                        try {
                            progList = readChannelShard(normLookup);
                        } catch (Exception e) {
                            SpiderDebug.log(TAG, "加载分片失败 " + normLookup + "：" + e.toString());
                        }
                    }
                    shardCache.put(normLookup, progList);
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
