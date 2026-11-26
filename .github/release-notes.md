### 新增
- iDuanJu（idj1）视频插件可作为站源：把 `xx.idjplugin` 放进 `tvbox/idj/` 后重新加载点播，站点列表出现「插件名 | IDJ」，分类、搜索、详情、播放都走插件自己的实现；插件包在加载配置时自动安装，同名插件版本变更会重装。
- 插件宿主按清单 `permissions.http` 做域名白名单，禁 IP 直连、禁非 80/443 端口，跳转逐跳校验并拦截解析到内网的域名。
- 成人插件改为显式开关：默认仍不安装 `adult: true` 的包，在「增强功能 → 来源屏蔽」里勾选「iDuanJu 成人插件（勾选＝允许）」后才安装，站点名带 🔞；关掉开关后站点立即消失，已安装目录保留。
- 宿主能力按 idj1 规范提供：HTTP 请求、CSS 选择器解析、md5/sha1/sha256/sha512/HMAC/AES、base64/hex/gunzip、m3u8 解析、插件私有 KV 和设置项；`rsa`、`unpackPacker`、`cloudflare`、`login` 在 `host.has` 中返回 false，由插件自行降级。
