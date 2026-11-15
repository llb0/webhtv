# 本地 SMB 浏览与播放

本文说明 WebHTV 的本地 SMB 能力：入口在哪里、如何连接 NAS/Windows 共享、支持哪些文件、播放器如何处理，以及常见故障怎么排查。实现以当前仓库的 `SmbBrowserActivity`、`SmbClient`、`SmbNetworkScanner`、`SmbPlaybackProxy`、`SmbCredentialStore` 为准。

## 入口与开关

本地 SMB 入口默认关闭，避免不需要局域网播放的用户看到多余入口。

- 打开路径：**设置 → 个性设置 → 本地SMB播放入口**。
- 手机端：开启后底栏出现 **SMB** 项，位置在“设置”左侧；关闭后立即隐藏。
- 电视端：开启后首页“更多/首页菜单”中出现 **本地SMB**，位置在 **选站** 和 **多仓** 之间；关闭后菜单不再插入该项。
- 开关值保存在 `Prefers` 的 `smb_entry_enabled`，手机和电视共用同一份配置；两端只是入口位置不同，扫描、登录、收藏和播放能力相同。

电视端没有独立的 SMB 首页按钮，入口只放在首页菜单里；手机端没有首页菜单，入口只放在底栏。这样不会改变原有的首页功能区和底部导航结构。

## 使用步骤

1. 让手机/电视和 SMB 服务器处于同一局域网。服务器可以是 NAS、路由器挂载的硬盘、Windows 共享目录或兼容 SMB2/SMB3 的设备。
2. 从上面的入口打开“局域网 SMB 播放”页面。页面会先扫描局域网，或者使用已经收藏/保存的主机。
3. 点选主机后登录：
   - 用户名留空：先尝试匿名会话，失败后回退 Guest；适合开放共享或来宾共享。
   - 有账号密码：填写用户名、密码；域可选，Windows 域共享或部分 NAS 才需要。
   - 需要下次自动填写时，在登录界面勾选 **保存登录信息**；保存后再次连接该主机会先用保存的账号尝试。登录界面同时提供 **清除已保存的登录信息**。
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
- **显示隐藏**：恢复被隐藏的主机或目录；电视端短按、手机端长按执行恢复。
- 主机/共享/文件长按或菜单键：收藏、隐藏、手动输入共享名等操作。
- 图片预览：手机竖屏可旋转/全屏查看，电视端可直接全屏显示。

## 播放路径与内核

SMB 文件不会直接以 `smb://` 地址交给播放器。点击视频/音乐时：

1. `SmbPlaybackProxy.createUrl(...)` 在 `127.0.0.1` 启动一个只在本机可访问的 NanoHTTPD 代理，并为当前文件生成短期 token；URL 末尾带上真实文件名/媒体扩展名，让播放器把代理地址识别为视频直链，而不是网页嗅探地址。
2. 代理按 Range 请求读取 SMB 文件，播放器拿到的是普通 HTTP 地址，因此可以正常 seek、拖动和断点续播。
3. `VideoActivity.startSmb(...)` 写入 `smb_source=true` 启动播放页。
4. 播放页 `initView` 在绑定播放服务前把本次会话的内核临时覆盖为 **EXO**，避免部分设备用 MPV 播放本地代理地址时出现兼容问题；退出播放页且不是配置变更重建时清除覆盖，**不会修改设置里的默认内核**。
5. token 和本次登录的账号密码只存在进程内存；代理最多保留 24 个 token，不写磁盘。若用户勾选保存登录信息，账号密码按主机写入 `smb_credentials`，使用 Android Keystore 的 AES/GCM 加密。

## 权限与网络

- 需要 `INTERNET`、`ACCESS_NETWORK_STATE` 和 `ACCESS_LOCAL_NETWORK`（Android 17/API 37 起本地网络访问需要运行时授权）。
- 首次在 Android 17 设备扫描时会弹出本地网络权限；拒绝后页面会提示授权，不会继续扫描。
- SMB 使用 TCP **445**。路由器/防火墙需要允许手机或电视访问服务器的 445 端口；只开放 Web 管理页面不够。
- 扫描只覆盖物理网卡的私有 IPv4 网段（10/8、172.16/12、192.168/16），最多按 /24 收窄；VPN、蜂窝和虚拟网卡会被跳过。跨网段请手动输入/收藏主机，或先把设备放到同一网段。
- 扫描是 445 端口连通性探测，不发送账号密码；登录和共享枚举在打开主机后才进行。

## 数据保存

使用独立的 `SharedPreferences("smb_hosts")`：

- 保存主机地址和备注、收藏项、隐藏主机、隐藏目录。
- **登录信息默认不保存**；用户名/密码/域只在当前浏览页面内存中使用。用户勾选“保存登录信息”后，账号密码按主机保存到 `smb_credentials`，值用 Android Keystore 的 AES/GCM 加密；登录界面的“清除已保存的登录信息”只删除当前主机。
- 播放代理的 token 不落盘；清除应用数据会同时清除主机收藏/隐藏记录和已保存的登录信息。

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

- `app/src/main/java/com/fongmi/android/tv/ui/activity/SmbBrowserActivity.java`：扫描、登录、目录浏览、收藏/隐藏、图片预览、APK 安装和播放入口。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbClient.java`：SMB2/SMB3 连接、共享/目录枚举、远程文件读取。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbNetworkScanner.java`：局域网主机扫描。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbPlaybackProxy.java`：本机 HTTP Range 代理；URL 保留文件扩展名，避免被当成待嗅探网页。
- `app/src/main/java/com/fongmi/android/tv/smb/SmbCredentialStore.java`：可选的按主机登录信息存储，Android Keystore AES/GCM 加密；不勾选保存时不写入。
- `app/src/main/java/com/fongmi/android/tv/setting/Setting.java`：`smb_entry_enabled` 开关。
- 手机入口：`app/src/mobile/res/menu/menu_nav.xml`、`app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`、`SettingPersonalFragment.java`。
- 电视入口：`app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java` 的首页菜单、`SettingPersonalActivity.java`。
- 播放兼容：`PlayerSetting.setRuntimePlayerOverride()`、两端 `VideoActivity.startSmb()` / `smb_source`。
- 依赖：`app/build.gradle` 的 `smbj`、`dcerpc`，`app/proguard-rules.pro` 的 Rapid7 `java.rmi` 兼容规则。
