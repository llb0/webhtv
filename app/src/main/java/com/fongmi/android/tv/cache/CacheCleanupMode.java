package com.fongmi.android.tv.cache;

public enum CacheCleanupMode {
    MODULE,
    LIGHT,
    STANDARD,
    DEEP,
    /**
     * Clears every module at once (the settings row's plain click). Unlike {@link #DEEP} it also
     * removes the report-only and owner-managed leftovers, so the cache directory really ends up
     * empty the way the pre-cache-management "clear everything" entry left it.
     */
    FULL,
    /**
     * Brings the whole cache directory back under the configured total limit once nothing plays.
     *
     * <p>Not a user-pressed tier: the module list comes from {@code CachePolicyEngine
     * .convergencePlan} and covers every module the registry lets a user clean by hand, so the
     * playback caches are reclaimable while the residual sweep of {@link #FULL} - plugin scripts
     * and unclassified cache - stays out of it. It keeps no age windows, because a total limit the
     * cache can not reach by deleting fresh files would be a limit that never takes effect.</p>
     */
    CONVERGE,
    /**
     * 后台自动清理（低空间、定时、启动）跑的计划。
     *
     * <p>覆盖范围与 {@link #FULL} 一致，唯一区别是锁闭合的模块完全不参与：计划里没有它们，
     * {@code cleanEverything} 的残留清扫也把它们自己的目录当作保留项，因此自动清理既不会清内容、
     * 也不会顺手把目录扫空。手动入口（一键清理、分级清理）不受锁影响，锁只约束"自动"。</p>
     */
    AUTO
}
