package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.DialogDeviceBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.dlna.DLNACast;
import com.fongmi.android.tv.dlna.DLNACastManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.smb.SmbCastSource;
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.ui.adapter.DeviceAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ScanTask;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class CastDialog extends BaseBottomSheetDialog implements DeviceAdapter.OnClickListener, ScanTask.Listener, DLNACastManager.DeviceListener, Callback {

    private final FormBody.Builder body;
    private final OkHttpClient client;

    private DialogDeviceBinding binding;
    private DeviceAdapter adapter;
    private ScanTask scanTask;
    private CastVideo video;
    private boolean historyReady;
    private boolean fm;
    private String historyName;

    public CastDialog() {
        scanTask = new ScanTask(this);
        body = new FormBody.Builder();
        body.add("device", Device.get().toString());
        body.add("config", Config.vod().toString());
        client = OkHttp.client(Constant.TIMEOUT_SYNC);
    }

    public static CastDialog create() {
        return new CastDialog();
    }

    public CastDialog history(History history) {
        if (history == null || TextUtils.isEmpty(history.getVodId())) return this;
        String fd = history.getVodId();
        if (fd.startsWith("/")) fd = Server.get().getAddress() + "/file" + fd.replace(Path.rootPath(), "");
        if (fd.startsWith("file")) fd = Server.get().getAddress() + "/" + fd.replace(Path.rootPath(), "").replace("://", "");
        // 本地 SMB 播放只传 SMB 连接要素（主机/共享/路径/账号），由接收端自己启动
        // SmbPlaybackProxy 并走它自己的 127.0.0.1 播放：不依赖发送端的进程、端口和局域网地址，
        // 发送端退出后接收端仍能继续播。SMB 是共享本身可达，不是发送端代理可达。
        String smb = SmbCastSource.describe(fd);
        if (!TextUtils.isEmpty(smb)) fd = smb;
        // 其余本机代理地址（HLS、文件服务等）仍要把回环地址换成局域网地址才发得出去。
        else if (fd.contains("127.0.0.1")) {
            String ip = Util.getIp();
            if (!TextUtils.isEmpty(ip)) fd = fd.replace("127.0.0.1", ip);
        }
        History copy = History.objectFrom(history.toString());
        if (copy == null) return this;
        // 发送端的本地截图在远端不存在，占位图/坏图也一并清空，让接收端按自己的非黑屏截图重新兜底。
        if (History.isMissingArtwork(copy.getVodPic())) copy.setVodPic("");
        if (History.isMissingArtwork(copy.getWallPic())) copy.setWallPic("");
        if (!TextUtils.isEmpty(historyName)) copy.setVodName(historyName);
        // 关键：用结构化的 key 重写，不再在已序列化的 JSON 里做字符串替换。
        // 旧写法 copy.toString().replace(copy.getVodId(), fd) 匹配不上——Gson 会把地址里的
        // “?”“=”等字符转义成 \u003d，导致发出去的仍是发送端本机地址，接收端连自己的
        // 127.0.0.1 失败，表现就是 0 时长、一直转圈、最后报“本地代理端口连接失败”。
        copy.setKey(copy.getSiteKey() + AppDatabase.SYMBOL + fd + AppDatabase.SYMBOL + copy.getCid());
        body.add("history", copy.toString());
        historyReady = true;
        return this;
    }

    public CastDialog history(History history, String name) {
        this.historyName = name;
        return history(history);
    }

    public CastDialog video(CastVideo video) {
        this.video = video;
        return this;
    }

    public CastDialog fm(boolean fm) {
        this.fm = fm;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof CastDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDeviceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.scan.setVisibility(fm ? View.VISIBLE : View.GONE);
        DLNACastManager.get().init(requireActivity());
        DLNACastManager.get().setDeviceListener(this);
        setRecyclerView();
        getDevice();
    }

    @Override
    protected void initEvent() {
        binding.scan.setOnClickListener(v -> onScan());
        binding.refresh.setOnClickListener(v -> onRefresh());
    }

    private void setRecyclerView() {
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter = new DeviceAdapter(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
    }

    private void setRecyclerVisible() {
        binding.recycler.setVisibility(adapter.getItemCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void getDevice() {
        adapter.setItems(Device.getAll(), () -> {
            adapter.sort(DLNACastManager.get().getRegistered(), this::setRecyclerVisible);
            if (adapter.getItemCount() == 0) onRefresh();
            else DLNACastManager.get().search();
        });
    }

    private void onScan() {
        launcher.launch(new Intent(requireActivity(), ScanActivity.class));
    }

    private void onRefresh() {
        adapter.clear(() -> {
            Device.delete();
            if (fm) scanTask.start();
            DLNACastManager.get().search();
            adapter.sort(DLNACastManager.get().getRegistered(), this::setRecyclerVisible);
        });
    }

    private void onCasted() {
        ((CastDialog.Listener) requireActivity()).onCasted();
        dismiss();
    }

    @Override
    public void onDeviceAdded(Device device) {
        binding.recycler.setVisibility(View.VISIBLE);
        adapter.sort(device);
    }

    @Override
    public void onDeviceRemoved(Device device) {
        adapter.remove(device);
    }

    @Override
    public void onFind(Device device) {
        binding.recycler.setVisibility(View.VISIBLE);
        adapter.sort(device);
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
        App.post(() -> Notify.show(e.getMessage()));
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try (Response res = response) {
            if (res.body().string().equals("OK")) App.post(this::onCasted);
            else App.post(() -> Notify.show(R.string.device_offline));
        }
    }

    @Override
    public void onItemClick(Device item) {
        if (item.isDLNA()) {
            if (video == null || TextUtils.isEmpty(video.url())) {
                Notify.show(R.string.cast_not_ready);
                return;
            }
            new DLNACast(video, this::onCasted).cast(item);
        } else {
            if (!historyReady) {
                Notify.show(R.string.cast_not_ready);
                return;
            }
            OkHttp.newCall(client, item.getIp().concat("/action?do=cast"), body.build()).enqueue(this);
        }
    }

    @Override
    public boolean onLongClick(Device item) {
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        DLNACastManager.get().setDeviceListener(null);
        DLNACastManager.get().release(requireActivity());
        scanTask.stop();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) scanTask.start(result.getData().getStringExtra("address"));
    });

    public interface Listener {

        void onCasted();
    }
}
