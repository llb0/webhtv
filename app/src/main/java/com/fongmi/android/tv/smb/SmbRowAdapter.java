package com.fongmi.android.tv.smb;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * SMB 浏览页的卡片式列表：所有视图（主机、共享、目录、收藏夹、隐藏项）共用这一个适配器，
 * 长按菜单与点击仍按 {@code mode + position} 取数据，显示层改版不影响它们。
 *
 * <p>非视频行只有一个小图标加标题；视频行同样先显示小图标，等缩略图真的取到了
 * 才把左侧换成 16:9 截图，避免大部分取不到截图的视频白白撑开一行大黑底。
 * 标题显示遵循设置里的「标题换行」：单行跑马灯，或按设置的 2/3/不限行换行。</p>
 */
public class SmbRowAdapter extends BaseAdapter {

    public static final class Row {
        public final String title;
        public final String subtitle;
        public final String glyph;
        public final String thumbKey;
        public final String thumbUrl;

        private Row(String title, String subtitle, String glyph, String thumbKey, String thumbUrl) {
            this.title = title;
            this.subtitle = subtitle;
            this.glyph = glyph;
            this.thumbKey = thumbKey;
            this.thumbUrl = thumbUrl;
        }

        /**
         * 从既有标签生成行：标签形如 "📁  名称" / "★ 主机 / 共享 / 路径" 时，
         * 把前面那个符号当作左侧图标，其余当标题；没有符号就当纯文本行。
         */
        public static Row fromLabel(String label) {
            if (label == null) return text("");
            int space = label.indexOf(' ');
            if (space > 0 && space <= 3) {
                String head = label.substring(0, space);
                if (!Character.isLetterOrDigit(head.charAt(0))) {
                    return icon(label.substring(space).trim(), "", head);
                }
            }
            return text(label);
        }

        /** 只有标题的普通行。 */
        public static Row text(String title) {
            return new Row(title, "", "", null, null);
        }

        /** 带小图标、没有缩略图的行。 */
        public static Row icon(String title, String subtitle, String glyph) {
            return new Row(title, subtitle, glyph == null ? "" : glyph, null, null);
        }

        /** 视频行：缩略图取到之前仍按小图标显示，取到后由适配器自己换成截图。 */
        public static Row video(String title, String subtitle, String thumbKey, String thumbUrl) {
            return new Row(title, subtitle, "▶", thumbKey, thumbUrl);
        }
    }

    private static final class Holder {
        TextView icon;
        FrameLayout thumbBox;
        ImageView thumb;
        TextView title;
        TextView subtitle;
        String thumbKey;
    }

    private final Context context;
    private final List<Row> rows = new ArrayList<>();
    private final int thumbWidth;
    private final int thumbHeight;

    public SmbRowAdapter(Context context) {
        this.context = context;
        this.thumbWidth = dp(132);
        this.thumbHeight = dp(74);
    }

    public void setRows(List<Row> values) {
        rows.clear();
        if (values != null) rows.addAll(values);
        notifyDataSetChanged();
    }

    public Row rowAt(int position) {
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Row getItem(int position) {
        return rowAt(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        Holder holder;
        if (view == null) {
            holder = new Holder();
            view = build(holder);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }
        bind(holder, rows.get(position));
        return view;
    }

    private View build(Holder holder) {
        int onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);
        int variant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFB8C4D8);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(12), dp(8));
        // 默认透明，让 ListView 自己的选中/焦点背景透出来；电视焦点环仍落在整行上。
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_window_focused},
                new ColorDrawable(0xFF2B3648));
        background.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(0xFF2B3648));
        background.addState(new int[]{}, new ColorDrawable(0x00000000));
        row.setBackground(background);

        holder.icon = new TextView(context);
        holder.icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        holder.icon.setTextColor(variant);
        holder.icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconParams.setMarginEnd(dp(10));
        row.addView(holder.icon, iconParams);

        holder.thumbBox = new FrameLayout(context);
        holder.thumbBox.setBackgroundColor(0xFF1A2130);
        holder.thumbBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(thumbWidth, thumbHeight);
        boxParams.setMarginEnd(dp(10));
        row.addView(holder.thumbBox, boxParams);

        holder.thumb = new ImageView(context);
        holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        holder.thumbBox.addView(holder.thumb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(text, textParams);

        holder.title = new TextView(context);
        holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        holder.title.setTextColor(onSurface);
        holder.title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.addView(holder.title, new LinearLayout.LayoutParams(-1, -2));

        holder.subtitle = new TextView(context);
        holder.subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        holder.subtitle.setTextColor(variant);
        holder.subtitle.setSingleLine(true);
        holder.subtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(3);
        text.addView(holder.subtitle, subtitleParams);
        return row;
    }

    private void bind(Holder holder, Row row) {
        holder.title.setText(row.title == null ? "" : row.title);
        // 「标题换行」设置统一决定是单行跑马灯，还是按 2/3/不限行换行。
        Setting.applyTitleMaxLines(holder.title);
        holder.title.setSelected(true);
        holder.subtitle.setText(row.subtitle == null ? "" : row.subtitle);
        holder.subtitle.setVisibility(TextUtils.isEmpty(row.subtitle) ? View.GONE : View.VISIBLE);

        boolean hasThumb = !TextUtils.isEmpty(row.thumbKey) && !TextUtils.isEmpty(row.thumbUrl);
        holder.icon.setText(row.glyph == null ? "" : row.glyph);
        holder.icon.setVisibility(TextUtils.isEmpty(row.glyph) ? View.GONE : View.VISIBLE);
        holder.thumbBox.setVisibility(View.GONE);
        holder.thumb.setImageDrawable(null);
        holder.thumb.setTag(null);
        holder.thumbKey = row.thumbKey;
        if (!hasThumb) return;

        // 截图回来之前保持紧凑的小图标行；回来后才把左侧换成缩略图并让整行重新量一次高度。
        SmbThumbnailCache.load(context, row.thumbKey, row.thumbUrl, holder.thumb, thumbWidth, thumbHeight, () -> {
            if (row.thumbKey == null || !row.thumbKey.equals(holder.thumbKey)) return;
            holder.icon.setVisibility(View.GONE);
            holder.thumbBox.setVisibility(View.VISIBLE);
            View parent = (View) holder.thumbBox.getParent();
            if (parent != null) parent.requestLayout();
        });
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
