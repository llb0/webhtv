package com.fongmi.android.tv.ui.dialog;

import android.view.KeyEvent;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogHomeMenuBinding;
import com.fongmi.android.tv.ui.activity.SettingActivity;
import com.fongmi.android.tv.ui.adapter.HomeMenuAdapter;
import com.fongmi.android.tv.utils.KeyUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class HomeMenuDialog extends BaseAlertDialog implements HomeMenuAdapter.OnClickListener {

    private DialogHomeMenuBinding binding;
    private String[] items;
    private int spanCount;

    public static HomeMenuDialog create() {
        return new HomeMenuDialog();
    }

    public HomeMenuDialog items(String[] items) {
        this.items = items;
        this.spanCount = calcSpanCount(items.length);
        return this;
    }

    private int calcSpanCount(int count) {
        int span = (int) Math.ceil(Math.sqrt(count));
        return Math.max(2, Math.min(5, span));
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), HomeMenuDialog.class.getSimpleName());
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogHomeMenuBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot()).setOnKeyListener((dialog, keyCode, event) -> {
            if (KeyUtil.isMenuKey(event) && event.getAction() == KeyEvent.ACTION_DOWN) {
                SettingActivity.start(requireActivity());
                dismiss();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void initView() {
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setLayoutManager(new GridLayoutManager(getActivity(), spanCount));
        binding.recycler.setAdapter(new HomeMenuAdapter(this, items));
    }

    @Override
    public void onItemClick(int position) {
        ((Listener) requireActivity()).onHomeMenu(position);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        float width = 0.3f + spanCount * 0.1f;
        setWidth(Math.min(width, 0.8f));
    }

    public interface Listener {
        void onHomeMenu(int which);
    }
}
