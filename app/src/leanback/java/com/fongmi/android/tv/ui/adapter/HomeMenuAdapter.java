package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterDohBinding;

public class HomeMenuAdapter extends RecyclerView.Adapter<HomeMenuAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final String[] items;

    public HomeMenuAdapter(OnClickListener listener, String[] items) {
        this.listener = listener;
        this.items = items;
    }

    public interface OnClickListener {
        void onItemClick(int position);
    }

    @Override
    public int getItemCount() {
        return items.length;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDohBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(items[position]);
        holder.binding.text.setOnClickListener(v -> listener.onItemClick(position));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterDohBinding binding;

        public ViewHolder(@NonNull AdapterDohBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
