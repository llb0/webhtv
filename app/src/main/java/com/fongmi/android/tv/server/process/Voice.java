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
        if (TextUtils.isEmpty(cmd)) return Nano.ok("webhtv-voice:" + (com.fongmi.android.tv.utils.Util.isMobile() ? "mobile" : "tv"));
        SpiderDebug.log("voice", "cmd=%s q=%s", cmd, params.get("q"));
        return switch (cmd) {
            case "ping" -> Nano.ok("webhtv-voice:" + (com.fongmi.android.tv.utils.Util.isMobile() ? "mobile" : "tv"));
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
            case "nav", "key" -> nav(params);
            case "touch" -> touch(params);
            case "input", "type" -> input(params);
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

    /**
     * 遥控器方向盘/菜单键：把按键投给当前界面，由界面自己的焦点逻辑处理
     * （播放页里"右键"就是快进、"确定"就是暂停，跟真遥控器一致）。
     */
    private Response nav(Map<String, String> params) {
        // 兼容 k / key / code 三种参数名，避免客户端版本不一致时全变成 unknown
        String code = firstNonEmpty(params.get("k"), params.get("key"), params.get("code"));
        boolean hold = "1".equals(params.get("hold"));
        if (TextUtils.isEmpty(code)) return Nano.ok("unknown:none");
        if ("home".equals(code)) {
            // 遥控器的"首页"是回本 App 首页：关掉其它页面，不是把 App 丢到后台
            VoiceNav.collapse();
            return Nano.ok("nav:home");
        }
        Activity activity = App.activity();
        if (activity == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        int keyCode = keyCode(code);
        if (keyCode < 0) return Nano.ok("unknown:" + code);
        App.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            android.view.View decor = activity.getWindow().getDecorView();
            // 长按：先按下，再补几次 repeat，最后抬起，模拟遥控器按住不放
            decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode));
            if (hold) {
                for (int i = 1; i <= 3; i++) {
                    final int repeat = i;
                    App.post(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        decor.dispatchKeyEvent(new android.view.KeyEvent(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_DOWN, keyCode, repeat));
                    }, 150L * i);
                }
                App.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode));
                }, 600);
            } else {
                decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode));
            }
        });
        return Nano.ok((hold ? "nav-hold:" : "nav:") + code);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    /** 触摸板：把归一化坐标(0~1000)投成本地触摸事件，相当于用鼠标点界面。 */
    private static long touchDownTime;

    private Response touch(Map<String, String> params) {
        Activity activity = App.activity();
        if (activity == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        final float nx = parseFloat(params.get("x"));
        final float ny = parseFloat(params.get("y"));
        final int action = (int) parseFloat(params.get("a"));
        // a=3 是"鼠标右键"：约定功能为返回
        if (action == 3) {
            App.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                android.view.View decor = activity.getWindow().getDecorView();
                decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
            });
            return Nano.ok("touch:3");
        }
        if (nx < 0 || ny < 0) return Nano.error(Response.Status.BAD_REQUEST, "missing xy");
        App.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            android.view.View decor = activity.getWindow().getDecorView();
            if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return;
            long now = android.os.SystemClock.uptimeMillis();
            // a=5 是"取消当前这次按下"（客户端从单指切到双指时用，避免误触发点击）
            int type = action == 5 ? android.view.MotionEvent.ACTION_CANCEL : (action == 2 ? android.view.MotionEvent.ACTION_UP : (action == 0 ? android.view.MotionEvent.ACTION_DOWN : android.view.MotionEvent.ACTION_MOVE));
            boolean up = action == 2 || action == 5;
            android.view.MotionEvent event = android.view.MotionEvent.obtain(
                    up || touchDownTime == 0 ? now : touchDownTime, now,
                    type,
                    nx * decor.getWidth() / 1000f, ny * decor.getHeight() / 1000f, 0);
            if (action == 0) touchDownTime = now;
            decor.dispatchTouchEvent(event);
            event.recycle();
            if (up) touchDownTime = 0;
        });
        return Nano.ok("touch:" + action);
    }

    /**
     * 把文本"打"进目标端当前焦点所在的输入框（相当于远程输入法）：
     * 先找焦点控件，是 EditText 就直接插入光标处；
     * 否则取它的 InputConnection 用 commitText 提交（WebView 的内嵌输入框走这条）；
     * 两者都不行时兜底：写剪贴板 + 发粘贴键。
     */
    private Response input(Map<String, String> params) {
        String text = params.get("text");
        if (text == null || text.isEmpty()) text = params.get("q");
        if (text == null || text.isEmpty()) return Nano.error(Response.Status.BAD_REQUEST, "missing text");
        Activity activity = App.activity();
        if (activity == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        final String value = text;
        App.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            android.view.View decor = activity.getWindow().getDecorView();
            if (fill(decor.findFocus() != null ? decor.findFocus() : findWeb(decor), value)) return;
            paste(activity, decor, value);
        });
        return Nano.ok("input:" + value.length());
    }

    /** 优先 EditText 插入，其次 InputConnection 提交。 */
    private boolean fill(android.view.View focus, String text) {
        if (focus == null) return false;
        if (focus instanceof android.widget.EditText) {
            android.widget.EditText edit = (android.widget.EditText) focus;
            android.text.Editable editable = edit.getText();
            int start = Math.max(0, edit.getSelectionStart());
            int end = Math.max(start, edit.getSelectionEnd());
            if (end > editable.length()) end = editable.length();
            if (start > editable.length()) start = editable.length();
            editable.replace(start, end, text);
            return true;
        }
        // onCreateInputConnection 只要一个 EditorInfo（取当前控件的属性即可）
        android.view.inputmethod.EditorInfo info = new android.view.inputmethod.EditorInfo();
        info.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        info.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE;
        android.view.inputmethod.InputConnection connection = focus.onCreateInputConnection(info);
        if (connection != null) {
            connection.commitText(text, 1);
            return true;
        }
        return false;
    }

    /** 树里找 WebView（网页里的输入框焦点在 WebView 内部，不在 View 焦点链上）。 */
    private android.view.View findWeb(android.view.View root) {
        if (root == null) return null;
        if (root instanceof android.webkit.WebView) return root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = findWeb(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 兜底：写剪贴板再发一次粘贴键。 */
    private void paste(Activity activity, android.view.View decor, String text) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("webhtv", text));
            decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PASTE));
            decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_PASTE));
        } catch (Exception ignored) {
        }
    }

    private static float parseFloat(String value) {
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return -1f;
        }
    }

    private int keyCode(String code) {
        if (code == null) return -1;
        return switch (code) {
            case "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP;
            case "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN;
            case "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            case "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER;
            case "menu" -> android.view.KeyEvent.KEYCODE_MENU;
            case "back" -> android.view.KeyEvent.KEYCODE_BACK;
            case "home" -> android.view.KeyEvent.KEYCODE_HOME;
            default -> -1;
        };
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
