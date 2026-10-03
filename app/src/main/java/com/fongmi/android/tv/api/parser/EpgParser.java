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

import org.simpleframework.xml.core.Persister;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
 
import okhttp3.Request;
import okhttp3.Response;
 
public class EpgParser {

    private static final String TAG = EpgParser.class.getSimpleName();
    private static final long UPDATE_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    private static final int KEEP_DAYS = 7;
 
    public static void start(Live live) {
        if (live == null || live.getGroups().isEmpty()) return;
        List<String> urls = LiveEpgSetting.getXmlUrls(live);
        if (urls.isEmpty()) return;
        ZoneId zoneId = zoneIdOf(live.getTimeZone());
        for (String url : urls) {
            try {
                parseUrl(live, url, zoneId);
            } catch (Exception e) {
                Log.w(TAG, "parseUrl failed url=" + url + ": " + e.getMessage());
            }
        }
    }

    private static void parseUrl(Live live, String url, ZoneId zoneId) throws Exception {
        File cacheFile = ensureCache(url);
        if (cacheFile == null || !cacheFile.exists()) return;
        String content = readCacheContent(cacheFile);
        if (content.isEmpty()) return;
        Tv tv = parseTv(content);
        if (tv == null) return;
        Map<String, Channel> liveChannelMap = prepareLiveChannels(live);
        Map<String, List<Tv.Channel>> xmlChannelMap = tv.getChannel().stream()
                .collect(java.util.stream.Collectors.groupingBy(Tv.Channel::getId));
        Map<String, Map<String, Epg>> sourceMap = buildSourceMap(tv, liveChannelMap, xmlChannelMap, zoneId);
        mergeIntoLive(live, sourceMap);
    }

    /** 取缓存：缺失则下载；超过 6 小时则 HEAD 比对，有变化才重新下载 */
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
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            File xml = Path.epg(file.getName() + ".xml");
            FileUtil.gzipDecompress(file, xml);
            bytes = Path.readToByte(xml);
        }
        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
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

    /** 将单个 EPG 源解析为 tvgId -> date -> Epg 的映射（频道匹配复用直播源的 tvgId/tvgName/name 及 display-name 回退） */
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
            OffsetDateTime endDate = parseFull(programme.getStop(), zoneId);
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

    /** 增量合并到直播频道：已有日期保留，新日期补充；并清理 7 天前数据 */
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
        LocalDate keepFrom = LocalDate.now().minusDays(KEEP_DAYS);
        List<Epg> result = new ArrayList<>();
        for (Epg epg : list) {
            try {
                LocalDate date = LocalDate.parse(epg.getDate(), Formatters.DATE);
                if (!date.isBefore(keepFrom)) result.add(epg);
            } catch (Exception e) {
                result.add(epg);
            }
        }
        result.sort((a, b) -> a.getDate().compareTo(b.getDate()));
        return result;
    }
 
    /** 供 Epg.objectFrom 使用：解析单频道 EPG 字符串为单日 Epg */
    public static Epg getEpg(String xml, String key, ZoneId zoneId) {
        try {
            String content = sanitizeXml(xml);
            if (content.isEmpty()) return new Epg();
            Tv tv = parseTv(content);
            String rawDate = tv.getDate();
            String date = rawDate.isEmpty() ? LocalDate.now(zoneId).format(Formatters.DATE) : parseFull(rawDate, zoneId).atZoneSameInstant(zoneId).format(Formatters.DATE);
            Epg epg = Epg.create(key, date);
            for (Tv.Programme programme : tv.getProgramme()) epg.getList().add(getEpgData(programme, zoneId));
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
