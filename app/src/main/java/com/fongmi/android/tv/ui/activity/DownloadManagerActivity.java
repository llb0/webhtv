package com.fongmi.android.tv.ui.activity;

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

import java.util.List;

/** 下载管理界面：任务列表（取消/删除）+ 存储路径设置；由状态栏下载通知点击进入。 */
public class DownloadManagerActivity extends BaseActivity implements DownloadAdapter.OnClickListener {

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
    public void onCancel(DownloadTask task) {
        DownloadManager.get().cancelTask(task.id);
        refreshList();
    }

    @Override
    public void onRemove(DownloadTask task) {
        DownloadManager.get().removeTask(task.id);
        refreshList();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }
}
