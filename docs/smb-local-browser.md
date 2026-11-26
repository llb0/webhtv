# 本地 SMB 浏览与播放

本文说明 WebHTV 的本地 SMB 能力：入口在哪里、如何连接 NAS/Windows 共享、支持哪些文件、播放器如何处理，以及常见故障怎么排查。实现以当前仓库的 `SmbBrowserActivity`、`SmbClient`、`SmbNetworkScanner`、`SmbPlaybackProxy`、`SmbCredentialStore` 为准。

## 入口与开关

本地 SMB 入口默认关闭，避免不需要局域网播放的用户看到多余入口。

- 打开路径：**设置 → 个性设置 → 本地SMB播放入口**。
- 手机端：开启后底栏出现 **SMB** 项，位置在“设置”左侧；关闭后立即隐藏。
- 电视端：开启后首页“更多/首页菜单”中出现 **本地SMB**，位置在 **选站** 和 **多仓** 之间；关闭后菜单不再插入该项。
- 开关值保存在 `Prefers` 的 `smb_entry_enabled`，手机和电视共用同一份配置；两端只是入口位置不同，扫描、登录、收藏和播放能力相同。

电视端没有独立的 SMB 首页按钮，入口只放在首页菜单里；手机端没有首页菜单，入口只放在底栏。这样不会改变原有的首页功能区和底部导航结构。

## 分享本机（只读 SMB 服务端）

除了“浏览别人”，WebHTV 自己也能当共享方：在 **SMB 主机列表的最后一行** 点 **分享本机**，就把本机目录按只读 SMB2 共享出去，另一台手机/电视像访问网上邻居一样直接浏览、播放，不需要任何中转服务。

设置弹窗（纯文本行，运行中也能改，改完立即重启服务端生效）：

- **开始分享 / 停止分享**：开启时启动前台服务并持有 WakeLock，退出页面后仍在分享；关闭即停止监听。
- **分享目录**：应用内逐级浏览选择，选中的目录就是共享根（不引入 SAF，服务端要按 `File` 随机读）。默认是外部存储根目录。
- **账号密码**：**账号和密码都填** 才开启校验；**两者都留空** 表示匿名开放全部内容；一旦设置了账号密码，匿名访问直接拒绝。账号不区分大小写，密码走 NTLMv2（Type3 + HMAC-MD5/MD4 校验），失败返回 `STATUS_LOGON_FAILURE`。
- **本机地址**：显示当前可用于访问的 `IP:4450`，点击可复制/查看。

固定约定：

- **端口 4450**：非 root 应用不能绑 445，因此默认 4450；对端要在主机输入框里写 `192.168.0.5:4450` 这种带端口的地址。
- **共享名固定 `webhtv`**：不再允许修改。共享名只是 `TREE_CONNECT` 里的名字，改名只会让对端对不上、需要补填，没有收益（它不是主机名）。
- **只读**：服务端只实现 SMB2 的协商/会话/连接/打开/读取/列目录/查询信息/关闭等读路径，写、删除、重命名一律返回 `NOT_SUPPORTED`。

对端怎么连：

1. 两端在同一局域网；对端打开 SMB 页，扫描到 `host:4450` 或手动输入主机地址。
2. 带端口的主机会被识别为“本项目的自家分享”，直接进共享目录，不再走共享枚举、也不再询问共享名。
3. 设置了账号密码时，对端会弹 **访问分享** 对话框（账号在上、密码在下），填对后自动进入，并可保存该主机凭据；下次从历史或收藏进入会先带着凭据登录。

同一目录下存在多个视频时，点播放会按文件名排序组成剧集：点中的那一集排第一（详情页当前集、起播位置都跟随它），最多取 24 集以匹配直链池上限。剧集的 vod id 用稳定的描述符 `smb://host/share/dir?n=当前文件` 而不是临时直链，因此从历史点开时会重新列目录、重建整张选集表，不会只剩“目录名 + 点的那一集”；片名用点开的那一集，线路名用目录名。

## 使用步骤

1. 让手机/电视和 SMB 服务器处于同一局域网。服务器可以是 NAS、路由器挂载的硬盘、Windows 共享目录或兼容 SMB2/SMB3 的设备。
2. 从上面的入口打开“局域网 SMB 播放”页面。页面会先扫描局域网，或者使用已经收藏/保存的主机。
3. 点选主机后登录：
   - 用户名留空：先尝试匿名会话，失败后回退 Guest；适合开放共享或来宾共享。
   - 有账号密码：填写用户名、密码；域可选，Windows 域共享或部分 NAS 才需要。
   - 需要下次自动填写时，在登录界面勾选 **保存登录信息**；保存后再次连接该主机会先用保存的账号尝试。登录界面同时提供 **清除已保存的登录信息**。
   - 如果取消勾选“保存登录信息”并成功登录，会同时清除该主机此前保存的旧凭据，让“是否保存”真正随本次勾选生效。
   - 共享目录没有自动列出时，可以用“手动输入共享名”直接打开，例如 `video`、`media`。
4. 进入共享目录后浏览文件夹。视频/音乐点按播放，图片点按预览，APK 点按交给系统安装器。
5. 返回键逐级退回：文件 → 共享目录 → 主机列表 → 关闭页面。电视端用方向键移动焦点，OK 确认，菜单键可打开当前共享/文件的更多操作。

## 支持的文件与操作

目录列表只显示子目录和可识别的媒体/安装包，不会把普通文档混进播放列表：

- 视频：`mp4`、`mkv`、`avi`、`mov`、`m4v`、`ts`、`m2ts`、`wmv`、`flv`、`webm`、`mpg/mpeg`、`vob`、`3gp/3g2`、`mts`、`mxf`、`ogv` 等。
- 音频：`mp3`、`flac`、`m4a/m4b`、`aac`、`wav`、`ogg/opus`、`wma`、`ape`、`aiff/aif`、`amr`、`mid/midi`、`mka`、`ac3`、`dts` 等。
- 图片：`jpg/jpeg/jpe`、`png`、`gif`、`bmp`、`webp`、`heic/heif`、`avif`、`tif/tiff`。
- 安装包：`apk`。

页面还提供：

- **收藏夹**：保存常用主机/共享/目录；主机可写备注，收藏项可直接打开。
- **显示隐藏**：恢复被隐藏的主机或目录；电视端短按、手机端长按执行恢复。设置了隐藏密码时，先输入密码，校验通过才显示隐藏项目。
- 电视端菜单键 / 手机端长按会打开统一菜单：当前条目的操作之后固定追加 **收藏夹 / 显示隐藏 / 设置或取消隐藏密码 / 主机 / 关闭**。主机页没有选中条目时也能调出这五项；电视端菜单列表焦点在首/末项支持上下环跳。
- 隐藏密码保存在 `SharedPreferences("smb_hosts")` 的 `hidden_password`；设置非空密码后，菜单项变为“取消隐藏密码”，清除后恢复“设置隐藏密码”。
- 主机长按或菜单键：保存并备注、修改备注、清除登录信息、隐藏此地址；共享/文件长按或菜单键：收藏、隐藏、手动输入共享名等操作。
- “主机”表示回到主机列表并重新扫描一次；“关闭”等同页面关闭按钮。
- 图片预览：手机竖屏可旋转/全屏查看，电视端可直接全屏显示。

## 播放路径与内核

SMB 文件不会直接以 `smb://` 地址交给播放器。点击视频/音乐时：

1. `SmbPlaybackProxy.createUrl(...)` 启动一个 NanoHTTPD 代理，为当前文件生成短期 token；URL 保留真实文件名后缀并追加 `smb=1` 直链标记，让嗅探器/播放器把代理地址识别为视频直链，而不是网页嗅探地址。不会再给 AVI/WMV 补 `.mp4`，避免 Exo 按 MP4 解析旧格式。
2. 代理按 Range 请求读取 SMB 文件，播放器拿到的是普通 HTTP 地址，因此可以正常 seek、拖动和断点续播。
3. `VideoActivity.startSmb(...)` 写入 `smb_source=true` 启动播放页。
4. 播放页 `initView` 在绑定播放服务前把本次会话的内核临时覆盖为 **EXO**，避免部分设备用 MPV 播放本地代理地址时出现兼容问题；如果文件是 AVI/WMV/RMVB/VOB 等 Exo 不支持解复用的老容器，首次播放会自动改走带完整 FFmpeg 的 IJK。随包 IJK 由 `module-default.sh` 构建，已编入 AVI/ASF/RM 解复用器和 MPEG-4/DivX/Xvid、MS-MPEG4、WMV1/2/3、VC-1、RV30/40、Cook、AC-3、DTS 等老视频解码器。退出播放页且不是配置变更重建时清除覆盖，**不会修改设置里的默认内核**。
5. 代理监听 `0.0.0.0`，本机仍通过 `127.0.0.1` 播放；投屏/推送时远端把回环地址替换成局域网地址后也能访问同一 token，避免只绑回环地址导致“网络读取失败”。
6. 外调播放器使用 `SmbPlaybackProxy.externalUrl(...)` 返回的同 token 真实后缀地址，不再附加 `.mp4`；地址改用局域网 IP（代理监听 `0.0.0.0`），避免部分系统播放器拒绝 `127.0.0.1`。外调列表由应用内“选择打开方式”提供，不依赖系统 chooser。
7. token 和本次登录的账号密码只存在进程内存；代理最多保留 24 个 token，不写磁盘。若用户勾选保存登录信息，账号密码按主机写入 `smb_credentials`，使用 Android Keystore 的 AES/GCM 加密。

## 权限与网络

- 需要 `INTERNET`、`ACCESS_NETWORK_STATE` 和 `ACCESS_LOCAL_NETWORK`（Android 17/API 37 起本地网络访问需要运行时授权）。
- 首次在 Android 17 设备扫描时会弹出本地网络权限；拒绝后页面会提示授权，不会继续扫描。
- SMB 使用 TCP **445**；本机做分享方时监听 **4450**（非 root 只能绑高位端口）。路由器/防火墙需要允许两端互访对应端口；只开放 Web 管理页面不够。
- 4450 被别的应用占用时，分享会启动失败并提示端口被占用；连续改设置会让旧连接处于 TIME_WAIT，服务端内置重试绑定（8 次 × 50ms）。
- 扫描只覆盖物理网卡的私有 IPv4 网段（10/8、172.16/12、192.168/16），最多按 /24 收窄；VPN、蜂窝和虚拟网卡会被跳过。跨网段请手动输入/收藏主机，或先把设备放到同一网段。
- 扫描是 445 端口连通性探测，不发送账号密码；登录和共享枚举在打开主机后才进行。

## 数据保存

使用独立的 `SharedPreferences("smb_hosts")`：

- 保存主机地址和备注、收藏项、隐藏主机、隐藏目录。
- **登录信息默认不保存**；用户名/密码/域只在当前浏览页面内存中使用。用户勾选“保存登录信息”后，账号密码按主机保存到 `smb_credentials`，值用 Android Keystore 的 AES/GCM 加密；登录界面的“清除已保存的登录信息”只删除当前主机。
- 分享设置（开关状态、共享根目录、端口、账号密码）存在 `SharedPreferences("smb_share")`；共享名固定为 `webhtv`，不再存储可配值。
- 播放代理的 token 不落盘；清除应用数据会同时清除主机收藏/隐藏记录和已保存的登录信息。
- 播放历史缺失海报时的兜底截图保存在应用私有目录 `webhome_snapshot`；不会只按数量自动清理，删除对应历史记录或清空历史/恢复备份时会同步删除对应的截图文件。

## 常见问题

**扫描不到设备**

- 确认设备在同一局域网，且服务器 445 端口可访问；Windows 需开启“文件和打印机共享”，NAS 需开启 SMB 服务。
- 某些路由器开启了 AP 隔离/访客网络隔离，设备之间无法互访。
- Android 17 设备先允许本地网络权限；仍不行可手动输入主机 IP 并在主机列表长按收藏。

**能看到主机，登录失败**

- 检查用户名、密码、域；Windows 共享可尝试 `域\用户名` 或留空域。
- 匿名失败会自动回退 Guest；如果服务器关闭 Guest，必须填写有权限的账号。
- 保存过登录信息但自动登录失败时，打开登录界面核对或“清除已保存的登录信息”后重新输入；密码不会以明文保存。
- 账号能登录但看不到共享，通常是共享权限或 SMB 版本不匹配；在 NAS/Windows 侧检查该账号的共享权限。

**共享列表为空**

- 有些设备不返回共享枚举，使用“手动输入共享名”直接填共享名。
- 只显示可访问的磁盘共享；`IPC$`、`ADMIN$`、`PRINT$` 和盘符管理共享会被过滤。

**视频打不开或卡顿**

- 先确认文件格式在支持列表内；SMB 页面只负责浏览，真正的解码仍由播放器决定。
- 高码率 4K/原盘建议有线网络或稳定 5GHz Wi-Fi；SMB 代理会按 Range 读取，网络抖动会直接影响播放。
- 播放页本次会话已临时使用 EXO；如果仍失败，退出后换其他内核重试，或在详情页换源。

**APK 无法安装**

- 系统会要求“允许安装未知来源应用”；按提示授权后再返回页面继续安装。
- 页面只把 APK 交给系统安装器，不静默安装。

## 相关代码

- `app/src/main/java/com/fongmi/android/tv/ui/activity/SmbBrowserActivity.java`：扫描、登录、目录浏览、收藏/隐藏、图片预览、APK 安装、播放入口，以及“分享本机”的设置弹窗、目录选择器、账号密码弹窗和对端登录框。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbClient.java`：SMB2/SMB3 连接、共享/目录枚举、远程文件读取。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbNetworkScanner.java`：局域网主机扫描。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbPlaybackProxy.java`：本机 HTTP Range 代理；监听 `0.0.0.0`、URL 保留真实文件后缀并带 `smb=1`，支持外调使用真实后缀地址。
- `app/src/main/java/com/fongmi/android/tv/player/PlaybackFrameSnapshot.java`：历史缺失海报的播放画面兜底截图；删除历史/清空历史时同步删除对应截图缓存。
- `app/src/main/java/com/fongmi/android/tv/share/SmbShareServer.java`：只读 SMB2 服务端（NEGOTIATE/TREE_CONNECT/CREATE/READ/QUERY_DIRECTORY；写操作返回 NOT_SUPPORTED），共享名固定 `webhtv`。
- `app/src/main/java/com/fongmi/android/tv/share/NtlmV2Verifier.java`：Type3 解析与 NTLMv2 校验（含自实现 MD4）。
- `app/src/main/java/com/fongmi/android/tv/share/SmbShareController.java`：分享开关、共享根/端口/账号密码持久化与服务端重启。
- `app/src/main/java/com/fongmi/android/tv/share/SmbShareService.java`：分享期间的前台服务与 WakeLock。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbFolder.java`：SMB 目录剧集的稳定描述符（`smb://host/share/dir?n=文件`）与按描述符重建选集表。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbCredentialStore.java`：可选的按主机登录信息存储，Android Keystore AES/GCM 加密；不勾选保存时不写入。
- `app/src/main/java/com/fongmi/android/tv/setting/Setting.java`：`smb_entry_enabled` 开关。
- 手机入口：`app/src/mobile/res/menu/menu_nav.xml`、`app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`、`SettingPersonalFragment.java`。
- 电视入口：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java` 的首页菜单、`SettingPersonalActivity.java`。
- 播放兼容：`PlayerSetting.setRuntimePlayerOverride()`、两端 `VideoActivity.startSmb()` / `smb_source`。
- 依赖：`app/build.gradle` 的 `smbj`、`dcerpc`，`app/proguard-rules.pro` 的 Rapid7 `java.rmi` 兼容规则。
