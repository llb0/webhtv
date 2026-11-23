package com.fongmi.android.tv.ui.activity;

/**
 * 本地 SMB 浏览页：扫描/收藏局域网主机，登录后选择共享目录，浏览视频、音乐、图片和 APK。
 *
 * <p>入口默认关闭，由“设置 → 个性设置 → 本地SMB播放入口”控制：手机端显示底栏 SMB 项，
 * 电视端显示首页菜单“选站”和“多仓”之间的“本地SMB”。视频/音乐通过
 * {@link com.fongmi.android.tv.smb.SmbPlaybackProxy} 生成临时 HTTP 地址后交给
 * {@link VideoActivity#startSmb} 播放；图片在当前页预览，APK 交给系统安装器。
 * 主机备注、收藏和隐藏项保存在 SharedPreferences("smb_hosts")；登录密码默认只保留在本次页面内存，
 * 用户勾选“保存登录信息”后才按主机写入经 Android Keystore 加密的 smb_credentials，可随时清除。</p>
 */

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.smb.SmbClient;
import com.fongmi.android.tv.smb.SmbCredentialStore;
import com.fongmi.android.tv.smb.SmbNetworkScanner;
import com.fongmi.android.tv.smb.SmbPlaybackProxy;
import com.fongmi.android.tv.smb.SmbRowAdapter;
import com.fongmi.android.tv.smb.SmbThumbnailCache;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmbBrowserActivity extends AppCompatActivity {

    private enum Mode { HOSTS, SHARES, FILES, HIDDEN_FOLDERS, FAVORITES }

    private static final int REQUEST_LOCAL_NETWORK = 7301;
    private static final int REQUEST_INSTALL_APK_PERMISSION = 7302;
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String PREF_HIDDEN_PASSWORD = "hidden_password";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> hosts = new ArrayList<>();
    private final List<String> shares = new ArrayList<>();
    private final List<SmbClient.Entry> files = new ArrayList<>();
    private final List<SmbClient.Entry> visibleFiles = new ArrayList<>();
    private final Map<String, String> savedHosts = new LinkedHashMap<>();
    private final Set<String> hiddenHosts = new LinkedHashSet<>();
    private final Set<String> hiddenFolders = new LinkedHashSet<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<String> hiddenEntries = new ArrayList<>();

    private LinearLayout root;
    private TextView title;
    private TextView status;
    private ListView list;
    private SmbRowAdapter adapter;
    /** 图片预览点击分区用：最后一次按下/抬起的横坐标。 */
    private float lastTouchX;
    /** 从收藏夹点开图片时，目录列表还没加载完，翻页范围先用收藏夹里算好的这份。 */
    private List<String> pendingImagePages;

    private Mode mode = Mode.HOSTS;
    private String host;
    private String share;
    private String path = "";
    private String username = "";
    private String password = "";
    private String domain = "";
    private boolean loginAttempted;
    private boolean showingHiddenHosts;
    private boolean showingHiddenFolders;
    private boolean showingFavorites;
    private boolean installingApk;
    private File pendingApkFile;

    public static void start(Activity activity) {
        activity.startActivity(new android.content.Intent(activity, SmbBrowserActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSavedHosts();
        buildUi();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mode == Mode.HOSTS && !showingHiddenHosts && !showingHiddenFolders && !showingFavorites) finish();
                else goBack();
            }
        });
        scanNetwork();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));
        int surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFF10131B);
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);
        int onSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFB8C4D8);
        root.setBackgroundColor(surface);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setFocusable(false);
        toolbar.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        title = new TextView(this);
        title.setText(getString(R.string.smb_browser_title));
        title.setTextColor(onSurface);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        MaterialButton back = themedButton("关闭");
        back.setOnClickListener(v -> finish());
        MaterialButton favoritesButton = themedButton("收藏夹");
        favoritesButton.setOnClickListener(v -> showFavorites());
        MaterialButton hiddenButton = themedButton("显示隐藏");
        hiddenButton.setOnClickListener(v -> requestShowHiddenItems());
        back.setId(View.generateViewId());
        favoritesButton.setId(View.generateViewId());
        hiddenButton.setId(View.generateViewId());
        addToolbarButton(toolbar, back);
        addToolbarButton(toolbar, favoritesButton);
        addToolbarButton(toolbar, hiddenButton);
        root.addView(toolbar);

        status = new TextView(this);
        status.setTextColor(onSurfaceVariant);
        status.setTextSize(14);
        status.setPadding(dp(8), dp(12), dp(8), dp(12));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setId(View.generateViewId());
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setDividerHeight(dp(1));
        list.setDivider(new ColorDrawable(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, 0x334F5B6B)));
        list.setBackgroundColor(surface);
        adapter = new SmbRowAdapter(this);
        list.setAdapter(adapter);
        configureToolbarFocus(back, favoritesButton, hiddenButton);
        list.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_UP
                    && list.getSelectedItemPosition() <= 0) {
                back.requestFocus();
                return true;
            }
            return false;
        });
        list.setOnItemClickListener((parent, view, position, id) -> onItemClick(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            // 手机端长按与电视端菜单键共用同一套菜单：当前条目操作 + 收藏夹/显示隐藏/隐藏密码/主机/关闭。
            showContextMenu(position);
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void configureToolbarFocus(MaterialButton back, MaterialButton favorites, MaterialButton hidden) {
        MaterialButton[] buttons = new MaterialButton[]{back, favorites, hidden};
        for (int i = 0; i < buttons.length; i++) {
            MaterialButton button = buttons[i];
            button.setFocusable(true);
            button.setFocusableInTouchMode(false);
            button.setNextFocusDownId(list.getId());
            if (i > 0) button.setNextFocusLeftId(buttons[i - 1].getId());
            if (i < buttons.length - 1) button.setNextFocusRightId(buttons[i + 1].getId());
        }
        list.setNextFocusUpId(back.getId());
    }

    private void addToolbarButton(LinearLayout toolbar, MaterialButton button) {
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2), button.getPaddingTop(), dp(2), button.getPaddingBottom());
        button.setTextSize(18);
        button.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        toolbar.addView(button, params);
    }

    private MaterialButton themedButton(String label) {
        int background = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF394B63);
        int foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFFFFFFF);
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setStrokeWidth(dp(2));
        button.setStrokeColor(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{}
                },
                new int[]{0xFF8CCBFF, Color.TRANSPARENT}
        ));
        button.setCornerRadius(dp(10));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void loadSavedHosts() {
        savedHosts.clear();
        android.content.SharedPreferences preferences = getSharedPreferences("smb_hosts", MODE_PRIVATE);
        loadStringSet(preferences, "hidden", hiddenHosts);
        loadStringSet(preferences, "hidden_folders", hiddenFolders);
        loadStringSet(preferences, "favorites", favorites);
        String json = preferences.getString("saved", "{}");
        try {
            JSONObject object = new JSONObject(json);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String host = keys.next();
                savedHosts.put(host, object.optString(host, ""));
            }
        } catch (Exception ignored) {
        }
    }

    private void loadStringSet(android.content.SharedPreferences preferences, String key, Set<String> target) {
        try {
            Object stored = preferences.getAll().get(key);
            if (!(stored instanceof Set<?>)) return;
            Set<String> snapshot = new LinkedHashSet<>();
            for (Object value : (Set<?>) stored) {
                if (value instanceof String) snapshot.add((String) value);
            }
            target.clear();
            target.addAll(snapshot);
        } catch (RuntimeException error) {
            if (logActive()) android.util.Log.d("WebHTV-SMB", "load key=" + key + " failed: " + error.getClass().getSimpleName());
        }
    }

    private void persistStringSet(String key, Set<String> values) {
        boolean saved = getSharedPreferences("smb_hosts", MODE_PRIVATE).edit()
                .putStringSet(key, new HashSet<>(values)).commit();
        if (!saved) Toast.makeText(this, "隐藏记录保存失败，请重试", Toast.LENGTH_LONG).show();
    }

    private static boolean logActive() { return false; }

    private void persistSavedHosts() {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : savedHosts.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
            getSharedPreferences("smb_hosts", MODE_PRIVATE).edit().putString("saved", object.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    private void persistHiddenHosts() {
        persistStringSet("hidden", hiddenHosts);
    }

    private void persistHiddenFolders() {
        persistStringSet("hidden_folders", hiddenFolders);
    }

    private String folderKey(String folderPath) {
        return host + "\n" + share + "\n" + folderPath;
    }

    private String shareKey(String shareName) {
        return host + "\n" + shareName + "\n";
    }

    private boolean isHiddenHost(String value) {
        String expected = normalizeSmbPath(value);
        for (String item : hiddenHosts) if (normalizeSmbPath(item).equals(expected)) return true;
        return false;
    }

    private boolean isHiddenShare(String shareName) {
        if (host == null || shareName == null) return false;
        String expectedHost = normalizeSmbPath(host);
        String expectedShare = normalizeSmbPath(shareName);
        for (String value : hiddenFolders) {
            String[] parts = value.split("\\n", 3);
            if (parts.length == 3 && normalizeSmbPath(parts[2]).isEmpty()
                    && normalizeSmbPath(parts[0]).equals(expectedHost)
                    && normalizeSmbPath(parts[1]).equals(expectedShare)) return true;
        }
        return false;
    }

    private boolean isHiddenFolder(String folderPath) {
        if (host == null || share == null || folderPath == null) return false;
        String expectedHost = normalizeSmbPath(host);
        String expectedShare = normalizeSmbPath(share);
        String expectedPath = normalizeSmbPath(folderPath);
        for (String value : hiddenFolders) {
            String[] parts = value.split("\\n", 3);
            if (parts.length == 3 && !normalizeSmbPath(parts[2]).isEmpty()
                    && normalizeSmbPath(parts[0]).equals(expectedHost)
                    && normalizeSmbPath(parts[1]).equals(expectedShare)
                    && normalizeSmbPath(parts[2]).equals(expectedPath)) return true;
        }
        return false;
    }

    private String normalizeSmbPath(String value) {
        if (value == null) return "";
        String normalized = value.trim().replace('/', '\\');
        while (normalized.endsWith("\\") && !normalized.isEmpty()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private String hiddenPassword() {
        return getSharedPreferences("smb_hosts", MODE_PRIVATE).getString(PREF_HIDDEN_PASSWORD, "");
    }

    private void requestShowHiddenItems() {
        String saved = hiddenPassword();
        if (saved.isEmpty()) {
            showHiddenItems();
            return;
        }
        EditText input = field("输入隐藏密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("显示隐藏")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("确定", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (saved.equals(input.getText().toString())) {
                dialog.dismiss();
                showHiddenItems();
            } else {
                Toast.makeText(this, "隐藏密码不正确", Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
    }

    private void showHiddenPasswordDialog() {
        EditText input = field("设置隐藏密码（不能为空）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("设置隐藏密码")
                .setMessage("设置后，点击“显示隐藏”需要先输入密码；可在同一菜单取消。")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                Toast.makeText(this, "隐藏密码不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            getSharedPreferences("smb_hosts", MODE_PRIVATE).edit()
                    .putString(PREF_HIDDEN_PASSWORD, value).commit();
            Toast.makeText(this, "已设置隐藏密码", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void clearHiddenPassword() {
        String saved = hiddenPassword();
        if (saved.isEmpty()) {
            Toast.makeText(this, "当前没有设置隐藏密码", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = field("输入当前隐藏密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("取消隐藏密码")
                .setMessage("请输入当前隐藏密码，验证通过后才会取消。")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("验证并取消", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!saved.equals(input.getText().toString().trim())) {
                Toast.makeText(this, "隐藏密码不正确", Toast.LENGTH_SHORT).show();
                return;
            }
            getSharedPreferences("smb_hosts", MODE_PRIVATE).edit()
                    .remove(PREF_HIDDEN_PASSWORD).commit();
            Toast.makeText(this, "已取消隐藏密码", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showHiddenItems() {
        showingHiddenHosts=true; showingHiddenFolders=true; showingFavorites=false;
        mode=Mode.HIDDEN_FOLDERS; title.setText("显示隐藏"); hiddenEntries.clear();
        List<String> ips=new ArrayList<>(hiddenHosts); ips.sort(String.CASE_INSENSITIVE_ORDER);
        for(String value:ips) hiddenEntries.add("IP|"+value);
        List<String> paths=new ArrayList<>(hiddenFolders);
        paths.sort((x,y)->hiddenLabel(x).compareToIgnoreCase(hiddenLabel(y)));
        for(String value:paths) hiddenEntries.add("PATH|"+value);
        List<String> labels=new ArrayList<>();
        for(String entry:hiddenEntries) {
            String[] pair=entry.split("\\|",2); String value=pair[1];
            if(pair[0].equals("IP")) labels.add(value);
            else { String[] p=value.split("\\n",3);
                labels.add(p.length==3?p[0]+" / "+p[1]+(p[2].isEmpty()?"":" / "+p[2]):value); }
        }
        setItems(labels);
        setStatus(hiddenEntries.isEmpty()?"没有隐藏项目":(isTvDevice()?"短按打开，菜单键恢复显示":"短按打开，长按恢复显示"));
    }

    /**
     * 显示隐藏列表和收藏夹一样是“入口”，单击要先打开对应主机/共享/目录。
     * 恢复显示只放在长按（手机）或菜单键（电视）的上下文菜单里，避免点一下就把隐藏项放出来。
     */
    private void openHiddenItem(int position) {
        if (position < 0 || position >= hiddenEntries.size()) return;
        String[] pair = hiddenEntries.get(position).split("\\|", 2);
        if (pair.length != 2) return;
        if (pair[0].equals("IP")) {
            connectHost(pair[1], false);
        } else {
            openHiddenFolderValue(pair[1]);
        }
    }

    private void openHiddenFolderValue(String value) {
        if (value == null) return;
        String[] p = value.split("\\n", 3);
        if (p.length != 3) return;
        String targetHost = p[0];
        String targetShare = p[1];
        String targetPath = p[2];
        if (host == null || !host.equals(targetHost)) {
            host = targetHost;
            username = "";
            password = "";
            domain = "";
            SmbCredentialStore.Stored stored = SmbCredentialStore.load(this, host);
            if (stored != null) {
                username = stored.username;
                password = stored.password;
                domain = stored.domain;
            }
        }
        share = targetShare;
        path = targetPath;
        showingHiddenHosts = false;
        showingHiddenFolders = false;
        showingFavorites = false;
        if (path.isEmpty()) openShare(share); else loadDirectory();
    }

    private String hiddenLabel(String value) {
        String[] p=value.split("\\n",3);
        return p.length==3?p[0]+" / "+p[1]+" / "+p[2]:value;
    }
    private void activateHiddenItem(int position) {
        if(position<0||position>=hiddenEntries.size()) return;
        String[] p=hiddenEntries.get(position).split("\\|",2);
        if(p[0].equals("IP")) restoreHiddenHost(p[1]); else restoreHiddenFolderValue(p[1]);
    }
    private void restoreHiddenFolderValue(String value) {
        if(value==null||!hiddenFolders.remove(value)) return;
        persistHiddenFolders(); showHiddenItems();
        Toast.makeText(this,"已恢复显示路径",Toast.LENGTH_SHORT).show();
    }

    private String favoriteKey(String remote, boolean directory) {
        return host+"\n"+share+"\n"+remote+"\n"+(directory?"D":"F");
    }
    private void addFavorite(String remote, boolean directory) {
        if(remote==null||remote.isEmpty()) return;
        favorites.add(favoriteKey(remote,directory)); persistStringSet("favorites",favorites);
        Toast.makeText(this,"已加入收藏夹",Toast.LENGTH_SHORT).show();
    }
    private void showFolderActions(String folderPath) { showFileActions(folderPath,true); }
    private void showFileActions(String remote, boolean directory) {
        String key=favoriteKey(remote,directory); boolean saved=favorites.contains(key);
        String[] actions=directory?new String[]{"隐藏此文件夹",saved?"取消收藏":"加入收藏夹"}
                :new String[]{saved?"取消收藏":"加入收藏夹"};
        new MaterialAlertDialogBuilder(this).setTitle(remote).setItems(actions,(d,w)->{
            if(directory&&w==0) hideFolder(remote);
            else { if(saved) favorites.remove(key); else favorites.add(key);
                persistStringSet("favorites",favorites); }
        }).show();
    }
    private void showFavorites() {
        showingFavorites=true; showingHiddenHosts=false; showingHiddenFolders=false;
        mode=Mode.FAVORITES; title.setText("收藏夹");
        List<String> labels=new ArrayList<>();
        for(String value:favorites) { String[] p=value.split("\\n",4);
            if(p.length==4) labels.add((p[3].equals("D")?"📁  ":"★  ")+p[0]+" / "+p[1]+" / "+p[2]); }
        setItems(labels);
        setStatus(favorites.isEmpty()?"收藏夹为空":(isTvDevice()?"短按打开，菜单键取消收藏":"短按打开，长按取消收藏"));
    }
    private void openFavorite(int position) {
        List<String> values=new ArrayList<>(favorites);
        if(position<0||position>=values.size()) return;
        String[] p=values.get(position).split("\\n",4); if(p.length!=4)return;
        // 收藏夹只是主机/目录之上的临时视图；打开收藏项后必须退出该视图，
        // 否则列表已经换成目标文件夹，点击仍会按收藏夹的下标执行（看起来像一层透明列表挡着）。
        showingFavorites=false;
        if (host == null || !host.equals(p[0])) {
            host=p[0];
            username=""; password=""; domain="";
            SmbCredentialStore.Stored stored = SmbCredentialStore.load(this, host);
            if (stored != null) {
                username=stored.username;
                password=stored.password;
                domain=stored.domain;
            }
        }
        share=p[1]; String remote=p[2]; boolean dir=p[3].equals("D");
        if(dir) {
            path=remote;
            mode=Mode.FILES;
            if(path.isEmpty()) openShare(share); else loadDirectory();
            return;
        }
        int cut=remote.lastIndexOf((char)92); String name=cut<0?remote:remote.substring(cut+1);
        path=cut<0?"":remote.substring(0,cut);
        mode=Mode.FILES;
        // 先回到文件所在目录，播放/预览返回时列表就是真实目录，而不是残留的收藏夹。
        List<String> favoritePages = new ArrayList<>();
        if (SmbClient.isImage(name)) {
            for (String value : values) {
                String[] parts = value.split("\n", 4);
                if (parts.length != 4 || parts[3].equals("D")) continue;
                if (!parts[0].equals(host) || !parts[1].equals(share)) continue;
                if (SmbClient.isImage(parts[2])) favoritePages.add(parts[2]);
            }
            pendingImagePages = favoritePages;
        }
        loadDirectory();
        if(SmbClient.isVideo(name)||SmbClient.isAudio(name)) play(remote);
        else if(SmbClient.isImage(name)) showImage(remote);
        else if(SmbClient.isApk(name)) installApk(remote);
    }

    private void showShareActions(String shareName) {
        if (shareName == null || shareName.isEmpty()) return;
        new MaterialAlertDialogBuilder(this).setTitle(shareName)
                .setItems(new String[]{"隐藏此路径"},(d,w)->hideShare(shareName)).show();
    }

    private void hideShare(String shareName) {
        if (host == null || shareName == null || shareName.isEmpty()) return;
        hiddenFolders.add(shareKey(shareName));
        persistHiddenFolders();
        shares.remove(shareName);
        setItems(shares);
        setStatus("已隐藏路径：" + host + " / " + shareName);
    }

    private void hideFolder(String folderPath) {
        if (host == null || share == null || folderPath == null || folderPath.isEmpty()) return;
        hiddenFolders.add(folderKey(folderPath));
        persistHiddenFolders();
        loadDirectory();
        Toast.makeText(this, "已隐藏文件夹：" + folderPath, Toast.LENGTH_SHORT).show();
    }

    private void showFavoriteActions(int position) {
        List<String> values = new ArrayList<>(favorites);
        if (position < 0 || position >= values.size()) return;
        String favorite = values.get(position);
        new MaterialAlertDialogBuilder(this)
                .setItems(new String[]{"取消收藏"}, (dialog, which) -> {
                    if (!favorites.remove(favorite)) return;
                    persistStringSet("favorites", favorites);
                    showFavorites();
                    Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showHostsAndRescan() {
        showingFavorites = false;
        showingHiddenHosts = false;
        showingHiddenFolders = false;
        scanNetworkInternal();
    }

    /**
     * 电视菜单键/手机长按的统一菜单。先列当前条目自身操作，再追加全局操作，
     * 这样主机页、目录页、收藏夹和隐藏列表都至少能调到收藏夹/显示隐藏/主机/关闭。
     */
    private void showContextMenu(int position) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (showingHiddenHosts || showingHiddenFolders) {
            if (position >= 0 && position < hiddenEntries.size()) {
                int target = position;
                labels.add("恢复显示");
                actions.add(() -> activateHiddenItem(target));
            }
        } else if (showingFavorites) {
            List<String> values = new ArrayList<>(favorites);
            if (position >= 0 && position < values.size()) {
                String favorite = values.get(position);
                labels.add("取消收藏");
                actions.add(() -> removeFavorite(favorite));
            }
        } else if (mode == Mode.HOSTS) {
            if (position >= 0 && position < hosts.size()) addHostMenuActions(hosts.get(position), labels, actions);
        } else if (mode == Mode.SHARES) {
            if (position >= 0 && position < shares.size()) {
                String shareName = shares.get(position);
                labels.add("隐藏此路径");
                actions.add(() -> hideShare(shareName));
            }
        } else if (mode == Mode.FILES) {
            if (position >= 0 && position < visibleFiles.size()) {
                SmbClient.Entry entry = visibleFiles.get(position);
                String remote = path.isEmpty() ? entry.name : path + "\\" + entry.name;
                addFileMenuActions(remote, entry.directory, labels, actions);
            }
        }
        labels.add("收藏夹");
        actions.add(this::showFavorites);
        labels.add("显示隐藏");
        actions.add(this::requestShowHiddenItems);
        boolean hasPassword = !hiddenPassword().isEmpty();
        labels.add(hasPassword ? "取消隐藏密码" : "设置隐藏密码");
        actions.add(hasPassword ? this::clearHiddenPassword : this::showHiddenPasswordDialog);
        labels.add("主机");
        actions.add(this::showHostsAndRescan);
        labels.add("关闭");
        actions.add(this::finish);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("SMB 菜单")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    d.dismiss();
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .create();
        dialog.setOnShowListener(d -> installMenuFocusLoop(dialog));
        dialog.show();
    }

    private void installMenuFocusLoop(AlertDialog dialog) {
        ListView menuList = dialog.getListView();
        if (menuList == null) return;
        menuList.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int count = menuList.getAdapter() == null ? 0 : menuList.getAdapter().getCount();
            if (count <= 0) return false;
            int selected = menuList.getSelectedItemPosition();
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && selected <= 0) {
                menuList.setSelection(count - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && selected >= count - 1) {
                menuList.setSelection(0);
                return true;
            }
            return false;
        });
    }

    private void addHostMenuActions(String value, List<String> labels, List<Runnable> actions) {
        boolean saved = savedHosts.containsKey(value);
        boolean hasCredential = SmbCredentialStore.has(this, value);
        if (saved) {
            labels.add("修改备注");
            actions.add(() -> showHostNoteDialog(value));
            labels.add("取消保存");
            actions.add(() -> {
                savedHosts.remove(value);
                persistSavedHosts();
                setItems(hostLabels());
            });
        } else {
            labels.add("保存并备注");
            actions.add(() -> showHostNoteDialog(value));
        }
        labels.add("隐藏此地址");
        actions.add(() -> hideHost(value));
        if (hasCredential) {
            labels.add("清除登录信息");
            actions.add(() -> {
                SmbCredentialStore.clear(this, value);
                if (host != null && host.equals(value)) {
                    username = "";
                    password = "";
                    domain = "";
                }
                Toast.makeText(this, R.string.smb_credentials_cleared, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void addFileMenuActions(String remote, boolean directory, List<String> labels, List<Runnable> actions) {
        String key = favoriteKey(remote, directory);
        boolean saved = favorites.contains(key);
        if (directory) {
            labels.add("隐藏此文件夹");
            actions.add(() -> hideFolder(remote));
        }
        labels.add(saved ? "取消收藏" : "加入收藏夹");
        actions.add(() -> {
            if (saved) favorites.remove(key);
            else favorites.add(key);
            persistStringSet("favorites", favorites);
        });
    }

    private void removeFavorite(String favorite) {
        if (!favorites.remove(favorite)) return;
        persistStringSet("favorites", favorites);
        showFavorites();
        Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    private boolean isTvDevice() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private void restoreHiddenHost(String value) {
        if (value == null) return;
        String stored = null;
        for (String item : hiddenHosts) if (normalizeSmbPath(item).equals(normalizeSmbPath(value))) { stored = item; break; }
        if (stored == null || !hiddenHosts.remove(stored)) return;
        persistHiddenHosts();
        refreshHostsQuietly();
        showHiddenItems();
        Toast.makeText(this, "已恢复显示：" + value, Toast.LENGTH_SHORT).show();
    }

    private void refreshHostsQuietly() {
        worker.execute(() -> {
            try {
                List<String> found = SmbNetworkScanner.scan();
                runOnUiThread(() -> {
                    hosts.clear();
                    for (String item : found) if (!isHiddenHost(item)) hosts.add(item);
                    for (String saved : savedHosts.keySet())
                        if (!isHiddenHost(saved) && !hosts.contains(saved)) hosts.add(saved);
                    if (mode == Mode.HOSTS) setItems(hostLabels());
                });
            } catch (Exception ignored) { }
        });
    }

    private void hideHost(String value) {
        if (value == null || value.isEmpty()) return;
        hiddenHosts.add(value);
        persistHiddenHosts();
        hosts.remove(value);
        setItems(hostLabels());
        setStatus("已隐藏地址：" + value);
    }

    private List<String> hostLabels() {
        List<String> labels = new ArrayList<>();
        for (String host : hosts) {
            String note = savedHosts.get(host);
            labels.add((note == null ? "" : "★ ") + host + (note == null || note.isEmpty() ? "" : "  ·  " + note));
        }
        return labels;
    }

    private void showHostActions(String value) {
        boolean saved = savedHosts.containsKey(value);
        boolean hasCredential = SmbCredentialStore.has(this, value);
        List<String> actions = new ArrayList<>();
        if (saved) {
            actions.add("修改备注");
            actions.add("取消保存");
        } else {
            actions.add("保存并备注");
        }
        actions.add("隐藏此地址");
        if (hasCredential) actions.add("清除登录信息");
        String[] items = actions.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(value)
                .setItems(items, (dialog, which) -> {
                    if (hasCredential && which == actions.size() - 1) {
                        SmbCredentialStore.clear(this, value);
                        if (host != null && host.equals(value)) {
                            username = "";
                            password = "";
                            domain = "";
                        }
                        Toast.makeText(this, R.string.smb_credentials_cleared, Toast.LENGTH_SHORT).show();
                    } else if ((!saved && which == 0) || (saved && which == 0)) {
                        showHostNoteDialog(value);
                    } else if (saved && which == 1) {
                        savedHosts.remove(value);
                        persistSavedHosts();
                        setItems(hostLabels());
                    } else {
                        hideHost(value);
                    }
                })
                .show();
    }

    private void showHostNoteDialog(String value) {
        EditText note = field("备注（可留空）", InputType.TYPE_CLASS_TEXT);
        note.setText(savedHosts.getOrDefault(value, ""));
        new MaterialAlertDialogBuilder(this)
                .setTitle("保存 SMB 主机")
                .setView(note)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", (dialog, which) -> {
                    savedHosts.put(value, note.getText().toString().trim());
                    persistSavedHosts();
                    setItems(hostLabels());
                })
                .show();
    }

    private void setStatus(String value) {
        status.setText(value == null ? "" : value);
    }

    private void setItems(List<String> values) {
        List<SmbRowAdapter.Row> rows = new ArrayList<>();
        if (values != null) for (String value : values) rows.add(SmbRowAdapter.Row.fromLabel(value));
        adapter.setRows(rows);
    }

    private void scanNetwork() {
        if (Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            setStatus(getString(R.string.smb_local_network_permission));
            requestPermissions(new String[]{LOCAL_NETWORK_PERMISSION}, REQUEST_LOCAL_NETWORK);
            return;
        }
        scanNetworkInternal();
    }

    private void scanNetworkInternal() {
        showingHiddenHosts = false;
        showingHiddenFolders = false;
        showingFavorites = false;
        mode = Mode.HOSTS;
        host = null;
        share = null;
        path = "";
        title.setText(getString(R.string.smb_browser_title));
        setItems(new ArrayList<>());
        setStatus(getString(R.string.smb_scanning));
        worker.execute(() -> {
            try {
                List<String> found = SmbNetworkScanner.scan();
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    hosts.clear();
                    for (String value : found) if (!isHiddenHost(value)) hosts.add(value);
                    for (String saved : savedHosts.keySet()) {
                        if (!isHiddenHost(saved) && !hosts.contains(saved)) hosts.add(saved);
                    }
                    setItems(hostLabels());
                    setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers)
                            : getString(R.string.smb_server_count, hosts.size()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hosts.clear();
                    for (String saved : savedHosts.keySet()) if (!isHiddenHost(saved)) hosts.add(saved);
                    setItems(hostLabels());
                    setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers) : getString(R.string.smb_server_count, hosts.size()));
                });
            }
        });
    }

    private void onItemClick(int position) {
        if(showingFavorites) { openFavorite(position); return; }
        if(showingHiddenHosts||showingHiddenFolders) { openHiddenItem(position); return; }
        if (mode == Mode.HOSTS) {
            if (position < hosts.size()) connectHost(hosts.get(position), false);
            return;
        }
        if (mode == Mode.SHARES) {
            if (position < shares.size()) openShare(shares.get(position));
            return;
        }
        if (mode == Mode.FILES) {
            if (position < 0 || position >= visibleFiles.size()) return;
            SmbClient.Entry entry = visibleFiles.get(position);
            String remotePath = path.isEmpty() ? entry.name : path + "\\" + entry.name;
            if (entry.directory) {
                path = remotePath;
                loadDirectory();
            } else if (SmbClient.isVideo(entry.name) || SmbClient.isAudio(entry.name)) {
                play(remotePath);
            } else if (SmbClient.isImage(entry.name)) {
                showImage(remotePath);
            } else if (SmbClient.isApk(entry.name)) {
                installApk(remotePath);
            } else {
                Toast.makeText(this, R.string.smb_play_video_only, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void connectHost(String value, boolean withCredentials) {
        String previousHost = host;
        host = value;
        if (!value.equals(previousHost)) {
            username = "";
            password = "";
            domain = "";
        }
        if (!withCredentials && username.isEmpty()) {
            // 保存过登录信息的主机优先用保存的账号，避免每次都先走匿名再弹登录框。
            SmbCredentialStore.Stored stored = SmbCredentialStore.load(this, host);
            if (stored != null) {
                username = stored.username;
                password = stored.password;
                domain = stored.domain;
            }
        }
        loginAttempted = false;
        setStatus("正在连接 " + value + " …");
        worker.execute(() -> {
            try {
                List<String> found = SmbClient.listShares(host, username, password, domain);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    shares.clear();
                    for (String shareName : found) {
                        if (!isHiddenShare(shareName)) shares.add(shareName);
                    }
                    mode = Mode.SHARES;
                    path = "";
                    showingFavorites = false;
                    showingHiddenHosts = false;
                    showingHiddenFolders = false;
                    title.setText(host);
                    setItems(shares);
                    if (found.isEmpty()) {
                        setStatus(getString(R.string.smb_no_shares));
                        if (!loginAttempted) showAuthDialog();
                    } else {
                        setStatus("选择共享目录");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    setStatus(getString(R.string.smb_connect_failed) + "：" + shortError(e));
                    if (!loginAttempted) showAuthDialog();
                    else Toast.makeText(this, R.string.smb_auth_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showAuthDialog() {
        loginAttempted = true;
        SmbCredentialStore.Stored stored = SmbCredentialStore.load(this, host);
        if (stored != null && username.isEmpty()) {
            username = stored.username;
            password = stored.password;
            domain = stored.domain;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);

        EditText user = field(getString(R.string.smb_username), InputType.TYPE_CLASS_TEXT);
        EditText pass = field(getString(R.string.smb_password),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText realm = field(getString(R.string.smb_domain), InputType.TYPE_CLASS_TEXT);
        user.setText(username);
        pass.setText(password);
        realm.setText(domain);
        form.addView(user);
        form.addView(pass);
        form.addView(realm);

        CheckBox save = new CheckBox(this);
        save.setText(R.string.smb_save_login);
        save.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF));
        save.setChecked(stored != null);
        save.setPadding(0, dp(6), 0, 0);
        form.addView(save);

        MaterialButton clear = themedButton(getString(R.string.smb_clear_login));
        clear.setVisibility(stored == null ? View.GONE : View.VISIBLE);
        clear.setOnClickListener(v -> {
            SmbCredentialStore.clear(this, host);
            username = "";
            password = "";
            domain = "";
            user.setText("");
            pass.setText("");
            realm.setText("");
            save.setChecked(false);
            clear.setVisibility(View.GONE);
            Toast.makeText(this, R.string.smb_credentials_cleared, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-1, dp(44));
        clearParams.topMargin = dp(4);
        form.addView(clear, clearParams);

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.smb_auth_title) + " · " + host)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> setStatus(getString(R.string.smb_auth_failed)))
                .setPositiveButton(R.string.smb_login, (dialog, which) -> {
                    username = user.getText().toString().trim();
                    password = pass.getText().toString();
                    domain = realm.getText().toString().trim();
                    boolean saveLogin = save.isChecked();
                    worker.execute(() -> {
                        try {
                            List<String> found = SmbClient.listShares(host, username, password, domain);
                            runOnUiThread(() -> {
                                shares.clear();
                                for (String shareName : found) {
                                    if (!isHiddenShare(shareName)) shares.add(shareName);
                                }
                                mode = Mode.SHARES;
                                path = "";
                                showingFavorites = false;
                                showingHiddenHosts = false;
                                showingHiddenFolders = false;
                                title.setText(host);
                                setItems(shares);
                                setStatus(found.isEmpty() ? getString(R.string.smb_no_shares) : "选择共享目录");
                                if (saveLogin) {
                                    try {
                                        SmbCredentialStore.save(this, host, username, password, domain);
                                        Toast.makeText(this, R.string.smb_credentials_saved, Toast.LENGTH_SHORT).show();
                                    } catch (Exception saveError) {
                                        Toast.makeText(this, R.string.smb_credentials_save_failed, Toast.LENGTH_SHORT).show();
                                    }
                                } else if (stored != null) {
                                    // 用户取消勾选“保存登录信息”：本次登录成功后清理旧凭据，选择才算真正生效。
                                    SmbCredentialStore.clear(this, host);
                                }
                                if (found.isEmpty()) {
                                    setStatus(getString(R.string.smb_no_shares));
                                    showManualShareDialog();
                                }
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                setStatus(getString(R.string.smb_connect_failed) + "：" + shortError(e));
                                showManualShareDialog();
                            });
                        }
                    });
                })
                .show();
    }

    private void showManualShareDialog() {
        EditText input = field(getString(R.string.smb_share_name), InputType.TYPE_CLASS_TEXT);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.smb_manual_share)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.smb_open_share, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) openShare(value);
                })
                .show();
    }

    private EditText field(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setInputType(inputType);
        return edit;
    }

    private void openShare(String value) {
        showingFavorites = false;
        share = value;
        path = "";
        title.setText(host + " / " + share);
        loadDirectory();
    }

    private void loadDirectory() {
        // 目录列表一旦接管列表控件，收藏夹视图必须退出；否则点击下标会落到收藏夹上，
        // 看起来像有一层看不见的收藏列表盖在目录上。
        showingFavorites = false;
        showingHiddenHosts = false;
        showingHiddenFolders = false;
        mode = Mode.FILES;
        setStatus("正在读取目录…");
        worker.execute(() -> {
            try {
                List<SmbClient.Entry> found = SmbClient.listFiles(host, share, path, username, password, domain);
                runOnUiThread(() -> {
                    files.clear();
                    files.addAll(found);
                    visibleFiles.clear();
                    List<SmbRowAdapter.Row> rows = new ArrayList<>();
                    for (SmbClient.Entry entry : found) {
                        String remotePath = path.isEmpty() ? entry.name : path + "\\" + entry.name;
                        if (entry.directory && isHiddenFolder(remotePath)) continue;
                        visibleFiles.add(entry);
                        if (entry.directory) {
                            rows.add(SmbRowAdapter.Row.icon(entry.name, "文件夹", "📁"));
                        } else if (SmbClient.isVideo(entry.name)) {
                            // 视频行给缩略图：取帧走缩略图专用 token 池，不会挤掉正在播放的直链。
                            String thumbUrl = null;
                            try {
                                thumbUrl = SmbPlaybackProxy.createThumbUrl(host, share, remotePath, username, password, domain);
                            } catch (IOException ignored) {
                                thumbUrl = null;
                            }
                            rows.add(thumbUrl == null
                                    ? SmbRowAdapter.Row.icon(entry.name, "视频", "▶")
                                    : SmbRowAdapter.Row.video(entry.name, "视频",
                                    SmbThumbnailCache.key(host, share, remotePath), thumbUrl));
                        } else if (SmbClient.isAudio(entry.name)) {
                            rows.add(SmbRowAdapter.Row.icon(entry.name, "音频", "♫"));
                        } else if (SmbClient.isImage(entry.name)) {
                            rows.add(SmbRowAdapter.Row.icon(entry.name, "图片", "🖼️"));
                        } else if (SmbClient.isApk(entry.name)) {
                            rows.add(SmbRowAdapter.Row.icon(entry.name, "安装包", "📦"));
                        } else {
                            rows.add(SmbRowAdapter.Row.icon(entry.name, "", "📄"));
                        }
                    }
                    adapter.setRows(rows);
                    title.setText(host + " / " + share + (path.isEmpty() ? "" : " / " + path));
                    setStatus(found.isEmpty() ? getString(R.string.smb_no_files)
                            : "卡片列表：视频/音乐可播放，图片可查看，APK 可安装");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(getString(R.string.smb_connect_failed) + "：" + shortError(e)));
            }
        });
    }

    private void installApk(String remotePath) {
        if (installingApk) return;
        installingApk = true;
        setStatus("正在下载 APK 安装包");

        final String targetHost = host;
        final String targetShare = share;
        final String targetUsername = username;
        final String targetPassword = password;
        final String targetDomain = domain;

        worker.execute(() -> {
            File apkFile = null;
            try {
                File directory = new File(getCacheDir(), "smb-apk");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("无法创建 APK 缓存目录");
                }

                String fileName = remotePath.substring(remotePath.lastIndexOf((char) 92) + 1)
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                if (fileName.isEmpty() || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    throw new IOException("无效的 APK 文件名");
                }

                apkFile = new File(directory, fileName);
                if (apkFile.exists() && !apkFile.delete()) {
                    throw new IOException("无法覆盖缓存中的同名 APK");
                }

                try (SmbClient.RemoteFile remote = SmbClient.openFile(
                        targetHost, targetShare, remotePath,
                        targetUsername, targetPassword, targetDomain);
                     FileOutputStream output = new FileOutputStream(apkFile)) {
                    long size = remote.length();
                    if (size <= 0) throw new IOException("APK 文件为空");

                    byte[] buffer = new byte[64 * 1024];
                    long offset = 0;
                    while (offset < size) {
                        int count = remote.read(buffer, offset, 0,
                                (int) Math.min((long) buffer.length, size - offset));
                        if (count <= 0) throw new IOException("APK 下载不完整");
                        output.write(buffer, 0, count);
                        offset += count;
                    }
                    output.flush();
                }

                File readyFile = apkFile;
                runOnUiThread(() -> {
                    installingApk = false;
                    if (isFinishing()) return;
                    setStatus("APK 下载完成，正在打开安装程序");
                    launchApkInstaller(readyFile);
                });
            } catch (Exception e) {
                if (apkFile != null && apkFile.exists()) apkFile.delete();
                runOnUiThread(() -> {
                    installingApk = false;
                    if (isFinishing()) return;
                    setStatus("APK 下载失败：" + shortError(e));
                    Toast.makeText(this, "APK 下载失败：" + shortError(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void launchApkInstaller(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingApkFile = apkFile;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("允许安装应用")
                    .setMessage("需要允许 WebHTV 安装未知来源应用，授权后会继续打开安装程序。")
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> pendingApkFile = null)
                    .setPositiveButton("前往设置", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_INSTALL_APK_PERMISSION);
                        } catch (Exception e) {
                            pendingApkFile = null;
                            Toast.makeText(this, "无法打开安装权限设置", Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
            return;
        }
        openApkInstaller(apkFile);
    }

    private void openApkInstaller(File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".provider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开 APK 安装程序：" + shortError(e), Toast.LENGTH_LONG).show();
        }
    }

    private void play(String remotePath) {
        try {
            String url = SmbPlaybackProxy.createUrl(host, share, remotePath, username, password, domain);
            String name = remotePath.substring(remotePath.lastIndexOf((char) 92) + 1);
            VideoActivity.startSmb(this, SiteApi.PUSH, url, name);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.smb_connect_failed) + "：" + shortError(e), Toast.LENGTH_LONG).show();
        }
    }

    private void showImage(String remotePath) {
        List<String> pages = imagePages(remotePath);
        int index = pages.indexOf(remotePath);
        if (index < 0) {
            pages = new ArrayList<>();
            pages.add(remotePath);
            index = 0;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.BLACK);

        TextView imageTitle = new TextView(this);
        imageTitle.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE));
        imageTitle.setTextSize(18);
        imageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        imageTitle.setGravity(Gravity.CENTER_VERTICAL);
        imageTitle.setPadding(dp(20), dp(12), dp(20), dp(12));
        imageTitle.setBackgroundColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimaryContainer, 0xFF394B63));
        content.addView(imageTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        FrameLayout imageContainer = new FrameLayout(this);
        imageContainer.setClipChildren(false);
        imageContainer.setClipToPadding(false);
        content.addView(imageContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setFocusable(true);
        image.setFocusableInTouchMode(false);
        imageContainer.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        Dialog dialog = new Dialog(this);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(false);
        ImagePager pager = new ImagePager(dialog, imageTitle, image, imageContainer, pages, index);

        dialog.setOnKeyListener((dialogInterface, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    pager.toggleFullscreen(false);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_PAGE_UP:
                    pager.step(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    pager.step(1);
                    return true;
                default:
                    return false;
            }
        });
        // 手机竖屏：左右各三分之一翻页，中间进入/退出全屏；横屏时不用旋转图片，直接全屏横看。
        image.setOnClickListener(v -> {
            if (isTvDevice()) {
                pager.toggleFullscreen(false);
                return;
            }
            float x = v.getWidth() <= 0 ? 0 : lastTouchX / v.getWidth();
            if (x < 0.34f) {
                pager.step(-1);
            } else if (x > 0.66f) {
                pager.step(1);
            } else {
                pager.toggleFullscreen(true);
            }
        });
        image.setOnTouchListener((v, event) -> {
            lastTouchX = event.getX();
            return false;
        });
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.84f));
            }
            image.requestFocus();
        });
        dialog.setOnDismissListener(ignored -> pager.restoreOrientation());
        pager.bind();
        dialog.show();
    }

    /** 翻页范围：正常浏览时是当前目录里的图片，从收藏夹打开时是收藏夹里的图片。 */
    private List<String> imagePages(String remotePath) {
        if (pendingImagePages != null) {
            List<String> pending = pendingImagePages;
            pendingImagePages = null;
            return pending;
        }
        List<String> pages = new ArrayList<>();
        if (showingFavorites) {
            // 收藏项按 "host\nshare\npath\nD|F" 存；只取同一主机同一共享下的非目录图片，
            // 避免把别的共享里的图片混进翻页范围。
            for (String value : favorites) {
                String[] parts = value.split("\n", 4);
                if (parts.length != 4 || parts[3].equals("D")) continue;
                if (!parts[0].equals(host) || !parts[1].equals(share)) continue;
                if (SmbClient.isImage(parts[2])) pages.add(parts[2]);
            }
            return pages;
        }
        String folder = remotePath.lastIndexOf((char) 92) > 0 ? remotePath.substring(0, remotePath.lastIndexOf((char) 92)) : "";
        for (SmbClient.Entry entry : visibleFiles) {
            if (entry.directory) continue;
            String candidate = folder.isEmpty() ? entry.name : folder + "\\" + entry.name;
            if (SmbClient.isImage(candidate) && !isHiddenFolder(candidate)) pages.add(candidate);
        }
        return pages;
    }

    /** 图片预览的翻页与全屏状态：电视左右键/手机左右三分之一点翻页，全屏在手机上真横屏，退出还原方向。 */
    private final class ImagePager {

        private final Dialog dialog;
        private final TextView imageTitle;
        private final ImageView image;
        private final FrameLayout container;
        private final List<String> pages;
        private int index;
        private boolean landscape;
        private int savedOrientation = Integer.MIN_VALUE;

        ImagePager(Dialog dialog, TextView imageTitle, ImageView image, FrameLayout container,
                   List<String> pages, int index) {
            this.dialog = dialog;
            this.imageTitle = imageTitle;
            this.image = image;
            this.container = container;
            this.pages = pages;
            this.index = index;
        }

        void bind() {
            image.setRotation(0f);
            image.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            String path = pages.get(index);
            String name = path.substring(path.lastIndexOf((char) 92) + 1);
            imageTitle.setText(pages.size() > 1 ? (index + 1) + "/" + pages.size() + "  " + name : name);
            try {
                String url = SmbPlaybackProxy.createUrl(host, share, path, username, password, domain);
                Glide.with(SmbBrowserActivity.this)
                        .load(url)
                        .override(getResources().getDisplayMetrics().widthPixels,
                                getResources().getDisplayMetrics().heightPixels)
                        .downsample(DownsampleStrategy.AT_MOST)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_report_image)
                        .fitCenter()
                        .into(image);
            } catch (Exception e) {
                Toast.makeText(SmbBrowserActivity.this,
                        getString(R.string.smb_connect_failed) + "：" + shortError(e), Toast.LENGTH_LONG).show();
            }
        }

        void step(int delta) {
            int target = index + delta;
            if (target < 0) {
                Toast.makeText(SmbBrowserActivity.this, "已经是第一张", Toast.LENGTH_SHORT).show();
                return;
            }
            if (target >= pages.size()) {
                Toast.makeText(SmbBrowserActivity.this, "已经是最后一张", Toast.LENGTH_SHORT).show();
                return;
            }
            index = target;
            bind();
        }

        /** 进入或退出全屏；phoneLandscape 为真时手机上顺便切成横屏（退出时还原原方向）。 */
        void toggleFullscreen(boolean phoneLandscape) {
            boolean fullscreen = imageTitle.getVisibility() == View.VISIBLE;
            toggleImageFullscreen(dialog, imageTitle);
            if (isTvDevice()) return;
            if (fullscreen) {
                if (phoneLandscape) enterLandscape();
            } else {
                restoreOrientation();
            }
            if (fullscreen) fitWindow(true);
            else fitWindow(false);
        }

        private void enterLandscape() {
            if (landscape) return;
            savedOrientation = getRequestedOrientation();
            landscape = true;
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        void restoreOrientation() {
            if (!landscape) return;
            landscape = false;
            if (savedOrientation != Integer.MIN_VALUE) setRequestedOrientation(savedOrientation);
            savedOrientation = Integer.MIN_VALUE;
        }

        private void fitWindow(boolean fullscreen) {
            Window window = dialog.getWindow();
            if (window == null) return;
            container.post(() -> {
                if (!dialog.isShowing()) return;
                if (fullscreen) {
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                } else {
                    window.setLayout(
                            (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                            (int) (getResources().getDisplayMetrics().heightPixels * 0.84f));
                }
            });
        }
    }

    private void toggleImageFullscreen(Dialog dialog, TextView imageTitle) {
        Window window = dialog.getWindow();
        if (window == null) return;
        boolean fullscreen = imageTitle.getVisibility() == View.VISIBLE;
        if (fullscreen) {
            imageTitle.setVisibility(View.GONE);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            imageTitle.setVisibility(View.VISIBLE);
            window.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.84f));
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private String shortError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.length() > 90 ? message.substring(0, 90) : message;
    }

    private void goBack() {
        if (showingFavorites || showingHiddenHosts || showingHiddenFolders) {
            showingFavorites = false;
            showingHiddenHosts = false;
            showingHiddenFolders = false;
            mode = Mode.HOSTS;
            title.setText(getString(R.string.smb_browser_title));
            setItems(hostLabels());
            setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers)
                    : getString(R.string.smb_server_count, hosts.size()));
        } else if (mode == Mode.HIDDEN_FOLDERS) {
            showingHiddenFolders = false;
            mode = Mode.HOSTS;
            title.setText(getString(R.string.smb_browser_title));
            setItems(hostLabels());
            setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers) : getString(R.string.smb_server_count, hosts.size()));
        } else if (mode == Mode.FILES) {
            if (!path.isEmpty()) {
                int cut = path.lastIndexOf('\\');
                path = cut < 0 ? "" : path.substring(0, cut);
                loadDirectory();
            } else {
                mode = Mode.SHARES;
                title.setText(host);
                setItems(shares);
                setStatus("选择共享目录");
            }
        } else if (mode == Mode.SHARES) {
            mode = Mode.HOSTS;
            title.setText(getString(R.string.smb_browser_title));
            setItems(hosts);
            setStatus(hosts.isEmpty() ? getString(R.string.smb_no_servers) : getString(R.string.smb_server_count, hosts.size()));
        } else {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_INSTALL_APK_PERMISSION) return;

        File apkFile = pendingApkFile;
        pendingApkFile = null;
        if (apkFile == null) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls()) {
            openApkInstaller(apkFile);
        } else {
            Toast.makeText(this, "尚未允许 WebHTV 安装未知来源应用", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCAL_NETWORK) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanNetworkInternal();
            } else {
                setStatus(getString(R.string.smb_local_network_permission));
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isTvDevice() && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_MODE)) {
            int position = list == null ? ListView.INVALID_POSITION : list.getSelectedItemPosition();
            // 菜单键不再只服务当前条目：主机页没有条目时也能调出收藏夹/显示隐藏/隐藏密码/主机/关闭。
            showContextMenu(position);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
