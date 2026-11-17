package com.fongmi.android.tv.ui.helper;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.Sniffer;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 站源详情里下发的演员/导演点击链接（{@code [a=cr:{…}/]名字[/a]}）。
 *
 * <p>键是规范化后的人名，值是可以直接交给 {@code Result.type()} 的 JSON 串。
 * 只做名字到 JSON 的映射，不解释 id 语义：id 原样回传给站源的 categoryContent。
 */
public final class SourceClickLinks {

    private static final SourceClickLinks EMPTY = new SourceClickLinks();

    private final Map<String, String> links = new LinkedHashMap<>();

    public static SourceClickLinks empty() {
        return EMPTY;
    }

    public static SourceClickLinks from(@Nullable Vod vod) {
        if (vod == null) return EMPTY;
        SourceClickLinks result = new SourceClickLinks();
        result.parse(vod.getActor());
        result.parse(vod.getDirector());
        return result;
    }

    private void parse(String text) {
        if (text == null || text.isEmpty()) return;
        Matcher matcher = Sniffer.CLICKER.matcher(text);
        while (matcher.find()) {
            for (String json : Sniffer.clickItems(matcher.group(1))) {
                Class type = Class.objectFrom(json);
                String name = type.getTypeName();
                if (name.isEmpty()) continue;
                String key = normalize(name);
                if (key.isEmpty() || links.containsKey(key)) continue;
                links.put(key, json);
            }
        }
    }

    public boolean isEmpty() {
        return links.isEmpty();
    }

    /**
     * 按 TMDB 演职员名字找源站链接：先精确匹配，再互相包含（中英译名常常只对得上一部分）。
     */
    @Nullable
    public String find(String personName) {
        if (links.isEmpty() || personName == null) return null;
        String key = normalize(personName);
        if (key.isEmpty()) return null;
        String direct = links.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, String> entry : links.entrySet()) {
            String source = entry.getKey();
            if (source.length() < 2 || key.length() < 2) continue;
            if (source.contains(key) || key.contains(source)) return entry.getValue();
        }
        return null;
    }

    private static String normalize(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\s·.、,，:：]", "").toLowerCase(Locale.ROOT).trim();
    }
}
