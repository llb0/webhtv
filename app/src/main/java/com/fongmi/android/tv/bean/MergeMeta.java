package com.fongmi.android.tv.bean;

import java.util.HashMap;
import java.util.Map;

public class MergeMeta {

    public long lastMergeRun;
    public Map<String, SourceItem> sources = new HashMap<>();

    public static class SourceItem {
        public String fileMd5;
    }
}
