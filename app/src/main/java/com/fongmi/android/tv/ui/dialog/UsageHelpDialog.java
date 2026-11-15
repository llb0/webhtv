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
import com.fongmi.android.tv.databinding.ItemUsageHelpSectionBinding;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关于页的“使用帮助”入口。
 *
 * 模块默认全部折叠，只展示目录；点击标题展开说明。模块内再按子项展开，
 * 便于先看入口，再按需深入。内容按当前仓库 README、docs/integration、
 * WebHome 开发文档、设置页文案和代码注释整理。
 */
public final class UsageHelpDialog {

    private static final String COLLAPSED = "＋  ";
    private static final String EXPANDED = "－  ";
    private static final int BOTH = 0;
    private static final int MOBILE = 1;
    private static final int LEANBACK = 2;

    private static final List<Module> MODULES = List.of(
            module("首页与多仓", "首页是点播、直播和 WebHome 的统一入口；设置页负责配置，首页菜单负责快捷操作。",
                    section("顶部导航", """
                            点播页顶部是站点和分类，直播页顶部是频道分组；WebHome 站点会按配置加载网页首页。
                            本站按手机/电视分别显示底部导航或首页功能区入口，详见下方本端入口。
                            """),
                    section("首页菜单", """
                            点播顶栏提供搜索、最近观看和“更多”。
                            “更多”包含：多仓、刷新、我的收藏、一键同步、推送apk、推送播放、接口下载、增强功能、下载管理。
                            这里的“接口下载”是下载点播接口配置，不是下载影片。
                            """),
                    section("多仓", """
                            首页“更多 → 多仓”打开多仓界面；顶部切换仓库，左侧是接口列表，右侧是该接口的站点网格。
                            多仓界面标题栏的设置按钮用于维护仓库名称和链接；首次没有仓库时会提示新增。
                            """),
                    section("设置页内容", """
                            设置页包含点播、直播、壁纸、增强功能、TMDB、AI 服务、个性设置、播放设置、去广告、弹幕设置、字幕设置、默认启动、外观与语言、无痕模式、DoH、缓存、备份和恢复。
                            “版本”项进入关于界面；关于界面里有检查更新、加速源和使用帮助。
                            """),
                    mobileSection("手机端入口与导航", """
                            手机从底栏“设置”进入；设置项以卡片列表呈现，点击整行进入。
                            手机点播页顶栏是搜索、最近观看和“更多”，底栏切换点播/直播/设置。
                            """),
                    tvSection("电视端入口与导航", """
                            电视从首页的“设置”按钮进入；设置页按“内容源 / 播放与内容 / 个性化与智能 / 数据与网络”分组。
                            电视用方向键移动焦点、OK 确认，没有触摸底栏；首页功能区进入点播、直播、设置。
                            """),
                    section("多仓与刷新", """
                            多仓用于维护多个接口仓库；切换仓库或点击“刷新”后，会重新加载对应仓库的接口和站点。
                            多仓只负责切换/维护配置，不替代设置页的点播地址；直连点播地址仍在“设置 → 点播”维护。
                            """)),

            module("本地 SMB 浏览与播放", "直接浏览同一局域网的 NAS、Windows 共享或 SMB 服务器；入口默认关闭，开关在“个性设置”，手机和电视共用同一份配置。",
                    section("开关与入口", """
                            打开“设置 → 个性设置 → 本地SMB播放入口”，把开关切到 On；关闭后手机底栏和电视首页菜单都会隐藏入口。
                            开关保存为 smb_entry_enabled，默认 Off；手机和电视共用这个值，但入口位置不同，见下方本端入口。
                            入口只控制“是否显示”，不会自动连接服务器；首次打开仍要扫描或手动选择主机。
                            """),
                    section("连接与登录", """
                            手机/电视必须和 SMB 服务器在同一局域网；服务器要开启 SMB2/SMB3 并允许 TCP 445 入站，路由器不能开启 AP/访客隔离。
                            打开页面会自动扫描物理网卡的私有 IPv4 网段（10/8、172.16/12、192.168/16），最多按 /24 收窄；VPN、蜂窝和虚拟网卡不扫描。
                            选中主机后登录：用户名留空会先试匿名、再回退 Guest；有账号时填写用户名/密码，域可留空，Windows 域共享或部分 NAS 才需要填。需要下次自动填写可勾选“保存登录信息”，登录界面也能“清除已保存的登录信息”。
                            共享目录没有自动列出时，用“手动输入共享名”直接输入，例如 video、media；共享枚举失败不代表共享不能打开。
                            """),
                    section("浏览、收藏与隐藏", """
                            目录只显示子目录和可识别的视频、音乐、图片、APK，普通文档不会混进播放列表。
                            视频/音乐点按播放，图片点按预览，APK 点按交给系统安装器；返回键逐级退回：文件 → 共享目录 → 主机 → 关闭页面。
                            “收藏夹”保存常用主机/共享/目录，主机可写备注；“显示隐藏”恢复被隐藏的主机或目录。
                            电视端菜单键、手机端长按会打开同一个 SMB 菜单：当前条目的操作之后固定追加“收藏夹 / 显示隐藏 / 设置或取消隐藏密码 / 主机 / 关闭”；主机页没有条目时也能调出这五项，电视端菜单焦点到首/末项会上下环跳。
                            “显示隐藏”设置了隐藏密码时会先要求输入密码，校验通过才显示；“设置隐藏密码”可设置非空密码，设置后同一位置改为“取消隐藏密码”，清除后恢复为设置状态。密码保存在 SharedPreferences("smb_hosts") 的 hidden_password。
                            主机长按（电视端按菜单键）可“保存并备注/修改备注”“清除登录信息”“隐藏此地址”；共享和文件长按可收藏、隐藏或手动打开共享。
                            收藏、隐藏和主机备注保存在 SharedPreferences("smb_hosts")；登录信息默认不保存，勾选后按主机加密保存到 smb_credentials，主机菜单里的“清除登录信息”可随时删除当前主机记录。取消勾选“保存登录信息”并在本次登录成功后，也会清除该主机此前保存的旧凭据。
                            """),
                    section("文件类型与播放兼容", """
                            视频包括 mp4、mkv、avi、mov、m4v、ts、m2ts、wmv、flv、webm、mpg/mpeg、vob、3gp/3g2、mts、mxf、ogv 等；音频包括 mp3、flac、m4a/m4b、aac、wav、ogg/opus、wma、ape、aiff/aif、amr、mid/midi、mka、ac3、dts 等。
                            图片包括 jpg/jpeg/jpe、png、gif、bmp、webp、heic/heif、avif、tif/tiff；APK 只负责交给系统安装器，不会静默安装。
                            播放时 SmbPlaybackProxy 把 SMB 文件暴露成本机 127.0.0.1 的 HTTP 地址并支持 Range，播放器可以正常 seek；代理 token 只在进程内存，最多保留 24 个。
                            代理 URL 保留真实文件名后缀并追加 smb=1 直链标记，不再把 AVI/WMV 补成 .mp4；播放页“外调/选择打开方式”使用同一 token 的真实后缀地址和局域网 IP，避免外调播放器按 MP4 解析或拒绝 127.0.0.1；打开方式列表由应用内提供，系统没有可用播放器时会提示。
                            代理同时监听 0.0.0.0，本机仍走 127.0.0.1；投屏/推送到远端时接收端可通过局域网访问发送端代理，不再因为只绑回环地址报“网络读取失败”。
                            SMB 播放页会在本次会话临时使用 EXO，退出后恢复设置里的默认内核；这个覆盖不写回全局播放设置。
                            """),
                    mobileSection("手机端入口", """
                            手机开启开关后，底栏在“设置”左侧出现 SMB；关闭后立即隐藏，不需要重启 App。
                            点击 SMB 会打开独立的局域网 SMB 播放页，返回后底栏选中状态会回到进入前的点播/设置页。
                            第一次在 Android 17/API 37 设备扫描时会请求本地网络权限；拒绝后页面会提示授权，不会继续扫描。
                            """),
                    tvSection("电视端入口", """
                            电视开启开关后，首页“更多/首页菜单”在“选站”和“多仓”之间插入“本地SMB”；关闭后菜单恢复原来的“选站 + 多仓 + 功能项”顺序。
                            电视首页没有单独的 SMB 功能区按钮；入口只在首页菜单里，避免改变原有首页功能区布局。
                            进入后用方向键移动焦点、OK 确认、返回键逐级退出；共享/文件的更多操作可用菜单键，图片可直接全屏查看。
                            """),
                    section("数据、权限与排查", """
                            SMB 页面需要 INTERNET、ACCESS_NETWORK_STATE、ACCESS_LOCAL_NETWORK（Android 17/API 37 起本地网络需要运行时授权）；服务器/防火墙要放行 TCP 445。
                            扫描不到先确认同一网段、SMB 服务已开启、445 可访问、没有 AP 隔离；仍不行可手动输入主机 IP 后在主机列表长按收藏。
                            登录失败检查账号密码/域和共享权限；匿名失败会自动回退 Guest，服务器关闭 Guest 时必须用有权限的账号。
                            共享列表为空多半是设备不返回枚举，改用手动输入共享名；视频卡顿优先检查 Wi-Fi/有线网络和码率，必要时退出后换内核重试。
                            清除应用数据会同时清除主机收藏、备注、隐藏记录和已保存的登录信息；登录信息未勾选保存时只在当前浏览页面内存中，退出页面即失效。
                            历史兜底截图保存在应用私有目录 webhome_snapshot；不会只按数量自动清理，删除对应历史记录或清空历史时会同步删除该截图的缓存文件。
                            """)),

            module("点播与站源", "点播支持接口源和文件源；接口源是 URL 配置，文件源放在存储根目录的 /tvbox/ 下。",
                    section("接口源", """
                            设置 → 点播 → 点播地址填写 JSON 接口 URL，保存后重新加载配置。
                            配置可包含 sites、parses、lives、doh、proxy、headers、rules 等字段；接口返回失败时先检查 URL、网络和返回内容。
                            """),
                    section("文件源类型与目录", """
                            点播文件源统一放在存储根目录的 /tvbox/ 下，按类型分目录；直播文件源单独放在 /tvbox/lives/。
                            存储根目录由 App 的 Path.root() 决定，不是安装目录；目录不存在或没有可读文件时不会生成站点。
                            文件源不是设置页里的单独按钮：把文件放进对应目录后重新加载点播配置即可；设置 → 点播主要负责接口地址和刷新。
                            文件源加载后会自动生成站点/频道，不需要再逐条填写 URL；改名或增删文件后要重新加载点播配置。
                            """),
                    section("sites-json（XBPQ 站源）", """
                            目录：/tvbox/sites-json/；一个文件对应一个站源，文件内容是该站源的 JSON 配置。
                            运行时：需要 /tvbox/jars/XBPQ.jar；本地没有该 JAR 时会尝试用配置里的全局 spider，二者都没有则站源无法运行。
                            站名：取文件名并按同一套“开关-样式”规则解析，列表里会追加“| PQ”标识；扩展名一般是 .json。
                            """),
                    section("sites-js（JS 站源）", """
                            目录：/tvbox/sites-js/api/；一个 .js 文件对应一个站源，不能直接放在 sites-js 根目录。
                            运行时：依赖全局 spider/JAR；站名按文件名解析，列表里追加“| JS”标识。
                            适用：已有 JS 爬虫脚本；脚本自身依赖的库/接口要能访问，单纯放文件不代表脚本一定可用。
                            """),
                    section("sites-py（Python 站源）", """
                            目录：/tvbox/sites-py/；一个 .py 文件对应一个站源。
                            运行时：依赖 Python 运行时和全局 spider/JAR；站名按文件名解析，列表里追加“| PY”标识。
                            常见问题：脚本缺少依赖、网络不可达或 Python 环境未启用时，站点会加载失败或被过滤。
                            """),
                    section("sites（原始 JSON 站点）", """
                            目录：/tvbox/sites/；每个文件（一般用 .json）是一个完整的站点 JSON 对象，可直接写 name、api、type、searchable、quickSearch 等字段。
                            加载方式是按 JSON 对象解析，不追加 PQ/JS/PY 后缀；适合单站点配置、临时覆盖或小型站点。
                            文件不是有效 JSON 对象时会被跳过；文件名没有 name 字段时才用作站名。
                            """),
                    section("lives（直播文件源）", """
                            目录：/tvbox/lives/；TXT、M3U、JSON 等直播源文件都可以放，一个文件对应一个直播源。
                            直播源名取文件名去掉扩展名；直播文件源不走点播的开关/样式解析，S/N/H/ratio 这类点播文件名参数不要套用到直播源上。
                            文件里没有可用频道分组时不会生成该直播源；直播配置与点播配置分开加载。
                            """),
                    section("文件名开关", """
                            文件源命名规则是“文件名.开关-样式.扩展名”；点号后的第一段是开关，横线后的尾段是样式。
                            开关 S：hide=1，只参与搜索，不在分类/站点列表展示。
                            开关 N：searchable=0、quickSearch=0，并默认 changeable=2，即禁止搜索、禁止自动换源，但站点仍保留在配置中。
                            """),
                    section("文件名样式", """
                            样式 H：ratio=1.33，横图展示。
                            样式 S：ratio=1.0，方图展示。
                            样式是数字时直接作为 ratio，例如 1.78；数字可以带小数点。
                            同一个字母 S 在两段含义不同：开关段的 S 是“只搜索不展示”，样式段的 S 是“1:1 方图”。例如“名.S-H”是开关 S 加横图 H；“名.N-S”是开关 N 加方图 S。
                            """),
                    section("文件名、排序与扩展名", """
                            站点名称取文件名第一个点之前的部分；不要在文件名里随意加点，否则点后的内容会被当作开关/样式段。
                            文件名开头的“01_”只用于排序，排序前缀会在解析时去掉，不进入站点名称；多个文件按这个前缀顺序加载。
                            扩展名取最后一个点后的部分，开关/样式写在扩展名之前；例如 01_名.N-1.78.json 会解析为禁止搜索 + 宽高比 1.78。
                            目录名 sites-json、sites-js、sites-py、sites 不能混用：它们对应不同的加载器和运行环境。
                            """),
                    section("浏览与详情", """
                            首页按分类浏览，搜索结果可进入详情；详情页提供线路、选集、换源、收藏、下载和 TMDB。
                            站点为空先检查接口/文件目录是否正确，再检查来源屏蔽是否把该站源挡住；文件源还要检查运行时 JAR/spider。
                            """),
                    mobileSection("手机端详情操作", """
                            手机端详情页是触屏卡片布局；TMDB 模式下“下载”按钮和换源、收藏、TMDB 在同一组按钮里，位于 TMDB 按钮之前。
                            小窗预览使用手机播放控件；点按换源/收藏/TMDB 进入对应面板，不需要遥控器焦点。
                            """),
                    tvSection("电视端详情操作", """
                            电视端详情页用方向键移动焦点、OK 键确认；操作按钮按横向焦点链进入，下载/换源/TMDB 的先后顺序以当前布局为准。
                            电视端没有触摸小窗；播放页用遥控器控制，返回键回详情，焦点会回到进入前的按钮。
                            """)),
            module("搜索", "搜索从首页顶栏进入，会并行查询多个可用站源；结果受站源开关、搜索配置和健康排序影响。",
                    section("搜索入口", """
                            点播首页顶栏的搜索图标进入搜索页；输入关键词后展示多站结果，结果可切换站点或换源。
                            """),
                    section("结果为空", """
                            先换关键词，再检查接口是否失效、网络是否正常、站源是否被来源屏蔽。
                            文件源开关 S 只隐藏站点展示，仍可被搜索命中；开关 N 会禁止搜索，检查文件名参数是否写错。
                            """),
                    section("搜索设置", """
                            设置 → 个性设置 → 搜索线程、搜索布局、搜索结果模式、搜索结果排序。
                            “搜索线程”控制并发搜索数量；线程过高可能被站点限流，过低会变慢。
                            """),
                    section("健康排序", """
                            站点健康排序入口在“设置 → 增强功能 → 站点健康排序”；它学习搜索、详情和播放成功率。
                            站点弹窗默认保留用户配置顺序，可在弹窗内单独开启健康排序；不想改变顺序时不要开启。
                            """),
                    mobileSection("手机端搜索操作", """
                            手机端搜索结果可以直接点按打开详情；搜索布局、搜索结果模式和排序在“设置 → 个性设置”。
                            手机端可直接用输入法键盘搜索，不需要移动遥控焦点。
                            """),
                    tvSection("电视端搜索操作", """
                            电视端搜索结果用方向键移动焦点、OK 打开；搜索布局/结果模式同样在“设置 → 个性设置”。
                            搜索框获得焦点后按 OK 进入输入，返回键收起键盘/回到结果。
                            """)),
            module("直播", "直播与点播是独立配置；直播源在设置页维护，播放失败通常先换线路或换内核。",
                    section("直播源类型", """
                            设置 → 直播可添加 TXT、M3U、JSON 或 Spider 直播源；直播文件源放 /tvbox/lives/。
                            直播源和点播源互不影响；点播接口里的 lives 字段也会生成直播配置。
                            """),
                    section("EPG 与回看", """
                            直播支持频道分组、EPG 节目单和回看；回看依赖直播源自身的 catchup 配置。
                            EPG 可以随源，也可以单独选择 XML/GZ 或带 {name}/{date} 模板的地址。
                            没有回看数据的频道只能在直播时间播放，不是所有频道都支持回看。
                            """),
                    section("播放与排查", """
                            直播播放失败可切换线路或播放内核；频道空白先检查源格式、分组字段和网络。
                            直播历史与点播历史分开维护；删除点播历史不会删除直播频道配置。
                            """)),

            module("详情页与 TMDB", "详情页负责线路、选集和换源；TMDB 负责条目匹配、季度和海报来源。",
                    section("申请与配置 TMDB Key", """
                            入口：设置 → TMDB → TMDB 数据配置。项目不内置 TMDB Key，需要用自己的账号申请。
                            申请：打开 https://www.themoviedb.org/settings/api，注册/登录 TMDB 账号后申请 API Key 或 v4 Access Token（免费），复制 Key 或 Token。
                            填写：在弹窗中启用 TMDB，填入 API Key/Access Token，语言建议 zh-CN；API 线路和图片线路可分别选择自动或自定义地址。
                            验证：点“测试配置”检查 API、图片和评分接口；Key 不要公开，图片线路与 API 线路要分别可用。
                            """),
                    section("匹配与季度", """
                            设置 → TMDB → TMDB 数据配置：详情打开方式、详情主题、匹配模式和分集文件名规则也在这里维护。
                            “匹配 TMDB 季度”提供自动判断（清除手动绑定）、按集号主键自动分季、保持原始集列表和 AI 识别集数/分季。
                            “自动判断”会清除已有手动绑定；“保持原始集列表”会保留站源给出的集列表，不再按 TMDB 季度重排。
                            """),
                    section("海报来源", """
                            普通站源历史海报：先取分类传入图；分类图是标题首字或空图时，详情匹配到 TMDB 且有图就直接用 TMDB 海报；没有 TMDB 图才取站源详情图；仍无图才用播放画面非黑屏截图。
                            历史卡片一旦已有可用海报，就不会被后续详情页空图覆盖。
                            推送历史的海报由接收设备生成；截图成功后左上角站源位置显示“推送”。
                            """),
                    section("展示模式", """
                            详情页可在影视原生、原生增强、融合等展示方式间切换；设置 → TMDB 的详情打开方式和详情主题会影响默认入口。
                            光影巨幕等属于播放页增强，不是独立的视频源；出现解析异常时先切回普通线路或重新选择站源。
                            """),
                    section("排查", """
                            详情页空白先检查站源接口、分类字段和来源屏蔽；TMDB 无数据先检查 Key、数据源和匹配模式。
                            换季/换集后播放失败，先切回普通线路；仍然失败再换源或切换播放内核，不要直接删除全部历史。
                            """),
                    mobileSection("手机端 TMDB 操作", """
                            手机端详情页的 TMDB 按钮和换源、收藏并排；TMDB 模式下“下载”按钮位于 TMDB 按钮之前，透明度与整组按钮一致。
                            小窗预览中的下载/TMDB 入口按触摸操作，不需要遥控器焦点。
                            """),
                    tvSection("电视端 TMDB 操作", """
                            电视端详情页用方向键在换源、收藏、下载、TMDB 按钮间移动，OK 进入；没有手机小窗预览。
                            电视端 TMDB 季度/匹配面板同样用方向键选择，返回键回到详情页。
                            """)),

            module("播放器", "播放设置负责内核、渲染、缓存和预加载；具体线路仍在详情页选择。",
                    section("入口与内核", """
                            设置 → 播放设置 → 内核；可选 EXO、IJK、MPV 等内核，渲染和硬解/软解选项也在该页。
                            “播放设置”里的缓存、预加载、回退和倍速是全局默认，不是单个视频的临时设置。
                            """),
                    section("播放控制", """
                            播放页控制栏提供音轨、字幕、弹幕、倍速、画面比例、旋转和投屏等操作；具体按钮按当前端布局显示。
                            """),
                    mobileSection("手机端播放控制", """
                            手机竖屏时是详情页内嵌小窗，横屏进入全屏；控制栏用触摸点按，旋转按钮按屏幕方向旋转。
                            手机小窗预览和全屏共用当前播放进度，投屏按钮会把当前视频交给远端。
                            """),
                    tvSection("电视端播放控制", """
                            电视端以全屏播放为主；方向键移动焦点、OK 确认，返回键退出控制栏/返回详情。
                            电视端控制栏的旋转按钮，其上键效果等同 OK 键，可顺时针旋转画面；没有触摸小窗操作。
                            """),
                    section("解码与故障", """
                            黑屏、花屏或只有声音时，依次尝试切换硬解/软解、切换播放内核、切换线路。
                            某条线路解析失败但其他线路正常，说明更可能是站源/线路问题，不是 TMDB 元数据问题。
                            删除历史后仍然失败，优先保留日志并换源验证；不要只靠删除历史判断。
                            """)),

            module("弹幕设置", "弹幕设置是设置页的独立入口，不在“播放设置”里；播放页另有弹幕面板。",
                    section("设置入口", """
                            设置 → 弹幕设置：包含弹幕加载、搜索接口、自动搜索和爬虫优先。
                            弹幕加载关闭时，播放页不显示弹幕按钮；打开后才允许加载站源或接口弹幕。
                            """),
                    section("加载与接口", """
                            弹幕加载打开后可填写搜索接口；接口为空时使用内置源或站源返回的弹幕。
                            自动搜索打开后，进入播放会按当前标题/集数尝试加载；爬虫优先时优先使用站源弹幕。
                            """),
                    section("播放页面板", """
                            播放页的弹幕按钮打开弹幕列表/设置面板；面板可调字号、透明度、粗体和描边等显示样式。
                            弹幕不显示时先确认弹幕加载已开启，再检查站源是否返回 danmaku 字段或接口是否可用。
                            """),
                    mobileSection("手机端弹幕面板", """
                            手机播放页点弹幕按钮打开列表/样式面板，触摸调整字号、透明度、粗体和描边。
                            """),
                    tvSection("电视端弹幕面板", """
                            电视播放页用方向键移动到弹幕按钮，OK 打开面板；面板内的选项同样用方向键/OK 调整。
                            """)),
            module("字幕设置", "字幕设置是设置页的独立入口；播放页的字幕按钮负责选择当前轨道。",
                    section("入口", """
                            设置 → 字幕设置：包含自动匹配字幕、字幕偏好语言和字幕源。
                            AI 实时字幕模型、并发和分块在“设置 → AI 服务”里，不在字幕设置里。
                            """),
                    section("自动匹配与语言", """
                            自动匹配会按当前视频标题/集数寻找字幕；字幕偏好语言用于选择优先语言。
                            自动匹配不等于一定有字幕：站源没有字幕或搜索源不可用时仍会为空。
                            """),
                    section("字幕源", """
                            字幕源可选择内置或外部字幕来源；接口需要的 Cookie、Referer 等请求头要由站源或字幕源提供。
                            播放页字幕按钮可切换已加载的轨道；轨道存在但不显示时检查字幕开关和当前轨道。
                            """),
                    mobileSection("手机端字幕操作", """
                            手机播放页点字幕按钮选择轨道；字幕设置里的自动匹配/语言/源在“设置 → 字幕设置”。
                            """),
                    tvSection("电视端字幕操作", """
                            电视播放页用方向键进入字幕按钮，OK 打开轨道列表；选择后用返回键关闭面板。
                            """)),
            module("历史、收藏与续播", "最近观看和我的收藏在首页顶栏；历史记录与收藏是两套数据。",
                    section("入口", """
                            首页顶栏“最近观看”进入点播历史；“我的收藏”进入收藏列表。
                            直播历史单独维护；删除点播历史不会删除收藏和直播频道。
                            """),
                    section("历史记录规则", """
                            设置 → 个性设置 → 记录分集进度、全局历史续播；设置 → TMDB → 历史记录跨源聚合。
                            “记录分集进度”保存每集进度；“全局历史续播”允许跨配置寻找可续播的源。
                            无痕模式开启后不写入播放历史，排查“历史不出现”时先检查该开关。
                            """),
                    section("海报优先级", """
                            普通站源：分类图 → TMDB 详情图 → 站源详情图 → 非黑屏截图；分类图是标题首字或空图时才继续向下取。
                            TMDB 来源（含推送/远端）优先使用已有海报或截图；只有缺失时才回退到详情海报。
                            已有可用海报不会被详情页空图覆盖；需要更新海报时先确认新图非空。
                            """),
                    section("收藏与清理", """
                            收藏独立保存，删除历史不会删除收藏；清空历史/收藏是分开的确认操作。
                            重要历史或收藏可在设置 → 备份中导出，恢复时注意覆盖范围。
                            """),
                    mobileSection("手机端历史与收藏", """
                            手机端历史/收藏以列表和卡片展示，点按卡片继续播放；长按或菜单可删除单条/清空。
                            手机端可从首页顶栏“最近观看”“我的收藏”直接进入，不需要遥控焦点。
                            """),
                    tvSection("电视端历史与收藏", """
                            电视端历史/收藏用横向卡片或网格展示，方向键移动、OK 继续播放；操作菜单用焦点进入。
                            电视端清空/删除是确认弹窗，返回键取消，不会直接删除。
                            """)),
            module("投屏与推送", "投屏从播放页发起；推送播放/推送apk在首页菜单，接收端会生成推送历史。",
                    section("投屏", """
                            播放页投屏按钮选择远端设备；双方需要同网可达，或已通过远程托管绑定。
                            WebHome 页面内投屏入口按端显示，手机端小窗预览会一并传递链接、headers 和标题。
                            """),
                    section("推送播放与 APK", """
                            首页“更多 → 推送播放”选择远端设备并推送当前播放；“推送apk”把安装包推送到接收设备。
                            远端接收后生成“推送”历史；再次播放后由接收端负责保存进度和海报。
                            """),
                    section("推送海报", """
                            接收端播放后使用自己的非黑屏截图作为历史海报；有效截图生成后，左上角站源位置显示“推送”。
                            只有纸飞机占位图说明截图尚未生成或截图失败；可等播放稳定后返回，或重新进入该历史。
                            """),
                    section("排查", """
                            投屏失败先检查两端网络、接收端服务状态和链接是否过期；HTTP/HTTPS 混合内容也可能被拦截。
                            推送历史没有海报时，先确认接收端确实播放出画面，再检查截图回填是否被空图覆盖。
                            """),
                    mobileSection("手机端投屏与推送", """
                            手机端从播放页/小窗预览的投屏按钮发起；推送播放/推送apk在首页“更多”里。
                            WebHome 小窗预览投屏会同时传链接、headers 和标题，接收端用标题生成推送历史。
                            """),
                    tvSection("电视端投屏与 PUSH", """
                            电视端从播放页投屏按钮发起，推送播放/推送apk在首页“更多”里。
                            电视端 HomeActivity 收到 PUSH 事件时会把 event.name() 传给 VideoActivity.push，标题会随推送带到接收端。
                            """)),
            module("WebHome 首页", "WebHome 是站点的网页首页能力；站点声明 homePage 后优先加载网页。",
                    section("配置", """
                            站点配置 homePage 后，切换该站点会优先加载网页首页；相对路径按配置文件 URL 解析。
                            WebHome 不需要 bridge: full；当前默认注入完整 SDK。站点 header 会应用 User-Agent 和 Cookie。
                            """),
                    section("SDK 与事件", """
                            页面可调用 window.fongmi 或 window.fm，SDK 注入后会触发 fmsdk 事件。
                            App 前后台切换会触发 fmresume/fmpause；视口或安全区变化触发 fmviewport；路由变化触发 fmurlchange。
                            关键持久化数据建议在 fmsdk 之后读写 fm.cache，不要在 SDK 未就绪时写入浏览器 fallback。
                            """),
                    section("全屏、皮肤与小窗", """
                            增强功能里有“全局 WebHome 皮肤”“WebHome 主页全屏”和“CSP预热”；首页菜单也有 WebHome 全屏按钮。
                            网页内快速试播入口按端显示；投屏到远端时会一并传递标题。
                            """),
                    section("排查", """
                            网页空白先检查 homePage 地址、WebView 版本、CSP、扩展脚本和网络，再检查站点 header。
                            页面内需要 App 播放按钮、直链或网盘路由时，用 WebHome 扩展补充，不要把网页问题当成播放器问题。
                            """),
                    mobileSection("手机端 WebHome", """
                            手机端可用小窗预览网页内视频，投屏按钮会把当前视频交给远端；返回键回网页/详情。
                            手机端 WebHome 全屏和皮肤入口在增强功能/首页菜单，按当前布局显示。
                            """),
                    tvSection("电视端 WebHome", """
                            电视端用方向键操作网页焦点，建议开启“WebHome 主页全屏”获得完整焦点区域；返回键退出全屏网页。
                            电视端 WebHome 全屏/皮肤在增强功能里配置，遥控器没有触摸滚动。
                            """)),
            module("WebHome 扩展", "WebHome 扩展是在真实网站页面注入的用户脚本，不替代原网站；入口在增强功能。",
                    section("入口与来源", """
                            设置 → 增强功能 → WebHome 扩展；来源支持本地文件、远程链接/manifest、直接代码、表单生成和 JSON。
                            扩展可写在单站点 extensions，也可放在根级 webHomeExtensions；单站点扩展通常不需要 cspKeyRegex。
                            """),
                    section("匹配与类型", """
                            匹配范围可从当前点播配置的 WebHome 站点多选，也可切换为 CSP key 正则。
                            扩展用于增加 App 播放按钮、直链/HLS/网盘路由、页面清理、TV 焦点和安全区适配，不用于绕过登录或风控。
                            """),
                    section("调试工作台", """
                            调试工作台可预览网页、查看 Console/Network/Elements，并在保存前检查脚本效果。
                            修改域名匹配或 runAt 后需要重新加载页面；document-start 扩展依赖 WebView 的 DOCUMENT_START_SCRIPT 支持。
                            """),
                    section("排查", """
                            扩展不生效时检查是否启用、匹配范围是否命中当前站点、脚本是否在正确的页面路径执行。
                            脚本报错先看 Console；网页本身打不开或需要人机验证时，扩展不能替代登录或绕过访问限制。
                            """)),

            module("站点注入与来源屏蔽", "站点注入用于新增站点配置；来源屏蔽用于保留配置但暂时不使用。",
                    section("站点注入", """
                            设置 → 增强功能 → 站点注入：添加 WebHome、通用 CSP 或直播站点，支持 JSON、代码、文件和链接。
                            新增/修改在独立表单中维护启用状态、插入位置、首页、搜索和换源行为；主列表显示摘要和快捷操作。
                            """),
                    section("识别与编辑", """
                            顶部“识别”可粘贴单个或多个松散站点 JSON 片段，自动归类后追加；WebHome 站点还可单独填写扩展 URL/JSON。
                            """),
                    section("来源屏蔽", """
                            设置 → 增强功能 → 来源屏蔽；接口源、点播文件源和直播文件源都可以单独屏蔽。
                            屏蔽只停用，不删除配置；恢复时取消屏蔽并重新加载。
                            文件源默认配置由文件名参数决定，不要用来源屏蔽替代文件名开关。
                            """)),

            module("增强功能", "增强功能集中了来源、站点、同步、管理页、网络和日志工具；手机和电视均有独立页面。",
                    section("工具列表", """
                            入口：设置 → 增强功能。包含来源屏蔽、网盘检测、站点健康排序、管理页面、远程托管、Git 云盘、Proxy、站点注入、WebHome 扩展、全局 WebHome 皮肤/全屏/CSP预热、观影记录同步、登录态学习和一键同步。
                            另有实验室、插件管理、本地小说漫画阅读、小说/漫画/音频/短剧源配置等扩展入口。
                            """),
                    section("站点健康排序", """
                            健康排序会学习搜索、详情和播放成功率；默认保留用户配置顺序，可在站点弹窗中单独开启。
                            排序异常时可在站点弹窗关闭健康排序，恢复按配置顺序使用。
                            """),
                    section("观影记录同步与登录态", """
                            观影记录同步提供总开关、本机 API 修改开关、远端同步源和 Webhook 上报；协议见 WebHome 开发文档。
                            登录态学习需要手动开启，学习 Cookie、Token、接口 Jar 网盘登录文件等路径；待确认项可在管理页查看/编辑。
                            """)),

            module("管理页面、远程托管与同步", "管理页面是浏览器管理入口；远程托管用于跨网段/跨网络绑定设备。",
                    section("管理页面", """
                            设置 → 增强功能 → 管理页面；启动后给出本机或局域网地址 http://…/m。
                            可管理文件、登录态、同步目录、站点注入、接口、壳代理、搜索和推送；运行期间有前台服务保活。
                            管理页面当前没有鉴权，只应在可信局域网内临时开启，用完立即停止服务。
                            """),
                    section("远程托管", """
                            通过自建 Cloudflare、Deno、Vercel、Go 或 Rust 中转服务绑定多台设备；Go/Rust 支持 WebSocket，不支持时回退 HTTP 轮询。
                            支持设备状态、远程搜索/推送、接口配置、主页设置、一键同步和最近日志。
                            """),
                    section("一键同步与 Git 云盘", """
                            一键同步可勾选配置、站源数据、登录态、WebHome 数据、搜索记录、观看历史、收藏和应用设置。
                            Git 云盘用于配置备份/上传；同步前确认目标设备、覆盖范围和网络，避免把测试配置覆盖到主设备。
                            """)),

            module("下载、缓存与备份", "下载管理、缓存清理和数据备份是三个不同入口，不要混用。",
                    section("下载管理", """
                            首页“更多 → 下载管理”进入任务列表；可查看状态、暂停/继续/重试/取消/删除，并修改存储路径。
                            下载入口也会出现在播放页或详情页；没有下载按钮时先检查线路是否支持下载。
                            """),
                    section("缓存", """
                            设置 → 缓存：单击清理全部缓存；开启缓存管理后长按打开分级清理面板。
                            缓存面板可管理播放缓存、预加载、各模块上限和自动清理；空间不足优先清缓存，不要删除配置和历史。
                            """),
                    section("备份与恢复", """
                            设置 → 备份导出应用数据；设置 → 恢复导入备份。首次使用需要授予文件权限。
                            备份包含配置、站源数据、历史、收藏、WebHome 数据等（以实际勾选/实现为准）；恢复后检查站源、登录态和同步目录。
                            """)),

            module("AI 服务", "AI 服务是设置页独立入口；AI 通用配置、个性推荐、AI 实时字幕都依赖这里的 Key。",
                    section("申请 AI API Key", """
                            App 不内置 AI Key，需要向所选服务商申请：OpenAI 登录 https://platform.openai.com/api-keys 创建 Secret Key（通常 sk- 开头）；Anthropic 在 https://console.anthropic.com/settings/keys 创建 Key；Gemini 在 https://aistudio.google.com/apikey 获取 Key。
                            兼容 OpenAI 的第三方服务（如 DeepSeek、Moonshot 等）到各自控制台创建 Key，并使用服务商给出的兼容端点；不要把网页登录地址当 API 地址。
                            Key 只保存在本机配置里，不要截图或公开；额度/计费由服务商账户负责，App 不会代付。
                            """),
                    section("填写连接参数", """
                             设置 → AI 服务 → AI 通用配置打开弹窗；先启用 AI 服务，再填协议类型、API 服务端点、API key 和模型。
                             协议可选 OpenAI Responses API、OpenAI Chat Completions、Anthropic Messages、Gemini Native generateContent；端点必须与协议匹配，默认 OpenAI Responses 是 https://api.openai.com/v1/responses。
                             模型可手填，也可点“获取模型”从端点拉取；有 UA 限制的供应商可填自定义 User-Agent；最后点“测试配置”，失败时按报错检查端点、Key、模型名和网络。
                             同一弹窗还有“AI 真实剧名识别”和“AI 提示词配置”；提示词可恢复默认，改错会影响识别/推荐结果。
                            """),
                    section("推荐与反馈", """
                             设置 → AI 服务 → 个性推荐、不感兴趣管理；个性推荐开启前会提示隐私影响，不需要时关闭即可。
                             AI 通用配置未启用或未填齐端点/Key/模型时，个性推荐和 AI 识别不会生效。
                            """),
                    section("AI 实时字幕", """
                             设置 → AI 服务 → 实时识别语言、AI字幕最大并发数、AI字幕分块数量。
                             分块越大越省请求，但单次延迟更高；并发过高可能被服务商限流，过低会变慢。
                             字幕设置里的自动匹配/语言/字幕源与 AI 实时字幕是两套配置。
                            """)),
            module("去广告与片头片尾", "去广告是设置页独立入口；规则管理和自动跳片头片尾都在这里。",
                    section("总开关与 AI 识别", """
                             设置 → 去广告 → 去广告总开关、AI 广告识别；两个开关同时满足时才会显示相应“有广告”能力。
                             """),
                    section("规则与统计", """
                             规则管理可维护广告域名/正则规则；统计页显示拦截次数，便于确认规则是否命中。
                             """),
                    section("片头片尾", """
                             自动跳过片头片尾和跳过类型在同一页；开启后仍可在播放页手动调整。
                             规则只影响匹配到的时间段，不会改变视频源本身。
                             """)),

            module("壁纸、外观与启动", "壁纸、外观与语言、默认启动和无痕模式是首页/设置页的独立入口。",
                    section("壁纸", """
                             设置 → 壁纸可填入图片 URL 或本地路径；旁边的按钮用于默认壁纸、刷新和壁纸历史。
                             壁纸加载失败先检查 URL/路径和网络，再检查图片格式。
                             """),
                    section("外观与语言", """
                             设置 → 外观与语言；可切换主题、语言和部分界面显示选项。
                             电视端和手机端共用配置，但部分布局会按设备形态分别适配。
                             """),
                    section("默认启动与无痕", """
                             设置 → 默认启动决定打开 App 后进入的页面；无痕模式开启后不记录播放历史。
                             排查历史/推荐异常时先检查默认启动和无痕模式，避免把“没有记录”误判为功能故障。
                             """)),

            module("网络与调试日志", "网络相关配置分布在点播配置和增强功能；调试日志是排查问题的统一入口。",
                    section("代理与 DoH", """
                             设置 → DoH 选择 DNS over HTTPS；增强功能 → Proxy 配置壳代理地址和匹配规则。
                             代理规则建议按站点/域名精确匹配，不要全局滥用；配置变更后重新加载。
                             """),
                    section("headers、hosts 与 rules", """
                             headers、hosts、rules、hlsRules 等字段写在点播配置 JSON 中，不是单独的设置页。
                             这些规则影响请求头和播放嗅探；修改后重新加载接口，不要只重启播放页。
                             """),
                    section("调试日志", """
                             设置 → 增强功能 → 调试日志；日志筛选、只看注释和时间戳会保留，支持清空和回到顶部。
                             崩溃页会给出可复制的局域网日志地址；反馈问题时保留完整日志，不要只截一行报错。
                             """),
                    section("GitHub 加速源", """
                             GitHub 加速源在“关于”界面的“加速源”按钮里，不在网络设置页。
                             加速源只影响 GitHub 下载/更新链路，不替代普通站点代理。
                             """)));

    private static Module module(String title, String intro, Section... sections) {
        return new Module(title, intro, List.of(sections));
    }

    private static Section section(String title, String content) {
        return new Section(title, content, BOTH);
    }

    private static Section mobileSection(String title, String content) {
        return new Section(title, content, MOBILE);
    }

    private static Section tvSection(String title, String content) {
        return new Section(title, content, LEANBACK);
    }

    private static List<Section> sectionsFor(Module module, boolean leanback) {
        List<Section> result = new ArrayList<>();
        for (Section section : module.sections()) {
            if (section.platform() == BOTH || section.platform() == (leanback ? LEANBACK : MOBILE)) result.add(section);
        }
        return result;
    }

    private UsageHelpDialog() {
    }

    public static void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        DialogUsageHelpBinding binding = DialogUsageHelpBinding.inflate(LayoutInflater.from(activity));
        List<Row> rows = new ArrayList<>();
        View firstHeader = null;
        boolean leanback = Util.isLeanback();
        for (Module module : MODULES) {
            List<Section> sections = sectionsFor(module, leanback);
            if (sections.isEmpty()) continue;
            ItemUsageHelpModuleBinding item = ItemUsageHelpModuleBinding.inflate(LayoutInflater.from(activity), binding.moduleList, false);
            Row row = new Row(item, module, new ArrayList<>());
            item.header.setText(COLLAPSED + module.title());
            item.intro.setText(module.intro());
            item.bodyGroup.setVisibility(View.GONE);
            item.header.setOnClickListener(v -> toggleModule(rows, binding, row));
            item.header.setId(View.generateViewId());
            binding.moduleList.addView(item.getRoot());
            rows.add(row);
            if (firstHeader == null) firstHeader = item.header;
            for (Section section : sections) {
                ItemUsageHelpSectionBinding sectionItem = ItemUsageHelpSectionBinding.inflate(LayoutInflater.from(activity), item.sectionList, false);
                SectionRow sectionRow = new SectionRow(sectionItem, section);
                sectionItem.header.setText(COLLAPSED + section.title());
                sectionItem.body.setText(section.content());
                sectionItem.body.setVisibility(View.GONE);
                sectionItem.header.setId(View.generateViewId());
                sectionItem.header.setOnClickListener(v -> toggleSection(row.sections(), sectionRow));
                item.sectionList.addView(sectionItem.getRoot());
                row.sections().add(sectionRow);
            }
        }
        wireFocus(rows, binding);

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

    private static void toggleModule(List<Row> rows, DialogUsageHelpBinding binding, Row current) {
        boolean expanded = current.binding().bodyGroup.getVisibility() == View.VISIBLE;
        for (Row row : rows) {
            if (row != current) collapseModule(row);
        }
        if (expanded) collapseModule(current);
        else expandModule(current);
        refreshModuleFocus(rows, binding);
    }

    private static void expandModule(Row row) {
        row.binding().bodyGroup.setVisibility(View.VISIBLE);
        row.binding().header.setText(EXPANDED + row.module().title());
    }

    private static void collapseModule(Row row) {
        row.binding().bodyGroup.setVisibility(View.GONE);
        row.binding().header.setText(COLLAPSED + row.module().title());
        for (SectionRow section : row.sections()) collapseSection(section);
    }

    private static void toggleSection(List<SectionRow> sections, SectionRow current) {
        boolean expanded = current.binding().body.getVisibility() == View.VISIBLE;
        for (SectionRow section : sections) {
            if (section != current) collapseSection(section);
        }
        if (expanded) collapseSection(current);
        else expandSection(current);
    }

    private static void expandSection(SectionRow row) {
        row.binding().body.setVisibility(View.VISIBLE);
        row.binding().header.setText(EXPANDED + row.section().title());
    }

    private static void collapseSection(SectionRow row) {
        row.binding().body.setVisibility(View.GONE);
        row.binding().header.setText(COLLAPSED + row.section().title());
    }

    private static void wireFocus(List<Row> rows, DialogUsageHelpBinding binding) {
        if (rows.isEmpty()) return;
        binding.close.setNextFocusDownId(binding.searchInput.getId());
        binding.searchInput.setNextFocusUpId(binding.close.getId());
        binding.searchInput.setNextFocusDownId(rows.get(0).binding().header.getId());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.binding().header.setNextFocusUpId(i == 0 ? binding.searchInput.getId() : rows.get(i - 1).binding().header.getId());
            row.binding().header.setNextFocusDownId(i + 1 < rows.size() ? rows.get(i + 1).binding().header.getId() : binding.close.getId());
            for (int j = 0; j < row.sections().size(); j++) {
                SectionRow section = row.sections().get(j);
                section.binding().header.setNextFocusUpId(j == 0 ? row.binding().header.getId() : row.sections().get(j - 1).binding().header.getId());
                section.binding().header.setNextFocusDownId(j + 1 < row.sections().size()
                        ? row.sections().get(j + 1).binding().header.getId()
                        : (i + 1 < rows.size() ? rows.get(i + 1).binding().header.getId() : binding.close.getId()));
            }
        }
        refreshModuleFocus(rows, binding);
    }

    private static void refreshModuleFocus(List<Row> rows, DialogUsageHelpBinding binding) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean expanded = row.binding().bodyGroup.getVisibility() == View.VISIBLE;
            int down = expanded && !row.sections().isEmpty()
                    ? row.sections().get(0).binding().header.getId()
                    : (i + 1 < rows.size() ? rows.get(i + 1).binding().header.getId() : binding.close.getId());
            row.binding().header.setNextFocusDownId(down);
            for (int j = 0; j < row.sections().size(); j++) {
                SectionRow section = row.sections().get(j);
                section.binding().header.setNextFocusUpId(j == 0 ? row.binding().header.getId() : row.sections().get(j - 1).binding().header.getId());
                section.binding().header.setNextFocusDownId(j + 1 < row.sections().size()
                        ? row.sections().get(j + 1).binding().header.getId()
                        : (i + 1 < rows.size() ? rows.get(i + 1).binding().header.getId() : binding.close.getId()));
            }
        }
    }

    private static void filter(List<Row> rows, DialogUsageHelpBinding binding, String raw) {
        String query = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int matchCount = 0;
        View firstMatch = null;
        for (Row row : rows) {
            boolean moduleTextMatch = query.isEmpty()
                    || matches(row.module().title(), query)
                    || matches(row.module().intro(), query);
            boolean anySectionMatch = false;
            for (SectionRow section : row.sections()) {
                boolean visible = query.isEmpty() || matches(section.section().title(), query) || matches(section.section().content(), query);
                section.binding().getRoot().setVisibility(visible ? View.VISIBLE : View.GONE);
                if (visible && !query.isEmpty()) anySectionMatch |= matches(section.section().title() + "\n" + section.section().content(), query);
            }
            boolean showModule = query.isEmpty() || moduleTextMatch || anySectionMatch;
            row.binding().getRoot().setVisibility(showModule ? View.VISIBLE : View.GONE);
            if (!showModule) continue;
            matchCount++;
            if (firstMatch == null) firstMatch = row.binding().header;
            if (query.isEmpty()) {
                collapseModule(row);
            } else {
                expandModule(row);
                for (SectionRow section : row.sections()) {
                    if (section.binding().getRoot().getVisibility() != View.VISIBLE) continue;
                    if (matches(section.section().title() + "\n" + section.section().content(), query)) expandSection(section);
                    else collapseSection(section);
                }
            }
        }
        binding.empty.setVisibility(matchCount == 0 ? View.VISIBLE : View.GONE);
        binding.moduleList.setVisibility(matchCount == 0 ? View.GONE : View.VISIBLE);
        if (query.isEmpty()) {
            binding.searchInput.setNextFocusDownId(firstMatch == null ? binding.close.getId() : firstMatch.getId());
            binding.helpScroll.scrollTo(0, 0);
            return;
        }
        View target = firstMatch;
        if (target == null) return;
        binding.searchInput.setNextFocusDownId(target.getId());
        binding.helpScroll.post(() -> {
            if (Util.isLeanback()) target.requestFocus();
            else binding.helpScroll.smoothScrollTo(0, target.getTop());
        });
    }

    private static boolean matches(String text, String query) {
        String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String term : query.split("\\s+")) {
            if (!term.isEmpty() && !haystack.contains(term)) return false;
        }
        return true;
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

    private record Module(String title, String intro, List<Section> sections) {
    }

    private record Section(String title, String content, int platform) {
    }

    private record Row(ItemUsageHelpModuleBinding binding, Module module, List<SectionRow> sections) {
    }

    private record SectionRow(ItemUsageHelpSectionBinding binding, Section section) {
    }
}
