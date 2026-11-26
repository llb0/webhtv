
## P1 实现（已落在 feature-lan-share）

- 服务端：`app/src/main/java/com/fongmi/android/tv/share/`（main 源集，双端共用）
  - `SmbShareServer`：只读 SMB2 服务端（TCP + NetBIOS 会话头），只实现客户端真正用到的命令：
    NEGOTIATE（只谈 2.0.2）、SESSION_SETUP（SPNEGO/NTLM，匿名与 guest 都不校验口令）、
    TREE_CONNECT、CREATE、READ、QUERY_DIRECTORY（FileIdBothDirectoryInformation）、
    QUERY_INFO（Basic/Standard/All）、CLOSE、ECHO、FLUSH、LOGOFF、TREE_DISCONNECT；
    写/改信息一律 NOT_SUPPORTED。默认端口 4450（非 root 绑不了 445），单共享 `webhtv`。
  - `SmbShareController`：开关、根目录（默认外部存储根）、端口、局域网地址。
  - `SmbShareService`：前台服务 + WakeLock，防止息屏/切后台被系统收掉。
- 客户端侧改动：`SmbClient` 支持 `host:port`（4 处 `connect` 统一走新 helper）；
  `SmbNetworkScanner` 445 不通时再探 4450，命中则以 `host:4450` 入列；
  浏览页 `SmbBrowserActivity` 在主机列表末尾加「分享本机/停止分享」一行，
  并对带端口的主机跳过 srvsvc 共享枚举、直连 `webhtv` 共享。
- 本地已用真实 smbj 客户端（匿名 + guest）跑通：列根目录/子目录、fileExists/folderExists、
  顺序读、随机偏移读；协议细节（CREATE 89 字节、目录项 Reserved2、FileStandardInformation 保留位、
  TREE_CONNECT 偏移基准、NTSTATUS 0xC0000016）均已按实测修正。
- 未做（留给后续）：写支持、目录选择器、`_smb._tcp` mDNS 名称展示、445 端口（需 root）。
