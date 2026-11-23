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

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * SMB 浏览页的卡片式列表：所有视图（主机、共享、目录、收藏夹、隐藏项）共用这一个适配器，
 * 长按菜单与点击仍按 {@code mode + position} 取数据，显示层改版不影响它们。
 *
 * <p>视频行带缩略图（{@link SmbThumbnailCache}），目录/音频/图片/APK 行用图标占位。</p>
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

        /** 只有标题的普通行（主机/共享/收藏夹文本）。 */
        public static Row text(String title) {
            return new Row(title, "", "", null, null);
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

        /** 带图标占位、但没有缩略图的行。 */
        public static Row icon(String title, String subtitle, String glyph) {
            return new Row(title, subtitle, glyph == null ? "" : glyph, null, null);
        }

        /** 视频行：缩略图异步取帧，副标题放大小等信息。 */
        public static Row video(String title, String subtitle, String thumbKey, String thumbUrl) {
            return new Row(title, subtitle, "▶", thumbKey, thumbUrl);
        }
    }

    private static final class Holder {
        FrameLayout box;
        ImageView thumb;
        TextView glyph;
        TextView title;
        TextView subtitle;
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
        row.setPadding(dp(6), dp(8), dp(10), dp(8));
        row.setMinimumHeight(dp(90));
        // 默认透明，让 ListView 自己的选中/焦点背景透出来；电视焦点环仍落在整行上。
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_window_focused},
                new ColorDrawable(0xFF2B3648));
        background.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(0xFF2B3648));
        background.addState(new int[]{}, new ColorDrawable(0x00000000));
        row.setBackground(background);

        FrameLayout thumbBox = new FrameLayout(context);
        thumbBox.setBackgroundColor(0xFF1A2130);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(thumbWidth, thumbHeight);
        row.addView(thumbBox, boxParams);

        holder.box = thumbBox;
        holder.thumb = new ImageView(context);
        holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbBox.addView(holder.thumb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        holder.glyph = new TextView(context);
        holder.glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        holder.glyph.setTextColor(onSurface);
        holder.glyph.setGravity(Gravity.CENTER);
        thumbBox.addView(holder.glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(text, textParams);

        holder.title = new TextView(context);
        holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        holder.title.setTextColor(onSurface);
        holder.title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        holder.title.setSingleLine(true);
        holder.title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
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
        holder.subtitle.setText(row.subtitle == null ? "" : row.subtitle);
        holder.subtitle.setVisibility(TextUtils.isEmpty(row.subtitle) ? View.GONE : View.VISIBLE);
        boolean hasThumb = !TextUtils.isEmpty(row.thumbKey) && !TextUtils.isEmpty(row.thumbUrl);
        // 既没有缩略图也没有图标的行不占左侧格子（例如隐藏主机里的纯 IP）。
        holder.box.setVisibility(hasThumb || !TextUtils.isEmpty(row.glyph) ? View.VISIBLE : View.GONE);
        holder.thumb.setImageDrawable(null);
        holder.thumb.setTag(null);
        holder.thumb.setVisibility(hasThumb ? View.VISIBLE : View.GONE);
        holder.glyph.setVisibility(hasThumb ? View.GONE : View.VISIBLE);
        holder.glyph.setText(row.glyph == null ? "" : row.glyph);
        if (hasThumb) SmbThumbnailCache.load(context, row.thumbKey, row.thumbUrl, holder.thumb, thumbWidth, thumbHeight);
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
