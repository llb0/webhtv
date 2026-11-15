package com.fongmi.android.tv.service;

import com.fongmi.android.tv.bean.AiConfig;
import com.github.catvod.crawler.SpiderDebug;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class AiDebugLog {

    private AiDebugLog() {
    }

    static void request(String tag, String scene, AiConfig config, AiCompletionClient.RequestSpec spec, String extra) {
        if (!SpiderDebug.isEnabled() || spec == null) return;
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        SpiderDebug.log(tag, "ai request scene=%s protocol=%s model=%s url=%s %s headers=%s body=%s",
                scene, safe.getProtocol(), safe.getModel(), spec.getUrl(), clean(extra), redactedHeaders(spec.getHeaders()), clip(spec.getBody().toString()));
    }

    static void response(String tag, String scene, int code, long cost, String body, String extra) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log(tag, "ai response scene=%s code=%d cost=%dms %s body=%s", scene, code, cost, clean(extra), clip(body));
    }

    static void error(String tag, String scene, long cost, Throwable error, String extra) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log(tag, "ai error scene=%s cost=%dms %s error=%s", scene, cost, clean(extra), error == null ? "" : error.getMessage());
    }

    static String clip(String text) {
        return Objects.toString(text, "").replace('\r', ' ').replace('\n', ' ').trim();
    }

    static Map<String, String> redactedHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) return result;
        if (SpiderDebug.isEnabled()) {
            result.putAll(headers);
            return result;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String lower = key == null ? "" : key.toLowerCase();
            result.put(key, lower.contains("key") || lower.contains("authorization") ? "<redacted>" : entry.getValue());
        }
        return result;
    }

    private static String clean(String text) {
        return Objects.toString(text, "").replace('\r', ' ').replace('\n', ' ').trim();
    }
}
