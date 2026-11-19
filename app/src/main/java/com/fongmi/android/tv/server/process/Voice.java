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
 * <p>手机端「语音遥控器」面板把识别出的文本发到这里，由对端自己解析并执行——手机不参与规则判断，
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
        if (TextUtils.isEmpty(cmd)) return Nano.ok(identity());
        SpiderDebug.log("voice", "cmd=%s q=%s", cmd, params.get("q"));
        return switch (cmd) {
            case "ping" -> Nano.ok(identity());
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
        try {
            StringBuilder builder = new StringBuilder("收到指令 ").append(cmd);
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String key = entry.getKey();
                if ("token".equals(key)) continue;
                builder.append(' ').append(key).append('=').append(entry.getValue());
            }
            com.github.catvod.crawler.DebugLogStore.add("voice", builder.toString());
        } catch (Throwable ignored) {
        }
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
            case "shot" -> shot(params);
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
        boolean self = "1".equals(params.get("self"));
        if (TextUtils.isEmpty(code)) return Nano.ok("unknown:none");
        if ("back".equals(code)) {
            App.post(() -> back(self));
            return Nano.ok("nav:back");
        }
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
            // 按下与抬起之间留一点间隔，和真遥控器的手感接近，个别界面要看按键时长
            dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode), self);
            if (hold) {
                for (int i = 1; i <= 3; i++) {
                    final int repeat = i;
                    App.post(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        dispatchKey(new android.view.KeyEvent(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_DOWN, keyCode, repeat), self);
                    }, 150L * i);
                }
                App.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode), self);
                }, 600);
            } else {
                App.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode), self);
                }, 30);
            }
        });
        return Nano.ok((hold ? "nav-hold:" : "nav:") + code);
    }

    /**
     * 投一次触摸。规则只有两条：
     * <p>① 是 Activity 自己的窗口（或者是"控制自己"）→ 直接 activity.dispatchTouchEvent；
     * <p>② 是对话框窗口 → 塞进它自己的 ViewRootImpl 输入管道（真手指走的就是这条路），
     * 反射失败再退回 decor.dispatchTouchEvent。不做"要不要转方向键""命中哪个窗口"之类判断。</p>
     */
    private static void deliverTouch(Activity activity, boolean self, android.view.View target, android.view.MotionEvent event) {
        if (target == null) {
            event.recycle();
            return;
        }
        int kind = event.getActionMasked();
        boolean log = kind == android.view.MotionEvent.ACTION_DOWN || kind == android.view.MotionEvent.ACTION_UP;
        if (self || target == activity.getWindow().getDecorView()) {
            boolean handled = activity.dispatchTouchEvent(event);
            if (log) com.github.catvod.crawler.DebugLogStore.add("voice", "触摸投递 Activity handled=" + handled);
            event.recycle();
            return;
        }
        if (postInput(target, event)) {
            if (log) com.github.catvod.crawler.DebugLogStore.add("voice", "触摸投递 " + target.getClass().getSimpleName() + " via ViewRootImpl");
            return;
        }
        boolean handled = target.dispatchTouchEvent(event);
        if (log) com.github.catvod.crawler.DebugLogStore.add("voice", "触摸投递 " + target.getClass().getSimpleName() + " via dispatch handled=" + handled);
        event.recycle();
    }

    /** 返回：先关掉最上层的对话框（DialogFragment），没有对话框再把 BACK 投给最上层窗口。 */
    private static void back() {
        back(false);
    }

    private static void back(boolean self) {
        Activity activity = App.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            if (activity instanceof androidx.fragment.app.FragmentActivity) {
                java.util.List<androidx.fragment.app.Fragment> fragments = ((androidx.fragment.app.FragmentActivity) activity).getSupportFragmentManager().getFragments();
                for (int i = fragments.size() - 1; i >= 0; i--) {
                    androidx.fragment.app.Fragment fragment = fragments.get(i);
                    if (fragment instanceof androidx.fragment.app.DialogFragment && fragment.isVisible()) {
                        ((androidx.fragment.app.DialogFragment) fragment).dismissAllowingStateLoss();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 遥控端的"返回"要和系统返回手势等价：直接走 Activity 的 OnBackPressedDispatcher，
        // 这样 onBackPressed、注册的返回回调、predictive back 全都按正常顺序跑；
        // 只 dispatch KEYCODE_BACK 在 leanback 上会被视频页/Dialog 吃掉，表现就是"返回键没反应"。
        // 上面盖着独立窗口（Dialog、下拉菜单、站点选择框这类）时，返回要投给那个窗口自己处理，
        // 直接走 Activity 的 OnBackPressedDispatcher 会把 Activity 结束掉，看起来就是"按返回退出程序"
        if (!self && targetView(activity) != activity.getWindow().getDecorView()) {
            dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK), self);
            dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK), self);
            return;
        }
        // 没有弹层时按手势返回处理：手机上 onBackPressed 才是等价物，光投 KEYCODE_BACK 很多页面不处理
        if (dispatchBack(activity)) return;
        dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK), self);
        dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK), self);
    }

    /** 和手势返回等价：优先 OnBackPressedDispatcher（androidx），失败再退回 onBackPressed。 */
    private static boolean dispatchBack(Activity activity) {
        try {
            if (activity instanceof androidx.activity.ComponentActivity) {
                ((androidx.activity.ComponentActivity) activity).getOnBackPressedDispatcher().onBackPressed();
                return true;
            }
            activity.onBackPressed();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前最上层的 DialogFragment（能在它自己的窗口里投事件）。 */
    private static androidx.fragment.app.DialogFragment topDialog(Activity activity) {
        try {
            if (!(activity instanceof androidx.fragment.app.FragmentActivity)) return null;
            java.util.List<androidx.fragment.app.Fragment> fragments = ((androidx.fragment.app.FragmentActivity) activity).getSupportFragmentManager().getFragments();
            for (int i = fragments.size() - 1; i >= 0; i--) {
                androidx.fragment.app.Fragment fragment = fragments.get(i);
                if (fragment instanceof androidx.fragment.app.DialogFragment && fragment.isVisible()) return (androidx.fragment.app.DialogFragment) fragment;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 把事件塞进最上层窗口自己的 ViewRootImpl 派发队列 —— 和真遥控器按键走的是同一条路：
     * 会经过 Window/DecorView/焦点查找/Activity.onKeyDown 全套，所以方向键在列表里能正常挪焦点。
     * 反射失败就返回 false，让调用方退回普通 dispatch。
     */
    private static boolean postInput(android.view.InputEvent event) {
        return postInput(targetView(App.activity()), event);
    }

    /**
     * 拿窗口的 ViewRootImpl：真遥控器的按键就是从这儿进系统的。
     * 两条路都试：View.getViewRootImpl()，以及 View.mAttachInfo.mViewRootImpl。
     */
    private static Object viewRoot(android.view.View view) {
        try {
            java.lang.reflect.Method method = android.view.View.class.getDeclaredMethod("getViewRootImpl");
            method.setAccessible(true);
            Object root = method.invoke(view);
            if (root != null) return root;
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field field = android.view.View.class.getDeclaredField("mAttachInfo");
            field.setAccessible(true);
            Object attach = field.get(view);
            if (attach == null) return null;
            java.lang.reflect.Field root = attach.getClass().getDeclaredField("mViewRootImpl");
            root.setAccessible(true);
            return root.get(attach);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean postInput(android.view.View view, android.view.InputEvent event) {
        android.view.View top = view;
        // 窗口没有焦点时投进去会被判为无效输入，这时退回普通 dispatch
        if (top == null || !top.hasWindowFocus()) return false;
        try {
            Object rootImpl = viewRoot(top);
            if (rootImpl == null) {
                com.github.catvod.crawler.DebugLogStore.add("voice", "按键投递：拿不到 ViewRootImpl");
                return false;
            }
            java.lang.reflect.Method dispatch = rootImpl.getClass().getMethod("dispatchInputEvent", android.view.InputEvent.class);
            dispatch.invoke(rootImpl, event);
            return true;
        } catch (Throwable error) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "按键投递失败：" + error.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 投一个按键。优先交给 Activity/Dialog 自己的 dispatchKeyEvent：
     * 真遥控器的按键就是这么进 Activity 的，这样 onKeyDown、Fragment 的按键处理、
     * 焦点查找（方向键移动）才会按正常顺序跑；只往某个 View 上 dispatch 会绕开这一层。
     */
    private static boolean dispatchKey(android.view.KeyEvent event) {
        return dispatchKey(event, false);
    }

    /**
     * @param self true 表示"手机控制自己"：这时候最上层窗口就是我们自己的遥控器面板，
     *             必须绕开它直接投给 Activity，否则按键和截图都在操作遥控器本身。
     */
    private static boolean dispatchKey(android.view.KeyEvent event, boolean self) {
        Activity activity = App.activity();
        if (activity == null) return false;
        if (self) return activity.dispatchKeyEvent(event);
        if (postInput(event)) return true;
        androidx.fragment.app.DialogFragment fragment = topDialog(activity);
        if (fragment != null) {
            android.app.Dialog dialog = fragment.getDialog();
            if (dialog != null && dialog.dispatchKeyEvent(event)) return true;
        }
        if (activity.dispatchKeyEvent(event)) return true;
        android.view.View top = targetView(activity);
        return top != null && top.dispatchKeyEvent(event);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    /**
     * 当前显示着的对话框窗口，按叠放顺序排列（后加的在上层）。
     *
     * <p>只用 FragmentManager 枚举，不反射 WindowManagerGlobal.mViews —— 反射那条路要自己判
     * 窗口尺寸/坐标是否对齐，判错了弹层会被整个过滤掉，触摸就落到下层 Activity 了。</p>
     */
    private static java.util.List<android.view.View> dialogDecors(Activity activity) {
        java.util.ArrayList<android.view.View> roots = new java.util.ArrayList<>();
        try {
            if (activity instanceof androidx.fragment.app.FragmentActivity support) {
                collectSupportDialogs(support.getSupportFragmentManager(), roots, 0);
            }
            collectPlatformDialogs(activity.getFragmentManager(), roots, 0);
        } catch (Throwable error) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "枚举对话框失败：" + error);
        }
        return roots;
    }

    /**
     * 递归往下找 DialogFragment。
     *
     * <p>站选择、搜索页里的站弹窗都是 {@code show(this)} 挂在 Fragment 自己的
     * childFragmentManager 上，只看 Activity 那一层会一个都枚举不到（日志表现就是"窗口=0"）。</p>
     */
    private static void collectSupportDialogs(androidx.fragment.app.FragmentManager manager, java.util.List<android.view.View> roots, int depth) {
        if (manager == null || depth > 4) return;
        for (androidx.fragment.app.Fragment fragment : manager.getFragments()) {
            if (fragment instanceof androidx.fragment.app.DialogFragment dialogFragment) {
                addDialogDecor(dialogFragment.getDialog(), roots);
            }
            collectSupportDialogs(fragment.getChildFragmentManager(), roots, depth + 1);
        }
    }

    private static void collectPlatformDialogs(android.app.FragmentManager manager, java.util.List<android.view.View> roots, int depth) {
        if (manager == null || depth > 4) return;
        for (android.app.Fragment fragment : manager.getFragments()) {
            if (fragment instanceof android.app.DialogFragment) {
                addDialogDecor(((android.app.DialogFragment) fragment).getDialog(), roots);
            }
            collectPlatformDialogs(fragment.getChildFragmentManager(), roots, depth + 1);
        }
    }

    private static void addDialogDecor(android.app.Dialog dialog, java.util.List<android.view.View> roots) {
        if (dialog == null || !dialog.isShowing() || dialog.getWindow() == null) return;
        android.view.View view = dialog.getWindow().getDecorView();
        if (isExcluded(view)) return;
        if (view.getWidth() > 0 && view.getHeight() > 0 && !roots.contains(view)) roots.add(view);
    }

    /**
     * 弹出式菜单（工具栏溢出菜单那种 PopupWindow）不是 Dialog，FragmentManager 枚举不到，
     * 只能从 WindowManagerGlobal.mViews 里挑出来。
     *
     * <p>这里**只认 PopupDecorView**，不做尺寸/坐标过滤 —— 以前那版按"必须完整落在 Activity
     * 窗口内"过滤，把正常弹层整个滤掉，才是"点了没反应"的根因。</p>
     */
    private static void addPopupRoots(java.util.List<android.view.View> roots) {
        int added = 0;
        try {
            Class<?> clazz = Class.forName("android.view.WindowManagerGlobal");
            Object global = clazz.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field field = clazz.getDeclaredField("mViews");
            field.setAccessible(true);
            Object value = field.get(global);
            if (value instanceof java.util.List) {
                for (Object item : (java.util.List<?>) value) {
                    if (!(item instanceof android.view.View)) continue;
                    android.view.View view = (android.view.View) item;
                    if (!view.getClass().getName().contains("PopupDecor")) continue;
                    if (!view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) continue;
                    if (isExcluded(view) || roots.contains(view)) continue;
                    roots.add(view);
                    added++;
                }
            }
        } catch (Throwable error) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "枚举弹出菜单失败：" + error);
            return;
        }
        if (added > 0) com.github.catvod.crawler.DebugLogStore.add("voice", "弹出菜单 " + added + " 个");
    }

    /** 参与截图和投递的全部窗口：对话框（含嵌套）+ 弹出菜单，按叠放顺序。 */
    private static java.util.List<android.view.View> windowTargets(Activity activity) {
        java.util.List<android.view.View> roots = dialogDecors(activity);
        addPopupRoots(roots);
        return roots;
    }

    /**
     * 触摸/按键投给谁：有对话框就投对话框，没有就投 Activity 自己的窗口。
     * 这是唯一口径，不再按坐标挑窗口。
     */
    private static android.view.View targetView(Activity activity) {
        if (activity == null) return null;
        java.util.List<android.view.View> targets = windowTargets(activity);
        if (!targets.isEmpty()) return targets.get(targets.size() - 1);
        return activity.getWindow().getDecorView();
    }

    /** 控制自己时，遥控器面板本身要排除掉，否则镜像里会出现无限套娃。 */
    private static java.lang.ref.WeakReference<android.view.View> excludedWindow;

    public static void excludeWindow(android.view.View view) {
        excludedWindow = view == null ? null : new java.lang.ref.WeakReference<>(view);
    }

    private static boolean isExcluded(android.view.View view) {
        android.view.View excluded = excludedWindow == null ? null : excludedWindow.get();
        return excluded != null && excluded == view;
    }

    /** 触摸板：把归一化坐标(0~1000)投成本地触摸事件，相当于用鼠标点界面。 */
    private static long touchDownTime;
    private Response touch(Map<String, String> params) {
        Activity activity = App.activity();
        if (activity == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        final float nx = parseFloat(params.get("x"));
        final float ny = parseFloat(params.get("y"));
        final int action = (int) parseFloat(params.get("a"));
        final boolean self = "1".equals(params.get("self"));
        // a=3 是"鼠标右键"：约定功能为返回
        if (action == 3) {
            App.post(() -> back());
            return Nano.ok("touch:3");
        }
        if (nx < 0 || ny < 0) return Nano.error(Response.Status.BAD_REQUEST, "missing xy");
        final String path = params.get("path");
        App.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            android.view.View base = activity.getWindow().getDecorView();
            if (base.getWidth() <= 0 || base.getHeight() <= 0) return;
            int[] baseLoc = new int[2];
            base.getLocationOnScreen(baseLoc);
            long now = android.os.SystemClock.uptimeMillis();
            if (action == 0) touchDownTime = now;
            if (touchDownTime == 0) touchDownTime = now;
            // 目标窗口：控制自己就投 Activity 自己；否则有对话框就投对话框，没有就投 Activity。
            android.view.View target = self ? base : targetView(activity);
            if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
                com.github.catvod.crawler.DebugLogStore.add("voice", "触摸丢弃：目标窗口尺寸为 0");
                return;
            }
            int[] location = new int[2];
            target.getLocationOnScreen(location);
            if (action == 0) {
                com.github.catvod.crawler.DebugLogStore.add("voice", "触摸按下 x=" + Math.round(nx) + " y=" + Math.round(ny)
                        + " 目标=" + target.getClass().getSimpleName() + " 目标尺寸=" + target.getWidth() + "x" + target.getHeight()
                        + " 坐标=" + Math.round(baseLoc[0] + nx * base.getWidth() / 1000f - location[0]) + "," + Math.round(baseLoc[1] + ny * base.getHeight() / 1000f - location[1]));
            }
            // 一批移动点：网络上一秒能发几次请求就这几次，中间的点全丢了，滑起来一卡一卡。
            // 这里一次请求带多个点，在本地按 8ms 间隔逐点补发，手势就连续了。
            if (action == 1 && path != null && !path.isEmpty()) {
                int index = 0;
                for (String pair : path.split(";")) {
                    String[] xy = pair.split(",");
                    if (xy.length < 2) continue;
                    float fx = parseFloat(xy[0]);
                    float fy = parseFloat(xy[1]);
                    float px = baseLoc[0] + fx * base.getWidth() / 1000f;
                    float py = baseLoc[1] + fy * base.getHeight() / 1000f;
                    android.view.MotionEvent event = android.view.MotionEvent.obtain(touchDownTime, now + index * 8L, android.view.MotionEvent.ACTION_MOVE, px - location[0], py - location[1], 0);
                    deliverTouch(activity, self, target, event);
                    index++;
                }
                return;
            }
            float px = baseLoc[0] + nx * base.getWidth() / 1000f;
            float py = baseLoc[1] + ny * base.getHeight() / 1000f;
            // a=5 是"取消当前这次按下"，保留兼容，避免误触发点击
            int type = action == 5 ? android.view.MotionEvent.ACTION_CANCEL : (action == 2 ? android.view.MotionEvent.ACTION_UP : (action == 0 ? android.view.MotionEvent.ACTION_DOWN : android.view.MotionEvent.ACTION_MOVE));
            boolean up = action == 2 || action == 5;
            // downTime 必须始终是按下那一刻，否则系统会把按下和抬起当成同一瞬间（列表就会"点一下再弹回"）
            android.view.MotionEvent event = android.view.MotionEvent.obtain(touchDownTime, now, type, px - location[0], py - location[1], 0);
            deliverTouch(activity, self, target, event);
            if (up) touchDownTime = 0;
        });
        return Nano.ok("touch:" + action);
    }

    /**
     * PixelCopy 已经把主窗口像素画满，这里只把弹层窗口（对话框、PopupWindow）叠上去。
     */
    private static void paintOverlays(android.graphics.Bitmap bitmap, int[] origin, android.view.View decor, java.util.List<android.view.View> roots) {
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        boolean dimmed = false;
        for (android.view.View root : roots) {
            if (root == null || root == decor || !root.isShown() || root.getWidth() <= 0 || root.getHeight() <= 0) continue;
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            if (!dimmed) {
                canvas.drawColor(0x66000000);
                dimmed = true;
            }
            canvas.save();
            canvas.translate(location[0] - origin[0], location[1] - origin[1]);
            canvas.clipRect(0, 0, root.getWidth(), root.getHeight());
            try {
                root.draw(canvas);
                com.github.catvod.crawler.DebugLogStore.add("voice", "叠加弹层 " + root.getClass().getSimpleName()
                        + " 尺寸=" + root.getWidth() + "x" + root.getHeight()
                        + " 位置=" + location[0] + "," + location[1] + " shown=" + root.isShown());
            } catch (Throwable error) {
                com.github.catvod.crawler.DebugLogStore.add("voice", "叠加弹层失败 " + root.getClass().getSimpleName() + "：" + error);
            }
            canvas.restore();
        }
    }

    /** 兜底：没有 PixelCopy 时 view.draw（主窗口 + 对话框）。 */
    private static void paintFallback(android.graphics.Bitmap bitmap, int[] origin, android.view.View decor, java.util.List<android.view.View> roots) {
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        if (decor != null && decor.getWidth() > 0 && decor.getHeight() > 0) {
            canvas.save();
            canvas.translate(0, 0);
            canvas.clipRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            decor.draw(canvas);
            canvas.restore();
            com.github.catvod.crawler.DebugLogStore.add("voice", "镜像兜底：主窗口 view.draw");
        }
        for (android.view.View root : roots) {
            if (root == null || !root.isShown() || root.getWidth() <= 0 || root.getHeight() <= 0) continue;
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            canvas.drawColor(0x66000000);
            canvas.save();
            canvas.translate(location[0] - origin[0], location[1] - origin[1]);
            canvas.clipRect(0, 0, root.getWidth(), root.getHeight());
            root.draw(canvas);
            canvas.restore();
        }
    }

    /** 抓一帧当前界面（JPEG），手机上把这张画面当触摸板底图用。 */
    private Response shot(Map<String, String> params) {
        final boolean self = "1".equals(params.get("self"));
        Activity activity = App.activity();
        if (activity == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final android.graphics.Bitmap[] holder = new android.graphics.Bitmap[1];
        App.post(() -> {
            android.view.View decor = activity.getWindow().getDecorView();
            int width = decor.getWidth();
            int height = decor.getHeight();
            if (width <= 0 || height <= 0) {
                latch.countDown();
                return;
            }
            int[] origin = new int[2];
            decor.getLocationOnScreen(origin);
            android.graphics.Bitmap bitmap;
            try {
                bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
            } catch (Throwable error) {
                latch.countDown();
                return;
            }
            java.util.List<android.view.View> roots = windowTargets(activity);
            try {
                StringBuilder builder = new StringBuilder("镜像抓帧 ").append(width).append('x').append(height).append(" 窗口=").append(roots.size());
                for (android.view.View root : roots) {
                    if (root == null) continue;
                    builder.append("｜").append(root.getClass().getSimpleName()).append(' ').append(root.getWidth()).append('x').append(root.getHeight());
                }
                com.github.catvod.crawler.DebugLogStore.add("voice", builder.toString());
            } catch (Throwable ignored) {
            }
            // WebView 是硬件渲染的，用 view.draw(canvas) 画出来会是空白、而且被缩到左上角，
            // 所以主窗口走 PixelCopy（直接拿窗口合成后的像素），弹层再用 view.draw 补在上面。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    android.view.PixelCopy.request(activity.getWindow(), bitmap, result -> {
                        try {
                            if (result == android.view.PixelCopy.SUCCESS) paintOverlays(bitmap, origin, decor, roots);
                            else paintFallback(bitmap, origin, decor, roots);
                            if (result != android.view.PixelCopy.SUCCESS) com.github.catvod.crawler.DebugLogStore.add("voice", "PixelCopy 失败 code=" + result);
                        } catch (Throwable error) {
                            com.github.catvod.crawler.DebugLogStore.add("voice", "叠加弹层异常：" + error);
                        } finally {
                            holder[0] = bitmap;
                            latch.countDown();
                        }
                    }, new android.os.Handler(android.os.Looper.getMainLooper()));
                    return;
                } catch (Throwable ignored) {
                }
            }
            try {
                paintFallback(bitmap, origin, decor, roots);
            } catch (Throwable ignored) {
            }
            holder[0] = bitmap;
            latch.countDown();
        });
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        android.graphics.Bitmap frame = holder[0];
        if (frame == null) return Nano.error(Response.Status.INTERNAL_ERROR, "no-frame");
        int max = 720;
        int width = frame.getWidth();
        int height = frame.getHeight();
        float scale = width > max ? max / (float) width : 1f;
        int outWidth = Math.max(1, Math.round(width * scale));
        int outHeight = Math.max(1, Math.round(height * scale));
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(frame, outWidth, outHeight, true);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, output);
        if (small != frame) small.recycle();
        frame.recycle();
        byte[] bytes = output.toByteArray();
        return Nano.jpeg(bytes, outWidth, outHeight);
    }

    /** 回包标识：端类型 + 当前屏幕方向，手机端据此排布触摸板（竖屏左右两半、横屏上下两半）。 */
    private static String identity() {
        String type = com.fongmi.android.tv.utils.Util.isMobile() ? "mobile" : "tv";
        String orientation = "landscape";
        Activity activity = App.activity();
        if (activity != null) {
            orientation = activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT ? "portrait" : "landscape";
        }
        return "webhtv-voice:" + type + ":" + orientation;
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

    private static int keyCode(String code) {
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
