/* iDuanJu idj1 → webhtv 站源适配器（在 idj-prelude.js 与插件源码之后执行）
 * 插件源码里的 export default 已被替换为 globalThis.__IDJ__ = {...}，这里把 idj1 的
 * home/category/search/detail/play 映射成 CatVod JS 站点协议需要的 JSON 字符串。
 * 只做视频点播（video / shortdrama）。漫画、直播、弹幕、代理变换不在本适配器范围内。
 */
(function () {
  'use strict';
  var plugin = globalThis.__IDJ__;
  if (!plugin || typeof plugin !== 'object') throw new Error('idj 插件没有导出默认对象');

  function need(name) {
    if (typeof plugin[name] !== 'function') {
      var e = new Error('插件未实现 ' + name + '()');
      e.code = 'E_UNSUPPORTED';
      throw e;
    }
  }

  function text(v, max) {
    if (v === null || v === undefined) return '';
    if (typeof v === 'object') return '';
    return String(v).trim().slice(0, max || 2000);
  }

  function itemId(o) { return text(o && o.id, 2000); }

  function item(o) {
    return {
      vod_id: itemId(o),
      vod_name: text(o && o.title, 200),
      vod_pic: text(o && o.cover, 2000),
      vod_remarks: text(o && o.remarks, 40),
      vod_content: text(o && o.desc, 4000),
      vod_actor: Array.isArray(o && o.actors) ? o.actors.map(function (x) { return text(x, 60); }).filter(Boolean).join(' ') : '',
      type_name: text(o && o.group, 40)
    };
  }

  function items(res) {
    var list = res && Array.isArray(res.items) ? res.items : [];
    return list.map(item).filter(function (o) { return o.vod_id && o.vod_name; });
  }

  function pageBody(res, p) {
    var list = items(res);
    var more = !!(res && res.hasMore) && list.length > 0;
    return JSON.stringify({
      page: p,
      pagecount: more ? p + 1 : p,
      limit: list.length,
      total: more ? 0 : (p - 1) * list.length + list.length,
      list: list
    });
  }

  function parsePage(p) {
    var n = Number(p);
    return isFinite(n) && n > 0 ? Math.floor(n) : 1;
  }

  function token(itemIdRaw, line, epId) {
    var json = JSON.stringify([itemIdRaw, line, epId]);
    try { return 'b64:' + host.codec.base64Encode(json); } catch (e) { return encodeURIComponent(json); }
  }

  function parseToken(value) {
    var raw = String(value || '');
    try {
      var json = raw.indexOf('b64:') === 0 ? host.codec.base64Decode(raw.slice(4)) : decodeURIComponent(raw);
      var arr = JSON.parse(json);
      if (Array.isArray(arr) && arr.length >= 3) return { id: String(arr[0]), line: String(arr[1]), ep: String(arr[2]) };
    } catch (e) {}
    // 兼容手写的 `name$id` 形式：只有一集时仍可播
    return null;
  }

  var spider = {
    init: async function (ext) {
      if (typeof plugin.init === 'function') await plugin.init({});
      return '';
    },
    home: async function (filter) {
      need('home');
      var res = await plugin.home({});
      var cats = res && Array.isArray(res.categories) ? res.categories : [];
      var classes = cats.map(function (c) {
        return { type_id: text(c && c.id, 200), type_name: text(c && c.name, 40) };
      }).filter(function (c) { return c.type_id && c.type_name; });
      var body = { class: classes, filters: {}, list: items(res) };
      globalThis.__IDJ_HOME__ = body.list;
      return JSON.stringify(body);
    },
    homeVod: async function () {
      return JSON.stringify({ list: globalThis.__IDJ_HOME__ || [] });
    },
    category: async function (tid, pg, filter, extend) {
      need('category');
      var p = parsePage(pg);
      var res = await plugin.category({ id: text(tid, 200), page: p, filters: {} });
      return pageBody(res, p);
    },
    search: async function (key, quick, pg) {
      need('search');
      var p = parsePage(pg);
      var res = await plugin.search({ keyword: text(key, 200), page: p });
      return pageBody(res, p);
    },
    detail: async function (id) {
      need('detail');
      var res = await plugin.detail({ id: text(id, 2000) });
      if (!res || !text(res.title)) throw hostError('E_PARSE', '插件未返回详情');
      var lines = Array.isArray(res.lines) ? res.lines : [];
      var from = [];
      var url = [];
      lines.forEach(function (line) {
        var eps = Array.isArray(line && line.episodes) ? line.episodes : [];
        var name = text(line && line.name, 40) || '默认';
        from.push(name);
        url.push(eps.map(function (ep, i) {
          var ename = (text(ep && ep.name, 80) || ('第' + (i + 1) + '集')).replace(/[#$]/g, ' ');
          return ename + '$' + token(text(id, 2000), name, text(ep && ep.id, 2000));
        }).join('#'));
      });
      return JSON.stringify({
        list: [{
          vod_id: text(id, 2000),
          vod_name: text(res.title, 200),
          vod_pic: text(res.cover, 2000),
          vod_content: text(res.desc, 4000),
          vod_director: text(res.director, 80),
          vod_actor: Array.isArray(res.actors) ? res.actors.map(function (x) { return text(x, 60); }).filter(Boolean).join(' ') : '',
          vod_year: text(res.year, 10),
          vod_area: text(res.area, 40),
          vod_remarks: text(res.remarks, 40),
          vod_play_from: from.join('$$$'),
          vod_play_url: url.join('$$$')
        }]
      });
    },
    play: async function (flag, id, vipFlags) {
      need('play');
      var tk = parseToken(id);
      if (!tk) throw hostError('E_PARSE', '无效的剧集 id');
      var res = await plugin.play({ line: tk.line, episodeId: tk.ep, id: tk.id });
      var streams = res && Array.isArray(res.streams) ? res.streams : [];
      var usable = streams.filter(function (s) { return s && /^https?:\/\//i.test(text(s.url, 2000)); });
      if (!usable.length) throw hostError('E_UNSUPPORTED', '插件没有返回可播放地址');
      var first = usable[0];
      var headers = {};
      if (first.headers && typeof first.headers === 'object') {
        Object.keys(first.headers).forEach(function (k) {
          var v = first.headers[k];
          if (typeof v === 'string' && !/[\r\n]/.test(v)) headers[k] = v;
        });
      }
      var body = {
        parse: 0,
        url: text(first.url, 2000),
        header: JSON.stringify(headers)
      };
      if (Array.isArray(res.subtitles) && res.subtitles.length) {
        body.subtitles = res.subtitles.map(function (s) {
          return { url: text(s && s.url, 2000), name: text(s && (s.name || s.lang), 40), lang: text(s && s.lang, 20) };
        }).filter(function (s) { return s.url; });
      }
      return JSON.stringify(body);
    },
    destroy: function () { return ''; }
  };

  function hostError(code, message) {
    var e = new Error(message);
    e.code = code;
    return e;
  }

  globalThis.__JS_SPIDER__ = spider;
})();
