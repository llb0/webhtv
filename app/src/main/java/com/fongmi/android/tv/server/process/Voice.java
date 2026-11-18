package com.fongmi.android.tv.server.process;

import android.app.Activity;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.ServerToken;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.voice.LiveDeepLink;
import com.fongmi.android.tv.voice.VoiceCommand;
import com.fongmi.android.tv.voice.VoiceCommandParser;
import com.fongmi.android.tv.voice.VoiceNav;
import com.fongmi.android.tv.voice.VoicePlay;
import com.github.catvod.crawler.SpiderDebug;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * 语音/文字指令接收端：{@code /voice?cmd=...&q=...&token=...}。
 *
 * <p>手机端悬浮球把识别出的文本发到这里，由电视自己解析并执行——手机不参与规则判断，
 * 换台/搜索/播放控制的行为只在这一个地方维护。</p>
 *
 * <p>鉴权：除 {@code cmd=ping} 与 {@code cmd=pair} 外都必须带正确 token。{@code pair}
 * 会在电视上弹确认框，用户用遥控器点"允许"才返回令牌，避免局域网内任意设备直接遥控电视。</p>
 */
public class Voice implements Process {

    private static final long PAIR_TIMEOUT_SECONDS = 30;

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/voice");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String cmd = params.get("cmd");
        if (TextUtils.isEmpty(cmd)) return Nano.ok("webhtv-voice");
        SpiderDebug.log("voice", "cmd=%s q=%s", cmd, params.get("q"));
        return switch (cmd) {
            case "ping" -> Nano.ok("webhtv-voice");
            case "pair" -> pair();
            default -> authorized(params) ? dispatch(cmd, params) : unauthorized();
        };
    }

    private boolean authorized(Map<String, String> params) {
        return ServerToken.matches(params.get("token"));
    }

    private Response unauthorized() {
        return Nano.error(Response.Status.UNAUTHORIZED, "unauthorized");
    }

    private Response dispatch(String cmd, Map<String, String> params) {
        return switch (cmd) {
            case "say" -> say(params.get("q"));
            case "search" -> search(params.get("q"));
            case "play" -> play(params.get("q"));
            case "live" -> live(params.get("q"));
            case "history" -> open(VoiceCommand.Type.HISTORY);
            case "collect" -> open(VoiceCommand.Type.COLLECT);
            case "site" -> site(params.get("q"));
            case "control" -> control(params.get("type"));
            default -> Nano.ok("unknown");
        };
    }

    /** 原文交给解析器，命中不了自动当关键词搜索。 */
    private Response say(String text) {
        VoiceCommand command = VoiceCommandParser.parse(text);
        if (command.isEmpty()) return Nano.ok("empty");
        return switch (command.type()) {
            case SEARCH -> search(command.arg());
            case PLAY -> play(command.arg());
            case LIVE -> live(command.arg());
            case HISTORY -> open(VoiceCommand.Type.HISTORY);
            case COLLECT -> open(VoiceCommand.Type.COLLECT);
            case SITE -> site(command.arg());
            case CONTROL_PLAY -> control("play");
            case CONTROL_PAUSE -> control("pause");
            case CONTROL_NEXT -> control("next");
            case CONTROL_PREV -> control("prev");
            default -> Nano.ok("unknown");
        };
    }

    private Response search(String word) {
        if (TextUtils.isEmpty(word)) return Nano.error(Response.Status.BAD_REQUEST, "missing q");
        if (App.activity() == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        App.post(() -> ServerEvent.search(word));
        return Nano.ok("search:" + word);
    }

    /** "看某某"：直接全屏播放，不经过搜索结果页。 */
    private Response play(String word) {
        if (TextUtils.isEmpty(word)) return Nano.error(Response.Status.BAD_REQUEST, "missing q");
        boolean started = VoicePlay.play(word);
        if (!started) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        return Nano.ok("play:" + word);
    }

    /**
     * 直播：不指定频道就直接开直播页；指定了但当前源里没有这个台，也把直播页打开，
     * 只在返回值里说明没找到，避免"手机说 404、电视其实什么都没发生"。
     */
    private Response live(String name) {
        if (TextUtils.isEmpty(name)) return LiveDeepLink.openPlain() ? Nano.ok("live") : Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        // 频道数据要等直播页 parse 完才有，这里不做"找得到/找不到"的判断，
        // 找不到时由直播页在渲染后提示，避免把"源还没加载完"误报成没有这个频道。
        LiveDeepLink.open(name);
        return Nano.ok("live:" + name);
    }

    private Response open(VoiceCommand.Type type) {
        if (App.activity() == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        boolean opened = type == VoiceCommand.Type.HISTORY ? VoiceNav.openHistory() : VoiceNav.openCollect();
        if (!opened) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        return Nano.ok(type == VoiceCommand.Type.HISTORY ? "history" : "collect");
    }

    private Response site(String name) {
        if (TextUtils.isEmpty(name)) return Nano.error(Response.Status.BAD_REQUEST, "missing q");
        boolean switched = VoiceNav.switchSite(name);
        if (!switched) {
            VoiceNav.notifySiteMissing(name);
            return Nano.ok("no-site:" + name);
        }
        return Nano.ok("site:" + name);
    }

    private Response control(String type) {
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(type)) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "no-playback");
        switch (type) {
            case "play" -> App.post(() -> service.player().play());
            case "pause" -> App.post(() -> service.player().pause());
            case "next" -> App.post(service::dispatchNext);
            case "prev" -> App.post(service::dispatchPrev);
            default -> {
                return Nano.ok("unknown");
            }
        }
        return Nano.ok("control:" + type);
    }

    /**
     * 配对：电视端弹一次确认框，用户点"允许"才把令牌交给请求方。
     * 请求线程会阻塞等待，超时或 App 不在前台都按拒绝处理。
     */
    private Response pair() {
        Activity activity = App.activity();
        if (activity == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        String token = ServerToken.get();
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] allowed = {false};
        App.post(() -> {
            Activity current = App.activity();
            if (current == null) {
                latch.countDown();
                return;
            }
            new WebHtvAlertDialogBuilder(current)
                    .setTitle(R.string.voice_pair_title)
                    .setMessage(R.string.voice_pair_message)
                    .setNegativeButton(R.string.dialog_negative, (dialog, which) -> latch.countDown())
                    .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                        allowed[0] = true;
                        latch.countDown();
                    })
                    .show();
        });
        try {
            if (!latch.await(PAIR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return Nano.error(Response.Status.REQUEST_TIMEOUT, "pair-timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Nano.error(Response.Status.INTERNAL_ERROR, "pair-interrupted");
        }
        return allowed[0] ? Nano.ok(token) : Nano.error(Response.Status.FORBIDDEN, "pair-denied");
    }
}
