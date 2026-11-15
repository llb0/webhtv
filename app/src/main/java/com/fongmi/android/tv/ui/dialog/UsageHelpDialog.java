package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.databinding.DialogUsageHelpBinding;
import com.fongmi.android.tv.databinding.ItemUsageHelpModuleBinding;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关于页的“使用帮助”入口。
 *
 * 模块默认全部折叠，只展示目录；点击标题展开说明。内容按当前仓库 README、
 * docs/integration 与 WebHome 开发文档中的实际能力整理，不使用猜测性描述。
 */
public final class UsageHelpDialog {

    private static final String COLLAPSED = "＋  ";
    private static final String EXPANDED = "－  ";

    private static final List<Module> MODULES = List.of(
            new Module("首页与多仓", """
                    首页顶部可切换点播、直播和 WebHome 站点；点播支持配置多个仓，切换仓或刷新后会重新加载该仓的站点。
                    首页菜单提供搜索、历史、收藏、设置、推送播放和推送 APK 等入口；手机端为折叠菜单，电视端可用菜单键打开。
                    常用设置包括默认加载点播、首页源锁定、首页最近观看和全屏菜单键。
                    WebHome 站点声明 homePage 时优先加载网页首页；没有 homePage 或网页加载失败时按普通站点方式使用。
                    """),
            new Module("点播与站源", """
                    设置 → 点播可添加接口地址或文件源，支持 JSON、JAR、JS、PY 等常见配置。
                    文件源按“文件名.开关-样式.扩展名”命名：S 表示只搜索不展示，N 表示禁止搜索，H 表示横图，数字表示自定义展示比例。
                    首页用分类浏览或搜索进入详情；详情页可切换线路、选集、换源、收藏、下载和打开 TMDB。
                    站点没有内容时，先检查接口是否失效、分类是否为空、文件源目录是否正确，再检查来源屏蔽是否把该来源挡掉。
                    """),
            new Module("搜索", """
                    首页搜索会在多个可用站源中并行查找；可在设置中调整搜索线程、搜索布局、结果列数和结果排序。
                    搜索结果支持换源；没有结果时先换关键词，再确认站源接口、网络和来源屏蔽状态。
                    “快速搜索”和站点健康排序会影响搜索顺序；站点弹窗中可单独关闭健康排序，保留自己的配置顺序。
                    """),
            new Module("直播", """
                    设置 → 直播可添加 TXT、M3U、JSON 或 Spider 直播源，支持频道分组、EPG、回看和 DRM。
                    直播源与点播源相互独立；播放失败时可切换线路或播放内核。
                    回看依赖直播源自身的 catchup 配置；没有回看数据时不是所有频道都能回看。
                    """),
            new Module("详情页与 TMDB", """
                    详情页提供线路和剧集选择；TMDB 可自动匹配，也可手动绑定条目。
                    “匹配 TMDB 季度”支持自动判断、按集号主键自动分季、保持原始集列表和 AI 识别集数/分季；自动判断会清除手动绑定。
                    进入详情后如果匹配到 TMDB 且有详情海报，历史卡片优先采用 TMDB 海报；没有 TMDB 海报才用站源详情图，再没有才用非黑屏截图。
                    详情页可在影视原生、原生增强、融合等展示方式间切换；光影巨幕等属于播放页增强，出现解析异常时先切回普通线路或换源。
                    """),
            new Module("播放器", """
                    播放器支持 EXO、IJK、MPV 等内核；可调整音轨、字幕、弹幕、倍速、画面比例和旋转。
                    播放菜单遮盖层、自动跳过片头片尾、播放失败回退等开关在播放设置中。
                    电视端可用方向键移动焦点、OK 键确认，旋转按钮可用上键触发，效果等同 OK。
                    出现黑屏、花屏或只有声音时，依次尝试切换硬解/软解、切换内核、切换线路。
                    """),
            new Module("弹幕与字幕", """
                    弹幕与字幕入口在播放设置中；可开关弹幕、调整字幕大小/位置，并选择字幕语言或手动搜索字幕。
                    站源可在播放结果中提供 subs 和 danmaku 字段；字幕支持常见文本格式，弹幕支持站源字段和局域网注入。
                    字幕或弹幕不显示时，先确认对应开关已打开，再检查站源是否返回地址、是否需要 Cookie/Referer 等请求头。
                    """),
            new Module("历史、收藏与续播", """
                    历史记录保存剧集和播放进度；可开启记录分集进度、历史记录跨源聚合和全局历史续播。
                    历史海报的取图顺序：已有可用海报/截图 → TMDB 详情海报 → 站源详情页图 → 分类传入图 → 播放画面非黑屏截图。
                    历史卡片一旦已有可用海报，不会被后续详情页空图覆盖；普通站源没有海报时同样会走截图兜底。
                    收藏独立于历史；删除历史不会自动删除收藏，重要数据可在设置中备份或同步。
                    """),
            new Module("投屏与推送", """
                    播放页的投屏按钮用于选择远端设备；WebHome 小窗预览投屏时会同时传递链接、headers 和标题。
                    远端接收后会生成“推送”历史；播放后使用接收设备自己的非黑屏截图作为海报，有效截图生成后左上角站源位置显示“推送”。
                    首页菜单还提供推送播放和推送 APK；远端设备需要同网可达，或已通过远程托管绑定。
                    投屏失败时检查两端网络、接收端服务状态、链接是否过期，以及远端是否允许接收推送。
                    """),
            new Module("WebHome 首页", """
                    站点配置 homePage 后，切换该站点会优先加载网页首页；支持站点 header，其中 User-Agent 和 Cookie 会应用到 WebView。
                    WebHome 支持全屏、全局皮肤/主题、CSP 预热和小窗预览；页面可调用 fm/fongmi SDK 完成搜索、播放、收藏、历史、网盘检测和缓存。
                    网页空白或脚本不执行时，先检查 homePage 地址是否可访问，再检查 WebView 版本、CSP、扩展脚本和网络。
                    页面内需要 App 播放按钮或直链、网盘路由时，使用 WebHome 扩展补充。
                    """),
            new Module("WebHome 扩展", """
                    WebHome 扩展在真实网站页面中注入用户脚本，用于增加 App 播放按钮、直链/HLS/网盘路由、页面清理、TV 焦点和安全区适配。
                    入口：设置 → 增强功能 → WebHome 扩展；来源支持本地文件、远程链接/manifest、直接代码、表单生成和 JSON。
                    扩展可写在单站点 extensions 中，也可放在根级 webHomeExtensions；单站点扩展通常不需要再写 cspKeyRegex。
                    调试工作台可预览网页、查看 Console/Network/Elements，并在保存前检查脚本效果。
                    """),
            new Module("站点注入与来源屏蔽", """
                    增强功能 → 站点注入可添加 WebHome、通用 CSP 或直播站点，支持 JSON、代码、文件和链接，并可设置插入位置。
                    顶部“识别”可粘贴单个或多个松散站点 JSON，自动归类后追加；WebHome 站点还可单独配置扩展。
                    来源屏蔽用于保留配置但暂时不使用某些接口源或文件源，不会删除配置。
                    文件源的默认展示样式和搜索行为由文件名参数决定，改名后需要重新加载站点。
                    """),
            new Module("增强功能", """
                    增强功能是多个工具的统一入口：来源屏蔽、网盘检测、站点健康排序、管理页面、远程托管、WebHome 扩展、登录态学习、观影记录同步、APP 代理和调试日志。
                    站点健康排序会学习搜索、详情和播放成功率；默认保留用户配置顺序，站点弹窗内可单独开启健康排序。
                    网盘检测支持 WebHome 和本地 HTTP API 调用；观影记录同步支持 Webhook 上报和从远端 API 拉取批量记录。
                    """),
            new Module("管理页面、远程托管与同步", """
                    增强功能 → 管理页面会启动前台服务，并给出本机或局域网地址 http://…/m；可在浏览器管理文件、登录态、同步目录、站点注入、接口、壳代理、搜索和推送。
                    远程托管通过自建 Cloudflare、Deno、Vercel、Go 或 Rust 中转服务绑定多台设备，支持设备状态、远程搜索/推送、接口配置、主页设置、一键同步和最近日志。
                    一键同步可勾选配置、站源数据、登录态、WebHome 数据、搜索记录、观看历史、收藏和应用设置；Git 云盘用于配置备份。
                    管理页面当前没有鉴权，只应在可信局域网内临时开启，用完立即停止服务。
                    """),
            new Module("下载、缓存与备份", """
                    首页菜单的下载管理可查看任务、修改存储路径、取消或删除任务；下载前先确认目标格式和存储空间。
                    缓存管理可清理播放缓存、预加载数据和 TMDB 缓存；空间不足时优先清缓存，不要直接删除配置和历史。
                    换机或升级前先导出备份，必要时开启自动备份；恢复后检查站源、接口、登录态和同步目录是否完整。
                    """),
            new Module("网络与调试日志", """
                    网络相关设置有 Proxy/壳代理规则、DoH、headers 和 GitHub 代理；代理规则建议按站点或域名精确匹配，不要全局滥用。
                    调试日志入口在设置中；日志筛选、只看注释和时间戳会保留，支持清空和回到顶部。
                    崩溃页会提供可复制的局域网日志地址。排查播放或站源问题时，尽量保留完整日志再反馈，不要只截取一行报错。
                    """));

    private UsageHelpDialog() {
    }

    public static void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        DialogUsageHelpBinding binding = DialogUsageHelpBinding.inflate(LayoutInflater.from(activity));
        List<Row> rows = new ArrayList<>();
        View firstHeader = null;
        View previousHeader = null;
        for (Module module : MODULES) {
            ItemUsageHelpModuleBinding item = ItemUsageHelpModuleBinding.inflate(LayoutInflater.from(activity), binding.moduleList, false);
            item.header.setText(COLLAPSED + module.title());
            item.body.setText(module.content());
            item.body.setVisibility(View.GONE);
            item.header.setOnClickListener(v -> toggle(item, module));
            item.header.setId(View.generateViewId());
            binding.moduleList.addView(item.getRoot());
            rows.add(new Row(item, module));
            if (firstHeader == null) firstHeader = item.header;
            if (previousHeader != null) previousHeader.setNextFocusDownId(item.header.getId());
            previousHeader = item.header;
        }
        if (firstHeader != null) {
            binding.close.setNextFocusDownId(binding.searchInput.getId());
            binding.searchInput.setNextFocusUpId(binding.close.getId());
            binding.searchInput.setNextFocusDownId(firstHeader.getId());
            firstHeader.setNextFocusUpId(binding.searchInput.getId());
        }
        if (previousHeader != null) previousHeader.setNextFocusDownId(binding.close.getId());

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(binding.getRoot());
        binding.close.setOnClickListener(v -> dialog.dismiss());
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(rows, binding, s == null ? "" : s.toString());
            }
        });
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        configureWindow(activity, dialog, binding);
        View focusTarget = firstHeader;
        if (focusTarget != null) binding.helpScroll.post(() -> focusTarget.requestFocus());
        else binding.close.requestFocus();
    }

    private static void filter(List<Row> rows, DialogUsageHelpBinding binding, String raw) {
        String query = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int matchCount = 0;
        View firstMatch = null;
        for (Row row : rows) {
            boolean match = query.isEmpty() || matches(row.module(), query);
            row.binding().getRoot().setVisibility(match ? View.VISIBLE : View.GONE);
            if (!match) continue;
            matchCount++;
            if (firstMatch == null) firstMatch = row.binding().header;
            boolean expanded = !query.isEmpty();
            row.binding().body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            row.binding().header.setText((expanded ? EXPANDED : COLLAPSED) + row.module().title());
        }
        binding.empty.setVisibility(matchCount == 0 ? View.VISIBLE : View.GONE);
        binding.moduleList.setVisibility(matchCount == 0 ? View.GONE : View.VISIBLE);
        if (query.isEmpty()) {
            binding.helpScroll.scrollTo(0, 0);
            return;
        }
        View target = firstMatch;
        if (target == null) return;
        binding.helpScroll.post(() -> {
            if (Util.isLeanback()) target.requestFocus();
            else binding.helpScroll.smoothScrollTo(0, target.getTop());
        });
    }

    private static boolean matches(Module module, String query) {
        String haystack = (module.title() + "\n" + module.content()).toLowerCase(Locale.ROOT);
        for (String term : query.split("\\s+")) {
            if (!term.isEmpty() && !haystack.contains(term)) return false;
        }
        return true;
    }

    private static void toggle(ItemUsageHelpModuleBinding item, Module module) {
        boolean expanded = item.body.getVisibility() == View.VISIBLE;
        item.body.setVisibility(expanded ? View.GONE : View.VISIBLE);
        item.header.setText((expanded ? COLLAPSED : EXPANDED) + module.title());
    }

    private static void configureWindow(FragmentActivity activity, Dialog dialog, DialogUsageHelpBinding binding) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.CENTER;
        params.dimAmount = 0.62f;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        if (Util.isLeanback()) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
            int inset = ResUtil.dp2px(24);
            View root = binding.getRoot();
            root.setPadding(
                    root.getPaddingLeft() + inset,
                    root.getPaddingTop() + inset,
                    root.getPaddingRight() + inset,
                    root.getPaddingBottom() + inset);
        } else {
            params.width = (int) (ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.72f : 0.92f));
            params.height = (int) (ResUtil.getScreenHeight(activity) * (ResUtil.isLand(activity) ? 0.84f : 0.86f));
            window.setAttributes(params);
        }
    }

    private record Module(String title, String content) {
    }

    private record Row(ItemUsageHelpModuleBinding binding, Module module) {
    }
}
