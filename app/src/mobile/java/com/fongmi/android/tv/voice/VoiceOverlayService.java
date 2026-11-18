package com.fongmi.android.tv.voice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.server.ServerToken;
import com.github.catvod.Proxy;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 手机端语音/文字指令悬浮球。
 *
 * <p>点球展开面板：输入框 + "说话"（系统语音识别）+ "设备"（局域网扫描选择电视）。指令原文
 * 直接发给电视端 {@code /voice?cmd=say}，由电视解析执行，手机不参与规则判断。</p>
 *
 * <p>首次连接某台电视需要配对：电视会弹一次确认框，用户用遥控器点"允许"后电视返回令牌，
 * 手机记住。之后所有请求都带这个令牌。</p>
 */
public class VoiceOverlayService extends android.app.Service implements NsdDeviceDiscovery.Listener, RecognitionListener {

    private static final String KEY_TARGET = "voice_lan_target";
    private static final String KEY_TOKEN = "voice_lan_token";
    private static final int OVERLAY_PERMISSION_REQUEST = 4210;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> devices = new LinkedHashSet<>();

    private WindowManager manager;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams panelParams;
    private View ball;
    private View panel;
    private EditText input;
    private TextView status;

    private NsdDeviceDiscovery discovery;
    private SpeechRecognizer recognizer;

    public static void start(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
                if (context instanceof android.app.Activity) ((android.app.Activity) context).startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
                else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Notify.show(R.string.voice_overlay_permission);
            } catch (Exception e) {
                Notify.show(R.string.voice_overlay_permission);
            }
            return;
        }
        try {
            context.startService(new Intent(context, VoiceOverlayService.class));
        } catch (Exception e) {
            Notify.show(e.getMessage() == null ? "voice overlay failed" : e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildBall();
        buildPanel();
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ball == null) buildBall();
        attach(ball, ballParams);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        detach(ball);
        detach(panel);
        stopDiscovery();
        releaseRecognizer();
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 悬浮球

    private void buildBall() {
        TextView view = new TextView(this);
        view.setText("AI");
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0xCC1F6FEB);
        shape.setStroke(2, Color.WHITE);
        view.setBackground(shape);
        view.setContentDescription(getString(R.string.voice_overlay_label));

        ballParams = new WindowManager.LayoutParams(dp(46), dp(46),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = dp(8);
        ballParams.y = dp(320);

        view.setOnTouchListener(new DragListener());
        ball = view;
        attach(ball, ballParams);
    }

    private class DragListener implements View.OnTouchListener {

        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    startX = ballParams.x;
                    startY = ballParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    moved = false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    ballParams.x = startX + dx;
                    ballParams.y = startY + dy;
                    if (ball != null) manager.updateViewLayout(ball, ballParams);
                }
                case MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel();
                }
                default -> {
                }
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- 面板

    private void buildPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(0xF2202020);
        shape.setStroke(1, 0x66FFFFFF);
        root.setBackground(shape);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText(R.string.voice_overlay_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        root.addView(title);

        input = new EditText(this);
        input.setHint(R.string.voice_overlay_hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x99FFFFFF);
        input.setTextSize(14);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(8);
        root.addView(input, inputParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        root.addView(row, rowParams);

        row.addView(button(R.string.voice_overlay_speak, view -> listen()));
        row.addView(button(R.string.voice_overlay_device, view -> chooseDevice()));
        row.addView(button(R.string.voice_overlay_send, view -> send(input.getText().toString())));

        status = new TextView(this);
        status.setTextColor(0xCCFFD166);
        status.setTextSize(12);
        status.setPadding(0, dp(6), 0, 0);
        status.setVisibility(View.GONE);
        root.addView(status);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(250), LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER;
        panel = root;
        panelParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.CENTER;
        panelParams.width = dp(272);
    }

    private Button button(int text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.setMarginEnd(dp(6));
        button.setLayoutParams(params);
        return button;
    }

    private void togglePanel() {
        if (panel == null) return;
        if (panel.getParent() != null) {
            detach(panel);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Notify.show(R.string.voice_overlay_permission);
            return;
        }
        try {
            manager.addView(panel, panelParams);
        } catch (Exception e) {
            Notify.show("add panel failed");
        }
    }

    private void attach(View view, WindowManager.LayoutParams params) {
        if (view == null || manager == null || view.getParent() != null) return;
        try {
            manager.addView(view, params);
        } catch (Exception ignored) {
        }
    }

    private void detach(View view) {
        if (view == null || manager == null || view.getParent() == null) return;
        try {
            manager.removeView(view);
        } catch (Exception ignored) {
        }
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
        if (result.code == 200) {
            if ("empty".equals(result.body)) show(R.string.voice_overlay_unrecognized);
            else if (result.body != null && result.body.startsWith("no-channel")) show(R.string.voice_overlay_no_channel);
            else show(getString(R.string.voice_overlay_done, result.body == null ? "" : result.body));
        } else {
            show(getString(R.string.voice_overlay_failed, result.code + " " + result.body));
        }
    }

    /** 本机（这台手机自己）：127.0.0.1 走本机 Nano 服务，令牌直接取本地配对令牌，不需要弹框配对。 */
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
        Activity activity = App.activity();
        if (activity == null) {
            Notify.show(R.string.voice_overlay_open_app);
            return;
        }
        devices.clear();
        String current = Prefers.getString(KEY_TARGET, "");
        if (!TextUtils.isEmpty(current)) devices.add(current);
        startDiscovery();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.voice_overlay_device)
                .setMessage(activity.getString(R.string.voice_overlay_scanning, devices.size()))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.voice_overlay_use_current, (dialog, which) -> {
                    stopDiscovery();
                    if (!TextUtils.isEmpty(Prefers.getString(KEY_TARGET, ""))) {
                        Prefers.put(KEY_TOKEN, "");
                        show(R.string.voice_overlay_ready);
                    }
                })
                .show();
        handler.postDelayed(() -> {
            stopDiscovery();
            showDeviceList(activity);
        }, 2500);
    }

    private void showDeviceList(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || devices.isEmpty()) {
            Notify.show(R.string.voice_overlay_no_device);
            return;
        }
        // 第一项固定是本机：不用连局域网设备，手机自己就能搜索/开播/控制。
        String local = localTarget();
        List<String> labels = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        if (!TextUtils.isEmpty(local)) {
            labels.add(getString(R.string.voice_overlay_local));
            targets.add(local);
        }
        for (String device : devices) {
            labels.add(device);
            targets.add(device);
        }
        if (labels.isEmpty()) {
            Notify.show(R.string.voice_overlay_no_device);
            return;
        }
        new AlertDialog.Builder(activity)
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

    private void startDiscovery() {
        stopDiscovery();
        try {
            discovery = new NsdDeviceDiscovery(this);
            discovery.start();
        } catch (Exception e) {
            discovery = null;
            Notify.show(R.string.voice_overlay_no_device);
        }
    }

    private void stopDiscovery() {
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
    }

    @Override
    public void onServiceFound(String url) {
        if (url != null) devices.add(url);
    }

    // ---------------------------------------------------------------- 语音识别

    private void listen() {
        Activity activity = App.activity();
        if (activity == null) {
            Notify.show(R.string.voice_overlay_open_app);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4211);
            Notify.show(R.string.voice_overlay_mic_permission);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Notify.show(R.string.voice_overlay_no_asr);
            return;
        }
        releaseRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            recognizer.startListening(intent);
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
        int[] messages = {
                R.string.voice_overlay_asr_net, R.string.voice_overlay_asr_net, R.string.voice_overlay_asr_audio,
                R.string.voice_overlay_asr_silence, R.string.voice_overlay_asr_server, R.string.voice_overlay_asr_client,
                R.string.voice_overlay_asr_silence, R.string.voice_overlay_asr_nomatch, R.string.voice_overlay_asr_busy,
                R.string.voice_overlay_asr_permission, R.string.voice_overlay_asr_silence, R.string.voice_overlay_asr_client
        };
        int index = error >= 1 && error <= messages.length ? error - 1 : messages.length - 1;
        show(messages[index]);
    }

    @Override
    public void onResults(android.os.Bundle results) {
        String text = first(results);
        if (!TextUtils.isEmpty(text)) {
            input.setText(text);
            send(text);
        } else {
            show(R.string.voice_overlay_unrecognized);
        }
        releaseRecognizer();
    }

    @Override
    public void onPartialResults(android.os.Bundle partialResults) {
        String text = first(partialResults);
        if (!TextUtils.isEmpty(text)) input.setText(text);
    }

    @Override
    public void onEvent(int eventType, android.os.Bundle params) {
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

    private void show(int resId) {
        show(getString(resId));
    }

    private void show(String text) {
        if (status == null) return;
        status.setVisibility(View.VISIBLE);
        status.setText(text);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

}
