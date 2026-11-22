package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogSiteBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.SiteGroupOrderStore;
import com.fongmi.android.tv.theme.ThemeController;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;
import com.fongmi.android.tv.ui.adapter.SiteAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.helper.SiteDialogTheme;
import com.fongmi.android.tv.ui.helper.TouchOptimizationHelper;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener, SiteAdapter.OnDeleteListener {

    private static final int GRID_COUNT = 3;
    private static final String TAG = "site_dialog";
    private static final int INITIAL_BATCH = 48;
    /** 站点卡片高度，必须与 adapter_site*.xml 的 layout_height 一致。 */
    private static final int ROW_HEIGHT_DP = 52;
    /** 网格行间距，必须与 SpaceItemDecoration(GRID_COUNT, 16) 的 16 一致。 */
    private static final int ROW_SPACING_DP = 16;
    /**
     * 面板占屏宽的比例，对齐仓库既有大弹窗惯例（CacheManagementDialog.SCREEN_FRACTION = 0.9f）。
     * 这里刻意不再叠加 dp 上限：真机电视常见 density 1.0，1920px 屏即 1920dp，旧上限 1180dp
     * 会把面板压到只剩 61% 屏宽，分组标签行放不下、末尾标签被硬裁。
     */
    private static final float PANEL_WIDTH_RATIO = 0.9f;
    /** 极窄屏兜底宽度，避免面板窄到无法阅读。 */
    private static final int PANEL_MIN_WIDTH_DP = 480;
    /**
     * 面板高度占可用高度的上限。同样对齐 0.9 惯例，让一屏能多显示几行站点；
     * 网格高度仍按整数行收敛，不会把末行裁半截。
     */
    private static final float PANEL_MAX_HEIGHT_RATIO = 0.9f;

    private static String selectedGroup = "";

    private RecyclerView.ItemDecoration decoration;
    private DialogSiteBinding binding;
    private FragmentActivity activity;
    private Dialog directDialog;
    private SiteListener listener;
    private SiteAdapter adapter;
    private List<String> groups;
    private List<String> originalGroups;
    private String reorderingGroup = "";
    private long reorderStartedAt;
    private long showStart;
    private boolean groupReordering;
    private boolean groupSwitching;
    private boolean action;
    private boolean listLoaded;
    private int type;

    public static SiteDialog create() {
        return new SiteDialog();
    }

    public SiteDialog search() {
        type = 1;
        return this;
    }

    public SiteDialog action() {
        action = true;
        return this;
    }

    public void show(FragmentActivity activity) {
        showStart = System.currentTimeMillis();
        this.activity = activity;
        if (activity instanceof SiteListener) listener = (SiteListener) activity;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        log("click received action=%s type=%s", action, type);
        showDirect(activity);
    }

    private int getCount() {
        return GRID_COUNT;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = new Dialog(requireActivity());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(getBinding().getRoot());
        initView();
        initEvent();
        dialog.setOnKeyListener((d, keyCode, event) -> onDialogKey(keyCode, event));
        ThemeController.bindDialog(dialog);
        SiteDialogTheme.applyShell(binding.getRoot(), binding.keyword, binding.config, binding.search,
                binding.change, binding.select, binding.cancel, binding.mode);
        return dialog;
    }

    private void showDirect(FragmentActivity activity) {
        long start = System.currentTimeMillis();
        log("inflate start");
        binding = DialogSiteBinding.inflate(activity.getLayoutInflater());
        log("inflate end cost=%sms", cost(start));
        long dialogStart = System.currentTimeMillis();
        directDialog = new Dialog(activity);
        directDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        directDialog.setContentView(binding.getRoot());
        log("dialog content ready cost=%sms total=%sms", cost(dialogStart), cost());
        long initStart = System.currentTimeMillis();
        initShellView();
        initEvent();
        directDialog.setOnKeyListener((d, keyCode, event) -> onDialogKey(keyCode, event));
        log("shell init end cost=%sms total=%sms", cost(initStart), cost());
        directDialog.setOnDismissListener(d -> {
            resetGroupReorderState();
            directDialog = null;
            binding = null;
            this.activity = null;
        });
        runAfterFirstPreDraw("shell preDraw", () -> loadList(false));
        long showDialogStart = System.currentTimeMillis();
        log("show call start total=%sms", cost());
        directDialog.show();
        log("show call end cost=%sms total=%sms", cost(showDialogStart), cost());
        applyWindow(directDialog.getWindow());
        ThemeController.bindDialog(directDialog);
        SiteDialogTheme.applyShell(binding.getRoot(), binding.keyword, binding.config, binding.search,
                binding.change, binding.select, binding.cancel, binding.mode);
        TouchOptimizationHelper.sync(directDialog);
        log("window applied total=%sms", cost());
    }

    @Override
    protected void initView() {
        initShellView();
        loadList(true);
    }

    private void initShellView() {
        long start = System.currentTimeMillis();
        setRootWidth();
        setRecyclerHeight(INITIAL_BATCH);
        binding.header.setVisibility(View.VISIBLE);
        binding.title.setText(action ? R.string.site_dialog_title_action : R.string.site_dialog_title);
        binding.count.setVisibility(View.GONE);
        binding.searchBar.setVisibility(View.GONE);
        binding.keyword.setVisibility(View.GONE);
        binding.actionGap.setVisibility(View.GONE);
        binding.action.setVisibility(action ? View.VISIBLE : View.GONE);
        binding.search.setVisibility(action ? View.VISIBLE : View.GONE);
        binding.change.setVisibility(action ? View.VISIBLE : View.GONE);
        binding.select.setVisibility(action ? View.VISIBLE : View.GONE);
        binding.cancel.setVisibility(action ? View.VISIBLE : View.GONE);
        binding.mode.setVisibility(View.GONE);
        SiteDialogTheme.applyShell(binding.getRoot(), binding.keyword, binding.config, binding.search,
                binding.change, binding.select, binding.cancel, binding.mode);
        setActionEnabled(false);
        binding.recycler.setAdapter(null);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        log("shell configured cost=%sms total=%sms", cost(start), cost());
    }

    private void loadList(boolean immediate) {
        if (binding == null || listLoaded) return;
        listLoaded = true;
        long start = System.currentTimeMillis();
        adapter = new SiteAdapter(this);
        adapter.setDisplayLimit(INITIAL_BATCH);
        groups = getGroups();
        normalizeSelectedGroup();
        if (!TextUtils.isEmpty(selectedGroup)) {
            adapter.filter(selectedGroup, "");
            adapter.setDisplayLimit(INITIAL_BATCH);
        }
        log("adapter created cost=%sms items=%s action=%s immediate=%s", cost(start), adapter.getTotalCount(), action, immediate);
        if (adapter.getTotalCount() == 0) {
            log("dismiss empty total=%sms", cost());
            dismiss();
            return;
        }
        long layoutStart = System.currentTimeMillis();
        setType(type);
        setRecyclerView();
        setMode();
        setGroupView();
        setRecyclerHeight(adapter.getItemCount());
        setActionEnabled(true);
        updateCountText();
        log("view configured cost=%sms total=%sms", cost(layoutStart), cost());
        runAfterFirstPreDraw("list preDraw", () -> {
            if (adapter != null) adapter.showAll();
            log("list expanded total=%sms items=%s", cost(), adapter == null ? -1 : adapter.getItemCount());
            // 首帧后 wrap_content 的标题/分组行才有真实高度，此时再收敛一次网格高度，
            // 面板高度才是「内容 + 整数行网格」，不会在屏幕外多留一行。
            if (binding != null && adapter != null) {
                setRecyclerHeight(adapter.getItemCount());
                updateCountText();
            }
            focusSelectedSite();
        });
    }

    private void updateCountText() {
        if (binding == null || adapter == null) return;
        int count = adapter.getItemCount();
        // 走视图自己的 context：TV 版是裸 Dialog（showDirect），Fragment 从未 attach 到
        // FragmentManager，Fragment.getString()/requireContext() 会抛
        // IllegalStateException: not attached to a context。
        binding.count.setText(binding.getRoot().getContext().getString(R.string.site_dialog_count, count));
        binding.count.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void initEvent() {
        binding.config.setOnClickListener(v -> {
            FragmentActivity activity = getDialogActivity();
            dismiss();
            App.post(() -> HistoryDialog.create().vod().readOnly().show(activity, item -> loadConfig(activity, item)), 100);
        });
        binding.mode.setOnClickListener(this::onMode);
        binding.select.setOnClickListener(v -> {
            if (adapter != null) adapter.selectAll();
        });
        binding.cancel.setOnClickListener(v -> {
            if (adapter != null) adapter.cancelAll();
        });
        binding.search.setOnClickListener(v -> setType(v.isSelected() ? 0 : 1));
        binding.change.setOnClickListener(v -> setType(v.isSelected() ? 0 : 2));
        binding.keyword.addTextChangedListener(new com.fongmi.android.tv.ui.custom.CustomTextListener() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (adapter == null) return;
                adapter.filter(selectedGroup, s.toString());
                setRecyclerView();
                setRecyclerHeight(adapter.getItemCount());
                setMode();
                updateCountText();
                setWidth();
            }
        });
    }

    private void setRecyclerView() {
        if (binding.recycler.getAdapter() == null) binding.recycler.setAdapter(adapter);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        if (decoration == null) binding.recycler.addItemDecoration(decoration = new SpaceItemDecoration(getCount(), 16));
        if (binding.recycler.getLayoutManager() == null) binding.recycler.setLayoutManager(new GridLayoutManager(getDialogActivity(), getCount()));
        log("recycler ready adapter=%s layout=%s total=%sms", binding.recycler.getAdapter() != null, binding.recycler.getLayoutManager() != null, cost());
    }

    private void setRecyclerHeight(int count) {
        // 面板不再铺满：网格高度收敛到整数行，多余的行交给滚动，避免最后一行被裁掉半截。
        // 高度在这里显式给定，因此不再需要 maxHeight 兜底（Integer.MAX_VALUE 还会被
        // CustomRecyclerView 折成 AT_MOST 的畸形 spec）。
        ViewGroup.LayoutParams params = binding.recycler.getLayoutParams();
        params.height = gridHeight(count);
        binding.recycler.setLayoutParams(params);
    }

    private int gridHeight(int count) {
        int rows = Math.max(1, (Math.max(0, count) + getCount() - 1) / getCount());
        int row = ResUtil.dp2px(ROW_HEIGHT_DP);
        int spacing = ResUtil.dp2px(ROW_SPACING_DP);
        int budget = gridBudgetHeight();
        if (budget < row) return row;
        int fitRows = Math.max(1, (budget + spacing) / (row + spacing));
        int visibleRows = Math.min(rows, fitRows);
        return visibleRows * row + (visibleRows - 1) * spacing;
    }

    /** 网格高度预算 = 可用高度 × 面板高度上限 − 面板里除网格以外的固定内容高度。 */
    private int gridBudgetHeight() {
        return Math.max(0, Math.round(windowVisibleHeight() * PANEL_MAX_HEIGHT_RATIO) - panelChromeHeight());
    }

    /**
     * 窗口实际可用高度。优先用 decor 的可见显示区域：它已经扣掉了系统栏，
     * 比直接用屏幕高度更接近面板真正能占的高度；拿不到时退回屏幕高度。
     */
    private int windowVisibleHeight() {
        View root = binding == null ? null : binding.getRoot();
        if (root != null) {
            Rect frame = new Rect();
            root.getWindowVisibleDisplayFrame(frame);
            if (frame.height() > 0) return frame.height();
        }
        return ResUtil.getScreenHeight(getDialogActivity());
    }

    /**
     * 面板除网格以外的固定高度。按实际布局测量，避免把 dialog_site.xml 的尺寸写死成第二份常量：
     * 改布局的人不需要记得同步这里。
     */
    private int panelChromeHeight() {
        if (binding == null) return 0;
        int chrome = binding.getRoot().getPaddingTop() + binding.getRoot().getPaddingBottom();
        chrome += fixedHeight(binding.header);
        chrome += fixedHeight(binding.searchBar);
        chrome += fixedHeight(binding.groupScroll);
        chrome += fixedHeight(binding.groupHint);
        if (binding.recycler.getParent() instanceof View container && container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
            chrome += Math.max(0, margins.topMargin);
        }
        return chrome;
    }

    private int fixedHeight(View view) {
        if (view == null || view.getVisibility() == View.GONE) return 0;
        int height = view.getMeasuredHeight();
        if (height <= 0 && view.getLayoutParams() != null) height = Math.max(0, view.getLayoutParams().height);
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) height += Math.max(0, margins.topMargin);
        return height;
    }

    private void setRootWidth() {
        ViewGroup.LayoutParams params = binding.getRoot().getLayoutParams();
        if (params == null) params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 宽度填满窗口（窗口本身已按屏宽比例收窄），高度贴合内容。
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        binding.getRoot().setLayoutParams(params);
    }

    private void setType(int type) {
        binding.search.setSelected(type == 1);
        binding.change.setSelected(type == 2);
        binding.select.setClickable(type > 0);
        binding.cancel.setClickable(type > 0);
        this.type = type;
        if (adapter != null) adapter.setType(type);
        setActionEnabled(listLoaded && adapter != null);
    }

    private void setMode() {
        binding.mode.setEnabled(false);
    }

    private void setWidth() {
        Window window = directDialog != null ? directDialog.getWindow() : getDialog() == null ? null : getDialog().getWindow();
        applyWindow(window);
    }

    private void onMode(View view) {
        setRecyclerView();
        setMode();
        setWidth();
    }

    private void setActionEnabled(boolean enabled) {
        binding.search.setEnabled(enabled);
        binding.change.setEnabled(enabled);
        // Keep bulk actions in the D-pad focus chain even in plain switch mode.
        // setType() controls whether they are clickable; enabled only reflects loading state.
        binding.select.setEnabled(enabled);
        binding.cancel.setEnabled(enabled);
    }

    @Override
    public void onItemClick(Site item) {
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onDelete(Site item) {
        if (!item.isFile()) return;
        FragmentActivity activity = host();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        new WebHtvAlertDialogBuilder(activity, R.style.Theme_WebHTV_Dialog)
                .setTitle(R.string.setting_site_delete_title)
                .setMessage(activity.getString(R.string.setting_site_delete_message, item.getName()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteFileSite(item))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteFileSite(Site item) {
        FragmentActivity activity = host();
        if (activity == null) return;
        String type = item.getFileType();
        String fileName = item.getFileName();
        if (fileName.isEmpty()) return;
        String subDir = switch (type) {
            case "XBPQ" -> "sites-json";
            case "JS" -> "sites-js/api";
            case "PY" -> "sites-py";
            case "RAW" -> "sites";
            default -> "";
        };
        if (subDir.isEmpty()) return;
        File file = new File(Path.root() + "/tvbox/" + subDir, fileName);
        boolean deleted = file.exists() && file.delete();
        if (!deleted && file.exists()) {
            Toast.makeText(activity, R.string.setting_site_delete_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Site currentHome = VodConfig.get().getHome();
        List<Site> siteList = VodConfig.get().getSites();
        if (currentHome != null && currentHome.getKey().equals(item.getKey())) {
            int delIndex = -1;
            for (int i = 0; i < siteList.size(); i++) {
                if (siteList.get(i).getKey().equals(item.getKey())) {
                    delIndex = i;
                    break;
                }
            }
            Site fallbackHome = new Site();
            if (delIndex != -1 && siteList.size() > 1) {
                if (delIndex + 1 < siteList.size()) fallbackHome = siteList.get(delIndex + 1);
                else fallbackHome = siteList.get(delIndex - 1);
            }
            VodConfig.get().setHome(fallbackHome);
        }
        if (!siteList.isEmpty()) siteList.remove(item);
        Toast.makeText(activity, activity.getString(R.string.setting_site_delete_done, item.getName()), Toast.LENGTH_SHORT).show();
        if (adapter != null && binding != null) {
            adapter.removeSite(item);
            setRecyclerHeight(adapter.getItemCount());
        }
    }

    @Override
    public boolean onItemKeyUp(int position) {
        if (position >= getCount()) return false;
        return requestGroupFocus();
    }

    /**
     * 整页当作一张表的环形跳转：只在「页面外缘」把焦点回绕到另一端，其余一律返回 false
     * 交给原有焦点搜索，容器之间的移动（网格↔分组栏、网格↔右排管理按钮）保持原样。
     *
     * <p>回绕对应关系：分组栏按上＝网格最后一行；网格最后一行按下＝分组栏；
     * 分组栏首尾标签左右互绕；网格某行行首按左、行尾按右互绕（管理模式下让位给右排按钮）；
     * 右排按钮列上端按上＝网格最后一行、下端按下＝分组栏、按右＝该行最左那个格子。
     * 菜单键仍回分组第一个「全部」，逻辑未动。
     */
    private boolean handlePageRing(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (binding == null || adapter == null || groupReordering) return false;
        View focus = binding.getRoot().findFocus();
        if (focus == null) return false;
        if (isGroupChip(focus)) return handleGroupRing(focus, keyCode);
        if (isInActionColumn(focus)) return handleActionRing(focus, keyCode);
        if (isInGrid(focus)) return handleGridRing(focus, keyCode);
        return false;
    }

    private boolean isGroupChip(View view) {
        return view != null && view.getParent() == binding.groupList;
    }

    private boolean isInActionColumn(View view) {
        return isDescendant(binding.action, view);
    }

    private boolean isInGrid(View view) {
        return isDescendant(binding.recycler, view);
    }

    private boolean isDescendant(ViewGroup root, View view) {
        View current = view;
        while (current != null) {
            if (current == root) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    private boolean handleGroupRing(View focus, int keyCode) {
        int count = binding.groupList.getChildCount();
        int index = binding.groupList.indexOfChild(focus);
        if (index < 0 || count <= 0) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return focusSitePosition(adapter.getItemCount() - 1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index == 0) return focusGroupAt(count - 1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index == count - 1) return focusGroupAt(0);
        return false;
    }

    private boolean handleGridRing(View focus, int keyCode) {
        int position = gridPositionOf(focus);
        if (position < 0) return false;
        int count = getCount();
        int total = adapter.getItemCount();
        int rowStart = position - position % count;
        int rowEnd = Math.min(rowStart + count, total) - 1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position / count >= (total - 1) / count) return requestGroupFocus();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position == rowStart) {
            if (hasActionColumn()) return focusActionColumnNear(focus);
            return focusSitePosition(rowEnd);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && position == rowEnd) {
            // 管理模式右侧有按钮列，右键让位给它（原有跨容器移动）
            if (hasActionColumn()) return false;
            return focusSitePosition(rowStart);
        }
        return false;
    }

    private boolean handleActionRing(View focus, int keyCode) {
        List<View> items = visibleActionItems();
        if (items.isEmpty()) return false;
        int index = items.indexOf(focus);
        if (index < 0) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && index == 0) return focusSitePosition(adapter.getItemCount() - 1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index == items.size() - 1) return requestGroupFocus();
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return focusGridRowStartNear(focus);
        return false;
    }

    private List<View> visibleActionItems() {
        List<View> items = new ArrayList<>();
        if (binding == null || binding.action.getVisibility() != View.VISIBLE) return items;
        for (int i = 0; i < binding.action.getChildCount(); i++) {
            View child = binding.action.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()) items.add(child);
        }
        return items;
    }

    private boolean hasActionColumn() {
        return !visibleActionItems().isEmpty();
    }

    private int gridPositionOf(View focus) {
        View item = focus;
        while (item != null && item.getParent() != binding.recycler) {
            if (!(item.getParent() instanceof View)) return -1;
            item = (View) item.getParent();
        }
        return item == null ? -1 : binding.recycler.getChildAdapterPosition(item);
    }

    /** 页右缘：落到右排按钮里与参照格子竖向最接近的那个。 */
    private boolean focusActionColumnNear(View reference) {
        View best = nearestOf(visibleActionItems(), reference);
        return best != null && best.requestFocus();
    }

    /** 页左缘：回到网格里与参照按钮竖向最接近那一行的行首。 */
    private boolean focusGridRowStartNear(View reference) {
        List<View> cells = new ArrayList<>();
        for (int i = 0; i < binding.recycler.getChildCount(); i++) {
            View child = binding.recycler.getChildAt(i);
            int position = binding.recycler.getChildAdapterPosition(child);
            if (position >= 0 && position % getCount() == 0) cells.add(child);
        }
        View best = nearestOf(cells, reference);
        if (best != null && best.requestFocus()) return true;
        return focusSitePosition(0);
    }

    private View nearestOf(List<View> candidates, View reference) {
        if (candidates.isEmpty()) return null;
        int[] location = new int[2];
        reference.getLocationInWindow(location);
        int centerY = location[1] + reference.getHeight() / 2;
        View best = null;
        int distance = Integer.MAX_VALUE;
        for (View candidate : candidates) {
            candidate.getLocationInWindow(location);
            int candidateCenter = location[1] + candidate.getHeight() / 2;
            int delta = Math.abs(candidateCenter - centerY);
            if (delta < distance) {
                best = candidate;
                distance = delta;
            }
        }
        return best;
    }

    private boolean focusGroupAt(int index) {
        if (binding == null || binding.groupList.getChildCount() == 0) return false;
        View view = binding.groupList.getChildAt(Math.max(0, Math.min(index, binding.groupList.getChildCount() - 1)));
        return view != null && view.requestFocus();
    }

    private boolean focusSitePosition(int position) {
        if (binding == null || adapter == null) return false;
        int total = adapter.getItemCount();
        if (total <= 0) return false;
        int target = Math.max(0, Math.min(position, total - 1));
        DialogSiteBinding current = binding;
        current.recycler.scrollToPosition(target);
        if (focusSiteView(current, target)) return true;
        current.recycler.postDelayed(() -> focusSiteView(current, target), 100);
        return true;
    }

    private boolean focusSiteView(DialogSiteBinding current, int position) {
        if (binding != current) return false;
        RecyclerView.ViewHolder holder = current.recycler.findViewHolderForAdapterPosition(position);
        return holder != null && holder.itemView.requestFocus();
    }


    private void loadConfig(FragmentActivity activity, Config config) {
        if (config.getUrl().equals(VodConfig.getUrl())) return;
        VodConfig.load(config, new Callback() {
            @Override
            public void start() {
                Notify.progress(activity);
            }

            @Override
            public void success() {
                Notify.dismiss();
                LiveConfig.get().clear();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        });
    }

    private FragmentActivity getDialogActivity() {
        FragmentActivity host = host();
        return host != null ? host : requireActivity();
    }

    /**
     * TV 端走 showDirect()，Fragment 从未 attach 到 FragmentManager，
     * requireActivity()/getString() 会直接抛 "Fragment not attached to an activity"。
     * 这里从裸 Dialog 的宿主 activity 兜底，拿不到就返回 null 由调用方静默退出。
     */
    private FragmentActivity host() {
        FragmentActivity host = activity;
        if (host == null) host = getActivity();
        if (host == null && binding != null && binding.getRoot().getContext() instanceof FragmentActivity) {
            host = (FragmentActivity) binding.getRoot().getContext();
        }
        return host;
    }

    private void applyWindow(Window window) {
        if (window == null) return;
        window.setWindowAnimations(0);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        // 居中浮层面板：四周留出安全边距，高度贴合内容，不再铺满整屏。
        params.width = panelWidth();
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        params.dimAmount = 0.62f;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
    }

    private int panelWidth() {
        int screen = ResUtil.getScreenWidth(getDialogActivity());
        int min = Math.min(ResUtil.dp2px(PANEL_MIN_WIDTH_DP), screen);
        return Math.max(min, Math.round(screen * PANEL_WIDTH_RATIO));
    }

    private void runAfterFirstPreDraw(String label, Runnable action) {
        View root = binding == null ? null : binding.getRoot();
        if (root == null) {
            if (action != null) action.run();
            return;
        }
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
                log("%s total=%sms items=%s", label, cost(), adapter == null ? -1 : adapter.getItemCount());
                if (action != null) root.post(action);
                return true;
            }
        });
    }

    private long cost() {
        return cost(showStart);
    }

    private long cost(long start) {
        return System.currentTimeMillis() - start;
    }

    private void log(String msg, Object... args) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log(TAG, msg, args);
    }

    @Override
    public void dismiss() {
        resetGroupReorderState();
        if (directDialog != null) directDialog.dismiss();
        else super.dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = getDialog() == null ? null : getDialog().getWindow();
        applyWindow(window);
        if (adapter.getItemCount() == 0) dismiss();
    }

    private List<String> getGroups() {
        return SiteGroupOrderStore.sort(new ArrayList<>(Site.getGroups(VodConfig.get().getSites())));
    }

    private void normalizeSelectedGroup() {
        if (groups == null || groups.isEmpty()) {
            selectedGroup = "";
            return;
        }
        if (!TextUtils.isEmpty(selectedGroup) && !groups.contains(selectedGroup)) selectedGroup = "";
    }

    private void setGroupView() {
        if (groups == null || groups.isEmpty()) {
            selectedGroup = "";
            binding.groupScroll.setVisibility(View.GONE);
            binding.groupHint.setVisibility(View.GONE);
            return;
        }
        binding.groupScroll.setVisibility(View.VISIBLE);
        binding.groupList.removeAllViews();
        binding.groupList.addView(getGroupView("", getDialogActivity().getString(R.string.site_group_all)));
        for (String group : groups) binding.groupList.addView(getGroupView(group, group));
        updateGroupView();
        binding.groupList.post(() -> requestGroupFocus());
    }

    private androidx.appcompat.widget.AppCompatTextView getGroupView(String group, String text) {
        androidx.appcompat.widget.AppCompatTextView button = new androidx.appcompat.widget.AppCompatTextView(getDialogActivity());
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 间距与内边距收紧到 10dp/14dp：面板 90% 宽时 14 个标签刚好放得下，
        // 不再让末尾标签被面板右缘裁掉半个（超出时仍由 groupScroll 横向滚动兜底）。
        params.setMarginEnd(ResUtil.dp2px(10));
        button.setLayoutParams(params);
        button.setTag(group);
        button.setText(text);
        button.setSingleLine(true);
        button.setAllCaps(false);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(ResUtil.dp2px(14), ResUtil.dp2px(8), ResUtil.dp2px(14), ResUtil.dp2px(8));
        SiteDialogTheme.applyGroup(button);
        button.setFocusable(true);
        button.setClickable(true);
        button.setNextFocusDownId(binding.recycler.getId());
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !groupSwitching) selectGroup(group, button);
        });
        button.setOnClickListener(v -> onGroupClick(group, v));
        button.setOnKeyListener((v, keyCode, event) -> onGroupKey(group, keyCode, event));
        if (!TextUtils.isEmpty(group)) button.setOnLongClickListener(v -> startGroupReorder(group, v));
        return button;
    }

    private boolean startGroupReorder(String group, View view) {
        if (groups == null || groups.size() < 2 || TextUtils.isEmpty(group)) return false;
        if (groupReordering) return true;
        originalGroups = new ArrayList<>(groups);
        reorderingGroup = group;
        reorderStartedAt = SystemClock.uptimeMillis();
        groupReordering = true;
        selectedGroup = group;
        updateGroupView();
        centerGroup(view);
        Notify.show(R.string.site_group_sort_tv_active);
        return true;
    }

    private boolean onGroupKey(String group, int keyCode, KeyEvent event) {
        if (!groupReordering || !TextUtils.equals(group, reorderingGroup)) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) moveGroup(group, keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return true;
        }
        if (KeyUtil.isEnterKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && event.getDownTime() >= reorderStartedAt) saveGroupReorder();
            return true;
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private void moveGroup(String group, int direction) {
        if (!SiteGroupOrderStore.move(groups, group, direction)) return;
        DialogSiteBinding current = binding;
        if (current == null) return;
        current.groupList.post(() -> {
            if (binding == current) setGroupView();
        });
    }

    private void saveGroupReorder() {
        if (!groupReordering) return;
        SiteGroupOrderStore.save(Site.getGroups(VodConfig.get().getSites()), groups);
        View active = findGroupView(reorderingGroup);
        resetGroupReorderState();
        updateGroupView();
        if (active != null) active.requestFocus();
        Notify.show(R.string.site_group_sort_saved);
    }

    private void cancelGroupReorder() {
        if (!groupReordering) return;
        if (originalGroups != null) {
            groups.clear();
            groups.addAll(originalGroups);
        }
        resetGroupReorderState();
        setGroupView();
        Notify.show(R.string.site_group_sort_cancelled);
    }

    private boolean onDialogKey(int keyCode, KeyEvent event) {
        if (handlePageRing(keyCode, event)) return true;
        if (KeyUtil.isMenuKey(event)) {
            View all = findGroupView("");
            if (all == null) return false;
            // 只搬焦点，不顺手切组：用户按菜单键是想回到分组栏，不是想清掉当前筛选。
            groupSwitching = true;
            boolean focused = all.requestFocus();
            groupSwitching = false;
            return focused;
        }
        if (!groupReordering || keyCode != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP) return false;
        cancelGroupReorder();
        return true;
    }

    private void resetGroupReorderState() {
        groupReordering = false;
        groupSwitching = false;
        reorderingGroup = "";
        originalGroups = null;
        reorderStartedAt = 0;
    }

    private boolean requestGroupFocus() {
        if (binding == null || binding.groupList.getChildCount() == 0) return false;
        View target = findGroupView(selectedGroup);
        if (target == null) target = binding.groupList.getChildAt(0);
        return target.requestFocus();
    }

    private void onGroupClick(String group, View view) {
        if (groupReordering) {
            if (TextUtils.equals(group, reorderingGroup)) saveGroupReorder();
            return;
        }
        if (!TextUtils.isEmpty(group) && group.equals(selectedGroup)) {
            View all = findGroupView("");
            if (all != null && all.requestFocus()) return;
            selectGroup("", all == null ? view : all);
            return;
        }
        selectGroup(group, view);
    }

    private void selectGroup(String group, View view) {
        if (groupReordering && !TextUtils.equals(group, reorderingGroup)) {
            View active = findGroupView(reorderingGroup);
            if (active != null) active.post(active::requestFocus);
            return;
        }
        if (binding == null || adapter == null) return;
        if (group.equals(selectedGroup)) {
            centerGroup(view);
            return;
        }
        selectedGroup = group;
        updateGroupView();
        adapter.filter(selectedGroup, "");
        setRecyclerHeight(adapter.getItemCount());
        updateCountText();
        scrollRecyclerToTop();
        centerGroup(view);
    }

    private void scrollRecyclerToTop() {
        RecyclerView.LayoutManager manager = binding.recycler.getLayoutManager();
        if (manager != null) manager.scrollToPosition(0);
    }

    private void updateGroupView() {
        if (binding == null) return;
        boolean reorderable = groups != null && groups.size() > 1;
        binding.groupHint.setVisibility(reorderable ? View.VISIBLE : View.GONE);
        binding.groupHint.setText(groupReordering ? R.string.site_group_sort_tv_active : R.string.site_group_sort_tv_hint);
        for (int i = 0; i < binding.groupList.getChildCount(); i++) {
            View view = binding.groupList.getChildAt(i);
            String group = (String) view.getTag();
            boolean selected = TextUtils.equals(group, selectedGroup);
            boolean reordering = groupReordering && TextUtils.equals(group, reorderingGroup);
            String text = TextUtils.isEmpty(group) ? getDialogActivity().getString(R.string.site_group_all) : group;
            int description = reordering ? R.string.site_group_sort_tv_active_desc : R.string.site_group_sort_tv_desc;
            androidx.appcompat.widget.AppCompatTextView button = (androidx.appcompat.widget.AppCompatTextView) view;
            button.setText(reordering ? "↔ " + text : text);
            button.setContentDescription(TextUtils.isEmpty(group) ? text : getDialogActivity().getString(description, text));
            view.setAlpha(groupReordering && !reordering ? 0.55f : 1.0f);
            view.setSelected(selected);
            SiteDialogTheme.applyGroup(button);
        }
    }

    private View findGroupView(String group) {
        if (binding == null) return null;
        for (int i = 0; i < binding.groupList.getChildCount(); i++) {
            View child = binding.groupList.getChildAt(i);
            if (TextUtils.equals(group, (String) child.getTag())) return child;
        }
        return null;
    }

    private void centerGroup(View view) {
        DialogSiteBinding current = binding;
        if (current == null) return;
        current.groupScroll.post(() -> {
            if (binding != current) return;
            current.groupScroll.smoothScrollTo(Math.max(0, view.getLeft() + view.getWidth() / 2 - current.groupScroll.getWidth() / 2), 0);
        });
    }

    private void focusSelectedSite() {
        if (binding == null || adapter == null) return;
        int selectedPosition = getSelectedPosition();
        if (selectedPosition >= 0) {
            binding.recycler.post(() -> {
                if (binding == null) return;
                RecyclerView.LayoutManager manager = binding.recycler.getLayoutManager();
                if (manager != null) manager.scrollToPosition(selectedPosition);
                binding.recycler.post(() -> {
                    if (binding == null) return;
                    RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(selectedPosition);
                    if (holder != null && holder.itemView != null) {
                        holder.itemView.requestFocus();
                        log("focused selected site position=%s total=%sms", selectedPosition, cost());
                    }
                });
            });
        } else {
            requestGroupFocus();
        }
    }

    private int getSelectedPosition() {
        if (adapter == null) return -1;
        List<Site> items = adapter.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isSelected()) return i;
        }
        return -1;
    }
}
