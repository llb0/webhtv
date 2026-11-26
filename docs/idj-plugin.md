# iDuanJu（idj1）视频插件作为站源

webhtv 现在可以直接吃 iDuanJu 的 idj1 单文件插件包（`.idjplugin`），把一个**视频点播类**插件当成普通 JS 站点源使用。实现只覆盖「让插件成为站源」这一条链路，不移植 iDuanJu 的插件商店、订阅、漫画、直播、弹幕和播放代理。

## 怎么装

1. 把 `xx.idjplugin` 放进应用的 `tvbox/idj/` 目录（设置页的远程文件管理、SMB 分享或系统文件管理都可以）。
2. 确认「设置 → 增强功能 → 来源屏蔽」里「iDuanJu 插件站点（勾选＝允许）」是勾上的（独立开关，默认开启，和 sites-js 无关）；成人插件另有「iDuanJu 成人插件」开关，默认也开启。
3. 重新加载点播接口（首页「更多 → 刷新」，或重进点播）。
4. 站点列表里会出现 `插件名 | IDJ`，分类、搜索、详情、播放都按插件自己的实现走。

同名插件包版本号变更会重新安装；安装失败只跳过该插件，不影响其它站点。

## 目录结构

```
tvbox/idj/xx.idjplugin           插件包（单 JSON：{manifest, files}），安装后保留原包
tvbox/idj/<id>/source.js         入口源码（manifest.entry 的内容）
tvbox/idj/<id>/manifest.json     清单副本（宿主读 permissions / settings）
tvbox/idj/<id>/settings.json     settings 默认值，可手改（宿主每次创建时读它覆盖默认值）
tvbox/idj/<id>/storage.json      host.storage 的私有 KV（上限 1MB）
```

## 映射规则

| idj1 | webhtv 站源 |
| --- | --- |
| `home()` | 首页分类（`categories`）+ 首页列表（`items`） |
| `category({id,page,filters})` | 分类翻页，`hasMore` 决定还能不能继续翻 |
| `search({keyword,page})` | 站内搜索 |
| `detail({id})` | 详情，`lines` 映射成线路，`episodes` 映射成选集 |
| `play({line,episodeId,id})` | 播放，取 `streams[0]` 的直链和请求头 |

选集里存的 id 是 `b64:` + base64 的 `[条目id, 线路名, 剧集id]`，播放时解回来再调用插件，所以详情里的线路名和 id 不需要 webhtv 额外保存。

## 宿主能力支持情况

已实现：`host.http.*`（域名白名单 + 禁 IP 直连 + 禁非 80/443 端口 + 每次跳转都校验 + 禁内网解析）、`host.html.parse/select/selectFirst/text/html/outerHtml/attr`、`host.crypto.md5/sha1/sha256/sha512/hmac/aes/randomHex`、`host.codec.base64/hex/gunzip/convert`、`host.m3u8.parse`、`host.storage.*`、`host.settings`、`host.log`、`host.error`。

未实现（`host.has` 返回 false，插件应自行降级）：`host.crypto.rsa`、`host.codec.unpackPacker`、`host.cloudflare`、`host.login`、图片 `transform`。

## 已知限制

- 只支持 `type` 为 `video` / `shortdrama`、且 `capabilities` 同时包含 `detail` 与 `play` 的插件；`comic`、`live` 会直接跳过。
- `adult: true` 的插件由「iDuanJu 成人插件」单独控制（默认开启），站点名带 🔞；关掉任一开关后对应站点立即从列表消失（已安装目录保留，再打开不用重装）。
- `streams[].proxy`（`stripFakeHeader` / `hlsKey` / `segmentDecrypt` / `drm` / `rewriteHosts`）不会被执行，只按原样直链播放；需要这些处理的流可能播不了。
- 弹幕（`danmaku()`）、播放进度跳过（`skip`）、图片解密（`transform`）不接入。
- 插件源码按「单文件 + `export default`」解析；带 `import` 的多文件插件、`eval` / `new Function` 一律不支持（quickjs 侧也不开放）。
- `settings` 只能通过 `tvbox/idj/<id>/settings.json` 手改，暂不做应用内设置界面。

## 相关代码

- `quickjs/src/main/java/com/fongmi/quickjs/crawler/Spider.java`：`clan://idj/<id>/source.js` 分支
- `quickjs/src/main/java/com/fongmi/quickjs/method/IdjHost.java`：`__host` 宿主桥
- `quickjs/src/main/assets/js/lib/idj-prelude.js`：idj1 的 `host` 前置脚本（移植自 iDuanJu，GPL-3.0）
- `quickjs/src/main/assets/js/lib/idj-spider.js`：idj1 → CatVod 站点协议适配器
- `app/src/main/java/com/fongmi/android/tv/plugin/idj/IdjPluginStore.java`：插件包安装、目录约定、站点生成
- `app/src/main/java/com/fongmi/android/tv/api/config/VodConfig.java`：`loadFileSites()` 里按独立开关 `idj_sites_enabled` 加载（不再跟随 sites-js 的来源屏蔽）
