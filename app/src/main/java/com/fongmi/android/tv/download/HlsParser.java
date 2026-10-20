package com.fongmi.android.tv.download;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 最小化 m3u8 解析器：提取分片 URL 和 EXTINF duration；master playlist 自动选最高码率 variant。 */
public class HlsParser {

    /** 解析入口：fetch manifest；若为 master playlist 则选最高码率 variant 后递归解析。 */
    public static Result parse(String manifestUrl, java.util.Map<String, String> headers) throws Exception {
        String content = fetchText(manifestUrl, headers);
        if (content == null || !content.contains("#EXTM3U")) {
            throw new IllegalArgumentException("Not a valid m3u8 manifest");
        }
        if (content.contains("#EXT-X-STREAM-INF")) {
            String variant = pickHighestBitrateVariant(content);
            if (variant != null) return parse(toAbsolute(variant, manifestUrl), headers);
        }
        return parseMediaPlaylist(content, manifestUrl);
    }

    private static Result parseMediaPlaylist(String content, String baseUrl) {
        List<String> urls = new ArrayList<>();
        List<Double> durations = new ArrayList<>();
        double lastDuration = 0;
        String segment = null;
        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                if (line.startsWith("#EXTINF")) {
                    try {
                        Matcher m = Pattern.compile("EXTINF:([\\d.]+)").matcher(line);
                        if (m.find()) lastDuration = Double.parseDouble(m.group(1));
                    } catch (Exception ignored) {}
                }
                continue;
            }
            // 这一行就是分片 URL
            String absolute = toAbsolute(line, baseUrl);
            urls.add(absolute);
            durations.add(lastDuration);
        }
        if (urls.isEmpty()) throw new IllegalStateException("No segments found in m3u8");
        return new Result(urls, durations);
    }

    private static String pickHighestBitrateVariant(String content) {
        Pattern streamInf = Pattern.compile("#EXT-X-STREAM-INF:[^\\n]*BANDWIDTH=(\\d+)[^\\n]*\\n(\\S+)");
        Matcher m = streamInf.matcher(content);
        long bestBw = -1;
        String bestUrl = null;
        while (m.find()) {
            long bw = Long.parseLong(m.group(1));
            if (bw > bestBw) { bestBw = bw; bestUrl = m.group(2); }
        }
        return bestUrl != null ? bestUrl : content;
    }

    private static String toAbsolute(String relOrAbs, String baseUrl) {
        try {
            URL url = new URL(baseUrl);
            URI base = URI.create(url.getProtocol() + "://" + url.getHost() + (url.getPort() > 0 ? ":" + url.getPort() : "") + url.getPath());
            return base.resolve(relOrAbs).toString();
        } catch (Exception e) { return relOrAbs; }
    }

    private static String fetchText(String url, java.util.Map<String, String> headers) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        if (headers != null) for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) conn.setRequestProperty(e.getKey(), e.getValue());
        }
        try (InputStream is = conn.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        } finally { conn.disconnect(); }
    }

    public static class Result {
        public final List<String> segmentUrls;
        public final List<Double> durations;
        public Result(List<String> segmentUrls, List<Double> durations) {
            this.segmentUrls = segmentUrls;
            this.durations = durations;
        }
    }
}
