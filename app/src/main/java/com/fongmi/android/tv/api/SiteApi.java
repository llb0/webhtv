package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.SiteContext;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CatWebEvent;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.smb.SmbCastSource;
import com.fongmi.android.tv.smb.SmbFolder;
import com.fongmi.android.tv.smb.SmbClient;
import com.fongmi.android.tv.utils.PushParser;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.VodDetailCache;
import com.fongmi.android.tv.web.WebHomeInlineVodStore;
import com.github.catvod.crawler.Spider;

import org.json.JSONObject;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Response;

public class SiteApi {

    public static final String PUSH = "push_agent";
    /** 冷源变量链（抓站点 JS → 截取 → 写回规则）的落地窗口，见 {@link #reheatBySearch}。 */
    private static final long PREHEAT_SETTLE_MS = 150L;
    /** 进详情前等在跑的 init 落完的上限，见 {@link SiteContext#awaitQuiescent}。 */
    private static final long DETAIL_QUIET_TIMEOUT_MS = 900L;

    public static String call(@NonNull Site site, @NonNull ArrayMap<String, String> params) throws IOException {
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        Call call = site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params) : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params));
        try (Response response = call.execute()) {
            return response.body().string();
        }
    }

    private static boolean isSpider(@NonNull Site site) {
        return site.getType() == 3;
    }

    private static String ac(int type) {
        return type == 0 ? "videolist" : "detail";
    }

    @NonNull
    public static Result homeContent(@NonNull Site site) throws Exception {
        long start = System.currentTimeMillis();
        try {
            Result result = homeContentRaw(site);
            SiteHealthStore.recordHome(site, true, result.getList().size(), System.currentTimeMillis() - start, "");
            return result;
        } catch (Exception e) {
            SiteHealthStore.recordHome(site, false, 0, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    @NonNull
    private static Result homeContentRaw(@NonNull Site site) throws Exception {
        SiteContext.markBrowse(site);
        if (isSpider(site)) {
            Spider spider = site.recent().spider();
            boolean crash = Prefers.getBoolean("crash");
            String home = crash ? "" : spider.homeContent(true);
            String video = crash ? "" : spider.homeVideoContent();
            Prefers.put("crash", false);
            SpiderDebug.log("home", home);
            SpiderDebug.log("homeVideo", video);
            SiteContext.markWarm(site);
            Result result = Result.fromJson(home);
            List<Vod> list = Result.fromJson(video).getList();
            if (!list.isEmpty()) result.setList(list);
            setTypes(site, result);
            return result;
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("filter", "true");
            String homeContent = call(site.fetchExt(), params);
            SpiderDebug.log("home", homeContent);
            Result result = Result.fromJson(homeContent);
            setTypes(site, result);
            return result;
        } else {
            try (Response response = OkHttp.newCall(site.getApi(), site.getHeader()).execute()) {
                String homeContent = response.body().string();
                SpiderDebug.log("home", homeContent);
                Result result = Result.fromType(site.getType(), homeContent);
                fetchPic(site, result);
                setTypes(site, result);
                return result;
            }
        }
    }

    @NonNull
    public static Result categoryContent(@NonNull String key, @NonNull String tid, @NonNull String page, boolean filter, @NonNull HashMap<String, String> extend) throws Exception {
        long start = System.currentTimeMillis();
        try {
            Result result = categoryContentRaw(key, tid, page, filter, extend);
            SiteHealthStore.recordCategory(key, true, result.getList().size(), System.currentTimeMillis() - start, "");
            return result;
        } catch (Exception e) {
            SiteHealthStore.recordCategory(key, false, 0, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    @NonNull
    private static Result categoryContentRaw(@NonNull String key, @NonNull String tid, @NonNull String page, boolean filter, @NonNull HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("category", "key=%s,tid=%s,page=%s,filter=%s,extend=%s", key, tid, page, filter, extend);
        Site site = VodConfig.get().getSite(key);
        SiteContext.markBrowse(site);
        if (isSpider(site)) {
            Spider categorySpider = site.recent().spider();
            // 分类不拿锁、不排队，但必须确认当前环境就是这个源的：搜索会把别的源 init 上来，
            // 回来刷新分类时若直接复用"最近实例"，读到的可能是别人的环境（XBPQ 这类把状态放静态字段的 jar）。
            SiteContext.ensureOwner(site, categorySpider);
            String categoryContent = categorySpider.categoryContent(tid, page, filter, extend);
            SiteContext.markWarm(site);
            SpiderDebug.log("category", categoryContent);
            return Result.fromJson(categoryContent);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            if (site.getType() == 1 && !extend.isEmpty()) params.put("f", App.gson().toJson(extend));
            if (site.getType() == 4) params.put("ext", Util.base64(App.gson().toJson(extend), Util.URL_SAFE));
            params.put("ac", ac(site.getType()));
            params.put("t", tid);
            params.put("pg", page);
            String categoryContent = call(site, params);
            SpiderDebug.log("category", categoryContent);
            return Result.fromType(site.getType(), categoryContent);
        }
    }

    /**
     * 进详情前的预热：只在该源"这次 init 之后还没跑过任何真实请求"时执行。
     *
     * <p>为什么需要它：XBPQ 这类源的请求头是 {@code web-sign${{sign}}#X-Client${{client}}}，
     * {@code sign}/{@code client} 不是常量，而是规则里"变量预加载"临时拉站点 JS 算出来、再
     * 写入规则的值。搜索会把环境 init 成别的源（静态实例的 jar 尤其），回到本源进详情时壳要
     * 先 init 一次，规则随之重建、写入的变量一起没了。详情是这条链上的**第一个**请求，规则
     * 来不及重算就把字面 {@code {{sign}}} 发出去，站点直接回 45x（见 3Qpc 的 458/457）。
     *
     * <p>预热先按该源自己的规则跑一次只读的首页，让它的规则链走一遍。但首页不一定够：有的源
     * {@code homeContent} 走"分类数据直出"提前返回（日志里 3Qpc 的 {@code preheat ok,size=683} 就是
     * 这一种），"变量预加载"没被执行，变量仍是空的。所以详情拿不到内容时还会再来一次
     * {@link #reheatBySearch}——那一次走的搜索路径一定执行变量链。
     *
     * <p>失败不阻断详情：预热只为把变量算出来，站点不给首页就当冷启动照旧发详情，由它自己报错。
     */
    private static void preheatIfCold(@NonNull Site site, @NonNull Spider spider) {
        if (SiteContext.isWarm(site)) return;
        long start = System.currentTimeMillis();
        try {
            String home = spider.homeContent(true);
            SpiderDebug.log("preheat", "site=%s,mode=home,ok,cost=%dms,size=%d" , site.getKey(), System.currentTimeMillis() - start, home == null ? 0 : home.length());
        } catch (Throwable e) {
            SpiderDebug.log("preheat", "site=%s,mode=home,error=%s,cost=%dms", site.getKey(), e, System.currentTimeMillis() - start);
        }
    }

    /**
     * 冷源第一次详情拿不到内容时的第二次尝试：用本源自己的一次搜索把"变量链"真正走完。
     *
     * <p>首页预热不一定够：有的源 {@code homeContent} 走的是"分类数据直出"分支（提前返回），
     * 规则里"变量预加载"那条链没被执行，请求头里的 {@code {{sign}}} 仍是字面占位符，详情照样 45x。
     * 而搜索路径一定会执行它（日志里同一源搜索时能看到 {@code 源码工具结果} → {@code 写入-sign}
     * → {@code 获取到变量 sign} 完整一段）。所以这里按最近一次搜索的关键词，对本源重放一次搜索。
     *
     * <p>变量是 jar 自己另起线程抓站点 JS、截取、写回规则字段算出来的，抓完到写回是毫秒级（同一
     * 环境实测 27ms），所以搜索返回后再给一个 {@link #PREHEAT_SETTLE_MS} 的落地窗口，让重试的详情
     * 拿到算好的值。没有关键词（用户没搜索过）时只等窗口，不额外发请求。
     */
    private static boolean reheatBySearch(@NonNull Site site, @NonNull Spider spider) {
        String keyword = SiteContext.lastKeyword();
        long start = System.currentTimeMillis();
        try {
            if (TextUtils.isEmpty(keyword)) {
                SpiderDebug.log("preheat", "site=%s,mode=none,cost=%dms", site.getKey(), System.currentTimeMillis() - start);
            } else {
                spider.searchContent(keyword, true);
                SpiderDebug.log("preheat", "site=%s,mode=search,keyword=%s,cost=%dms", site.getKey(), keyword, System.currentTimeMillis() - start);
            }
        } catch (Throwable e) {
            SpiderDebug.log("preheat", "site=%s,mode=search,error=%s,cost=%dms", site.getKey(), e, System.currentTimeMillis() - start);
        }
        try {
            Thread.sleep(PREHEAT_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * 只读诊断：把 jar 内部规则对象里跟"进详情能不能发出正确请求头"直接相关的几项打出来。
     *
     * <p>为什么需要它：`XBPQ` 的 `变量预加载` 只在 {@code xbpqIni()} 里启动异步线程
     * （{@code bl} 必须含 {@code {{ }}}）。壳看不到 jar 的规则，只能看到"有没有抓站点 JS"的日志，
     * 无法区分"没这条规则"和"有规则但线程没跑"。这里反射读一次键集合与三项值，只在日志开关
     * 打开时执行，不参与任何判断，也不改任何行为。
     */
    private static void dumpSpiderRule(String stage, @NonNull Site site, @NonNull Spider spider) {
        if (!SpiderDebug.isEnabled()) return;
        try {
            Field field = spider.getClass().getDeclaredField("rule");
            field.setAccessible(true);
            Object value = field.get(spider);
            if (!(value instanceof JSONObject)) {
                SpiderDebug.log("diag-rule", "stage=%s site=%s rule=not-json class=%s", stage, site.getKey(), value == null ? "null" : value.getClass().getName());
                return;
            }
            JSONObject rule = (JSONObject) value;
            String preload = rule.optString("变量预加载");
            SpiderDebug.log("diag-rule", "stage=%s site=%s keys=%d hasPreload=%s preload=%s home=%s header=%s",
                    stage, site.getKey(), rule.length(), rule.has("变量预加载"),
                    clip(preload), clip(rule.optString("主页url")), clip(rule.optString("请求头")));
        } catch (Throwable e) {
            SpiderDebug.log("diag-rule", "stage=%s site=%s error=%s", stage, site.getKey(), e);
        }
    }

    private static String clip(String text) {
        if (text == null) return "";
        String one = text.replace("\n", " ").replace("\r", " ");
        return one.length() > 60 ? one.substring(0, 60) : one;
    }

    /** spider 详情返回体 → Result，顺带打日志（凭证字段先行剥离，避免落日志）。 */
    @NonNull
    private static Result parseSpiderDetail(String detailContent) {
        TmdbSourceCredentialIngress.Ingress ingress = TmdbSourceCredentialIngress.extractRootAndStrip(detailContent);
        SpiderDebug.log("detail", ingress.getSanitizedJson());
        return Result.fromJson(ingress.getSanitizedJson());
    }

    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id) throws Exception {
        return detailContent(key, id, false);
    }

    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id, boolean refresh) throws Exception {
        SpiderDebug.log("detail", "key=%s,id=%s,refresh=%s", key, id, refresh);
        if (WebHomeInlineVodStore.KEY.equals(key)) return WebHomeInlineVodStore.detail(id);
        Site site = VodConfig.get().getSite(key);
        PushParser.Parsed push = PUSH.equals(key) ? PushParser.fromId(id) : null;
        String requestId = push == null ? id : push.getUrl();
        if (push != null && (site.isEmpty() || isLocalFileUrl(requestId))) return pushDetail(id, push);

        String sourceKey = detailCacheSourceKey(key, site);
        if (refresh) VodDetailCache.invalidateContent(sourceKey, id);
        String cached = refresh ? null : VodDetailCache.getContent(sourceKey, id);
        if (!TextUtils.isEmpty(cached)) {
            Result result = Result.fromJson(cached);
            if (!result.getList().isEmpty()) {
                SpiderDebug.log("detail-cache", "hit key=%s,id=%s,size=%d", key, id, cached.length());
                return result;
            }
            VodDetailCache.invalidateContent(sourceKey, id);
        }

        Result result;
        // 取一次时刻，隔着 spider 调用；调用后若最近一次开页请求落在这之后，就是这次调用开的页。
        // 那是个副作用，缓存会吞掉它，见 cacheDetail 的说明。
        long beforeSpider = System.currentTimeMillis();
        if (isSpider(site)) {
            // 环境独占不靠锁，靠调度：进详情之前搜索已经真的停了（见 SiteViewModel.haltSearch），
            // 这段没有别的源在跑，环境只属于本源。这里只确认归属，不是本源就按它的 ext 重建一次。
            // 搜索已经停了，但在跑的 init 可能还在写环境（中断不了它）。等它们落完再重建本源环境，
            // 否则详情刚建好的环境会被它最后写一次覆盖，变量又没了。
            SiteContext.awaitQuiescent(DETAIL_QUIET_TIMEOUT_MS);
            Spider detailSpider = site.recent().spider();
            SiteContext.initOwner(site, detailSpider);
            boolean cold = !SiteContext.isWarm(site);
            dumpSpiderRule("before-preheat", site, detailSpider);
            preheatIfCold(site, detailSpider);
            dumpSpiderRule("after-preheat", site, detailSpider);
            String detailContent = detailSpider.detailContent(Arrays.asList(requestId));
            result = parseSpiderDetail(detailContent);
            if (cold && result.getList().isEmpty()) {
                // 冷源第一次拿不到内容：变量链多半还没走完，详情带着字面 {{sign}} 被站方回 45x。
                // 用本源的一次搜索把链路走完，再要一次详情；成功与否都以第二次为准。
                SpiderDebug.log("detail", "cold retry key=%s,id=%s", key, id);
                if (reheatBySearch(site, detailSpider)) {
                    detailContent = detailSpider.detailContent(Arrays.asList(requestId));
                    result = parseSpiderDetail(detailContent);
                }
            }
            if (!result.getList().isEmpty()) SiteContext.markWarm(site);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("ac", ac(site.getType()));
            params.put("ids", requestId);
            String detailContent = call(site, params);
            TmdbSourceCredentialIngress.Ingress ingress = TmdbSourceCredentialIngress.extractRootAndStrip(detailContent);
            SpiderDebug.log("detail", ingress.getSanitizedJson());
            result = Result.fromType(site.getType(), ingress.getSanitizedJson());
        }
        Source.get().parse(result.getVod().setFlags());
        result = applyPushTitle(push, result);
        cacheDetail(key, sourceKey, id, result, beforeSpider);
        return result;
    }


    /**
     * 把详情结果写进缓存，除非这条不该缓存。
     *
     * <p>设置类入口的实际工作发生在 {@code detailContent} <b>里面</b>——弹输入框、写配置、扫码、
     * 请求宿主开网页。缓存命中时压根不调 spider，那些副作用就再也不发生：第二次点击弹窗不出、
     * 网页不开，宿主还会拿着占位结果直奔播放页。这正是「设置类入口只有第一次点击生效，
     * 第二次进播放页；过 5 分钟又能点一次」的由来。
     *
     * <p>三道挡板，各挡一种形态：
     * <ul>
     *   <li>{@link CatAction#blank}：什么都没有的条目（猫源老式动作项），缓存它也没意义
     *   <li>{@link #hasMetadata}：只有占位线路、没有元数据的条目（设置类站点的签名）
     *   <li>{@link CatWebEvent#requestedAfter}：这次调用顺带开过网页，那是副作用
     * </ul>
     * 前两道看结果形状，第三道看副作用——形状千变万化，副作用是确凿的，两类都要。
     *
     * @param beforeSpider 调 spider 之前的时刻。判定用 {@code >=}，所以同一毫秒内的陈旧开页
     *                     记录会让这条详情也躲过缓存——那个方向是安全的，代价只是下次点击
     *                     多走一次 spider。并发的另一次开页同理。
     */
    private static void cacheDetail(String key, String sourceKey, String id, Result result, long beforeSpider) {
        if (result.getList().isEmpty() || CatAction.blank(result.getVod())) return;
        if (!hasMetadata(result.getVod())) {
            SpiderDebug.log("detail-cache", "skip key=%s,id=%s reason=noMetadata", key, id);
            return;
        }
        if (result.getVod().getFlags().isEmpty()) {
            SpiderDebug.log("detail-cache", "skip key=%s,id=%s reason=noPlayableContent", key, id);
            return;
        }
        if (CatWebEvent.requestedAfter(beforeSpider)) {
            SpiderDebug.log("detail-cache", "skip key=%s,id=%s reason=webOpened", key, id);
            return;
        }
        String content = result.toString();
        VodDetailCache.putContent(sourceKey, id, content);
        SpiderDebug.log("detail-cache", "store key=%s,id=%s,size=%d", key, id, content.length());
    }

    /**
     * 这份详情带没带元数据——名字、封面、简介，有一项就算。
     *
     * <p>「只有线路、没有元数据」是<b>设置类站点</b>的签名。那类站点把每个设置项做成一个条目，
     * 真正的工作发生在 {@code detailContent} 里——弹输入框、写配置、扫码；返回值只是个占位的
     * 假线路，好让宿主别报错。实测某网盘配置源返回的就是：
     * <pre>{"list":[{"vod_play_from":"Config","vod_play_url":"Config$Config"}],"parse":0,"jx":0}</pre>
     *
     * <p>缓存住这种条目就等于把那个动作阉掉：命中缓存时 {@code detailContent} 压根不被调用，
     * 弹窗再也不出现，宿主还会拿着那个叫 {@code Config} 的假地址直奔播放页——这正是
     * 「设置类入口只有第一次点击生效，第二次进播放页」的由来。
     *
     * <p>{@link CatAction#blank} 挡不住它：{@code vod_play_from}/{@code vod_play_url} 会生成一条
     * Flag，{@code getFlags()} 非空，{@code blank} 就不成立。所以这里<b>刻意不看线路</b>——线路是
     * 占位结果也能轻易凑出来的东西，而元数据不是。
     *
     * <p>代价：真把元数据全放在列表项、detail 只回线路的源，每次进详情都要重新调一次 spider。
     * 那是缓存引入之前的原有行为，不是退步；而反过来把动作缓存掉是功能损坏。
     */
    private static boolean hasMetadata(Vod vod) {
        if (vod == null) return false;
        return !vod.getName().isEmpty()
                || !TextUtils.isEmpty(vod.getPic())
                || !TextUtils.isEmpty(vod.getContent());
    }

    private static String detailCacheSourceKey(String key, Site site) {
        if (site == null) return key;
        int signature = Objects.hash(site.getType(), site.getApi(), site.getExt(), site.getHeader());
        return key + "#" + Integer.toHexString(signature);
    }

    private static Result applyPushTitle(PushParser.Parsed push, Result result) {
        if (push == null || TextUtils.isEmpty(push.getTitle()) || result.getList().isEmpty()) return result;
        result.getVod().setName(push.getTitle());
        return result;
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id) throws Exception {
        return playerContent(key, flag, id, PlayerSetting.getActivePlayer());
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id, int playerType) throws Exception {
        return playerContent(key, flag, id, playerType, Source.get(), true);
    }

    @NonNull
    public static Result playerContentIsolated(@NonNull String key, @NonNull String flag, @NonNull String id) throws Exception {
        return playerContentIsolated(key, flag, id, PlayerSetting.getActivePlayer());
    }

    @NonNull
    public static Result playerContentIsolated(@NonNull String key, @NonNull String flag, @NonNull String id, int playerType) throws Exception {
        return playerContent(key, flag, id, playerType, new Source(), false);
    }

    @NonNull
    private static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id, int playerType, @NonNull Source source, boolean stopSource) throws Exception {
        SpiderDebug.log("player", "key=%s,flag=%s,id=%s", key, flag, id);
        if (stopSource) source.stop();
        if (WebHomeInlineVodStore.KEY.equals(key)) return WebHomeInlineVodStore.player(flag, id);
        Site site = VodConfig.get().getSite(key);
        String requestId = PUSH.equals(key) ? resolvePushPlayerUrl(id) : id;
        if (PUSH.equals(key) && (site.isEmpty() || isLocalFileUrl(requestId))) return pushPlayer(flag, requestId, playerType, source);
        if (site.getType() == 3) {
            String fallbackReason;
            try {
                Spider playerSpider = site.recent().spider();
                // 播放前不重建环境：详情阶段已经把本站点的环境准备好，并算出了 {{…}} 变量
                // 和写入字段；这里再 init 一遍会把这些打回原样，播放反而出错。
                String playerContent = playerSpider.playerContent(flag, requestId, VodConfig.get().getFlags());
                SpiderDebug.log("player", playerContent);
                Result result = Result.fromJson(playerContent);
                if (shouldFallbackPushSiteResult(key, result)) {
                    fallbackReason = result == null ? "" : result.getMsg();
                } else {
                    if (result.getFlag().isEmpty()) result.setFlag(flag);
                    result.setUrl(source.fetch(result, playerType));
                    result.setHeader(site.getHeader());
                    result.setKey(key);
                    return result;
                }
            } catch (Exception e) {
                if (PUSH.equals(key)) fallbackReason = e.getMessage();
                else throw e;
            }
            return fallbackPushPlayer(flag, requestId, playerType, fallbackReason, source);
        } else if (site.getType() == 4) {
            String fallbackReason;
            try {
                ArrayMap<String, String> params = new ArrayMap<>();
                params.put("play", requestId);
                params.put("flag", flag);
                String playerContent = call(site, params);
                SpiderDebug.log("player", playerContent);
                Result result = Result.fromJson(playerContent);
                if (shouldFallbackPushSiteResult(key, result)) {
                    fallbackReason = result == null ? "" : result.getMsg();
                } else {
                    if (result.getFlag().isEmpty()) result.setFlag(flag);
                    result.setUrl(source.fetch(result, playerType));
                    result.setHeader(site.getHeader());
                    return result;
                }
            } catch (Exception e) {
                if (PUSH.equals(key)) fallbackReason = e.getMessage();
                else throw e;
            }
            return fallbackPushPlayer(flag, requestId, playerType, fallbackReason, source);
        } else {
            Result result = new Result();
            result.setUrl(requestId);
            result.setFlag(flag);
            result.setHeader(site.getHeader());
            result.setPlayUrl(site.getPlayUrl());
            result.setParse(Sniffer.isVideoFormat(requestId) && result.getPlayUrl().isEmpty() ? 0 : 1);
            result.setUrl(source.fetch(result, playerType));
            SpiderDebug.log("player", result.toString());
            return result;
        }
    }

    static String resolvePushPlayerUrl(String id) {
        String pushUrl = PushParser.fromId(id).getUrl();
        // 投屏过来的本地 SMB 源：下发的是 SMB 连接要素描述符，这里由接收端自己启动代理，
        // 换成本机 127.0.0.1 直链后走和本机 SMB 完全相同的播放路径，不依赖发送端。
        try {
            String smb = SmbCastSource.toLocalUrl(pushUrl);
            if (!TextUtils.isEmpty(smb)) return smb;
        } catch (Exception e) {
            SpiderDebug.log("push-smb", "resolve failed=%s", e.getClass().getSimpleName());
        }
        return Sniffer.pushUrl(pushUrl);
    }

    /**
     * 投屏用：webhome_inline 的 VOD 只存在发送端内存里，远端拿到同一条历史必然
     * 报 "WebHome inline VOD not found"；改写成自包含的 push_agent 推送串（真实地址 + 请求头）后再发。
     */
    public static History castHistory(History history, String url, Map<String, String> headers) {
        return castHistory(history, url, headers, history == null ? "" : history.getVodName());
    }

    public static History castHistory(History history, String url, Map<String, String> headers, String name) {
        if (history == null || !WebHomeInlineVodStore.KEY.equals(history.getSiteKey())) return history;
        String base = Sniffer.gatewayTarget(url);
        if (TextUtils.isEmpty(base)) base = url;
        if (TextUtils.isEmpty(base)) return history;
        History copy = History.objectFrom(history.toString());
        if (copy == null) return history;
        // 发送端的本地截图文件在远端不存在，带过去只会让远端卡片退化成标题首字；
        // 清掉本地路径，让远端重新用自己设备上的非黑屏截图兜底。
        copy.setVodPic(clearLocalArtwork(copy.getVodPic()));
        copy.setWallPic(clearLocalArtwork(copy.getWallPic()));
        copy.setKey(PUSH + AppDatabase.SYMBOL + Sniffer.pushPayload(base, headers, name) + AppDatabase.SYMBOL + history.getCid());
        return copy;
    }

    private static String clearLocalArtwork(String pic) {
        if (TextUtils.isEmpty(pic)) return pic;
        return pic.startsWith("file://") || pic.startsWith("content://") ? "" : pic;
    }

    static boolean isLocalFileUrl(String url) {
        return url.regionMatches(true, 0, "file:", 0, 5);
    }

    static boolean shouldSniffPushUrl(String url) {
        if (!shouldSniffPushUrl(url, false)) return false;
        return !Sniffer.isVideoFormat(url);
    }

    static boolean shouldSniffPushUrl(String url, boolean videoFormat) {
        if (url == null || url.isEmpty() || isLocalFileUrl(url)) return false;
        boolean webUrl = url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8);
        return webUrl && !videoFormat;
    }

    static boolean shouldFallbackPushSiteResult(String key, Result result) {
        return PUSH.equals(key) && (result == null || result.hasMsg() || result.getUrl().isEmpty());
    }

    private static Result fallbackPushPlayer(String flag, String url, int playerType, String reason, Source source) throws Exception {
        SpiderDebug.log("player", "push site fallback reason=%s", TextUtils.isEmpty(reason) ? "empty result" : reason);
        return pushPlayer(flag, url, playerType, source);
    }

    private static Result pushPlayer(String flag, String url, int playerType, Source source) throws Exception {
        // 推送串可能带请求头：url@Headers=<encode(json)>，也可能只有 @Cookie= / @Referer= / @User-Agent=
        Map<String, String> pushHeaders = Sniffer.pushHeaders(url);
        String realUrl = Sniffer.pushUrl(url);
        Result result = new Result();
        result.setUrl(realUrl);
        result.setParse(shouldSniffPushUrl(realUrl) ? 1 : 0);
        result.setFlag(flag);
        if (!pushHeaders.isEmpty()) result.setHeader(pushHeaders);
        result.setUrl(source.fetch(result, playerType));
        SpiderDebug.log("player", result.toString());
        return result;
    }

    private static Result pushDetail(@NonNull String id, PushParser.Parsed push) throws Exception {
        // SMB 目录剧集：id 是 smb:// 描述符，历史/重进都按它重新列目录，剧集表才不会只剩点的那一集。
        if (SmbFolder.isDescriptor(push.getUrl())) return Result.vod(SmbFolder.detail(id, push.getName()));
        Vod vod = new Vod();
        vod.setId(id);
        vod.setName(push.getName());
        // 投屏过来的本地 SMB 源：描述符里带着真实文件名，片名不能显示成一整串描述符；
        // 地址保持描述符，等播放入口再换成本机代理地址（见 resolvePushPlayerUrl）。
        boolean smb = SmbCastSource.isDescriptor(push.getUrl());
        if (smb && !push.hasExplicitTitle()) vod.setName(SmbCastSource.titleOf(push.getUrl()));
        // 本地文件播放/推送到远程 app 时，把 file:/// 转成本机局域网代理地址，
        // 否则远程设备拿到 file:/// 无法访问（会崩）。与 fish 的 buildPushVod 一致。
        boolean localFile = !smb && push.getUrl().startsWith("file://");
        vod.setPlayUrl(localFile ? toLanHttpUrl(push.getUrl()) : push.getUrl());
        vod.setPlayFrom(ResUtil.getString(R.string.push));
        vod.setPic(ResUtil.getString(R.string.push_image));
        Source.get().parse(vod.setFlags());
        if (localFile) applyLocalFolderEpisodes(vod, push.getUrl());
        return Result.vod(vod);
    }

    /**
     * 本地单文件改成“同目录剧集”：只对本地 file:// 生效，链接一律走和单文件相同的
     * {@link #toLanHttpUrl}（本机 /file/ 代理），不改代理逻辑；点中的那个文件是当前集。
     * 目录里只有一个视频时保持原样。
     */
    private static void applyLocalFolderEpisodes(Vod vod, String fileUrl) {
        try {
            String path = fileUrl.substring("file://".length());
            File current = new File(Uri.parse(fileUrl).getPath() == null ? path : Uri.parse(fileUrl).getPath());
            File dir = current.getParentFile();
            if (dir == null || !dir.isDirectory()) return;
            File[] files = dir.listFiles(f -> f.isFile() && SmbClient.isVideo(f.getName()));
            if (files == null || files.length < 2) return;
            Arrays.sort(files, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
            String flagName = ResUtil.getString(R.string.push);
            String currentLan = toLanHttpUrl(fileUrl);
            // 点中的那个文件排第一：起播位置跟第一位走，不依赖 isSelected 在“线路重建”后
            // 还活着（这条链路里 setEpisodes 会重建条目、布尔位丢）。
            List<File> ordered = new ArrayList<>(files.length);
            ordered.add(current);
            for (File file : files) {
                if (!file.getAbsolutePath().equals(current.getAbsolutePath())) ordered.add(file);
            }
            StringBuilder urls = new StringBuilder();
            for (File file : ordered) {
                if (urls.length() > 0) urls.append('#');
                urls.append(stripFileExtension(file.getName())).append('$')
                        .append(toLanHttpUrl("file://" + file.getAbsolutePath()));
            }
            // 当前集仍然用点中的那个文件（和单文件播放同一个代理地址）
            vod.setPlayUrl(currentLan);
            Flag flag = Flag.create(flagName, urls.toString());
            for (Episode episode : flag.getEpisodes()) {
                if (currentLan.equals(episode.getUrl())) episode.setSelected(true);
            }
            vod.getFlags().add(0, flag);
        } catch (Exception ignored) {
            // 目录拿不到就退回单文件，不影响播放
        }
    }

    private static String stripFileExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 把 file:///绝对路径 转成本机 NanoHTTPD 的局域网 /file/ 代理地址。 */
    static String toLanHttpUrl(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("file://")) return url;
        String filePath = url.substring(7);
        String relative = stripStorageRoot(filePath);
        StringBuilder encodedPath = new StringBuilder();
        for (String segment : relative.split("/")) {
            if (segment.isEmpty()) {
                encodedPath.append("/");
            } else {
                encodedPath.append("/").append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
            }
        }
        String lan = Server.get().getAddress(false);
        if (lan.contains("http://:")) lan = Server.get().getAddress(true);
        return lan + "/file" + encodedPath;
    }

    private static String stripStorageRoot(String path) {
        String extRoot = com.github.catvod.utils.Path.rootPath();
        if (path.startsWith(extRoot + "/")) return path.substring(extRoot.length() + 1);
        if (path.startsWith("/sdcard/")) return path.substring(8);
        if (path.startsWith(extRoot)) return path.substring(extRoot.length());
        if (path.startsWith("/sdcard")) return path.substring(7);
        return path;
    }

    @NonNull
    /**
     * 搜索前的必要前置：把该源的 spider 建好并按它自己的 ext 初始化。
     *
     * <p>这一步会为环境排队，属于"必不可少的逻辑"；搜索任务的时间预算从它完成之后才开始算，
     * 所以排队多久都不算超时，只有初始化本身失败才会被结算成这个源没结果。
     */
    public static void prepareSpider(@NonNull Site site) {
        if (isSpider(site)) site.recent().spider();
    }

    public static Result searchContent(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) throws Exception {
        SpiderDebug.log("search", "site=%s,keyword=%s,quick=%s,page=%s", site.getName(), keyword, quick, page);
        boolean hasPage = !page.equals("1");
        if (isSpider(site)) {
            String searchContent = hasPage ? site.spider().searchContent(keyword, quick, page) : site.spider().searchContent(keyword, quick);
            SiteContext.markWarm(site);
            SpiderDebug.log("search", searchContent);
            Result result = Result.fromJson(searchContent);
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("wd", keyword);
            params.put("quick", String.valueOf(quick));
            params.put("extend", "");
            if (hasPage) params.put("pg", page);
            String searchContent = call(site, params);
            SpiderDebug.log("search", searchContent);
            Result result = fetchPic(site, Result.fromType(site.getType(), searchContent));
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        }
    }

    @NonNull
    public static Result action(@NonNull String key, @NonNull String action) throws Exception {
        Site site = VodConfig.get().getSite(key);
        SpiderDebug.log("action", "key=%s,action=%s", key, action);
        if (site.getType() == 3) return Result.fromJson(site.recent().spider().action(action));
        if (site.getType() == 4) return Result.fromJson(OkHttp.string(action));
        return Result.empty();
    }

    @NonNull
    public static Result fetchPic(@NonNull Site site, @NonNull Result result) throws Exception {
        if (site.getType() > 2 || result.getList().isEmpty() || !result.getVod().getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) if (empty || site.getCategories().contains(item.getTypeName())) ids.add(item.getId());
        if (ids.isEmpty()) return result.clear();
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("ac", ac(site.getType()));
        params.put("ids", TextUtils.join(",", ids));
        try (Response response = OkHttp.newCall(site.getApi(), site.getHeader(), params).execute()) {
            result.setList(Result.fromType(site.getType(), response.body().string()).getList());
            return result;
        }
    }

    private static void setTypes(@NonNull Site site, @NonNull Result result) {
        result.getTypes().stream().filter(type -> result.getFilters().containsKey(type.getTypeId())).forEach(type -> type.setFilters(result.getFilters().get(type.getTypeId())));
        if (site.getCategories().isEmpty()) return;
        Map<String, Class> typeByName = new HashMap<>();
        result.getTypes().forEach(type -> typeByName.put(type.getTypeName(), type));
        List<Class> types = site.getCategories().stream().map(typeByName::get).filter(Objects::nonNull).toList();
        if (!types.isEmpty()) result.setTypes(types);
    }
}
