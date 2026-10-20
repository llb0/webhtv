package com.fongmi.android.tv.download;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class HlsParser {

    public static class Variant {
        public int bandwidth;
        public String uri;
        public Variant(int b, String u) { bandwidth = b; uri = u; }
    }

    public static class ParseResult {
        public boolean isHls;
        public boolean isMaster;
        public List<String> segments = new ArrayList<>();
        public String baseUrl;
    }

    public static ParseResult parse(String url, java.util.Map<String, String> headers) throws Exception {
        ParseResult result = new ParseResult();
        result.baseUrl = baseOf(url);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (headers != null) for (java.util.Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.connect();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        String content = sb.toString();

        if (!content.contains("#EXTM3U")) return result;
        result.isHls = true;

        if (content.contains("#EXT-X-STREAM-INF")) {
            // master playlist — pick highest bandwidth
            result.isMaster = true;
            List<Variant> variants = new ArrayList<>();
            String[] lines = content.split("\n");
            int bw = 0;
            for (String line : lines) {
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    String[] attrs = line.split(",");
                    for (String a : attrs) if (a.trim().startsWith("BANDWIDTH=")) bw = Integer.parseInt(a.trim().split("=")[1]);
                } else if (!line.startsWith("#") && !line.trim().isEmpty()) {
                    variants.add(new Variant(bw, line.trim()));
                    bw = 0;
                }
            }
            Variant best = null;
            for (Variant v : variants) if (best == null || v.bandwidth > best.bandwidth) best = v;
            if (best != null) {
                String child = absoluteUrl(url, best.uri);
                return parse(child, headers); // recursive
            }
            return result;
        }

        // media playlist — collect segments
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (!line.startsWith("#") && !line.trim().isEmpty()) {
                result.segments.add(absoluteUrl(url, line.trim()));
            }
        }
        return result;
    }

    private static String baseOf(String url) {
        int i = url.lastIndexOf('/');
        return i >= 0 ? url.substring(0, i + 1) : url + "/";
    }

    private static String absoluteUrl(String parent, String child) {
        if (child.startsWith("http://") || child.startsWith("https://")) return child;
        return baseOf(parent) + child;
    }
}
