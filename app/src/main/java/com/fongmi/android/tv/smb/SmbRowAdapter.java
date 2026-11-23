package com.fongmi.android.tv.smb;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * SMB 浏览页的卡片式列表：所有视图（主机、共享、目录、收藏夹、隐藏项）共用这一个适配器，
 * 长按菜单与点击仍按 {@code mode + position} 取数据，显示层改版不影响它们。
 *
 * <p>非视频行只有一个小图标加标题；视频行先显示小图标，缩略图取到后才把左上角换成截图。
 * 截图贴在左上角当“嵌入图”，标题前几行从图右侧开始，超出的行回到图的左边继续排，
 * 长标题就不会在图片上下留出大片空白。标题显示遵循设置里的「标题换行」：
 * 单行跑马灯，或按设置的 2/3/不限行换行。</p>
 */
public class SmbRowAdapter extends BaseAdapter {

    public static final class Row {
        public final String title;
        public final String subtitle;
        public final String glyph;
        public final String thumbKey;
        public final String thumbUrl;
        public final boolean allowGenerate;

        private Row(String title, String subtitle, String glyph, String thumbKey, String thumbUrl,
                    boolean allowGenerate) {
            this.title = title;
            this.subtitle = subtitle;
            this.glyph = glyph;
            this.thumbKey = thumbKey;
            this.thumbUrl = thumbUrl;
            this.allowGenerate = allowGenerate;
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
            return new Row(title, "", "", null, null, false);
        }

        /** 带小图标、没有缩略图的行。 */
        public static Row icon(String title, String subtitle, String glyph) {
            return new Row(title, subtitle, glyph == null ? "" : glyph, null, null, false);
        }

        /**
         * 视频行：缩略图取到之前仍按小图标显示，取到后由适配器换成左上角截图。
         * {@code allowGenerate=false} 用于只有 IJK 能解的老容器：只认播放时补进来的截图。
         */
        public static Row video(String title, String subtitle, String thumbKey, String thumbUrl,
                                boolean allowGenerate) {
            return new Row(title, subtitle, "▶", thumbKey, thumbUrl, allowGenerate);
        }
    }

    private static final class Holder {
        WrapTitle title;
        TextView icon;
        FrameLayout thumbBox;
        ImageView thumb;
        String thumbKey;
    }

    /**
     * 标题视图：图片当左上角嵌入图时，前几行按图宽缩进，超出的行回到左侧继续排。
     * 手工按测量结果断行（每行一个段落），再用 {@link LeadingMarginSpan.Standard} 只给
     * 图旁边的那几行加左边距，TextView 本身无法按行做这件事。
     */
    private static final class WrapTitle extends TextView {

        private String raw = "";
        private int indentPx;
        private int besideHeightPx;

        WrapTitle(Context context) {
            super(context);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            setTextColor(MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF));
            setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setGravity(Gravity.TOP | Gravity.START);
            setIncludeFontPadding(false);
        }

        void setContent(String text, int indent, int besideHeight) {
            raw = text == null ? "" : text;
            indentPx = indent;
            besideHeightPx = besideHeight;
            rebuild();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (oldw != w) rebuild();
        }

        private void rebuild() {
            int maxLines = Setting.resolveTitleMaxLines();
            if (maxLines <= 1) {
                setSingleLine(true);
                setEllipsize(TextUtils.TruncateAt.MARQUEE);
                setMarqueeRepeatLimit(-1);
                setText(raw);
                setSelected(true);
                return;
            }
            setSingleLine(false);
            setMaxLines(maxLines);
            setEllipsize(TextUtils.TruncateAt.END);
            setSelected(false);

            int available = getWidth() - getPaddingLeft() - getPaddingRight();
            if (available <= 0 || indentPx <= 0) {
                setText(raw);
                return;
            }
            int lineHeight = Math.max(1, getLineHeight());
            // 图旁边能排几行：向上取整，保证下一行是从图片底边以下开始，不会压到图上。
            int besideLines = Math.max(1, (besideHeightPx + lineHeight - 1) / lineHeight);
            Paint paint = getPaint();
            SpannableStringBuilder out = new SpannableStringBuilder();
            int index = 0;
            int line = 0;
            while (index < raw.length() && line < maxLines) {
                int indent = line < besideLines ? indentPx : 0;
                int width = Math.max(1, available - indent);
                int count = paint.breakText(raw, index, raw.length(), true, width, null);
                if (count <= 0) count = 1;
                int start = out.length();
                out.append(raw, index, index + count);
                if (indent > 0) {
                    out.setSpan(new LeadingMarginSpan.Standard(indent, 0), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                index += count;
                line++;
                if (index < raw.length() && line < maxLines) out.append('\n');
            }
            if (index < raw.length()) out.append('…');
            setText(out);
        }
    }

    private final Context context;
    private final List<Row> rows = new ArrayList<>();
    private final int thumbWidth;
    private final int thumbHeight;
    private final int iconWidth;
    private final int iconGap;

    public SmbRowAdapter(Context context) {
        this.context = context;
        this.thumbWidth = dp(132);
        this.thumbHeight = dp(74);
        this.iconWidth = dp(28);
        this.iconGap = dp(10);
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
        int variant = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFB8C4D8);

        FrameLayout row = new FrameLayout(context);
        row.setPadding(dp(10), dp(8), dp(12), dp(8));
        // 默认透明，让 ListView 自己的选中/焦点背景透出来；电视焦点环仍落在整行上。
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_window_focused},
                new ColorDrawable(0xFF2B3648));
        background.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(0xFF2B3648));
        background.addState(new int[]{}, new ColorDrawable(0x00000000));
        row.setBackground(background);

        holder.title = new WrapTitle(context);
        row.addView(holder.title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        holder.icon = new TextView(context);
        holder.icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        holder.icon.setTextColor(variant);
        holder.icon.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconWidth, dp(28));
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.topMargin = dp(1);
        row.addView(holder.icon, iconParams);

        holder.thumbBox = new FrameLayout(context);
        holder.thumbBox.setBackgroundColor(0xFF1A2130);
        holder.thumbBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(thumbWidth, thumbHeight);
        boxParams.gravity = Gravity.TOP | Gravity.START;
        row.addView(holder.thumbBox, boxParams);

        holder.thumb = new ImageView(context);
        holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        holder.thumbBox.addView(holder.thumb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    private void bind(Holder holder, Row row) {
        boolean hasThumb = !TextUtils.isEmpty(row.thumbKey) && !TextUtils.isEmpty(row.thumbUrl);
        holder.icon.setText(row.glyph == null ? "" : row.glyph);
        boolean hasIcon = !TextUtils.isEmpty(row.glyph);
        holder.icon.setVisibility(hasIcon ? View.VISIBLE : View.GONE);
        holder.thumbBox.setVisibility(View.GONE);
        holder.thumb.setImageDrawable(null);
        holder.thumb.setTag(null);
        holder.thumbKey = row.thumbKey;

        // 还没有截图：小图标占位，标题整体避开图标。
        setTitle(holder, row.title, hasIcon ? iconWidth + iconGap : 0, false);
        if (!hasThumb) return;

        SmbThumbnailCache.load(context, row.thumbKey, row.thumbUrl, holder.thumb, thumbWidth, thumbHeight,
                row.allowGenerate, () -> {
            if (row.thumbKey == null || !row.thumbKey.equals(holder.thumbKey)) return;
            // 截图就位：图占左上角，标题前几行缩进到图的右侧，超出的行回到图的左边。
            holder.icon.setVisibility(View.GONE);
            holder.thumbBox.setVisibility(View.VISIBLE);
            setTitle(holder, row.title, thumbWidth + iconGap, true);
            View parent = (View) holder.thumbBox.getParent();
            if (parent != null) parent.requestLayout();
        });
    }

    private void setTitle(Holder holder, String text, int indent, boolean besideThumb) {
        // 单行跑马灯：只靠 padding 把标题挤到图标/图片右侧；多行时把图片当嵌入图，
        // 前几行用 span 缩进，超出的行回到最左边，padding 必须为 0 否则会一直缩进。
        boolean marquee = Setting.resolveTitleMaxLines() <= 1;
        if (marquee) {
            holder.title.setPadding(indent, 0, 0, 0);
            holder.title.setContent(text, 0, 0);
        } else if (besideThumb) {
            holder.title.setPadding(0, 0, 0, 0);
            holder.title.setContent(text, indent, thumbHeight);
        } else {
            holder.title.setPadding(indent, 0, 0, 0);
            holder.title.setContent(text, 0, 0);
        }
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
