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
     * 投一次触摸。规则只有两条：
     * <p>① 是 Activity 自己的窗口（或者是"控制自己"）→ 直接 activity.dispatchTouchEvent；
     * <p>② 是对话框窗口 → 塞进它自己的 ViewRootImpl 输入管道（真手指走的就是这条路），
     * 反射失败再退回 decor.dispatchTouchEvent。不做"要不要转方向键""命中哪个窗口"之类判断。</p>
     */
    private static void deliverTouch(Activity activity, android.view.View target, android.view.MotionEvent event) {
        if (target == null) {
            event.recycle();
            return;
        }
        int kind = event.getActionMasked();
        boolean log = kind == android.view.MotionEvent.ACTION_DOWN || kind == android.view.MotionEvent.ACTION_UP;
        if (target == activity.getWindow().getDecorView()) {
            boolean handled = activity.dispatchTouchEvent(event);
            if (log && !handled) com.github.catvod.crawler.DebugLogStore.add("voice", "触摸投递 Activity 未消费");
            event.recycle();
            return;
        }
        // 对话框/弹出菜单是独立窗口：直接 dispatch 进它的 view 树，这条路不看事件来源，真能点到控件。
        // ViewRootImpl 输入管道只作兜底（合成事件的 source 不对时会被它当无效输入丢掉）。
        boolean handled = false;
        try {
            handled = target.dispatchTouchEvent(event);
        } catch (Throwable error) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "触摸直接投递异常：" + error);
        }
        if (log && !handled) com.github.catvod.crawler.DebugLogStore.add("voice", "触摸直接投递未消费 " + target.getClass().getSimpleName());
        if (!handled) {
            android.view.MotionEvent copy = android.view.MotionEvent.obtain(event);
            if (postInput(target, copy)) {
                if (log) com.github.catvod.crawler.DebugLogStore.add("voice", "触摸投递 " + target.getClass().getSimpleName() + " via ViewRootImpl(兜底)");
                event.recycle();
                return;
            }
            copy.recycle();
        }
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
        android.view.View above = targetView(activity);
        if (!self && above != activity.getWindow().getDecorView()) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "返回投给弹层窗口 " + above.getClass().getSimpleName());
            dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK), self);
            dispatchKey(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK), self);
            return;   // 上面还有弹层：绝不走 OnBackPressedDispatcher，否则整个 Activity 被结束 = "按返回退出程序"
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
        if (top == null || !top.hasWindowFocus()) {
            if (event instanceof android.view.KeyEvent) com.github.catvod.crawler.DebugLogStore.add("voice", "按键投递失败：窗口没有焦点");
            return false;
        }
        try {
            Object rootImpl = viewRoot(top);
            if (rootImpl == null) {
                com.github.catvod.crawler.DebugLogStore.add("voice", "按键投递：拿不到 ViewRootImpl");
                return false;
            }
            java.lang.reflect.Method dispatch = rootImpl.getClass().getMethod("dispatchInputEvent", android.view.InputEvent.class);
            dispatch.invoke(rootImpl, event);
            // 成功投递不在这里记账：调用方 dispatchKey 每个按键只记一行，否则一次按键要出两三行
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
        android.view.View target = targetView(activity);
        android.view.View decor = activity.getWindow().getDecorView();
        // 盖着独立窗口（对话框、折叠菜单、电视端的"选择站点"）时，按键必须先给那个窗口，
        // 而且**只给它**：真遥控器的按键就是交给"有焦点的那个窗口"的。
        // 以前这里先试 activity.dispatchKeyEvent，下层页面只要说"我处理了"就把键吃掉了，
        // 表现就是"对话框盖在上面，方向键却在动下面的列表"。
        if (target != null && target != decor) {
            boolean posted = postInput(target, event);
            if (posted || target.dispatchKeyEvent(event)) {
                logKey(target, event);
                return true;
            }
            // 窗口还没拿到焦点时 postInput 会被拒，这里再走一次 Dialog 自己的 dispatch
            androidx.fragment.app.DialogFragment fragment = topDialog(activity);
            if (fragment != null) {
                android.app.Dialog dialog = fragment.getDialog();
                if (dialog != null && dialog.dispatchKeyEvent(event)) return true;
            }
            com.github.catvod.crawler.DebugLogStore.add("voice", "按键没投进弹层窗口，改投 Activity");
        }
        // 没有弹层（也就是首页/Vod/历史/收藏这些页面本身）：按键必须走 Activity 窗口自己的
        // ViewRootImpl 输入管道——真遥控器走的就是这条路。方向键的"焦点移动"是框架在输入管道里
        // 做 focusSearch 找下一个焦点控件完成的，activity.dispatchKeyEvent 只到 View 层级，
        // 到不了那一步：控件自己实现方向键（比如列表）还能动，其余场景完全没反应。
        // beta 版按键能用，正是因为它是 postInput(topView())（ViewRootImpl）而不是 dispatchKeyEvent。
        android.view.View root = target != null ? target : decor;
        boolean posted = postInput(root, event);
        if (posted) {
            logKey(root, event);
            return true;
        }
        if (activity.dispatchKeyEvent(event)) {
            logKey(activity.getWindow().getDecorView(), event);
            return true;
        }
        boolean handled = target != null && target != decor && target.dispatchKeyEvent(event);
        com.github.catvod.crawler.DebugLogStore.add("voice", "按键未消费 " + keyName(event) + " handled=" + handled);
        return handled;
    }

    /** 一个按键只记一行，且只记按下：按下与抬起都记会让日志量翻倍。 */
    private static void logKey(android.view.View target, android.view.KeyEvent event) {
        if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return;
        log("按键 " + keyName(event) + " → " + (target == null ? "?" : target.getClass().getSimpleName()));
    }

    /** 日志用：按键名。 */
    private static String keyName(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        String name = android.view.KeyEvent.keyCodeToString(code);
        return (event.getAction() == android.view.KeyEvent.ACTION_DOWN ? "down " : "up ") + name;
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
                for (androidx.fragment.app.Fragment fragment : support.getSupportFragmentManager().getFragments()) {
                    if (!(fragment instanceof androidx.fragment.app.DialogFragment dialogFragment)) continue;
                    android.app.Dialog dialog = dialogFragment.getDialog();
                    if (dialog == null || !dialog.isShowing() || dialog.getWindow() == null) continue;
                    android.view.View view = dialog.getWindow().getDecorView();
                    if (isExcluded(view)) continue;
                    if (view.getWidth() > 0 && view.getHeight() > 0 && !roots.contains(view)) roots.add(view);
                }
            }
            for (android.app.Fragment fragment : activity.getFragmentManager().getFragments()) {
                if (!(fragment instanceof android.app.DialogFragment)) continue;
                android.app.Dialog dialog = ((android.app.DialogFragment) fragment).getDialog();
                if (dialog == null || !dialog.isShowing() || dialog.getWindow() == null) continue;
                android.view.View view = dialog.getWindow().getDecorView();
                if (isExcluded(view)) continue;
                if (view.getWidth() > 0 && view.getHeight() > 0 && !roots.contains(view)) roots.add(view);
            }
        } catch (Throwable error) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "枚举对话框失败：" + error);
        }
        return roots;
    }

    /**
     * 工具栏溢出菜单这类 PopupWindow 不是 Dialog，FragmentManager 枚举不到，只能从
     * WindowManagerGlobal.mViews 里挑出来。**只认 PopupDecorView**，不做尺寸/坐标过滤
     * （以前按"必须完整落在 Activity 窗口内"过滤，把正常弹层整个滤掉，才是点了没反应的根因）。
     */
    private static void addPopupRoots(java.util.List<android.view.View> roots) {
        // 只有移动端需要它（工具栏溢出菜单是 PopupWindow）。leanback 上这层不能开：
        // 电视端任何 PopupDecorView 一旦被当成投递目标，对话框的触摸和按键就全被它接走了。
        if (!com.fongmi.android.tv.utils.Util.isMobile()) return;
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
    }

    /**
     * **只用于截图**的窗口表，按叠放顺序（越靠后越在上层）。
     *
     * <p>主路是反射 {@code WindowManagerGlobal.mViews}：它能拿到所有窗口，包括那些
     * 不走当前 Activity 的 FragmentManager、也不是 PopupWindow 的对话框窗口——
     * 电视端"选择站点"就是这种，只靠 FragmentManager 会一个都枚举不到（日志里窗口=0）。
     * 这里**不做任何尺寸/坐标过滤**，过滤会把正常弹层整个剔掉。</p>
     */
    private static java.util.List<android.view.View> displayRoots(Activity activity) {
        java.util.ArrayList<android.view.View> roots = new java.util.ArrayList<>();
        java.util.Map<android.view.View, String> extras = new java.util.HashMap<>();
        if (activity == null) return roots;
        android.view.View decor = activity.getWindow().getDecorView();
        try {
            Class<?> clazz = Class.forName("android.view.WindowManagerGlobal");
            Object global = clazz.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field field = clazz.getDeclaredField("mViews");
            field.setAccessible(true);
            Object value = field.get(global);
            java.util.List<?> windowParams = null;
            try {
                java.lang.reflect.Field paramsField = clazz.getDeclaredField("mParams");
                paramsField.setAccessible(true);
                Object params = paramsField.get(global);
                if (params instanceof java.util.List) windowParams = (java.util.List<?>) params;
            } catch (Throwable ignored) {
            }
            if (value instanceof java.util.List) {
                int index = 0;
                for (Object item : (java.util.List<?>) value) {
                    int at = index++;
                    if (!(item instanceof android.view.View)) continue;
                    android.view.View view = (android.view.View) item;
                    if (!view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) continue;
                    if (isExcluded(view) || roots.contains(view)) continue;
                    extras.put(view, describeWindow(windowParams, at));
                    roots.add(view);
                }
            }
        } catch (Throwable error) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "反射窗口表失败：" + error);
        }
        // 反射被拦时兜底：DialogFragment 的窗口 + 移动端的弹出菜单
        if (roots.isEmpty()) roots.addAll(dialogDecors(activity));
        addPopupRoots(roots);
        // 只画属于当前 Activity 的窗口。退出某个页面时，它的窗口会继续留在窗口表里一段时间
        // （实测返回首页那次滞留了约 10 秒），画上去就是一张整屏旧页面盖在新页面上——电视早就切好了，
        // 镜像却一直"卡"着旧画面，直到那个窗口被销毁才恢复。
        java.util.List<String> skipped = new java.util.ArrayList<>();
        java.util.Iterator<android.view.View> iterator = roots.iterator();
        while (iterator.hasNext()) {
            android.view.View root = iterator.next();
            if (root == decor) continue;
            Activity owner = ownerActivity(root.getContext());
            if (owner == activity) continue;
            // 归属不明（Context 解析不出 Activity）的窗口也一律不叠：实测返回首页时有一张
            // 全屏 DecorView 就是这么压在新页面上面约 10 秒的，镜像冻在旧画面上直到它消失。
            String name = owner == null ? "归属不明" : owner.getClass().getSimpleName();
            skipped.add(name + " " + root.getWidth() + "x" + root.getHeight() + extras.getOrDefault(root, ""));
            iterator.remove();
        }
        if (!roots.contains(decor)) roots.add(0, decor);
        logRootsChange(activity, decor, roots, skipped, extras);
        return roots;
    }

    /** 上一次的窗口表指纹，只在窗口集合/归属变化时记一行，正常 3 帧/秒的抓帧不写日志。 */
    private static String rootSignature;

    /**
     * 窗口表变化才记一行：哪张窗口在、尺寸位置、属于哪个 Activity、跳过了谁。
     * 镜像"卡住"这类问题只看这一个字段就能判断是基准页没换、还是被别的窗口盖住。
     */
    private static void logRootsChange(Activity activity, android.view.View decor, java.util.List<android.view.View> roots, java.util.List<String> skipped, java.util.Map<android.view.View, String> extras) {
        StringBuilder readable = new StringBuilder();
        StringBuilder signature = new StringBuilder(activity.getClass().getSimpleName());
        for (android.view.View root : roots) {
            Activity owner = root == decor ? activity : ownerActivity(root.getContext());
            String name = owner == null ? "?" : owner.getClass().getSimpleName();
            readable.append(readable.length() == 0 ? "" : "｜").append(root.getClass().getSimpleName())
                    .append(' ').append(root.getWidth()).append('x').append(root.getHeight()).append('|').append(name);
            signature.append('#').append(root.getClass().getSimpleName()).append(root.getWidth()).append('x')
                    .append(root.getHeight()).append('|').append(name);
        }
        if (!skipped.isEmpty()) {
            readable.append("｜跳过 ").append(TextUtils.join("、", skipped));
            signature.append('#').append(TextUtils.join("、", skipped));
        }
        if (signature.toString().equals(rootSignature)) return;
        rootSignature = signature.toString();
        com.github.catvod.crawler.DebugLogStore.add("voice", "镜像窗口 " + roots.size() + " 个｜" + readable);
    }

    /** 窗口类型/标题：归属不明时靠它认人（type=2 是对话框，1/1BBC 之类是 Activity 窗口）。 */
    private static String describeWindow(java.util.List<?> params, int index) {
        if (params == null || index < 0 || index >= params.size()) return "";
        Object item = params.get(index);
        if (!(item instanceof android.view.WindowManager.LayoutParams)) return "";
        android.view.WindowManager.LayoutParams lp = (android.view.WindowManager.LayoutParams) item;
        try {
            return " type=" + lp.type + " title=" + lp.getTitle();
        } catch (Throwable error) {
            return " type=" + lp.type;
        }
    }

    /**
     * 这个窗口属于哪个 Activity。对话框也是用 Activity 当 Context 创建的，所以弹层照样能取到；
     * 取不到（Application Context 之类）返回 null，按"不拦"处理。
     */
    private static Activity ownerActivity(android.content.Context context) {
        for (int i = 0; i < 8 && context instanceof android.content.ContextWrapper; i++) {
            if (context instanceof Activity) return (Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    /** 投递目标：按叠放顺序取最上层的那个对话框/弹出菜单，没有就投 Activity。 */
    private static android.view.View targetView(Activity activity) {
        if (activity == null) return null;
        java.util.List<android.view.View> targets = deliveryRoots(activity);
        if (!targets.isEmpty()) return targets.get(targets.size() - 1);
        return activity.getWindow().getDecorView();
    }

    /** 参与**事件投递**的窗口：FragmentManager 里的对话框 + 裸 Dialog 窗口 + 移动端弹出菜单。 */
    private static java.util.List<android.view.View> deliveryRoots(Activity activity) {
        java.util.List<android.view.View> roots = dialogDecors(activity);
        addAppDialogWindows(activity, roots);
        addPopupRoots(roots);
        return roots;
    }

    /**
     * 把"裸 Dialog"的窗口补进来。
     *
     * <p>有的对话框是 {@code new Dialog(activity).show()}，**从不进任何 FragmentManager**，
     * 所以 {@link #dialogDecors} 一个都枚举不到——电视端"选择站点"就是这种：镜像里显示得出来
     * （截图走窗口表），但按键/触摸全落到下层 Activity，表现就是"点不动、方向键在动下层的列表"。</p>
     *
     * <p>这里只按 Window 类型挑 {@code TYPE_APPLICATION}（真正的对话框窗口），
     * Activity 自己的窗口（TYPE_BASE_APPLICATION）、PopupWindow（TYPE_APPLICATION_PANEL/SUB_PANEL）、
     * Toast 之类的都不算，也不会做任何尺寸/坐标过滤。</p>
     */
    private static void addAppDialogWindows(Activity activity, java.util.List<android.view.View> roots) {
        android.view.View decor = activity == null ? null : activity.getWindow().getDecorView();
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
                    if (view == decor || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) continue;
                    if (isExcluded(view) || roots.contains(view)) continue;
                    if (!isAppDialogWindow(view)) continue;
                    roots.add(view);
                    added++;
                }
            }
        } catch (Throwable error) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "枚举对话框窗口失败：" + error);
            return;
        }
    }

    /** Window 类型是 TYPE_APPLICATION 才算对话框窗口。 */
    private static boolean isAppDialogWindow(android.view.View view) {
        try {
            android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
            if (!(params instanceof android.view.WindowManager.LayoutParams)) return false;
            return ((android.view.WindowManager.LayoutParams) params).type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
        } catch (Throwable ignored) {
            return false;
        }
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

    /**
     * 造一个触摸事件：必须声明来源是触摸屏。
     * <p>不设 source 时 obtain() 给的是 SOURCE_UNKNOWN，经 ViewRootImpl 输入管道会被当成
     * 无效输入丢掉——表现就是"目标窗口挑对了、坐标也对，但点了没反应"。</p>
     */
    private static android.view.MotionEvent obtainTouch(long downTime, long eventTime, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try {
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        } catch (Throwable ignored) {
        }
        return event;
    }

    // ---------------------------------------------------------------- 触摸滚动后的选中项跟随
    //
    // leanback 的网格（BaseGridView / VerticalGridView / HorizontalGridView）以"选中项"为布局锚点：
    // 触摸滚动只改了像素偏移，选中项还在原处；下一次布局（松手、点击、焦点变化都会触发）就把列表
    // 拉回到选中项该在的位置 —— 这就是"首页/Vod 列表松手弹回原位""点一下又弹回去"的根源。
    // 选站那种普通 RecyclerView 没有这个模型，所以它滑得动、也不弹。
    //
    // 首页是两层网格套着的：外层纵向（整页上下）、每行内层横向。手指落在一张卡片上时，
    // 命中的最内层是横向网格，它纵向永远不滚；上下滑动的其实是外层。所以按下时把整条祖先链
    // 都记下来，松手后逐层各自判断"这一层滚没滚"，滚了的对锚点、没滚的对按下的那一点。
    //
    // 真遥控器不存在这个问题（它只按键）。所以这段只做在镜像注入这条路上，不改 App 与系统行为。

    private static final String GRID_CLASS_NAME = "androidx.leanback.widget.BaseGridView";
    private static Class<?> gridClass;
    private static final java.util.List<GridMemo> gridMemos = new java.util.ArrayList<>();

    /** 按下时某一层网格的基准：滚到哪儿、焦点子项在哪儿、按下点在哪儿。 */
    private static final class GridMemo {

        private android.view.View grid;
        private int scrollX;
        private int scrollY;
        private int anchorLeft = -1;
        private int anchorTop = -1;
        private int downLeft;
        private int downTop;
        /** 按下点所在的那个子项，用来量"画面到底挪没挪"：它在网格里的坐标变化就是真实滚动量。 */
        private android.view.View probe;
        private int probeLeft;
        private int probeTop;
    }

    private static boolean isGrid(android.view.View view) {
        if (view == null) return false;
        try {
            if (gridClass == null) gridClass = Class.forName(GRID_CLASS_NAME);
            return gridClass.isInstance(view);
        } catch (Throwable error) {
            return false;
        }
    }

    /** 网格自身的滚动方向：HorizontalGridView 横向，VerticalGridView 纵向，其它（如纯 BaseGridView）返回 null 交给偏移量判断。 */
    private static Boolean gridHorizontal(android.view.View grid) {
        String name = grid.getClass().getName();
        if (name.contains("HorizontalGridView")) return Boolean.TRUE;
        if (name.contains("VerticalGridView")) return Boolean.FALSE;
        return null;
    }

    private static android.view.View probeChild(android.view.ViewGroup group, int left, int top) {
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child == null || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (left >= child.getLeft() && left < child.getRight() && top >= child.getTop() && top < child.getBottom()) return child;
        }
        return group.getChildCount() > 0 ? group.getChildAt(0) : null;
    }

    /**
     * 真实滚动量：拿按下时那个子项现在的坐标跟按下时比。
     *
     * <p>不直接用 computeVerticalScrollOffset：leanback 的 GridLayoutManager 自己接管排布，
     * 这个值在实机上一直是 0（日志里 dx=0 dy=0 拖了 400 多像素都没变），拿它判断"滚没滚"必然误判。
     * 子项坐标是被 offsetChildrenVertical 直接改的，量它最可靠。子项被回收了就退回用偏移量。</p>
     */
    private static int[] gridDelta(GridMemo memo) {
        android.view.View grid = memo.grid;
        if (memo.probe != null && memo.probe.getParent() == grid) {
            return new int[]{memo.probe.getLeft() - memo.probeLeft, memo.probe.getTop() - memo.probeTop};
        }
        return new int[]{gridScroll(grid, true) - memo.scrollX, gridScroll(grid, false) - memo.scrollY};
    }

    private static int gridScroll(android.view.View grid, boolean horizontal) {
        // computeHorizontalScrollOffset / computeVerticalScrollOffset 在 View 上是 protected，
        // 只有 RecyclerView 把它们覆写成了 public，所以必须按 RecyclerView 类型调
        if (!(grid instanceof androidx.recyclerview.widget.RecyclerView recycler)) return 0;
        try {
            return horizontal ? recycler.computeHorizontalScrollOffset() : recycler.computeVerticalScrollOffset();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 从窗口根按坐标往下找，把经过的每一层网格按"由内到外"记进 out。
     *
     * @return 这个子树里有没有网格被命中
     */
    private static boolean collectGrids(android.view.View view, float x, float y, java.util.List<android.view.View> out) {
        if (view == null) return false;
        boolean inside = x >= 0 && y >= 0 && x <= view.getWidth() && y <= view.getHeight();
        boolean hit = false;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                android.view.View child = group.getChildAt(i);
                if (child == null || child.getVisibility() != android.view.View.VISIBLE) continue;
                float cx = x - child.getLeft() + group.getScrollX();
                float cy = y - child.getTop() + group.getScrollY();
                if (cx < 0 || cy < 0 || cx > child.getWidth() || cy > child.getHeight()) continue;
                if (collectGrids(child, cx, cy, out)) {
                    hit = true;
                    break;
                }
            }
        }
        if (!inside) return hit;
        if (!isGrid(view)) return hit;
        out.add(view);
        return true;
    }

    /** 按下时记基准：每一层网格当时滚到哪儿、焦点子项在网格里的偏移、按下点在网格里的坐标。 */
    private static void rememberGrid(android.view.View windowRoot, float screenX, float screenY, int[] windowLoc) {
        gridMemos.clear();
        if (windowRoot == null || windowLoc == null) return;
        java.util.List<android.view.View> grids = new java.util.ArrayList<>();
        collectGrids(windowRoot, screenX - windowLoc[0], screenY - windowLoc[1], grids);
        if (grids.isEmpty()) return;
        for (android.view.View grid : grids) {
            GridMemo memo = new GridMemo();
            memo.grid = grid;
            memo.scrollX = gridScroll(grid, true);
            memo.scrollY = gridScroll(grid, false);
            int[] location = new int[2];
            grid.getLocationOnScreen(location);
            memo.downLeft = Math.round(screenX - location[0]);
            memo.downTop = Math.round(screenY - location[1]);
            if (grid instanceof android.view.ViewGroup group) {
                android.view.View focused = group.getFocusedChild();
                if (focused != null) {
                    memo.anchorLeft = focused.getLeft();
                    memo.anchorTop = focused.getTop();
                }
                memo.probe = probeChild(group, memo.downLeft, memo.downTop);
                if (memo.probe != null) {
                    memo.probeLeft = memo.probe.getLeft();
                    memo.probeTop = memo.probe.getTop();
                }
            }
            gridMemos.add(memo);
        }
    }

    /** 手势结束：逐层把选中项跟到"手指实际停下/点到"的那一项。 */
    private static void finishGrid(boolean cancel) {
        java.util.List<GridMemo> memos = new java.util.ArrayList<>(gridMemos);
        gridMemos.clear();
        if (cancel) return;
        for (GridMemo memo : memos) followGrid(memo);
    }

    /**
     * 单层网格的跟随。
     *
     * <p>滚动过：选"现在正好落在按下时焦点项那个偏移"的子项 —— 还原对齐时画面不动，也就不会弹。
     * <br>没滚动（单纯点一下）：不动选中项 —— 改变选中项会让网格按对齐策略平移，点一下画面跳一下，
     * 而点击本身系统已经按坐标投给手指底下那个 View 了，不需要我们再帮忙。</p>
     */
    private static void followGrid(GridMemo memo) {
        android.view.View grid = memo.grid;
        if (grid == null || !(grid instanceof android.view.ViewGroup group) || group.getChildCount() == 0) return;
        String name = grid.getClass().getSimpleName();
        int[] moved = gridDelta(memo);
        int dx = moved[0];
        int dy = moved[1];
        boolean scrolled = dx != 0 || dy != 0;
        // 方向必须按网格自己的类型判，不能拿 |dx|>=|dy| 猜：两边都是 0 的时候那个判法会把纵向网格
        // 当成横向，拿 X 坐标去跟每行的 getLeft()（全是 0）比，结果永远选中第一行。
        Boolean axis = gridHorizontal(grid);
        boolean horizontal = axis != null ? axis : Math.abs(dx) >= Math.abs(dy);
        // 没滚动（只是点了一下）：不动选中项。动了反而会让网格按对齐策略把整页/整行平移过去，
        // 看起来就是"点一下画面跳一下"，而点击本身系统已经按坐标投给手指底下那个 View 了。
        if (!scrolled) return;   // 只是点了一下：不动选中项，也不记账
        int anchor = horizontal ? memo.anchorLeft : memo.anchorTop;
        if (anchor < 0) anchor = horizontal ? grid.getWidth() / 2 : grid.getHeight() / 2;
        int span = horizontal ? grid.getWidth() : grid.getHeight();
        android.view.View best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child == null || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            int start = horizontal ? child.getLeft() : child.getTop();
            int size = horizontal ? child.getWidth() : child.getHeight();
            if (start + size <= 0 || start >= span) continue;   // 完全滑出可视区的不算
            int offset = Math.abs(start - anchor);
            if (offset < bestDelta) {
                bestDelta = offset;
                best = child;
            }
        }
        if (best == null) return;
        int position = -1;
        try {
            position = ((androidx.recyclerview.widget.RecyclerView) grid).getChildAdapterPosition(best);
        } catch (Throwable ignored) {
        }
        if (position < 0) return;
        if (!selectPosition(grid, position)) {
            log("网格跟随失败：没有 setSelectedPosition " + name);
            return;
        }
        // 触屏模式下这些控件通常不参与触摸取焦，拿不到焦点也没关系：选中项已经对上，布局不会弹回
        if (!best.isFocused()) best.requestFocus();
        log("触摸抬起 " + name + " position=" + position + " dx=" + dx + " dy=" + dy
                + (memo.probe != null && memo.probe.getParent() == grid ? "" : "（探针失效，用偏移量）"));
    }

    private static boolean selectPosition(android.view.View grid, int position) {
        for (String name : new String[]{"setSelectedPosition", "setSelectedPositionSmooth"}) {
            try {
                grid.getClass().getMethod(name, int.class).invoke(grid, position);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void log(String text) {
        try {
            com.github.catvod.crawler.DebugLogStore.add("voice", text);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 抓帧链路（3 次/秒）专用的限流日志：同类问题只留一条，最少隔 30 秒才再记一次。
     * 卡帧、慢帧会连续几十上百次触发，逐条记就是刷屏；限流后既能看出"有没有问题"，日志量也是常数级。
     */
    private static final java.util.Map<String, Long> shotLogTimes = new java.util.HashMap<>();

    /**
     * 抓帧链路（3 次/秒）专用的限流日志。
     *
     * <p>按"问题种类"各自计时：卡帧、慢帧、PixelCopy 失败、叠加绘制失败……各自 30 秒最多一条，
     * 互不挤占。以前共用一个计时器，一条慢帧会把随后 30 秒内的卡帧全吞掉，正好把要查的东西藏起来。</p>
     */
    private static void logThrottled(String key, String text) {
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (shotLogTimes) {
            Long last = shotLogTimes.get(key);
            if (last != null && now - last < 30000) return;
            shotLogTimes.put(key, now);
        }
        log(text);
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
            // 目标一律走窗口表：控制本机时也一样，遥控器面板由 excludeWindow 排除，
            // 这样折叠菜单这种弹出窗口才收得到触摸。
            android.view.View target = targetView(activity);
            if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
                logThrottled("触摸丢弃", "触摸丢弃：目标窗口尺寸为 0");
                return;
            }
            int[] location = new int[2];
            target.getLocationOnScreen(location);
            float screenX = baseLoc[0] + nx * base.getWidth() / 1000f;
            float screenY = baseLoc[1] + ny * base.getHeight() / 1000f;
            if (action == 0) rememberGrid(target, screenX, screenY, location);
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
                    android.view.MotionEvent event = obtainTouch(touchDownTime, now + index * 8L, android.view.MotionEvent.ACTION_MOVE, px - location[0], py - location[1]);
                    deliverTouch(activity, target, event);
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
            android.view.MotionEvent event = obtainTouch(touchDownTime, now, type, px - location[0], py - location[1]);
            deliverTouch(activity, target, event);
            // 手势结束：leanback 网格是按"选中项"排布的，触摸只挪了像素没挪选中项，
            // 不改的话下一次布局会把列表拉回原处（松手弹回、点一下也弹回）。这里把选中项跟到现实位置。
            if (up) finishGrid(action == 5);
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
            } catch (Throwable error) {
                logThrottled("叠加窗口失败", "叠加窗口失败 " + root.getClass().getSimpleName() + "：" + error);
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
            logThrottled("镜像兜底", "镜像兜底：主窗口 view.draw");
        }
        for (android.view.View root : roots) {
            if (root == null || !root.isShown() || root.getWidth() <= 0 || root.getHeight() <= 0) continue;
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            canvas.drawColor(0x66000000);
            canvas.save();
            canvas.translate(location[0] - origin[0], location[1] - origin[1]);
            canvas.clipRect(0, 0, root.getWidth(), root.getHeight());
            try {
                root.draw(canvas);
            } catch (Throwable error) {
                logThrottled("叠加窗口失败", "叠加窗口失败 " + root.getClass().getSimpleName() + "：" + error);
            }
            canvas.restore();
        }
    }

    /**
     * 抓帧状态：一次只抓一帧，抓不动就把上一帧原样回给客户端（带 X-Frame-Stale 头）。
     *
     * <p>以前每来一个 shot 就新起一次 PixelCopy + 新建一张 1080p Bitmap（8MB）。
     * 界面切换（返回/首页）时 PixelCopy 迟迟不回调，请求就一层层堆起来：主线程队列被占满、
     * 内存一路涨、后面的帧全都超时——表现就是"画面等好几秒才动，最后彻底卡在一张图上不动"，
     * 关掉遥控器重开也救不回来（堆的是电视这侧的任务）。所以这里做互斥 + 缓存上一帧。</p>
     */
    private static volatile boolean shotBusy;
    private static volatile byte[] shotLastJpeg;
    private static volatile int shotLastW, shotLastH, shotLastSeq;
    /**
     * 相邻帧的画面指纹与"连续一致"计数：只在连续一致/恢复变化时各记一行，不按帧写日志。
     * 分叠加前（PixelCopy 出的基准画面）和叠加后（叠了弹层/残留窗口的成图）两份——
     * 成图一直不变而基准画面在变，就说明是新画面被叠加层整屏盖住了；两份都不变，才是基准窗口自己没刷新。
     */
    private static volatile long shotBaseSig, shotFinalSig;
    private static long prevBaseSig, prevFinalSig;
    private static int shotSameFrames;
    private static long shotSameSince;
    /**
     * 抓帧"在途"标记：真正结束的标志是主线程上那张图绘制完（PixelCopy 回调 + 叠加弹层绘制），
     * 而不是 shot() 这个请求线程等满 900ms 就撤。以前只认 900ms，超时后主线程上的绘制还在跑，
     * 手机每 350ms 又来一帧，主线程上就堆起一串整屏绘制——返回首页那一下（WebView 重新落层、
     * view.draw 特别慢）能堆到十几帧，镜像就一直回上一帧，看着像卡了十来秒。远程按键不受影响，
     * 因为输入事件照常在帧与帧之间被处理。
     */
    private static volatile boolean capturePending;
    private static volatile long captureSince;
    private static final long CAPTURE_STALE_MS = 5000;

    /** 回上一帧缓存（带 X-Frame-Stale=1），没有缓存就报 frame-busy。 */
    private Response cachedFrame() {
        byte[] cached = shotLastJpeg;
        if (cached != null) return Nano.jpeg(cached, shotLastW, shotLastH, shotLastSeq, true);
        return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "frame-busy");
    }

    /** 抓帧真正收尾：清"在途"标记并放行等待方。所有结束路径都必须走这里。 */
    private static void finishCapture(java.util.concurrent.CountDownLatch latch) {
        capturePending = false;
        latch.countDown();
    }

    /** 抓一帧当前界面（JPEG），手机上把这张画面当触摸板底图用。 */
    private Response shot(Map<String, String> params) {
        Activity activity = App.activity();
        if (activity == null) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "app-not-open");
        long now = android.os.SystemClock.uptimeMillis();
        // 上一帧还在抓 / 主线程还在画它：直接回上一帧，别再压任务。5 秒是兜底，
        // 万一那次绘制因为异常没收尾，也不至于把镜像永久卡死。
        if (shotBusy || (capturePending && now - captureSince < CAPTURE_STALE_MS)) {
            logThrottled("抓帧占用", "抓帧占用中，回上一帧");
            return cachedFrame();
        }
        shotBusy = true;
        capturePending = true;
        captureSince = now;
        try {
            final int seq = shotLastSeq + 1;
            final long started = android.os.SystemClock.uptimeMillis();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final android.graphics.Bitmap[] holder = new android.graphics.Bitmap[1];
            App.post(() -> {
                android.view.View decor = activity.getWindow().getDecorView();
                int width = decor.getWidth();
                int height = decor.getHeight();
                if (width <= 0 || height <= 0) {
                    finishCapture(latch);
                    return;
                }
                int[] origin = new int[2];
                decor.getLocationOnScreen(origin);
                android.graphics.Bitmap bitmap;
                try {
                    bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
                } catch (Throwable error) {
                    logThrottled("抓帧建图失败", "抓帧建图失败：" + error);
                    finishCapture(latch);
                    return;
                }
                java.util.List<android.view.View> roots = displayRoots(activity);
                // WebView 是硬件渲染的，用 view.draw(canvas) 画出来会是空白、而且被缩到左上角，
                // 所以主窗口走 PixelCopy（直接拿窗口合成后的像素），弹层再用 view.draw 补在上面。
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try {
                        android.view.PixelCopy.request(activity.getWindow(), bitmap, result -> {
                            try {
                                if (result == android.view.PixelCopy.SUCCESS) {
                                    long base = bitmapSig(bitmap);
                                    paintOverlays(bitmap, origin, decor, roots);
                                    shotBaseSig = base;
                                    shotFinalSig = bitmapSig(bitmap);
                                } else {
                                    logThrottled("PixelCopy 失败", "PixelCopy 失败 result=" + result + "，改用 view.draw");
                                    paintFallback(bitmap, origin, decor, roots);
                                    shotBaseSig = 0L;
                                    shotFinalSig = bitmapSig(bitmap);
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                holder[0] = bitmap;
                                finishCapture(latch);
                            }
                        }, new android.os.Handler(android.os.Looper.getMainLooper()));
                        return;
                    } catch (Throwable error) {
                        logThrottled("PixelCopy 异常", "PixelCopy 异常，改用 view.draw：" + error);
                    }
                }
                try {
                    paintFallback(bitmap, origin, decor, roots);
                } catch (Throwable ignored) {
                }
                shotBaseSig = 0L;
                shotFinalSig = bitmapSig(bitmap);
                holder[0] = bitmap;
                finishCapture(latch);
            });
            try {
                // 900ms 足够一帧（原来等 3s，界面切换时把请求全堵在这，越堵越慢）
                latch.await(900, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            android.graphics.Bitmap frame = holder[0];
            long cost = android.os.SystemClock.uptimeMillis() - started;
            if (frame == null) {
                logThrottled("抓帧未完成", "抓帧未完成 " + cost + "ms，回上一帧");
                byte[] cached = shotLastJpeg;
                if (cached != null) return Nano.jpeg(cached, shotLastW, shotLastH, shotLastSeq, true);
                return Nano.error(Response.Status.INTERNAL_ERROR, "no-frame");
            }
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
            trackFrameContent(shotFinalSig, shotBaseSig, seq);
            shotLastJpeg = bytes;
            shotLastW = outWidth;
            shotLastH = outHeight;
            shotLastSeq = seq;
            // 正常帧不再记账（3 帧/秒的流水会瞬间写满日志），只留慢帧
            if (cost >= 200) logThrottled("出帧慢", "出帧慢 seq=" + seq + " 耗时=" + cost + "ms");
            return Nano.jpeg(bytes, outWidth, outHeight, seq, false);
        } finally {
            shotBusy = false;
        }
    }

    /** 画面指纹：8x8 网格抽 64 个像素，够便宜，也足以判断两帧是不是同一张图。 */
    private static long bitmapSig(android.graphics.Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return 0L;
        long sig = ((long) bitmap.getWidth() << 32) | bitmap.getHeight();
        int stepX = Math.max(1, bitmap.getWidth() / 8);
        int stepY = Math.max(1, bitmap.getHeight() / 8);
        try {
            for (int y = 0; y < bitmap.getHeight(); y += stepY) {
                for (int x = 0; x < bitmap.getWidth(); x += stepX) sig = sig * 1000003L + bitmap.getPixel(x, y);
            }
        } catch (Throwable error) {
            return 0L;
        }
        return sig;
    }

    /**
     * 连续输出同一张图时记两行（开始一致、恢复变化），不按帧记账。
     * 开始一致那一行会带上"叠加前画面有没有在变"：变了就是被叠加层盖住，没变就是基准窗口自己没刷新。
     */
    private static void trackFrameContent(long finalSig, long baseSig, int seq) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean same = shotSameFrames > 0 && finalSig != 0L && finalSig == prevFinalSig;
        if (same) {
            shotSameFrames++;
            if (shotSameFrames == 3) {
                com.github.catvod.crawler.DebugLogStore.add("voice", "镜像内容开始一致（连续 3 帧同图）seq=" + seq
                        + "，叠加前画面" + (baseSig != 0L && prevBaseSig != 0L && baseSig != prevBaseSig
                        ? "在变（是被叠加层盖住的）" : "也没变（基准窗口本身没刷新）")
                        + "，窗口=" + rootSignature);
                shotSameSince = now;
            }
            prevBaseSig = baseSig;
            return;
        }
        if (shotSameFrames >= 3) {
            com.github.catvod.crawler.DebugLogStore.add("voice", "镜像内容恢复变化：连续 " + shotSameFrames + " 帧同图，历时 "
                    + (now - shotSameSince) + "ms，seq=" + seq);
        }
        shotSameFrames = 0;
        prevFinalSig = finalSig;
        prevBaseSig = baseSig;
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
