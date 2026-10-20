package com.fongmi.android.tv.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterDownloadBinding;
import com.fongmi.android.tv.download.DownloadTask;

import java.util.ArrayList;
import java.util.List;

/** 下载任务列表适配器：显示名称/进度/状态，并提供取消（进行中）或删除（已结束）按钮。 */
public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {

    public interface OnClickListener {
        void onCancel(DownloadTask task);   // 取消进行中的任务
        void onRemove(DownloadTask task);   // 删除已结束的任务记录
    }

    private final OnClickListener listener;
    private final List<DownloadTask> items = new ArrayList<>();

    public DownloadAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<DownloadTask> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDownloadBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadTask t = items.get(position);
        holder.binding.name.setText(t.name);
        holder.binding.status.setText(describe(holder.binding.getRoot().getContext(), t));
        if (t.totalSize > 0) holder.binding.progress.setProgress((int) Math.min(100L, t.downloadedBytes * 100 / t.totalSize));
        else if (t.totalSegments > 0) holder.binding.progress.setProgress(Math.min(100, t.completedSegments * 100 / t.totalSegments));
        else holder.binding.progress.setProgress(0);
        boolean running = t.status == DownloadTask.Status.RUNNING;
        holder.binding.action.setText(running ? R.string.download_manager_cancel : R.string.download_manager_delete);
        holder.binding.action.setOnClickListener(v -> {
            if (running) listener.onCancel(t);
            else listener.onRemove(t);
        });
    }

    private String describe(Context ctx, DownloadTask t) {
        String size = t.totalSize > 0
                ? formatSize(t.downloadedBytes) + " / " + formatSize(t.totalSize)
                : formatSize(t.downloadedBytes);
        String status;
        switch (t.status) {
            case COMPLETED: status = ctx.getString(R.string.download_done_notify_title); break;
            case FAILED: status = ctx.getString(R.string.download_failed_notify_title); break;
            case CANCELLED: status = ctx.getString(R.string.download_manager_cancelled); break;
            case RUNNING: status = ctx.getString(R.string.download_notify_title); break;
            default: status = ctx.getString(R.string.download_manager_pending); break;
        }
        return status + " · " + size;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format("%.1f %s", v, units[i]);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final AdapterDownloadBinding binding;

        ViewHolder(AdapterDownloadBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
