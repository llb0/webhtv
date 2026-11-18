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
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogVoiceControlBinding;
import com.fongmi.android.tv.server.ServerToken;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;
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
public class VoiceControlDialog extends BaseBottomSheetDialog implements NsdDeviceDiscovery.Listener, RecognitionListener {

    private static final String KEY_TARGET = "voice_lan_target";
    private static final String KEY_TOKEN = "voice_lan_token";
    /** 底部按钮行的对齐方式：0 居中 1 靠左 2 靠右。 */
    private static final String KEY_ALIGN = "voice_panel_align";
    private static final int REQUEST_MIC = 4211;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 设备地址 → 设备名（mDNS 注册名去掉 WebHTV- 前缀），列表里显示"名称 + IP"。 */
    private final Map<String, String> devices = new LinkedHashMap<>();

    private DialogVoiceControlBinding binding;
    private NsdDeviceDiscovery discovery;
    private SpeechRecognizer recognizer;

    public static VoiceControlDialog create() {
        return new VoiceControlDialog();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogVoiceControlBinding.inflate(inflater, container, false);
    }

    @Override
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
        binding.menu.setOnClickListener(view -> nav("menu"));
        binding.back.setOnClickListener(view -> nav("back"));
        binding.home.setOnClickListener(view -> nav("home"));
        binding.up.setOnClickListener(view -> nav("up"));
        binding.down.setOnClickListener(view -> nav("down"));
        binding.left.setOnClickListener(view -> nav("left"));
        binding.right.setOnClickListener(view -> nav("right"));
        binding.ok.setOnClickListener(view -> nav("ok"));
        binding.align.setOnClickListener(view -> cycleAlign());
        applyAlign();
        updateStatus();
        // 打开面板就进入"等待说话"：已选好设备直接开麦，没选设备先弹设备列表。
        binding.getRoot().postDelayed(this::autoStart, 350);
    }

    /** 不允许下滑关闭（可以按返回键关闭）。 */
    @Override
    protected void setBehavior(com.google.android.material.bottomsheet.BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        android.widget.FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        com.google.android.material.bottomsheet.BottomSheetBehavior<android.widget.FrameLayout> behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
        behavior.setDraggable(false);
        // 面板整体收窄居中，两侧露出背景（原来是通栏，看起来像整块糊在屏幕上）
        int screen = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(com.fongmi.android.tv.utils.ResUtil.dp2px(340), screen - com.fongmi.android.tv.utils.ResUtil.dp2px(32));
        android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) sheet.getLayoutParams();
        params.width = width;
        params.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
        sheet.setLayoutParams(params);
    }

    /** 底部两排按钮的对齐方式：居中 → 靠左 → 靠右 循环，记住上次选择。 */
    private void cycleAlign() {
        int next = (Prefers.getInt(KEY_ALIGN, 0) + 1) % 3;
        Prefers.put(KEY_ALIGN, next);
        applyAlign();
    }

    private void applyAlign() {
        if (binding == null) return;
        int gravity;
        int text;
        switch (Prefers.getInt(KEY_ALIGN, 0)) {
            case 1 -> { gravity = android.view.Gravity.START; text = R.string.voice_remote_align_left; }
            case 2 -> { gravity = android.view.Gravity.END; text = R.string.voice_remote_align_right; }
            default -> { gravity = android.view.Gravity.CENTER_HORIZONTAL; text = R.string.voice_remote_align_center; }
        }
        binding.rowFirst.setGravity(gravity);
        binding.rowSecond.setGravity(gravity);
        binding.align.setText(text);
    }

    @Override
    public void onDestroyView() {
        stopDiscovery();
        releaseRecognizer();
        executor.shutdownNow();
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
        executor.execute(() -> {
            Result result = request(target, "cmd=say&q=" + encode(text), token(target));
            handler.post(() -> handleResult(target, text, result));
        });
    }

    private void handleResult(String target, String text, Result result) {
        if (result.code == 401) {
            if (isLocal(target)) show(getString(R.string.voice_overlay_failed, "401"));
            else pair(target, text);
            return;
        }
        if (result.code != 200) {
            show(getString(R.string.voice_overlay_failed, result.code + " " + result.body));
            return;
        }
        String body = result.body == null ? "" : result.body;
        if (body.startsWith("no-site:")) show(getString(R.string.voice_site_not_found, body.substring("no-site:".length())));
        else if ("empty".equals(body)) show(R.string.voice_overlay_unrecognized);
        else show(getString(R.string.voice_overlay_done, body));
    }

    /** 发一个方向/菜单键给目标设备，由它投给当前前台界面。 */
    private void nav(String key) {
        String target = Prefers.getString(KEY_TARGET, "");
        if (TextUtils.isEmpty(target)) {
            chooseDevice();
            return;
        }
        show(R.string.voice_overlay_sending);
        executor.execute(() -> {
            Result result = request(target, "cmd=nav&k=" + key, token(target));
            handler.post(() -> {
                if (result.code == 401) {
                    if (isLocal(target)) show(getString(R.string.voice_overlay_failed, "401"));
                    else pair(target, "");
                    return;
                }
                if (result.code != 200) show(getString(R.string.voice_key_failed, result.code + " " + result.body));
                else show(getString(R.string.voice_overlay_done, result.body));
            });
        });
    }

    /** 打开面板后的自动流程：有目标就直接等说话，没有目标先选设备。 */
    private void autoStart() {
        if (binding == null || !isAdded()) return;
        String target = Prefers.getString(KEY_TARGET, "");
        if (TextUtils.isEmpty(target)) {
            chooseDevice();
            return;
        }
        listen();
    }

    /** 本机（这台手机自己）：127.0.0.1 走本机服务，令牌直接取本地令牌，不需要配对。 */
    private boolean isLocal(String target) {
        return target != null && (target.contains("127.0.0.1") || target.contains("localhost"));
    }

    private String token(String target) {
        return isLocal(target) ? ServerToken.get() : Prefers.getString(KEY_TOKEN, "");
    }

    private String localTarget() {
        int port = Proxy.getPort();
        return port > 0 ? "http://127.0.0.1:" + port : "";
    }

    // ---------------------------------------------------------------- 配对

    /** 电视端弹确认框，用户同意后把令牌交回来并重发一次刚才的命令。 */
    private void pair(String target, String text) {
        show(R.string.voice_overlay_pairing);
        executor.execute(() -> {
            Result result = request(target, "cmd=pair", "");
            handler.post(() -> {
                if (result.code == 200 && !TextUtils.isEmpty(result.body)) {
                    Prefers.put(KEY_TOKEN, result.body);
                    show(R.string.voice_overlay_paired);
                    if (!TextUtils.isEmpty(text)) send(text);
                } else {
                    show(getString(R.string.voice_overlay_pair_failed, result.body == null ? String.valueOf(result.code) : result.body));
                }
            });
        });
    }

    // ---------------------------------------------------------------- 设备

    private void chooseDevice() {
        if (getActivity() == null || binding == null) return;
        devices.clear();
        String current = Prefers.getString(KEY_TARGET, "");
        if (!TextUtils.isEmpty(current)) devices.put(current, "");
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
        if (!TextUtils.isEmpty(local)) {
            labels.add(getString(R.string.voice_overlay_local));
            targets.add(local);
        }
        for (Map.Entry<String, String> entry : devices.entrySet()) {
            labels.add(deviceLabel(entry.getValue(), entry.getKey()));
            targets.add(entry.getKey());
        }
        if (labels.isEmpty()) {
            show(R.string.voice_overlay_no_device);
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.voice_overlay_device)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String target = targets.get(which);
                    boolean changed = !target.equals(Prefers.getString(KEY_TARGET, ""));
                    Prefers.put(KEY_TARGET, target);
                    if (changed) Prefers.put(KEY_TOKEN, "");
                    show(getString(R.string.voice_overlay_selected, labels.get(which)));
                    if (!isLocal(target)) pair(target, "");
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
        devices.put(url, TextUtils.isEmpty(name) ? "" : name);
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
        if (TextUtils.isEmpty(target)) return;
        show(getString(R.string.voice_overlay_selected, isLocal(target) ? getString(R.string.voice_overlay_local) : target));
    }

    private void show(int resId) {
        show(getString(resId));
    }

    private void show(int resId, Object... args) {
        show(getString(resId, args));
    }

    private void show(String text) {
        if (binding == null) return;
        binding.status.setVisibility(View.VISIBLE);
        binding.status.setText(text);
    }
}
