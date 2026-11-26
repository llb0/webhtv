/* iDuanJu idj1 —— 受信任的宿主前置脚本（移植自 iDuanJu STV/Plugins/PluginPrelude.swift，GPL-3.0）
 * 在插件源码之前执行：把原生 __host(name, argsJSON) 包装成 frozen 的 host 对象。
 * 与原版的差别：错误不走原生抛异常，而是 __host 返回 {"__idjError":{code,message}}，这里还原成 JS Error，
 * 以免 quickjs 原生异常穿透导致整个 JS 站点挂掉。
 */
(function (h) {
  'use strict';
  if (!h) throw new Error('__host 未注册：idj 插件必须由 clan://idj/ 站点加载');
  const fail = (e) => { const err = new Error(String(e && e.message != null ? e.message : e && e.code)); err.code = String(e && e.code || 'E_PARSE'); return err; };
  const call = (n, a) => {
    const r = h(n, JSON.stringify(a === undefined ? null : a));
    if (r === undefined) return undefined;
    const v = JSON.parse(r);
    if (v && typeof v === 'object' && v.__idjError) throw fail(v.__idjError);
    return v;
  };
  const deepFreeze = (o) => {
    for (const k of Object.getOwnPropertyNames(o)) { const v = o[k]; if (v && typeof v === 'object' && !Object.isFrozen(v)) deepFreeze(v); }
    return Object.freeze(o);
  };
  const node = (id) => Object.freeze({
    text: () => call('html.text', { id }),
    html: () => call('html.html', { id }),
    outerHtml: () => call('html.outer', { id }),
    attr: (k) => call('html.attr', { id, k: String(k) }),
    select: (q) => call('html.select', { id, q: String(q) }).map(node),
    selectFirst: (q) => { const r = call('html.select', { id, q: String(q), first: true }); return r.length ? node(r[0]) : null; }
  });
  const opt = (o, extra) => Object.assign({}, o || {}, extra);
  const host = {
    version: call('host.version'),
    has: (cap) => call('host.has', { cap: String(cap) }),
    http: {
      request: async (o) => call('http.request', o || {}),
      get: async (url, o) => call('http.request', opt(o, { url: String(url) })),
      post: async (url, body, o) => call('http.request', opt(o, { url: String(url), method: 'POST', body: body })),
      allowed: (url) => call('http.allowed', { url: String(url) })
    },
    html: { parse: (text, baseUrl) => node(call('html.parse', { text: text == null ? '' : String(text), base: baseUrl ? String(baseUrl) : '' })) },
    url: {
      resolve: (base, rel) => call('url.resolve', { base: String(base), rel: String(rel) }),
      encodeForm: (obj) => Object.keys(obj || {}).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(obj[k] == null ? '' : String(obj[k]))).join('&')
    },
    crypto: {
      md5: (s, o) => call('crypto.hash', opt(o, { alg: 'md5', data: String(s) })),
      sha1: (s, o) => call('crypto.hash', opt(o, { alg: 'sha1', data: String(s) })),
      sha256: (s, o) => call('crypto.hash', opt(o, { alg: 'sha256', data: String(s) })),
      sha512: (s, o) => call('crypto.hash', opt(o, { alg: 'sha512', data: String(s) })),
      hmac: (alg, key, data, o) => call('crypto.hmac', opt(o, { alg: String(alg), key: String(key), data: String(data) })),
      aes: (o) => call('crypto.aes', o || {}),
      randomHex: (n) => call('crypto.random', { n: n | 0 })
    },
    codec: {
      base64Encode: (s, o) => call('codec.convert', opt(o, { data: String(s), from: (o && o.from) || 'utf8', to: 'base64' })),
      base64Decode: (s, o) => call('codec.convert', opt(o, { data: String(s), from: 'base64', to: (o && o.to) || 'utf8' })),
      hexEncode: (s, o) => call('codec.convert', opt(o, { data: String(s), from: (o && o.from) || 'utf8', to: 'hex' })),
      convert: (s, from, to) => call('codec.convert', { data: String(s), from: String(from), to: String(to) }),
      gunzip: (b64) => call('codec.gunzip', { data: String(b64) }),
      unpackPacker: (js) => call('codec.unpack', { js: String(js) })
    },
    m3u8: { parse: (text, baseUrl) => call('m3u8.parse', { text: String(text), base: baseUrl ? String(baseUrl) : '' }) },
    storage: {
      get: (k) => call('storage.get', { k: String(k) }),
      set: (k, v) => { call('storage.set', { k: String(k), v: v === undefined ? null : v }); },
      remove: (k) => { call('storage.remove', { k: String(k) }); }
    },
    settings: call('settings.all') || {},
    error: (code, message) => { const e = new Error(String(message == null ? code : message)); e.code = String(code); return e; },
    log: (...a) => { call('log', { m: a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') }); }
  };
  deepFreeze(host);
  Object.defineProperty(globalThis, 'host', { value: host, enumerable: false, writable: false, configurable: false });
  const con = Object.freeze({ log: host.log, info: host.log, warn: host.log, error: host.log });
  Object.defineProperty(globalThis, 'console', { value: con, enumerable: false, writable: false, configurable: false });
})(globalThis.__host);
delete globalThis.__host;
