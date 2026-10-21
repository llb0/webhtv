package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityDownloadManagerBinding;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.download.DownloadTask;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.DownloadAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** 下载管理界面：任务列表（暂停/继续/重试/取消/删除）+ 存储路径设置；由状态栏下载通知点击进入。 */
public class DownloadManagerActivity extends BaseActivity implements DownloadAdapter.OnClickListener {

    public static void start(Context context) {
        context.startActivity(new Intent(context, DownloadManagerActivity.class));
    }
 
    private final ActivityResultLauncher<Intent> dirLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null) return;
                String path = FileChooser.getPathFromUri(uri);
                if (path == null || path.isEmpty()) return;
                Setting.setDownloadDir(path);
                Notify.show(R.string.download_manager_path_changed);
                updatePath();
            });

    private ActivityDownloadManagerBinding mBinding;
    private DownloadAdapter mAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshList();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDownloadManagerBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setTitle(R.string.download_manager_title);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.setAdapter(mAdapter = new DownloadAdapter(this));
        mBinding.pathRow.setOnClickListener(v -> FileChooser.from(dirLauncher).showDirectory());
        updatePath();
        refreshList();
    }

    private void updatePath() {
        mBinding.path.setText(Setting.getDownloadDir());
    }

    private void refreshList() {
        List<DownloadTask> tasks = DownloadManager.get().getAllTasks();
        mAdapter.setItems(tasks);
        mBinding.empty.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    @Override
    public void onPause(DownloadTask task) {
        DownloadManager.get().pauseTask(task.id);
        refreshList();
    }
 
    @Override
    public void onResume(DownloadTask task) {
        DownloadManager.get().resumeTask(task.id);
        refreshList();
    }
 
    @Override
    public void onRetry(DownloadTask task) {
        DownloadManager.get().retryTask(task.id);
        refreshList();
    }
 
    @Override
    public void onCancel(DownloadTask task) {
        DownloadManager.get().cancelTask(task.id);
        refreshList();
    }

    @Override
    public void onRemove(DownloadTask task) {
        DownloadManager.get().removeTask(task.id);
        refreshList();
    }

    /** 播放成品。
     *
     *  这里必须反射：本 Activity 在 main 源集，而 VideoActivity 只在 mobile / leanback
     *  两个 flavor 源集里各有一份，main 无法直接引用（直接引用会编译不过）。
     *  走 VideoActivity.file() 而不是 file:// 直传播放器，是因为它内部会把本地文件
     *  换算成本机代理地址（http://192.168.x.x:port/file/...），这样既能本机播，
     *  也能在播放器里推到局域网设备；用 127.0.0.1 的话远程设备拉不到流。 */
    @Override
    public void onPlay(DownloadTask task) {
        File file = DownloadAdapter.outputFile(task);
        if (file == null) {
            Notify.show(R.string.download_failed_notify_title);
            refreshList();
            return;
        }
        try {
            Class<?> clazz = Class.forName("com.fongmi.android.tv.ui.activity.VideoActivity");
            String path = file.getAbsolutePath();
            Method m = findFileMethod(clazz);
            if (m == null) throw new NoSuchMethodException("VideoActivity.file");
            m.invoke(null, this, path);
        } catch (Throwable e) {
            Notify.show(R.string.download_failed_notify_title);
        }
    }

    /** 兼容两种签名：file(FragmentActivity, String) / file(Activity, String) */
    private static Method findFileMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!"file".equals(m.getName())) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[1] == String.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }
}
