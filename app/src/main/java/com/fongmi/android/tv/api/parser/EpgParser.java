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
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
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
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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

    // [NEW] 手写 TypeAdapter：去掉 Gson 对 Tv.Programme 的反射反序列化。
    private static final Field F_START;
    private static final Field F_STOP;
    private static final Field F_CHANNEL;
    private static final Field F_TITLE;
    private static final Field F_TITLE_TEXT;

    static {
        Field start = null, stop = null, channel = null, title = null, titleText = null;
        try {
            start = Tv.Programme.class.getDeclaredField("start");
            stop = Tv.Programme.class.getDeclaredField("stop");
            channel = Tv.Programme.class.getDeclaredField("channel");
            title = Tv.Programme.class.getDeclaredField("title");
            titleText = Tv.Title.class.getDeclaredField("text");
            start.setAccessible(true);
            stop.setAccessible(true);
            channel.setAccessible(true);
            title.setAccessible(true);
            titleText.setAccessible(true);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "Programme字段初始化失败，将回退反射：" + e);
        }
        F_START = start;
        F_STOP = stop;
        F_CHANNEL = channel;
        F_TITLE = title;
        F_TITLE_TEXT = titleText;
    }

    private static void put(Field f, Object target, Object value) {
        if (f == null) return;
        try {
            f.set(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static Tv.Title newTitle(String text) {
        Tv.Title t = new Tv.Title();
        put(F_TITLE_TEXT, t, text);
        return t;
    }

    /**
     * 手写读写，字段与原来 Gson 反射生成的 JSON 完全一致：
     * {"start":"...","stop":"...","channel":"...","title":[{"text":"..."}]}
     * 所以新旧分片文件互相兼容，不需要迁移。
     */
    private static final TypeAdapter<Tv.Programme> PROGRAMME_ADAPTER = new TypeAdapter<Tv.Programme>() {

        @Override
        public void write(JsonWriter out, Tv.Programme p) throws java.io.IOException {
            if (p == null) {
                out.nullValue();
                return;
            }
            out.beginObject();
            out.name("start").value(p.getStart());
            out.name("stop").value(p.getStop());
            out.name("channel").value(p.getChannel());
            out.name("title");
            out.beginArray();
            // 只保留第一个标题：既缩小文件，也让 getTitle() 的 stream() 只在 1 个元素上跑
            out.beginObject();
            out.name("text").value(p.getTitle());
            out.endObject();
            out.endArray();
            out.endObject();
        }

        @Override
        public Tv.Programme read(JsonReader in) throws java.io.IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            Tv.Programme p = new Tv.Programme();
            String start = null, stop = null, channel = null, title = null;
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                if ("start".equals(name)) {
                    start = in.nextString();
                } else if ("stop".equals(name)) {
                    stop = in.nextString();
                } else if ("channel".equals(name)) {
                    channel = in.nextString();
                } else if ("title".equals(name)) {
                    // 只取第一个非空标题，其余跳过 —— 省掉 List + 多个 Title 对象的创建
                    in.beginArray();
                    while (in.hasNext()) {
                        in.beginObject();
                        while (in.hasNext()) {
                            if ("text".equals(in.nextName())) {
                                String v = in.nextString();
                                if (title == null && v != null && !v.isEmpty()) title = v;
                            } else {
                                in.skipValue();
                            }
                        }
                        in.endObject();
                    }
                    in.endArray();
                } else {
                    in.skipValue();
                }
            }
            in.endObject();
            put(F_START, p, start == null ? "" : start);
            put(F_STOP, p, stop == null ? "" : stop);
            put(F_CHANNEL, p, channel == null ? "" : channel);
            if (title != null) {
                List<Tv.Title> list = new ArrayList<>(1);
                list.add(newTitle(title));
                put(F_TITLE, p, list);
            }
            return p;
        }
    };

    // [CHANGED] 后台合并启动延迟：1.5s → 8s，避免冷启动时与首屏 loadMergedIndex 抢 IO / 抢锁
    private static final long BACKGROUND_MERGE_DELAY_MS = 25000L;

    // [NEW] 首屏绑定的自有截止时间。超过就把「已绑好的部分」先交给 UI，
    // 剩下的继续在后台跑（不取消），把 MEM 预热好，下次进入就是毫秒级。
    private static final long BIND_DEADLINE_MS = 40000L;

    // [NEW] 首屏优先绑定的频道数：绑完这些就返回让 UI 刷新，其余后台补。
    // 电视一屏最多可见 ~20 个频道，取 40 留足余量。
    private static final int FAST_BIND_COUNT = 40;

    // [CHANGED] 分片读写锁：读并发、写独占，替代原来的全局 synchronized (SYNC_LOCK)
    private static final ReentrantReadWriteLock SHARD_RW = new ReentrantReadWriteLock();
    private static final Lock SHARD_READ = SHARD_RW.readLock();
    private static final Lock SHARD_WRITE = SHARD_RW.writeLock();

    // [CHANGED] 分片绑定并行化：频道多时并发读文件，缩短首屏耗时
    private static final boolean PARALLEL_BIND = true;
    private static final int MIN_PARALLEL_CHANNELS = 24;

    // [NEW] 分片目录缓存：避免每个频道都 new File + exists() 走一次磁盘 stat
    private static volatile File SHARDS_DIR;

    // [NEW] parseFull 失败日志计数器（只打前几条，避免日志 IO 拖慢首屏）
    private static final java.util.concurrent.atomic.AtomicInteger PARSE_ERR_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    // [NEW] 分片内存缓存：频道名 → 节目单。命中后零 IO、零 Gson 解析。
    // 首次冷启动后，再次进入直播（含退出直播再进）直接命中，耗时降到毫秒级。
    private static final java.util.concurrent.ConcurrentHashMap<String, List<Tv.Programme>> MEM =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int BIND_THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final ExecutorService BIND_EXECUTOR = Executors.newFixedThreadPool(BIND_THREADS, r -> {
        Thread t = new Thread(r, "epg-bind");
        t.setDaemon(true);
        return t;
    });

    /**
     * 保留：仅用于 meta 文件的读写互斥（分片已改用 SHARD_RW）
     */
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
        File dir = SHARDS_DIR;
        if (dir != null) return dir;
        dir = new File(getEpgCacheDir(), SHARDS_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        SHARDS_DIR = dir;
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

    /**
     * [CHANGED] 返回「成功绑定 EPG 的频道数」，且全程不再向外抛异常。
     * 调用方应据此判断是否刷新 UI，而不是靠「有没有抛异常」。
     */
    public static int start(Live live) {
        if (live == null || live.getGroups().isEmpty()) return 0;
        int bindCount = 0;
        try {
            ZoneId zoneId = zoneIdOf(live.getTimeZone());
            MergeMeta meta = loadMergeMeta();
            // 兼容旧版单文件总索引：若存在旧 merged_index.json 且无分片目录，则迁移为分片
            File oldIndex = getOldIndexFile();
            if (oldIndex.exists() && !hasAnyShard()) {
                migrateOldIndexToShards();
            }
            if (!hasAnyShard()) {
                SpiderDebug.log(TAG, "没有Epg持久索引，进入首次快速加载并持久化");
                bindCount = buildInitialEpgAndSave(live, zoneId);
                startBackgroundMerge(live, zoneId);
            } else {
                SpiderDebug.log(TAG, "Epg持久化索引存在，正常加载");
                long t0 = System.currentTimeMillis();
                bindCount = loadMergedIndex(live, zoneId);
                SpiderDebug.log(TAG, "loadMergedIndex耗时=" + (System.currentTimeMillis() - t0) + "ms, bind=" + bindCount);
                long age = System.currentTimeMillis() - meta.lastMergeRun;
                if (meta.lastMergeRun == 0 || age > UPDATE_INTERVAL_MS) {
                    startBackgroundMerge(live, zoneId);
                }
            }
        } catch (Throwable e) {
            // [CHANGED] 用 SpiderDebug 打全栈（原来的 e.printStackTrace() 按 "EpgParser" 过滤看不到）
            SpiderDebug.log(TAG, "start()异常：" + e);
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            SpiderDebug.log(TAG, "start()堆栈：" + sw);
        }
        return bindCount;
    }

    private static boolean hasAnyShard() {
        File dir = getShardsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        return files != null && files.length > 0;
    }

    /**
     * [CHANGED] 返回本次绑定到的频道数（原来为 void）
     */
    private static int buildInitialEpgAndSave(Live live, ZoneId zoneId) {
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return 0;
        for (String url : urls) {
            try {
                SpiderDebug.log(TAG, "首次尝试源：" + url);
                File cacheFile = getCacheFile(url);
                if (!download(url, cacheFile)) {
                    SpiderDebug.log(TAG, "首次下载源失败：" + url);
                    continue;
                }
                Map<String, List<Tv.Programme>> indexMap = parseSourceByName(cacheFile, zoneId);

                // [CHANGED] 写分片用写锁；meta 仍用 SYNC_LOCK；两者不再嵌套，避免锁序问题
                writeShardedIndex(indexMap);

                synchronized (SYNC_LOCK) {
                    MergeMeta meta = loadMergeMeta();
                    String urlMd5 = Util.md5(url);
                    String remoteEtag = fetchRemoteTag(url);
                    MergeMeta.SourceItem sourceItem = new MergeMeta.SourceItem();
                    sourceItem.etag = remoteEtag;
                    meta.sources.put(urlMd5, sourceItem);
                    meta.lastMergeRun = System.currentTimeMillis();
                    saveMergeMeta(meta);
                }

                int n = loadMergedIndex(live, zoneId);
                SpiderDebug.log(TAG, "首次加载Epg成功并持久化, source=" + url + ", bind=" + n);
                return n;
            } catch (Exception e) {
                SpiderDebug.log(TAG, "首次加载源失败 url=" + url + "：" + e.toString());
            }
        }
        return 0;
    }

    /**
     * 全量写出索引（首次加载场景）：逐频道写分片，不构造超大中间字符串。
     * [CHANGED] 每个频道的写锁在 writeChannelShard 内部逐把获取/释放，允许读操作穿插。
     */
    private static void writeShardedIndex(Map<String, List<Tv.Programme>> indexMap) throws Exception {
        for (Map.Entry<String, List<Tv.Programme>> entry : indexMap.entrySet()) {
            writeChannelShard(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 流式写入单个频道分片文件：内容为该频道的节目单数组。
     * tmp 文件名带线程 ID 后缀，避免多线程并发写同一 tmp 文件导致内容交错损坏。
     * [CHANGED] 内部加写锁（可重入，外层 mergeSourceToShards 再加也不会死锁）。
     */
    private static void writeChannelShard(String channelName, List<Tv.Programme> programmes) throws Exception {
        File shardFile = getShardFile(channelName);
        File tempFile = new File(shardFile.getParent(), Util.md5(channelName) + ".json.tmp." + Thread.currentThread().getId());
        File parent = tempFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        SHARD_WRITE.lock();
        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8);
                 JsonWriter writer = new JsonWriter(osw)) {
                writer.setIndent("");
                writer.beginArray();
                for (Tv.Programme p : programmes) {
                    PROGRAMME_ADAPTER.write(writer, p);
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
            // [NEW] 写成功则同步内存缓存，保证后续读到最新数据
            MEM.put(channelName, programmes);
        } finally {
            SHARD_WRITE.unlock();
        }
    }

    /**
     * 删除单个频道分片（清理空频道时用）。
     */
    private static void deleteChannelShard(String channelName) {
        File shardFile = getShardFile(channelName);
        SHARD_WRITE.lock();
        try {
            MEM.remove(channelName);
            if (shardFile.exists()) shardFile.delete();
        } finally {
            SHARD_WRITE.unlock();
        }
    }

    /**
     * 流式读取单个频道分片，返回该频道的节目单列表；不存在返回空列表。
     * [CHANGED] 读锁：多个频道可并发读，不再与 UI 线程/合并线程互斥整块。
     */
    private static List<Tv.Programme> readChannelShard(String channelName) throws Exception {
        // [NEW] 内存缓存命中：零 IO、零 Gson，电视上这是最大的一次提速
        List<Tv.Programme> cached = MEM.get(channelName);
        if (cached != null) return cached;

        File shardFile = getShardFile(channelName);
        List<Tv.Programme> list = new ArrayList<>();
        if (!shardFile.exists() || shardFile.length() == 0) return list;
        SHARD_READ.lock();
        try (FileInputStream fis = new FileInputStream(shardFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             InputStreamReader isr = new InputStreamReader(bis, StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            reader.beginArray();
            while (reader.hasNext()) {
                list.add(PROGRAMME_ADAPTER.read(reader));
            }
            reader.endArray();
        } finally {
            SHARD_READ.unlock();
        }
        // [NEW] 回填缓存
        MEM.put(channelName, list);
        return list;
    }

    /**
     * 将旧版单文件总索引迁移为分片索引：流式边读边写，内存峰值 = 单个频道。
     * [CHANGED] 不再外层加 SYNC_LOCK，写锁由 writeChannelShard 内部持有。
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
                    progs.add(PROGRAMME_ADAPTER.read(reader));
                }
                reader.endArray();
                if (!progs.isEmpty()) {
                    writeChannelShard(channelName, progs);
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
                // [CHANGED] 1500ms → 8s：先让首屏 loadMergedIndex 跑完，再动磁盘
                Thread.sleep(BACKGROUND_MERGE_DELAY_MS);
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
                if (cacheFile.exists()) cacheFile.delete();
            } catch (Exception e) {
                SpiderDebug.log(TAG, "后台更新Epg出错 url=" + url + "：" + e.toString());
            }
        }

        if (!needMerge) {
            SpiderDebug.log(TAG, "远程Epg数据无变化，未更新");
            cleanStaleEpgCache(meta, urls);
            return false;
        }
        // [CHANGED] 原来这里会 cleanAllShards() 全量重扫所有分片（最大的 IO 放大源）。
        // mergeSourceToShards 写回时已按 KEEP_DAYS 过滤，这里不再重复全扫。
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

            Map<String, String> localXmlToBizName = new HashMap<>();
            for (Tv.Channel ch : tv.getChannel()) {
                String xmlChId = ch.getId();
                String rawBizName = null;
                Field dnField = ch.getClass().getDeclaredField("displayName");
                dnField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Tv.DisplayName> dnList = (List<Tv.DisplayName>) dnField.get(ch);
                for (Tv.DisplayName dn : dnList) {
                    String txt = dn.getText().trim();
                    if (!txt.isEmpty()) {
                        rawBizName = txt;
                        break;
                    }
                }
                if (rawBizName != null && !rawBizName.isEmpty()) {
                    String normName = normalizeChannelName(rawBizName);
                    localXmlToBizName.put(xmlChId, normName);
                }
            }

            Map<String, List<Tv.Programme>> sourceByName = new HashMap<>();
            for (Tv.Programme p : tv.getProgramme()) {
                String xmlId = p.getChannel();
                String bizName = localXmlToBizName.get(xmlId);
                if (bizName == null) continue;
                sourceByName.computeIfAbsent(bizName, k -> new ArrayList<>()).add(p);
            }
            return sourceByName;
        } finally {
            if (rawIn != null) rawIn.close();
        }
    }

    /**
     * 逐频道将单个源的数据合并写入分片：只读该频道旧分片，7天过滤后写回。
     * 内存峰值 = 单个频道的节目单列表。
     * [CHANGED] 锁粒度收窄到 readChannelShard / writeChannelShard 内部，不再整块独占。
     */
    private static void mergeSourceToShards(Map<String, List<Tv.Programme>> sourceByName, ZoneId zoneId) throws Exception {
        LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
        for (Map.Entry<String, List<Tv.Programme>> entry : sourceByName.entrySet()) {
            String bizName = entry.getKey();
            List<Tv.Programme> newProgs = entry.getValue();

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
     * [CHANGED] 不再由 syncEpgSourcesInternal 自动调用（写回时已过滤），仅保留供手动/迁移后调用，
     * 避免每次合并都把几百个分片全量重写一遍。
     */
    private static void cleanAllShards(ZoneId zoneId) {
        LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
        File[] files = getShardsDir().listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        // [NEW] 按文件直接改分片，无法映射回频道名，内存缓存整体失效
        MEM.clear();
        for (File f : files) {
            try {
                List<Tv.Programme> progs = readChannelShardFile(f);
                Set<String> seen = new HashSet<>();
                List<Tv.Programme> outList = new ArrayList<>();
                collectByDate(progs, zoneId, keepFrom, seen, outList, true, f.getName());
                if (outList.isEmpty()) {
                    f.delete();
                } else if (outList.size() != progs.size()) {
                    writeChannelShardFile(f, outList);
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
        SHARD_READ.lock();
        try (FileInputStream fis = new FileInputStream(shardFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             InputStreamReader isr = new InputStreamReader(bis, StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            reader.beginArray();
            while (reader.hasNext()) {
                list.add(PROGRAMME_ADAPTER.read(reader));
            }
            reader.endArray();
        } finally {
            SHARD_READ.unlock();
        }
        return list;
    }

    /**
     * 流式写入指定分片文件（供 cleanAllShards 原地写回时使用）。
     */
    private static void writeChannelShardFile(File shardFile, List<Tv.Programme> programmes) throws Exception {
        File tempFile = new File(shardFile.getParent(), shardFile.getName() + ".tmp." + Thread.currentThread().getId());
        SHARD_WRITE.lock();
        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8);
                 JsonWriter writer = new JsonWriter(osw)) {
                writer.setIndent("");
                writer.beginArray();
                for (Tv.Programme p : programmes) {
                    PROGRAMME_ADAPTER.write(writer, p);
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
        } finally {
            SHARD_WRITE.unlock();
        }
    }

    private static void cleanStaleEpgCache(MergeMeta meta, List<String> validUrls) {
        File cacheDir = getEpgCacheDir();
        File[] allFiles = cacheDir.listFiles();
        if (allFiles == null) return;
        Set<String> validUrlMd5Set = new HashSet<>();
        for (String url : validUrls) {
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
            for (String md5 : validUrlMd5Set) {
                if (name.startsWith(md5)) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
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

    /**
     * [CHANGED] 返回成功绑定 EPG 的频道数；内部先做频道快照再并发读分片，缩短首屏耗时。
     */
    private static List<Channel> snapshotChannels(Live live) {
        List<Channel> all = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            all.clear();
            try {
                for (Group group : new ArrayList<>(live.getGroups())) {
                    if (group == null) continue;
                    List<Channel> chs = group.getChannel();
                    if (chs == null || chs.isEmpty()) continue;
                    all.addAll(new ArrayList<>(chs));
                }
                return all;
            } catch (ConcurrentModificationException e) {
                SpiderDebug.log(TAG, "快照groups时并发修改，重试 " + (attempt + 1));
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "快照groups异常：" + e);
                break;
            }
        }
        return all;
    }

    private static int loadMergedIndex(Live live, ZoneId zoneId) {
        if (!hasAnyShard()) {
            // 兼容：若只有旧版单文件索引，先迁移
            if (getOldIndexFile().exists()) {
                migrateOldIndexToShards();
            } else {
                return 0;
            }
        }
        // [CHANGED] 用快照替代直接遍历 live.getGroups()，彻底规避 CME
        List<Channel> channels = snapshotChannels(live);
        if (channels.isEmpty()) {
            SpiderDebug.log(TAG, "快照频道为空，无法绑定");
            return 0;
        }

        if (!PARALLEL_BIND || channels.size() < MIN_PARALLEL_CHANNELS) {
            return bindChunk(channels, 0, channels.size(), zoneId);
        }

        int fastEnd = Math.min(channels.size(), FAST_BIND_COUNT);
        int bindCount = fastEnd > 0 ? bindRange(channels, 0, fastEnd, zoneId) : 0;

        if (fastEnd < channels.size()) {
            final int restFrom = fastEnd;
            final int restTo = channels.size();
            try {
                BIND_EXECUTOR.submit(() -> {
                    int n = bindRange(channels, restFrom, restTo, zoneId);
                    SpiderDebug.log(TAG, "后台补绑剩余频道完成 " + restFrom + "~" + restTo + " 共" + n);
                    return n;
                });
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "后台补绑提交失败：" + e);
            }
        }

        SpiderDebug.log(TAG, "loadMergedIndex首屏完成，频道总数=" + channels.size() + " 首屏绑定=" + bindCount);
        return bindCount;
    }

    /**
     * 并行绑定 [from, to)，全部完成后返回绑定数。
     * 外部中断不会打断任务本身（不 cancel），只影响等待。
     */
    private static int bindRange(List<Channel> channels, int from, int to, ZoneId zoneId) {
        int size = to - from;
        if (size <= 0) return 0;
        if (size < MIN_PARALLEL_CHANNELS) return bindChunk(channels, from, to, zoneId);

        // 按线程数切块，每块一个任务；块内串行绑定同一频道的读写锁不冲突
        int chunk = (size + BIND_THREADS - 1) / BIND_THREADS;
        List<Future<Integer>> futures = new ArrayList<>(BIND_THREADS);
        for (int start = from; start < to; start += chunk) {
            final int s = start;
            final int e = Math.min(start + chunk, to);
            try {
                futures.add(BIND_EXECUTOR.submit((Callable<Integer>) () -> bindChunk(channels, s, e, zoneId)));
            } catch (Throwable t) {
                SpiderDebug.log(TAG, "并行绑定提交失败，退化为串行：" + t);
                return bindChunk(channels, from, to, zoneId);
            }
        }
        int bindCount = 0;
        long deadline = System.currentTimeMillis() + BIND_DEADLINE_MS;
        boolean swallowInterrupt = false;
        for (Future<Integer> f : futures) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;
            try {
                Integer n = f.get(remain, TimeUnit.MILLISECONDS);
                if (n != null) bindCount += n;
            } catch (InterruptedException e) {
                // 中断标志已被清除；不 break、不取消，继续等剩下的，
                // 让 BIND_EXECUTOR 把 MEM 预热完，下次进入就是毫秒级。
                swallowInterrupt = true;
                SpiderDebug.log(TAG, "绑定等待被外部中断，忽略并继续，已绑定=" + bindCount);
            } catch (java.util.concurrent.TimeoutException e) {
                SpiderDebug.log(TAG, "绑定到达自有截止时间 " + BIND_DEADLINE_MS + "ms，先返回已绑定=" + bindCount);
                break;
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "并行绑定任务异常：" + e);
            }
        }
        if (swallowInterrupt) {
            SpiderDebug.log(TAG, "已吞掉外部中断标志，避免污染线程池中的复用线程");
        }
        return bindCount;
    }

    /**
     * [CHANGED] 绑定快照中 [from, to) 区间的频道（并行任务单元）。
     * 每个频道单独 try-catch，单个失败不影响其它频道。
     * 不再检查中断标志：外层不取消任务，检查反而会误伤。
     */
    private static int bindChunk(List<Channel> channels, int from, int to, ZoneId zoneId) {
        int count = 0;
        for (int i = from; i < to; i++) {
            Channel ch = channels.get(i);
            if (ch == null) continue;
            try {
                if (bindChannel(ch, zoneId)) count++;
            } catch (Throwable e) {
                String name = String.valueOf(ch.getName());
                SpiderDebug.log(TAG, "bindChannel异常 ch=" + name + "：" + e);
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                SpiderDebug.log(TAG, "bindChannel堆栈：" + sw);
            }
        }
        return count;
    }

    /**
     * [CHANGED] 新增：读取某频道分片并组装 Epg 列表后 setDataList。
     * 缓存避免同频道重复读文件。
     */
    private static boolean bindChannel(Channel ch, ZoneId zoneId) {
        String lookupKey = ch.getTvgName();
        if (lookupKey == null || lookupKey.isEmpty()) {
            lookupKey = ch.getName();
        }
        if (lookupKey == null || lookupKey.isEmpty()) return false;
        String normLookup = normalizeChannelName(lookupKey.trim());
        if (normLookup.isEmpty()) return false;

        List<Tv.Programme> progList;
        try {
            progList = readChannelShard(normLookup);
        } catch (Exception e) {
            SpiderDebug.log(TAG, "加载分片失败 " + normLookup + "：" + e.toString());
            return false;
        }
        if (progList == null || progList.isEmpty()) return false;

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
            epg.getList().add(getEpgData(startDate, endDate, zoneId, programme));
        }
        epgList.addAll(dateGroup.values());
        ch.setDataList(epgList);
        return true;
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
            if (PARSE_ERR_COUNT.incrementAndGet() <= 3) {
                SpiderDebug.log(TAG, "parseFull出错 src=" + s + "：" + e.toString());
            }
            return OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
