package com.fongmi.android.tv.voice;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogVoiceControlBinding;
import com.fongmi.android.tv.server.ServerToken;
import androidx.fragment.app.DialogFragment;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.github.catvod.Proxy;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 手机端语音/文字控制面板。
 *
 * <p>做成应用内弹层而不是系统悬浮球：悬浮窗会盖在设备选择、配对确认这些对话框上面，点不到；
 * 放在应用内则"设备列表/配对提示"永远在上层，可以正常操作。</p>
 *
 * <p>指令原文只发一次给目标端（本机或电视），由对端解析执行，手机不参与规则判断。</p>
 */
public class VoiceControlDialog extends DialogFragment implements NsdDeviceDiscovery.Listener, RecognitionListener {

    private static final String KEY_TARGET = "voice_lan_target";
    /** 已绑定设备库：JSON 对象，IP -> {name, token, url}；按 IP 记，端口变了也不用重新配对。 */
    private static final String KEY_STORE = "voice_lan_devices";
    /** 按键区形态：0=方向盘按键 1=触摸板（触摸板是远程画面镜像；对端是手机时固定触摸板）。 */
    private static final String KEY_MODE = "voice_panel_mode";
    /** 面板位置（按屏幕宽高的比例保存，换分辨率/旋转后仍然合理）。 */
    private static final String KEY_POS_X = "voice_panel_x";
    private static final String KEY_POS_Y = "voice_panel_y";
    private static final int REQUEST_MIC = 4211;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 拉画面单独一条线程，别和按键/指令抢，避免画面卡时操作也卡。 */
    private final ExecutorService frameExecutor = Executors.newSingleThreadExecutor();
    /** 设备地址 → 设备名（mDNS 注册名去掉 WebHTV- 前缀），列表里显示"名称 + IP"。 */
    private final Map<String, String> devices = new LinkedHashMap<>();

    /** 线程池可能已经被 onDestroyView 关掉，晚到的任务直接丢弃，别抛 RejectedExecutionException。 */
    private void run(Runnable task) {
        try {
            if (!executor.isShutdown()) executor.execute(task);
        } catch (Throwable ignored) {
        }
    }

    private void runFrame(Runnable task) {
        try {
            if (!frameExecutor.isShutdown()) frameExecutor.execute(task);
        } catch (Throwable ignored) {
        }
    }

    /** 面板可能已经 detach，取字符串一律走这里；拿不到就返回空串。 */
    private String str(int resId, Object... args) {
        try {
            return args == null || args.length == 0 ? getString(resId) : getString(resId, args);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private DialogVoiceControlBinding binding;
    /** 当前窗口左上角坐标（gravity=TOP|START 时生效）。 */
    private int winX;
    private int winY;
    private int winWidth;
    /** 当前目标端类型：tv / mobile（来自 /voice?cmd=ping 的回包）。 */
    private String targetType = "tv";
    private long lastMoveSent;
    private String targetOrientation = "landscape";
    private int lastFrameW;
    private int lastFrameH;
    /** 输入框内容按"指令"还是"输入"发送：0=指令（每次打开都是这个） 1=输入。仅本次打开有效，不持久化。 */
    private int inputMode = 0;
    private NsdDeviceDiscovery discovery;
    private SpeechRecognizer recognizer;

    public static VoiceControlDialog create() {
        return new VoiceControlDialog();
    }

    @NonNull
    @Override
    public android.app.Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new android.app.Dialog(requireContext(), R.style.Theme_WebHTV_Dialog);
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogVoiceControlBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        com.fongmi.android.tv.theme.ThemeController.bindDialog(getDialog());
        initView();
    }

    protected void initView() {
        binding.input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send(binding.input.getText().toString());
                return true;
            }
            return false;
        });
        binding.speak.setOnClickListener(view -> listen());
        binding.device.setOnClickListener(view -> chooseDevice());
        binding.send.setOnClickListener(view -> send(binding.input.getText().toString()));
        binding.textMode.setOnClickListener(view -> toggleInputMode());
        key(binding.menu, "menu");
        key(binding.back, "back");
        key(binding.home, "home");
        key(binding.up, "up");
        key(binding.down, "down");
        key(binding.left, "left");
        key(binding.right, "right");
        key(binding.ok, "ok");
        binding.mode.setOnClickListener(view -> toggleMode());
        // 手机远程没有按键区，用右上角这个返回代替手势返回
        key(binding.mobileBack, "back");
        applyInputMode();
        setupPad();
        updateStatus();
        probeType(Prefers.getString(KEY_TARGET, ""));
        enableDrag(binding.getRoot());
        // 打开面板就进入"等待说话"：已选好设备直接开麦，没选设备先弹设备列表。
        binding.getRoot().postDelayed(this::autoStart, 350);
    }

    /**
     * 普通 Dialog 而不是 BottomSheetDialog。
     *
     * <p>BottomSheet 的内容必须挂在全屏容器里，收窄/弹键盘时会出现通栏底色（白条）和 insets 问题；
     * 这里直接用 wrap_content、背景透明的 Dialog，窗口本身就只有面板那么大。</p>
     *
     * <p>位置：默认贴底居中；拖过之后记住上次位置（按屏幕比例存，旋转/换分辨率仍可用）。</p>
     */
    @Override
    public void onStart() {
        super.onStart();
        android.app.Dialog dialog = getDialog();
        if (dialog == null) return;
        dialog.setCanceledOnTouchOutside(false);
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.6f);
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        int screen = getResources().getDisplayMetrics().widthPixels;
        winWidth = Math.min(com.fongmi.android.tv.utils.ResUtil.dp2px(252), screen - com.fongmi.android.tv.utils.ResUtil.dp2px(24));
        window.setLayout(winWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        android.view.View root = binding == null ? null : binding.getRoot();
        if (root == null) return;
        root.post(() -> {
            int height = root.getHeight();
            int screenH = getResources().getDisplayMetrics().heightPixels;
            float savedX = Prefers.getFloat(KEY_POS_X, -1f);
            float savedY = Prefers.getFloat(KEY_POS_Y, -1f);
            if (savedX > 0f && savedY > 0f) {
                moveWindow((int) (savedX * screen), (int) (savedY * screenH), height);
            } else {
                moveWindow((screen - winWidth) / 2, screenH - height, height);
            }
            applyRemoteUi();
        });
    }

    /** 移动窗口到屏幕坐标（自动夹在屏幕范围内）。 */
    private void moveWindow(int x, int y, int height) {
        android.app.Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        android.view.Window window = dialog.getWindow();
        android.view.WindowManager.LayoutParams lp = window.getAttributes();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.x = Math.max(0, Math.min(x, screenW - winWidth));
        lp.y = Math.max(0, Math.min(y, Math.max(0, screenH - height)));
        window.setAttributes(lp);
        winX = lp.x;
        winY = lp.y;
    }

    /** 按住空白处拖动整块面板；松手后按屏幕比例记住位置。 */
    private void enableDrag(android.view.View root) {
        final int slop = android.view.ViewConfiguration.get(requireContext()).getScaledTouchSlop();
        root.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;
            private boolean dragging;

            @Override
            public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = winX;
                        startY = winY;
                        dragging = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (!dragging && Math.abs(dx) + Math.abs(dy) > slop) dragging = true;
                        if (dragging) moveWindow(startX + dx, startY + dy, view.getHeight());
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            int screenW = getResources().getDisplayMetrics().widthPixels;
                            int screenH = getResources().getDisplayMetrics().heightPixels;
                            if (screenW > 0 && screenH > 0) {
                                Prefers.put(KEY_POS_X, Math.max(0f, Math.min(1f, winX * 1f / screenW)));
                                Prefers.put(KEY_POS_Y, Math.max(0f, Math.min(1f, winY * 1f / screenH)));
                            }
                        }
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 一个遥控键：单击发一次；长按（按住约 400ms）发"按住不放"。 */
    private void key(android.view.View button, String code) {
        button.setOnClickListener(view -> nav(code, false));
        button.setOnLongClickListener(view -> {
            nav(code, true);
            return true;
        });
    }

    @Override
    public void onDestroyView() {
        // 先把排队/延迟的回调全清掉：否则 350ms 的 autoStart、frameTick、startFrames
        // 会在面板销毁后继续跑，再去 execute 已 shutdown 的线程池就是 RejectedExecutionException
        handler.removeCallbacksAndMessages(null);
        stopFrames();
        stopDiscovery();
        releaseRecognizer();
        executor.shutdownNow();
        frameExecutor.shutdownNow();
        binding = null;
        super.onDestroyView();
    }

    // ---------------------------------------------------------------- 发送

    private void send(String text) {
        String target = Prefers.getString(KEY_TARGET, "");
        if (TextUtils.isEmpty(text)) {
            show(R.string.voice_overlay_empty);
            return;
        }
        if (TextUtils.isEmpty(target)) {
            chooseDevice();
            return;
        }
        show(R.string.voice_overlay_sending);
        final boolean typing = inputMode == 1;
        run(() -> {
            // 指令：交给对端解析执行；输入：只把文字打进对端的输入框
            Result result = typing ? request(target, "cmd=input&text=" + encode(text), token(target))
                    : request(target, "cmd=say&q=" + encode(text), token(target));
            handler.post(() -> handleResult(target, text, result));
        });
    }

    private void handleResult(String target, String text, Result result) {
        if (result.code == 401) {
            if (isLocal(target)) show(str(R.string.voice_overlay_failed, "401"));
            else pair(target, text);
            return;
        }
        if (result.code != 200) {
            show(str(R.string.voice_overlay_failed, result.code + " " + result.body));
            return;
        }
        String body = result.body == null ? "" : result.body;
        if (body.startsWith("input:")) show(R.string.voice_input_done);
        else if (body.startsWith("no-site:")) show(str(R.string.voice_site_not_found, body.substring("no-site:".length())));
        else if ("empty".equals(body)) show(R.string.voice_overlay_unrecognized);
        else show(str(R.string.voice_overlay_done, body));
    }

    /** 发一个方向/菜单键给目标设备，由它投给当前前台界面。 */
    private void nav(String key, boolean hold) {
        String target = Prefers.getString(KEY_TARGET, "");
        if (TextUtils.isEmpty(target)) {
            chooseDevice();
            return;
        }
        show(R.string.voice_overlay_sending);
        run(() -> {
            Result result = request(target, "cmd=nav&k=" + key + (hold ? "&hold=1" : "") + selfParam(target), token(target));
            handler.post(() -> {
                if (result.code == 401) {
                    if (isLocal(target)) show(str(R.string.voice_overlay_failed, "401"));
                    else pair(target, "");
                    return;
                }
                if (result.code != 200) show(str(R.string.voice_key_failed, result.code + " " + result.body));
                else show(str(R.string.voice_nav_done, keyName(key)) + (hold ? str(R.string.voice_nav_hold) : ""));
            });
        });
    }

    /** 问一下目标端是电视还是手机（ping 回包 webhtv-voice:tv / webhtv-voice:mobile）。 */
    private void probeType(String target) {
        if (TextUtils.isEmpty(target)) {
            targetType = "tv";
            applyRemoteUi();
            return;
        }
        if (isLocal(target)) {
            targetType = com.fongmi.android.tv.utils.Util.isMobile() ? "mobile" : "tv";
            targetOrientation = localOrientation();
            applyRemoteUi();
            return;
        }
        run(() -> {
            Result result = request(target, "cmd=ping", "");
            handler.post(() -> {
                String body = result.body == null ? "" : result.body;
                if (body.startsWith("webhtv-voice:")) {
                    // 回包形如 webhtv-voice:tv:portrait，取第二段才是端类型
                    String[] parts = body.split(":");
                    targetType = parts.length > 1 ? parts[1].trim() : "tv";
                    targetOrientation = parts.length > 2 ? parts[2].trim() : "landscape";
                    remember(target, nameOf(target), "");
                } else if (result.code == 200) {
                    targetType = "tv";
                } else {
                    targetType = "tv";
                }
                applyRemoteUi();
            });
        });
    }

    /** 设备/发送之间的按钮：显示"指令"表示发送=执行指令，显示"输入"表示发送=填进远程输入框。 */
    private void toggleInputMode() {
        inputMode = inputMode == 1 ? 0 : 1;
        applyInputMode();
    }

    /** 每次打开面板都从"指令"开始（状态不保存）。 */
    private void applyInputMode() {
        if (binding == null) return;
        binding.textMode.setText(inputMode == 1 ? R.string.voice_mode_input : R.string.voice_mode_cmd);
    }

    private void toggleMode() {
        Prefers.put(KEY_MODE, Prefers.getInt(KEY_MODE, 0) == 0 ? 1 : 0);
        applyRemoteUi();
    }

    /**
     * 按键区形态：
     * 远程是手机 → 没有方向键语义，整块按键区隐藏，只留语音/文本；
     * 远程是电视 → 默认方向键盘，右上角可切到触摸板（模拟鼠标）。
     */
    private void applyRemoteUi() {
        if (binding == null) return;
        boolean mobile = "mobile".equals(targetType);
        int mode = Prefers.getInt(KEY_MODE, 0);
        // 手机端没有方向键语义，但一样能被触摸板"点"：固定给触控面板，不给切换按钮
        boolean touchpad = mobile || mode == 1;
        // 触摸板模式只藏方向盘：返回/菜单/首页是任何形态都要用的，不能跟着一起藏
        binding.keyArea.setVisibility(mobile ? android.view.View.GONE : android.view.View.VISIBLE);
        int dpad = touchpad ? android.view.View.GONE : android.view.View.VISIBLE;
        binding.up.setVisibility(dpad);
        binding.down.setVisibility(dpad);
        binding.dpadRow.setVisibility(dpad);
        binding.pad.setVisibility(touchpad ? android.view.View.VISIBLE : android.view.View.GONE);
        applyLayout(touchpad && "tv".equals(targetType) && !padRotate());
        if (touchpad) startFrames(); else stopFrames();
        // 只有"电视端"才给按键/触摸板切换按钮；手机端固定触控面板
        binding.mode.setVisibility(TextUtils.isEmpty(Prefers.getString(KEY_TARGET, "")) || mobile ? android.view.View.GONE : android.view.View.VISIBLE);
        binding.mobileBack.setVisibility(mobile ? android.view.View.VISIBLE : android.view.View.GONE);
        binding.mode.setText(mode == 1 ? R.string.voice_mode_keys : R.string.voice_mode_touchpad);
    }

    /**
     * 布局方向：电视端切到触摸板时整块面板改成横屏——画面在左、按键和指令区在右，
     * 这样镜像能占大一点；其它情况保持竖排（画面在上、按键在下）。
     */
    private void applyLayout(boolean landscape) {
        if (binding == null) return;
        android.widget.LinearLayout body = binding.body;
        android.widget.LinearLayout right = binding.rightSide;
        android.widget.LinearLayout.LayoutParams left = (android.widget.LinearLayout.LayoutParams) binding.padWrap.getLayoutParams();
        android.widget.LinearLayout.LayoutParams rightParams = (android.widget.LinearLayout.LayoutParams) right.getLayoutParams();
        int gap = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(10);
        body.setOrientation(landscape ? android.widget.LinearLayout.HORIZONTAL : android.widget.LinearLayout.VERTICAL);
        if (landscape) {
            // 横屏面板：画面铺在左、指令按钮挤成右侧一窄条，整块面板是"宽而矮"的形状
            left.width = 0;
            left.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            left.weight = 1;
            rightParams.width = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(104);
            rightParams.weight = 0;
            rightParams.setMarginStart(gap);
            binding.rowSecond.setOrientation(android.widget.LinearLayout.VERTICAL);
            binding.rowFirst.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            moveInputToRoot();
            moveFirstRowOut();
        } else {
            left.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            left.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            left.weight = 0;
            rightParams.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            rightParams.weight = 0;
            rightParams.setMarginStart(0);
            binding.rowSecond.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            binding.rowFirst.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            moveInputBack();
            moveFirstRowIn();
        }
        int pad = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(landscape ? 6 : 16);
        binding.getRoot().setPadding(pad, binding.getRoot().getPaddingTop(), pad, binding.getRoot().getPaddingBottom());
        binding.padWrap.setLayoutParams(left);
        right.setLayoutParams(rightParams);
        // rowFirst（返回/菜单/首页）永远是横排：竖屏在方向盘下面，横屏铺在画面下方
        stackButtons(binding.rowFirst, false, 38, 6);
        stackButtons(binding.rowSecond, landscape, 38, 6);
        applyWindowWidth(landscape);
        if (lastFrameW > 0 && lastFrameH > 0) binding.pad.post(() -> applyPadRatio(lastFrameW, lastFrameH));
    }

    /** 横屏时「返回/菜单/首页」铺到画面正下方一整行，右侧窄栏只留四个指令按钮。 */
    private void moveFirstRowOut() {
        android.view.ViewGroup parent = (android.view.ViewGroup) binding.rowFirst.getParent();
        if (parent == null || parent.getId() == R.id.rootVoice) return;
        parent.removeView(binding.rowFirst);
        binding.rowFirst.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(8);
        binding.getRoot().addView(binding.rowFirst, binding.getRoot().getChildCount(), params);
    }

    /** 竖屏时放回按键区（方向盘下面）。 */
    private void moveFirstRowIn() {
        android.view.ViewGroup parent = (android.view.ViewGroup) binding.rowFirst.getParent();
        if (parent == binding.keyArea) return;
        if (parent != null) parent.removeView(binding.rowFirst);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(18);
        binding.keyArea.addView(binding.rowFirst, binding.keyArea.getChildCount(), params);
    }

    /** 横屏时输入框挪到面板最底下一整行，右侧窄栏只留四个按钮。 */
    private void moveInputToRoot() {
        android.view.ViewGroup parent = (android.view.ViewGroup) binding.input.getParent();
        if (parent instanceof android.widget.LinearLayout && parent.getId() == R.id.rootVoice) return;
        if (parent == null) return;
        parent.removeView(binding.input);
        android.widget.LinearLayout root = binding.getRoot();
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(8);
        root.addView(binding.input, root.getChildCount(), params);
    }

    /** 竖排时把输入框放回右栏（方向盘下面）。 */
    private void moveInputBack() {
        android.view.ViewGroup parent = (android.view.ViewGroup) binding.input.getParent();
        if (parent == binding.rightSide) return;
        if (parent != null) parent.removeView(binding.input);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(14);
        binding.rightSide.addView(binding.input, Math.min(1, binding.rightSide.getChildCount()), params);
    }

    /** 一排按钮在横屏窄栏里要竖着堆，否则三个按钮挤成一条看不到字。 */
    private void stackButtons(android.widget.LinearLayout row, boolean stack) {
        stackButtons(row, stack, 38, 6);
    }

    private void stackButtons(android.widget.LinearLayout row, boolean stack, int heightDp, int gapDp) {
        int gap = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(gapDp);
        for (int i = 0; i < row.getChildCount(); i++) {
            android.view.View child = row.getChildAt(i);
            android.widget.LinearLayout.LayoutParams params = (android.widget.LinearLayout.LayoutParams) child.getLayoutParams();
            if (stack) {
                params.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT;
                params.height = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(36);
                params.weight = 0;
                params.topMargin = i == 0 ? 0 : gap;
                params.bottomMargin = 0;
                params.setMarginStart(0);
                params.setMarginEnd(0);
            } else {
                params.width = 0;
                params.weight = 1;
                params.height = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(38);
                params.topMargin = 0;
                params.setMarginStart(i == 0 ? 0 : (int) com.fongmi.android.tv.utils.ResUtil.dp2px(2));
                params.setMarginEnd(i == row.getChildCount() - 1 ? 0 : (int) com.fongmi.android.tv.utils.ResUtil.dp2px(2));
            }
            child.setLayoutParams(params);
        }
    }

    /** 横屏面板要更宽，否则画面太小；尺寸变了之后把窗口重新摆一次。 */
    private void applyWindowWidth(boolean landscape) {
        android.app.Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null || binding == null) return;
        android.content.res.Resources resources = safeResources();
        if (resources == null) return;
        int screen = resources.getDisplayMetrics().widthPixels;
        boolean padVisible = binding.pad.getVisibility() == android.view.View.VISIBLE;
        int width;
        if (landscape) {
            width = screen;
        } else if (padVisible) {
            // 触摸板要看画面：面板撑到屏幕宽（左右各留 6dp）
            width = screen - (int) com.fongmi.android.tv.utils.ResUtil.dp2px(12);
        } else {
            width = Math.min((int) com.fongmi.android.tv.utils.ResUtil.dp2px(252), screen - (int) com.fongmi.android.tv.utils.ResUtil.dp2px(24));
        }
        winWidth = width;
        dialog.getWindow().setLayout(winWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        if (landscape) winX = 0;
        else if (padVisible) winX = Math.max(0, (screen - winWidth) / 2);
        binding.getRoot().post(() -> moveWindow(winX, winY, binding.getRoot().getHeight()));
    }

    /** 触摸板就是远程画面的实时缩略图：直接点/拖/长按画面，等于在对端屏幕上操作。 */
    private void setupPad() {
        binding.pad.setBackground(null);
        binding.padShot.setBackground(null);
        binding.padShot.setOnTouchListener((view, event) -> {
            String target = Prefers.getString(KEY_TARGET, "");
            if (TextUtils.isEmpty(target) || binding.padShot.getWidth() <= 0 || binding.padShot.getHeight() <= 0) return false;
            int[] rect = imageRect();
            float u = rect[2] <= 0 ? 0f : (event.getX() - rect[0]) / rect[2];
            float v = rect[3] <= 0 ? 0f : (event.getY() - rect[1]) / rect[3];
            int x;
            int y;
            if (padRotate()) {
                // 画面顺时针转了 90°：显示上的"右"对应原图的"下"，显示上的"下"对应原图的"左"
                x = clampNorm(v * 1000f);
                y = clampNorm((1f - u) * 1000f);
            } else {
                x = clampNorm(u * 1000f);
                y = clampNorm(v * 1000f);
            }
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    hideHint();
                    sendTouch(target, x, y, 0); // 按下不松 = 长按
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastMoveSent < 16) return true;
                    lastMoveSent = now;
                    sendTouch(target, x, y, 1);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    sendTouch(target, x, y, 2);
                    pullFrame(); // 操作完立刻刷新一帧
                    return true;
                default:
                    return false;
            }
        });
    }

    /**
     * 远端画面是横的、本机是竖屏时，把镜像旋转 90° 铺进竖屏（不然横画面在竖屏里只能很小）。
     * 旋转后触摸坐标要跟着换算（见 setupPad）。
     */
    /** 本机当前的屏幕方向。 */
    private String localOrientation() {
        try {
            android.content.res.Resources resources = binding != null ? binding.getRoot().getResources() : getResources();
            return resources.getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT ? "portrait" : "landscape";
        } catch (Throwable ignored) {
            return "portrait";
        }
    }

    private boolean padRotate() {
        // 只看方向：远端横屏 + 本机竖屏就转（电视和手机都一样），不掺其它判断
        if (binding == null) return false;
        if (!"landscape".equals(targetOrientation)) return false;
        android.content.res.Configuration config = binding.getRoot().getResources().getConfiguration();
        return config.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    /** 画面在触摸板里实际绘制出来的矩形（fitCenter 会留白，留白不算触摸区）。 */
    private int[] imageRect() {
        if (binding == null) return new int[]{0, 0, 0, 0};
        int vw = binding.padShot.getWidth();
        int vh = binding.padShot.getHeight();
        int fw = lastFrameW > 0 ? lastFrameW : vw;
        int fh = lastFrameH > 0 ? lastFrameH : vh;
        if (vw <= 0 || vh <= 0 || fw <= 0 || fh <= 0) return new int[]{0, 0, Math.max(1, vw), Math.max(1, vh)};
        float scale;
        int w;
        int h;
        if (padRotate()) {
            // 旋转 90° 后画面的宽高互换
            scale = Math.min(vw / (float) fh, vh / (float) fw);
            w = Math.round(fh * scale);
            h = Math.round(fw * scale);
        } else {
            scale = Math.min(vw / (float) fw, vh / (float) fh);
            w = Math.round(fw * scale);
            h = Math.round(fh * scale);
        }
        return new int[]{(vw - w) / 2, (vh - h) / 2, Math.max(1, w), Math.max(1, h)};
    }

    /** 像素 → 0~1000 的归一化坐标（对端按自己屏幕尺寸换算回像素）。 */
    private int clampNorm(float value) {
        return Math.max(0, Math.min(1000, (int) value));
    }

    private int normalize(float value, int size) {
        if (size <= 0) return 0;
        int result = (int) (value * 1000f / size);
        return Math.max(0, Math.min(1000, result));
    }

    private void hideHint() {
        if (binding != null && binding.padHint.getVisibility() != android.view.View.GONE) binding.padHint.setVisibility(android.view.View.GONE);
    }

    /** 是否在拉画面：只有触摸板可见时才拉，省带宽。 */
    private final Runnable frameTick = new Runnable() {
        @Override
        public void run() {
            pullFrame();
            if (handler != null) handler.postDelayed(this, 350);
        }
    };

    /** 换了目标设备：旧画面立刻丢掉，重新问端类型并按新设备拉帧。 */
    private void resetFrames(String target) {
        lastFrameW = 0;
        lastFrameH = 0;
        targetOrientation = "landscape";
        targetType = "tv";
        stopFrames();
        if (binding == null) return;
        binding.padShot.setImageBitmap(null);
        binding.padHint.setVisibility(android.view.View.VISIBLE);
        probeType(target);
        if (binding.pad.getVisibility() == android.view.View.VISIBLE) startFrames();
        else {
            applyRemoteUi();
            if (binding.pad.getVisibility() == android.view.View.VISIBLE) startFrames();
        }
    }

    private void startFrames() {
        handler.removeCallbacks(frameTick);
        handler.post(frameTick);
    }

    private void stopFrames() {
        handler.removeCallbacks(frameTick);
    }

    private void pullFrame() {
        String target = Prefers.getString(KEY_TARGET, "");
        if (TextUtils.isEmpty(target) || binding == null || binding.pad.getVisibility() != android.view.View.VISIBLE) return;
        runFrame(() -> {
            Frame frame = shot(target);
            handler.post(() -> {
                if (frame != null) showFrame(frame);
            });
        });
    }

    private void showFrame(Frame frame) {
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(frame.data, 0, frame.data.length);
        if (bitmap == null || binding == null) return;
        binding.padShot.setImageBitmap(bitmap);
        applyShotMatrix(bitmap);
        if (frame.width > 0 && frame.height > 0) {
            lastFrameW = frame.width;
            lastFrameH = frame.height;
            applyPadRatio(frame.width, frame.height);
        }
    }

    /** 面板高度按远程画面的宽高比走，画面不拉伸，横向/纵向自动跟着变。 */
    /** 按当前是否旋转，给 ImageView 一个"旋转 90° + 等比缩放居中"的矩阵。 */
    private void applyShotMatrix(android.graphics.Bitmap bitmap) {
        if (binding == null) return;
        android.widget.ImageView view = binding.padShot;
        int vw = view.getWidth();
        int vh = view.getHeight();
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return;
        if (!padRotate()) {
            view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            view.setImageMatrix(null);
            return;
        }
        view.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
        float scale = Math.min(vw / (float) bh, vh / (float) bw);
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(90, bw / 2f, bh / 2f);
        matrix.postScale(scale, scale, bw / 2f, bh / 2f);
        matrix.postTranslate((vw - bw) / 2f, (vh - bh) / 2f);
        view.setImageMatrix(matrix);
    }

    /** Fragment 可能已经 detach，取 Resources 一律走这里，拿不到就返回 null 让调用方跳过。 */
    private android.content.res.Resources safeResources() {
        try {
            if (binding != null) return binding.getRoot().getResources();
            return getResources();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyPadRatio(int width, int height) {
        if (binding == null) return;
        android.content.res.Resources resources = safeResources();
        if (resources == null || width <= 0 || height <= 0) return;
        int padWidth = binding.pad.getWidth();
        if (padWidth <= 0) padWidth = (int) (com.fongmi.android.tv.utils.ResUtil.dp2px(252) - com.fongmi.android.tv.utils.ResUtil.dp2px(32));
        int target = Math.round(padWidth * (padRotate() ? (width / (float) height) : (height / (float) width)));
        int min = (int) com.fongmi.android.tv.utils.ResUtil.dp2px(90);
        int max = (int) (resources.getDisplayMetrics().heightPixels * (padRotate() ? 0.46f : 0.55f));
        target = Math.max(min, Math.min(max, target));
        android.view.ViewGroup.LayoutParams params = binding.pad.getLayoutParams();
        if (params != null && params.height != target) {
            params.height = target;
            binding.pad.setLayoutParams(params);
            binding.pad.post(() -> {
                if (binding == null) return;
                android.graphics.drawable.Drawable drawable = binding.padShot.getDrawable();
                if (drawable instanceof android.graphics.drawable.BitmapDrawable) applyShotMatrix(((android.graphics.drawable.BitmapDrawable) drawable).getBitmap());
            });
        }
    }

    private Frame shot(String target) {
        HttpURLConnection connection = null;
        try {
            String url = target + "/voice?cmd=shot" + selfParam(target) + (TextUtils.isEmpty(token(target)) ? "" : "&token=" + token(target));
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(6000);
            if (connection.getResponseCode() != 200) return null;
            int width = parseInt(connection.getHeaderField("X-Frame-W"));
            int height = parseInt(connection.getHeaderField("X-Frame-H"));
            byte[] data = readBytes(connection.getInputStream());
            return data.length == 0 ? null : new Frame(data, width, height);
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private byte[] readBytes(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) > 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private record Frame(byte[] data, int width, int height) {
    }

    private void sendTouch(String target, int x, int y, int action) {
        run(() -> request(target, "cmd=touch&x=" + x + "&y=" + y + "&a=" + action + selfParam(target), token(target)));
    }

    private String keyName(String key) {
        return switch (key) {
            case "up" -> str(R.string.voice_remote_up);
            case "down" -> str(R.string.voice_remote_down);
            case "left" -> str(R.string.voice_remote_left);
            case "right" -> str(R.string.voice_remote_right);
            case "ok" -> str(R.string.voice_remote_ok);
            case "menu" -> str(R.string.voice_remote_menu);
            case "back" -> str(R.string.voice_remote_back);
            case "home" -> str(R.string.voice_remote_home);
            default -> key;
        };
    }

    /** 打开面板后的自动流程：有目标就直接等说话，没有目标先选设备。 */
    private void autoStart() {
        if (binding == null || !isAdded()) return;
        // 打开遥控器不再自动开麦，先连上设备并显示当前连接的是谁
        updateStatus();
        autoConnect();
    }

    /** 当前目标在线就直连；不在线（或本来是本机）就去挑一台在线已绑定的远程设备，没得挑才用本机。 */
    private void autoConnect() {
        String current = Prefers.getString(KEY_TARGET, "");
        if (!TextUtils.isEmpty(current) && !isLocal(current)) {
            run(() -> {
                Result result = request(current, "cmd=ping", "");
                boolean online = result.code == 200 && result.body != null && result.body.startsWith("webhtv-voice:");
                handler.post(() -> {
                    if (binding == null || !isAdded()) return;
                    if (online) probeType(current);
                    else pickOnlineDevice();
                });
            });
            return;
        }
        pickOnlineDevice();
    }

    private void pickOnlineDevice() {
        final java.util.List<String> candidates = new java.util.ArrayList<>();
        org.json.JSONObject object = store();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String host = keys.next();
            org.json.JSONObject item = object.optJSONObject(host);
            if (item == null) continue;
            String url = item.optString("url", "");
            if (TextUtils.isEmpty(url)) url = "http://" + host + ":9978";
            if (isSelf(url)) continue;
            candidates.add(url);
        }
        if (candidates.isEmpty()) {
            fallbackLocal();
            return;
        }
        run(() -> {
            String found = null;
            for (String url : candidates) {
                Result result = request(url, "cmd=ping", "");
                if (result.code == 200 && result.body != null && result.body.startsWith("webhtv-voice:")) {
                    found = url;
                    break;
                }
            }
            final String target = found;
            handler.post(() -> {
                if (binding == null || !isAdded()) return;
                if (target == null) {
                    fallbackLocal();
                    return;
                }
                Prefers.put(KEY_TARGET, target);
                resetFrames(target);
                updateStatus();
            });
        });
    }

    private void fallbackLocal() {
        String local = localTarget();
        if (TextUtils.isEmpty(local)) {
            updateStatus();
            return;
        }
        Prefers.put(KEY_TARGET, local);
        resetFrames(local);
        updateStatus();
    }

    /** 本机（这台手机自己）：127.0.0.1 走本机服务，令牌直接取本地令牌，不需要配对。 */
    /** 控制本机时要告诉服务端：绕过遥控器自己的窗口，否则按键和截图都在操作面板本身。 */
    private String selfParam(String target) {
        return isLocal(target) ? "&self=1" : "";
    }

    private boolean isLocal(String target) {
        return target != null && (target.contains("127.0.0.1") || target.contains("localhost"));
    }

    private String token(String target) {
        if (isLocal(target)) return ServerToken.get();
        return tokenOf(target);
    }

    // ---------------------------------------------------------------- 设备库

    private org.json.JSONObject store() {
        try {
            return new org.json.JSONObject(Prefers.getString(KEY_STORE, "{}"));
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }

    private void saveStore(org.json.JSONObject object) {
        Prefers.put(KEY_STORE, object.toString());
    }

    /** 设备库按 IP 记（端口会变，换端口不用重新配对），值里同时存最近一次用到的 url。 */
    private String hostOf(String url) {
        String host = url == null ? "" : url.replace("http://", "").replace("https://", "");
        int index = host.indexOf(':');
        if (index >= 0) host = host.substring(0, index);
        index = host.indexOf('/');
        if (index >= 0) host = host.substring(0, index);
        return host.trim();
    }

    private void remember(String url, String name, String token) {
        String host = hostOf(url);
        if (TextUtils.isEmpty(host) || isLocal(url)) return;
        try {
            org.json.JSONObject object = store();
            // 老版本按完整 url 存过，迁移一把
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("http")) {
                    object.put(hostOf(key), object.opt(key));
                    object.remove(key);
                }
            }
            org.json.JSONObject item = object.optJSONObject(host);
            if (item == null) item = new org.json.JSONObject();
            if (!TextUtils.isEmpty(name)) item.put("name", name);
            if (!TextUtils.isEmpty(token)) item.put("token", token);
            item.put("url", url);
            object.put(host, item);
            saveStore(object);
        } catch (Exception ignored) {
        }
    }

    private String tokenOf(String url) {
        org.json.JSONObject item = store().optJSONObject(hostOf(url));
        return item == null ? "" : item.optString("token", "");
    }

    private String nameOf(String url) {
        org.json.JSONObject item = store().optJSONObject(hostOf(url));
        return item == null ? "" : item.optString("name", "");
    }

    /** 扫描到的地址是不是这台手机自己（取本机所有网卡地址比对）。 */
    private boolean isSelf(String url) {
        String host = url == null ? "" : url.replace("http://", "").replace("https://", "");
        int index = host.indexOf(':');
        if (index >= 0) host = host.substring(0, index);
        if (TextUtils.isEmpty(host)) return false;
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) return true;
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (!network.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress()) continue;
                    if (host.equals(address.getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String localTarget() {
        int port = Proxy.getPort();
        return port > 0 ? "http://127.0.0.1:" + port : "";
    }

    // ---------------------------------------------------------------- 配对

    /** 电视端弹确认框，用户同意后把令牌交回来并重发一次刚才的命令。 */
    private void pair(String target, String text) {
        show(R.string.voice_overlay_pairing);
        run(() -> {
            Result result = request(target, "cmd=pair", "");
            handler.post(() -> {
                if (result.code == 200 && !TextUtils.isEmpty(result.body)) {
                    remember(target, nameOf(target), result.body);
                    show(R.string.voice_overlay_paired);
                    if (!TextUtils.isEmpty(text)) send(text);
                } else {
                    show(str(R.string.voice_overlay_pair_failed, result.body == null ? String.valueOf(result.code) : result.body));
                }
            });
        });
    }

    // ---------------------------------------------------------------- 设备

    private void chooseDevice() {
        if (getActivity() == null || binding == null) return;
        devices.clear();
        // 先放已绑定的（历史配对过的），再等扫描结果补充
        org.json.JSONObject object = store();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String host = keys.next();
            org.json.JSONObject item = object.optJSONObject(host);
            if (item == null) continue;
            String url = item.optString("url", "");
            if (TextUtils.isEmpty(url)) url = "http://" + host + ":9978";
            devices.put(url, item.optString("name", ""));
        }
        startDiscovery();
        show(R.string.voice_overlay_scanning, devices.size());
        handler.postDelayed(() -> {
            stopDiscovery();
            if (!isAdded() || binding == null) return;
            showDeviceList();
        }, 1500);
    }

    private void showDeviceList() {
        String local = localTarget();
        List<String> labels = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (!TextUtils.isEmpty(local)) {
            labels.add(str(R.string.voice_overlay_local));
            targets.add(local);
            names.add(str(R.string.voice_overlay_local));
        }
        for (Map.Entry<String, String> entry : devices.entrySet()) {
            String name = TextUtils.isEmpty(entry.getValue()) ? nameOf(entry.getKey()) : entry.getValue();
            labels.add(deviceLabel(name, entry.getKey()));
            targets.add(entry.getKey());
            names.add(name);
        }
        if (labels.isEmpty()) {
            show(R.string.voice_overlay_no_device);
            return;
        }
        // 远程设备探一下在不在线，列表里标出来；本机不用探
        run(() -> {
            java.util.Map<String, Boolean> online = new java.util.HashMap<>();
            for (String target : targets) {
                if (isLocal(target)) continue;
                Result result = request(target, "cmd=ping", "");
                online.put(target, result.code == 200 && result.body != null && result.body.startsWith("webhtv-voice:"));
            }
            handler.post(() -> {
                if (!isAdded() || binding == null) return;
                presentDeviceList(labels, targets, names, online);
            });
        });
    }

    /** 列表文案：当前连接的那台标"已连接"，其它标"已绑定"，都带在线/离线。 */
    private void presentDeviceList(List<String> labels, List<String> targets, List<String> names, java.util.Map<String, Boolean> online) {
        List<String> shown = new ArrayList<>();
        String current = Prefers.getString(KEY_TARGET, "");
        for (int i = 0; i < labels.size(); i++) {
            String target = targets.get(i);
            String label = labels.get(i);
            if (isLocal(target)) {
                shown.add(label);
                continue;
            }
            boolean connected = target.equals(current);
            String state = str(connected ? R.string.voice_device_connected : R.string.voice_device_paired);
            boolean isOnline = Boolean.TRUE.equals(online.get(target));
            state += (isOnline ? str(R.string.voice_device_online) : str(R.string.voice_device_offline));
            shown.add(label + state);
        }
        android.content.Context context = getContext();
        if (context == null) return;
        new AlertDialog.Builder(context)
                .setTitle(R.string.voice_overlay_device)
                .setItems(shown.toArray(new String[0]), (dialog, which) -> {
                    String target = targets.get(which);
                    String name = names.get(which);
                    Prefers.put(KEY_TARGET, target);
                    resetFrames(target);
                    show(str(R.string.voice_overlay_selected, name));
                    if (isLocal(target)) return;
                    remember(target, name, "");
                    probeType(target);
                    // 已绑定的直接复用令牌，不再弹配对
                    if (!TextUtils.isEmpty(tokenOf(target))) return;
                    pair(target, "");
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    /** 列表项文案：和"一键同步"一致，第一行设备名、第二行 IP:端口。 */
    private String deviceLabel(String name, String url) {
        String host = url.replace("http://", "").replace("https://", "");
        return TextUtils.isEmpty(name) ? host : name + "\n" + host;
    }

    private void startDiscovery() {
        stopDiscovery();
        try {
            discovery = new NsdDeviceDiscovery(this);
            discovery.start();
        } catch (Exception e) {
            discovery = null;
        }
    }

    private void stopDiscovery() {
        if (discovery != null) {
            try {
                discovery.stop();
            } catch (Exception ignored) {
            }
            discovery = null;
        }
    }

    @Override
    public void onServiceFound(String url) {
        onServiceFound("", url);
    }

    @Override
    public void onServiceFound(String name, String url) {
        if (url == null) return;
        if (isSelf(url)) return; // 手机自己也在广播 mDNS，别把自己当可投的电视
        // 同一台设备换了端口：用扫到的新地址覆盖旧条目，令牌按 IP 复用，不需要重新配对
        String host = hostOf(url);
        for (java.util.Iterator<String> iterator = devices.keySet().iterator(); iterator.hasNext(); ) {
            String key = iterator.next();
            if (hostOf(key).equals(host)) iterator.remove();
        }
        devices.put(url, TextUtils.isEmpty(name) ? "" : name);
        remember(url, name, "");
        if (binding != null && devices.size() == 1 && TextUtils.isEmpty(Prefers.getString(KEY_TARGET, ""))) updateStatus();
    }

    // ---------------------------------------------------------------- 语音识别

    private void listen() {
        if (getContext() == null) return;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            show(R.string.voice_overlay_no_asr);
            return;
        }
        releaseRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
        recognizer.setRecognitionListener(this);
        android.content.Intent start = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        start.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        start.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString());
        start.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        try {
            recognizer.startListening(start);
            show(R.string.voice_overlay_listening);
        } catch (Exception e) {
            show(R.string.voice_overlay_no_asr);
        }
    }

    private void releaseRecognizer() {
        if (recognizer == null) return;
        try {
            recognizer.cancel();
            recognizer.destroy();
        } catch (Exception ignored) {
        }
        recognizer = null;
    }

    @Override
    public void onReadyForSpeech(android.os.Bundle params) {
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onError(int error) {
        show(error == SpeechRecognizer.ERROR_NO_MATCH ? R.string.voice_overlay_asr_nomatch
                : error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? R.string.voice_overlay_asr_permission
                : R.string.voice_overlay_asr_client);
        releaseRecognizer();
    }

    @Override
    public void onResults(android.os.Bundle results) {
        String text = first(results);
        if (!TextUtils.isEmpty(text)) {
            binding.input.setText(text);
            send(text);
        } else {
            show(R.string.voice_overlay_unrecognized);
        }
        releaseRecognizer();
    }

    @Override
    public void onPartialResults(android.os.Bundle partialResults) {
        String text = first(partialResults);
        if (!TextUtils.isEmpty(text)) binding.input.setText(text);
    }

    @Override
    public void onEvent(int eventType, android.os.Bundle params) {
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != REQUEST_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) listen();
        else show(R.string.voice_overlay_mic_permission);
    }

    private String first(android.os.Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    // ---------------------------------------------------------------- 网络

    private record Result(int code, String body) {
    }

    private Result request(String base, String query, String token) {
        HttpURLConnection connection = null;
        try {
            String url = base + "/voice?" + query + (TextUtils.isEmpty(token) ? "" : "&token=" + token);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(32000);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : read(stream);
            return new Result(code, body);
        } catch (Exception e) {
            return new Result(-1, e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String read(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int read;
        while ((read = stream.read(buffer)) > 0) output.write(buffer, 0, read);
        return output.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private void updateStatus() {
        String target = Prefers.getString(KEY_TARGET, "");
        if (TextUtils.isEmpty(target)) {
            show(R.string.voice_overlay_no_device);
            return;
        }
        String name = isLocal(target) ? str(R.string.voice_overlay_local) : nameOf(target);
        if (TextUtils.isEmpty(name)) name = target;
        show(str(R.string.voice_overlay_connected, name));
    }

    private void show(int resId) {
        if (binding == null) return;
        show(str(resId));
    }

    private void show(int resId, Object... args) {
        if (binding == null) return;
        show(str(resId, args));
    }

    private void show(String text) {
        if (binding == null) return;
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(text);
    }
}
