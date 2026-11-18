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
     * 把事件投给目标窗口。Activity 自己的窗口直接走 dispatchTouchEvent（这种要自己回收）；
     * 别的窗口走 ViewRootImpl 输入管道（系统分发完会回收，不能再 recycle）。
     */
    private static void deliverTouch(Activity activity, boolean self, android.view.View decor, android.view.MotionEvent event) {
        boolean activityWindow = (decor == activity.getWindow().getDecorView());
        if (self || activityWindow) {
            boolean handled = activity.dispatchTouchEvent(event);
            event.recycle();
            if (handled || self) return;
            return;
        }
        if (postInput(decor, event)) return;
        decor.dispatchTouchEvent(event);
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
        if (!self && topView() != null && topView() != activity.getWindow().getDecorView()) {
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
        return postInput(topView(), event);
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

    /** 屏幕坐标点在哪个窗口里就投给谁（弹窗、抽屉菜单也是独立窗口），从上往下找。 */
    private static android.view.View hitView(float screenX, float screenY) {
        int x = (int) screenX;
        int y = (int) screenY;
        java.util.List<android.view.View> roots = windowRoots(false);
        for (int i = roots.size() - 1; i >= 0; i--) {
            android.view.View root = roots.get(i);
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            if (x >= location[0] && x < location[0] + root.getWidth() && y >= location[1] && y < location[1] + root.getHeight()) return root;
        }
        return topView();
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
        android.view.View top = topView();
        return top != null && top.dispatchKeyEvent(event);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    /** 抓帧范围写进 webhtv 调试日志（日志页/AI 诊断能看到，不用连 adb）。 */
    private static void reportRoots(java.util.List<android.view.View> roots) {
        try {
            int dialogs = Math.max(0, roots.size() - 1);
            com.github.catvod.crawler.DebugLogStore.add("voice", "镜像只用 Activity 窗口，弹层 " + dialogs + " 个");
        } catch (Throwable ignored) {
        }
    }

    /** 把正在显示的对话框窗口也加进来（DialogFragment 的窗口不需要反射就能拿到）。 */
    private static void addDialogRoots(Activity activity, java.util.List<android.view.View> roots) {
        try {
            if (activity instanceof androidx.fragment.app.FragmentActivity support) {
                for (androidx.fragment.app.Fragment fragment : support.getSupportFragmentManager().getFragments()) {
                    if (!(fragment instanceof androidx.fragment.app.DialogFragment dialogFragment)) continue;
                    android.app.Dialog dialog = dialogFragment.getDialog();
                    if (dialog == null || !dialog.isShowing() || dialog.getWindow() == null) continue;
                    android.view.View view = dialog.getWindow().getDecorView();
                    if (view.getWidth() > 0 && view.getHeight() > 0 && !roots.contains(view)) roots.add(view);
                }
            }
            for (android.app.Fragment fragment : activity.getFragmentManager().getFragments()) {
                if (!(fragment instanceof android.app.DialogFragment dialogFragment)) continue;
                android.app.Dialog dialog = dialogFragment.getDialog();
                if (dialog == null || !dialog.isShowing() || dialog.getWindow() == null) continue;
                android.view.View view = dialog.getWindow().getDecorView();
                if (view.getWidth() > 0 && view.getHeight() > 0 && !roots.contains(view)) roots.add(view);
            }
        } catch (Throwable ignored) {
        }
    }

    private static android.view.View topView() {
        java.util.List<android.view.View> roots = windowRoots(false);
        return roots.isEmpty() ? null : roots.get(roots.size() - 1);
    }

    /**
     * 参与截图 / 触摸 / 返回判定的窗口列表（按 z 序，越靠后越在上层）。
     *
     * <p>窗口来源有两路：反射 {@code WindowManagerGlobal.mViews} 拿全量窗口，拿不到时退回
     * Activity 窗口 + DialogFragment 的窗口。**只收完整落在 Activity 窗口内的窗口**——
     * 网页页面那种尺寸/坐标跟 Activity 对不上的额外窗口，一旦叠上来，画面和触点会一起偏移。</p>
     */
    private static java.util.List<android.view.View> windowRoots() {
        return windowRoots(true);
    }

    /** @param capture true=截图用（只收完整在屏内的窗口，避免拼错位）；false=触摸/按键用（要能命中任意弹层）。 */
    /** 控制自己时，遥控器面板本身要排除掉，否则镜像里会出现无限套娃。 */
    private static java.lang.ref.WeakReference<android.view.View> excludedWindow;

    public static void excludeWindow(android.view.View view) {
        excludedWindow = view == null ? null : new java.lang.ref.WeakReference<>(view);
    }

    private static boolean isExcluded(android.view.View view) {
        android.view.View excluded = excludedWindow == null ? null : excludedWindow.get();
        return excluded != null && excluded == view;
    }

    private static java.util.List<android.view.View> windowRoots(boolean capture) {
        java.util.ArrayList<android.view.View> roots = new java.util.ArrayList<>();
        Activity activity = App.activity();
        if (activity == null) return roots;
        android.view.View decor = activity.getWindow().getDecorView();
        int decorWidth = decor.getWidth();
        int decorHeight = decor.getHeight();
        if (decorWidth <= 0 || decorHeight <= 0) {
            roots.add(decor);
            return roots;
        }
        int[] origin = new int[2];
        decor.getLocationOnScreen(origin);
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
                    if (!view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) continue;
                    if (isExcluded(view)) continue;
                    int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    int tolerance = 8;
                    if (capture) {
                        if (location[0] < origin[0] - tolerance || location[1] < origin[1] - tolerance) continue;
                        if (location[0] + view.getWidth() > origin[0] + decorWidth + tolerance) continue;
                        if (location[1] + view.getHeight() > origin[1] + decorHeight + tolerance) continue;
                    }
                    roots.add(view);
                }
            }
        } catch (Throwable ignored) {
        }
        if (!roots.contains(decor)) roots.add(0, decor);
        // 反射被"非 SDK 接口限制"挡掉时，至少把 DialogFragment 的窗口补上
        addDialogRoots(activity, roots);
        reportRoots(roots);
        return roots;
    }

    /** 触摸板：把归一化坐标(0~1000)投成本地触摸事件，相当于用鼠标点界面。 */
    private static long touchDownTime;
    /** 这一下手势是不是落在焦点式视频列表上（是则拖动改成方向键步进）。 */
    private static boolean dragFocus;

    private static boolean dragStepped;
    private static int dragAnchorX;
    private static int dragAnchorY;
    private static long dragKeyAt;

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
            android.view.View decor = self ? base : hitView(baseLoc[0] + nx * base.getWidth() / 1000f, baseLoc[1] + ny * base.getHeight() / 1000f);
            if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) return;
            int[] location = new int[2];
            decor.getLocationOnScreen(location);

            // 按下时判断落点是不是"焦点式视频列表"（leanback 首页 / Vod 列表 / 收藏 / 历史）。
            // 这种列表触摸拖动会被"点中卡片→居中"立刻弹回原位，只能靠方向键移动焦点；
            // 普通对话框、可滚动页面照常走真实触摸。
            if (action == 0) {
                dragFocus = isFocusList(deepView(decor, baseLoc[0] + nx * base.getWidth() / 1000f - location[0], baseLoc[1] + ny * base.getHeight() / 1000f - location[1]));
                dragAnchorX = Math.round(nx);
                dragAnchorY = Math.round(ny);
                dragStepped = false;
                dragKeyAt = 0;
            }

            if (action == 1 && dragFocus) {
                if (path != null && !path.isEmpty()) {
                    for (String pair : path.split(";")) {
                        String[] xy = pair.split(",");
                        if (xy.length < 2) continue;
                        stepDrag(activity, location, base, baseLoc, parseFloat(xy[0]), parseFloat(xy[1]), self);
                    }
                } else {
                    stepDrag(activity, location, base, baseLoc, nx, ny, self);
                }
                return;
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
                    deliverTouch(activity, self, decor, event);
                    index++;
                }
                return;
            }
            float px = baseLoc[0] + nx * base.getWidth() / 1000f;
            float py = baseLoc[1] + ny * base.getHeight() / 1000f;
            // a=5 是"取消当前这次按下"（旧客户端从单指切到双指时用过，保留兼容，避免误触发点击）
            int type = action == 5 ? android.view.MotionEvent.ACTION_CANCEL : (action == 2 ? android.view.MotionEvent.ACTION_UP : (action == 0 ? android.view.MotionEvent.ACTION_DOWN : android.view.MotionEvent.ACTION_MOVE));
            boolean up = action == 2 || action == 5;
            if (up && dragFocus && dragStepped) {
                // 已经转成方向键走过了，就别再补一次"抬起"，否则等于又点了一下卡片
                touchDownTime = 0;
                dragStepped = false;
                dragFocus = false;
                return;
            }
            // downTime 必须始终是按下那一刻：之前 UP 把 downTime 写成 now，等于告诉系统
            // "按下和抬起同一瞬间"，列表会当成一次点击后再回弹
            android.view.MotionEvent event = android.view.MotionEvent.obtain(touchDownTime, now, type, px - location[0], py - location[1], 0);
            deliverTouch(activity, self, decor, event);
            if (up) {
                touchDownTime = 0;
                dragFocus = false;
                dragStepped = false;
            }
        });
        return Nano.ok("touch:" + action);
    }

    /**
     * 焦点列表上的"滑动"：位移超过一格就发一次方向键。
     *
     * <p>方向是按用户习惯反过来的：手指往上滑是想看下面的内容，焦点就要往下走；
     * 手指往左滑是想看右边的分类，焦点/选中项就要往右走。</p>
     */
    private static void stepDrag(Activity activity, int[] location, android.view.View base, int[] baseLoc, float fx, float fy, boolean self) {
        int step = 110; // 归一化坐标的一格（约画面 1/9）
        int dx = Math.round(fx) - dragAnchorX;
        int dy = Math.round(fy) - dragAnchorY;
        if (Math.abs(dx) < step && Math.abs(dy) < step) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - dragKeyAt < 140) return; // 限速：一秒最多走 7 格
        dragKeyAt = now;
        String key;
        if (Math.abs(dx) >= Math.abs(dy)) key = dx > 0 ? "left" : "right";
        else key = dy > 0 ? "up" : "down";
        int keyCode = keyCode(key);
        if (keyCode <= 0) return;
        if (!dragStepped) {
            dragStepped = true;
            // 先把这次按下取消掉，免得卡片被点中后被居中逻辑拉回去
            android.view.MotionEvent cancel = android.view.MotionEvent.obtain(touchDownTime, now, android.view.MotionEvent.ACTION_CANCEL, dragAnchorX * base.getWidth() / 1000f - location[0], dragAnchorY * base.getHeight() / 1000f - location[1], 0);
            deliverTouch(activity, self, base, cancel);
            // leanback 的列表要等焦点碰到边缘才真正滚动，所以第一下先把焦点冲到边缘（最多 6 格）
            for (int i = 0; i < 6; i++) {
                if (!press(keyCode, base, key, self)) break;
            }
        } else if (!press(keyCode, base, key, self)) {
            moveFocus(base, key);
        }
        dragAnchorX = Math.round(fx);
        dragAnchorY = Math.round(fy);
    }

    /** 发一次方向键；返回是否真的送进了系统（送不进就靠 focusSearch 兜底）。 */
    private static boolean press(int keyCode, android.view.View base, String key, boolean self) {
        boolean sent = dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode), self);
        dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode), self);
        if (!sent) return moveFocus(base, key);
        return true;
    }

    /** 兜底：没有按键通道时，直接拿当前焦点找指定方向的下一个焦点。 */
    private static boolean moveFocus(android.view.View root, String key) {
        try {
            android.view.View focus = root.findFocus();
            if (focus == null) return false;
            int direction = switch (key) {
                case "up" -> android.view.View.FOCUS_UP;
                case "down" -> android.view.View.FOCUS_DOWN;
                case "left" -> android.view.View.FOCUS_LEFT;
                default -> android.view.View.FOCUS_RIGHT;
            };
            android.view.View next = focus.focusSearch(direction);
            if (next == null || next == focus) return false;
            return next.requestFocus(direction);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 落点处最深的那个 view（按各层 scroll 偏移逐级换算）。 */
    private static android.view.View deepView(android.view.View root, float x, float y) {
        android.view.View current = root;
        while (current instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) current;
            android.view.View found = null;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View child = group.getChildAt(i);
                if (child.getVisibility() != android.view.View.VISIBLE) continue;
                float cx = x + group.getScrollX() - child.getLeft();
                float cy = y + group.getScrollY() - child.getTop();
                if (cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight()) {
                    found = child;
                    x = cx;
                    y = cy;
                    break;
                }
            }
            if (found == null) break;
            current = found;
        }
        return current;
    }

    /**
     * 只有"焦点式视频列表"才需要方向键步进：leanback 的 Grid 容器，
     * 或者多列的 RecyclerView（首页/Vod/收藏/历史那种海报网格）。
     * 设置页、站点选择这类单列 RecyclerView 照常滚动，不再抢焦点。
     */
    private static boolean isFocusList(android.view.View view) {
        for (android.view.View current = view; current != null; ) {
            String name = current.getClass().getName();
            // 网页、普通滚动容器：照常滚动，绝不改成方向键
            if (current instanceof android.webkit.WebView || name.contains("WebView")) return false;
            if (current instanceof android.widget.ScrollView || current instanceof android.widget.HorizontalScrollView) return false;
            // 只有 leanback 的网格（首页横向行、Vod 的 leanback 网格）才需要方向键步进：
            // 这种容器触摸拖动会被"选中卡片→回弹"整段吃掉。普通 RecyclerView（历史、收藏、
            // 搜索结果、对话框列表）用原生触摸滚得好好的，绝不能抢它的触摸。
            if (name.contains("BaseGridView") || name.contains("HorizontalGridView") || name.contains("VerticalGridView")) return true;
            if (current instanceof androidx.recyclerview.widget.RecyclerView) return false;
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof android.view.View ? (android.view.View) parent : null;
        }
        return false;
    }

    /**
     * 把文本"打"进目标端当前焦点所在的输入框（相当于远程输入法）：
     * 先找焦点控件，是 EditText 就直接插入光标处；
     * 否则取它的 InputConnection 用 commitText 提交（WebView 的内嵌输入框走这条）；
     * 两者都不行时兜底：写剪贴板 + 发粘贴键。
     */
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
            root.draw(canvas);
            canvas.restore();
        }
    }

    /** 兜底：没有 PixelCopy 时逐个窗口 view.draw。 */
    private static void paintFallback(android.graphics.Bitmap bitmap, int[] origin, java.util.List<android.view.View> roots) {
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        boolean first = true;
        for (android.view.View root : roots) {
            if (root == null || !root.isShown() || root.getWidth() <= 0 || root.getHeight() <= 0) continue;
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            if (!first) canvas.drawColor(0x66000000);
            first = false;
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
            java.util.List<android.view.View> roots = windowRoots(true);
            // WebView 是硬件渲染的，用 view.draw(canvas) 画出来会是空白、而且被缩到左上角，
            // 所以主窗口走 PixelCopy（直接拿窗口合成后的像素），弹层再用 view.draw 补在上面。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    android.view.PixelCopy.request(activity.getWindow(), bitmap, result -> {
                        try {
                            if (result == android.view.PixelCopy.SUCCESS) paintOverlays(bitmap, origin, decor, roots);
                            else paintFallback(bitmap, origin, roots);
                        } catch (Throwable ignored) {
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
                paintFallback(bitmap, origin, roots);
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
