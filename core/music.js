// 群星音乐 · 核心。遥控 DLNA/UPnP 播放器（实测 A&K CA1000T 的 AK Connect）：
//  · 浏览它自己的曲库（ContentDirectory：文件夹 / 歌手 / 专辑 / 歌单 / 全部歌曲）
//  · 推给它的原生播放器播放（AVTransport），暂停 / 继续 / 跳转 / 上一首 / 下一首，音量（RenderingControl）
//  · 队列在群星里维护：一首播完自动接下一首
//  · 歌词：播放时按 歌名 + 歌手 + 时长 实时去 LRCLIB 查同步歌词（只在内存里缓存，不写文件）
// 页面：/music（电脑）或 /music?k=口令（平板、手机在家里 Wi-Fi / Tailscale 里打开）
// 注意：CA1000T 的 HTTP 服务很脆，所有请求排队一个一个发、短超时；DSF 推送曾把它卡死过。
const http = require('http');
const https = require('https');
const dgram = require('dgram');
const fs = require('fs');
const path = require('path');
const { Ftp } = require('./ftp');
// 繁体 → 简体（歌词、显示用的歌名/歌手）。日文（有假名）的不转
let T2S = s => s;
try { const O = require('./opencc_t2cn'); const c = O.Converter({ from: 'tw', to: 'cn' }); T2S = s => (!s || (String(s).match(/[\u3040-\u30ff]/g) || []).length > 3) ? s : c(String(s)); } catch (e) {}

const ST_RENDERER = 'urn:schemas-upnp-org:device:MediaRenderer:1';
const AV = 'urn:schemas-upnp-org:service:AVTransport:1';
const RC = 'urn:schemas-upnp-org:service:RenderingControl:1';
const CD = 'urn:schemas-upnp-org:service:ContentDirectory:1';

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const unesc = s => String(s || '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
const tag = (x, t) => { const m = new RegExp('<(?:\\w+:)?' + t + '(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?' + t + '>').exec(x); return m ? unesc(m[1]) : ''; };
const hms = s => { const p = String(s || '0').split(':').map(Number); return p.length === 3 ? p[0] * 3600 + p[1] * 60 + p[2] : (p[0] * 60 + (p[1] || 0)); };
const toHms = t => { t = Math.max(0, Math.floor(t)); return `${Math.floor(t / 3600)}:${String(Math.floor(t / 60) % 60).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`; };

function req(url, { method = 'GET', headers = {}, body = null, timeout = 6000 } = {}) {
  return new Promise((ok, no) => {
    const u = new URL(url), lib = u.protocol === 'https:' ? https : http;
    const r = lib.request(u, { method, headers, timeout }, res => {
      const bufs = []; res.on('data', b => bufs.push(b));
      res.on('end', () => ok({ status: res.statusCode, headers: res.headers, body: Buffer.concat(bufs) }));
    });
    r.on('timeout', () => r.destroy(new Error('超时')));
    r.on('error', no);
    if (body) r.write(body);
    r.end();
  });
}

class MusicCore {
  constructor() {
    this.ip = null; this.ctl = null; this.playerName = '';   // 播放器 IP、各服务控制地址
    this.q = Promise.resolve();                  // 请求队列（一次只发一个）
    this.st = { state: 'UNKNOWN', rel: 0, dur: 0, uri: '', vol: null, at: 0, online: false, err: '' };
    this.queue = []; this.pos = -1; this.cur = null;
    this.lyr = new Map();                        // 歌词缓存（内存）
    this.wantPlay = false; this.lastStart = 0;
    this.mode = 'order';            // order 顺序 / loop 列表循环 / one 单曲循环 / shuffle 随机
    this.nextIdx = -1; this.hist = []; this.sleep = null; this.loud = null;
  }

  // opts: { cfg: 配置（见 config.example.json 的 player 部分）, dataDir: 数据目录, log }
  async init(opts) {
    opts = opts || {};
    this.cfg = opts.cfg || {};
    this.dataDir = opts.dataDir || path.join(__dirname, '..', 'data');
    fs.mkdirSync(this.dataDir, { recursive: true });
    this.log = opts.log || (() => {});
    const c = this.cfg;
    if (c.ip) this.ip = c.ip;
    try { const p = JSON.parse(fs.readFileSync(path.join(this.dataDir, 'player.json'), 'utf8')); if (!c.ip || p.ip === c.ip) { this.ctl = p.ctl; this.ip = this.ip || p.ip; this.playerName = p.name; } } catch (e) {}
    this.discover().catch(() => {});
    setInterval(() => this.discover().catch(() => {}), 10 * 60e3);
    this.tick = setInterval(() => this.watch().catch(() => {}), 2000);
    this.pdir = path.join(this.dataDir, 'playlists'); fs.mkdirSync(path.join(this.pdir, '_history'), { recursive: true });
    this.metaFile = path.join(this.pdir, '_meta.json');
    try { this.pmeta = JSON.parse(fs.readFileSync(this.metaFile, 'utf8')); } catch (e) { this.pmeta = {}; }
    this.jobs = new Map(); this.ftpBusy = 0; this.ftpWait = [];
    setInterval(() => this.plSyncDirty().catch(() => {}), 60e3);
    // 队列存盘：hub 重启（改程序、重启电脑）后接着用
    this.qFile = path.join(this.dataDir, 'music_queue.json');
    try { const q = JSON.parse(fs.readFileSync(this.qFile, 'utf8')); this.queue = q.queue || []; this.pos = q.pos != null ? q.pos : -1; this.cur = this.queue[this.pos] || null; if (q.mode) this.mode = q.mode; } catch (e) {}
    this.sleepT = setInterval(() => this.sleepTick().catch(() => {}), 1000);
  }

  /* ---------- 发现 ---------- */
  // 通用 UPnP 发现：搜 MediaRenderer / MediaServer，读设备描述，找到各服务的 controlURL。
  // 选哪台：配置里的 name（名字包含）或 ip；都没写就选第一台播放端，曲库优先选同一个 IP 的。
  async discover() {
    const locs = await new Promise(done => {
      const s = dgram.createSocket('udp4'); const found = new Set();
      const finish = () => { try { s.close(); } catch (e) {} done([...found]); };
      s.on('message', b => { const loc = (/^location:\s*(.+)$/im.exec(b.toString()) || [])[1]; if (loc) found.add(loc.trim()); });
      s.on('error', finish);
      s.bind(0, () => {
        for (const st of [ST_RENDERER, 'urn:schemas-upnp-org:device:MediaServer:1', 'ssdp:all']) {
          s.send(`M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ${st}\r\n\r\n`, 1900, '239.255.255.250');
        }
      });
      setTimeout(finish, 3500);
    });
    const devs = [];
    for (const loc of locs) {
      try {
        const x = (await req(loc, { timeout: 6000 })).body.toString('utf8');
        const base = tag(x, 'URLBase') || loc;
        for (const dm of x.matchAll(/<device>([\s\S]*?)<\/device>/g)) {
          const d = dm[1], type = tag(d, 'deviceType'), svcs = {};
          for (const sm of d.matchAll(/<service>([\s\S]*?)<\/service>/g)) {
            const st = tag(sm[1], 'serviceType'), cu = tag(sm[1], 'controlURL');
            const k = /AVTransport/.test(st) ? 'avt' : /RenderingControl/.test(st) ? 'rc' : /ConnectionManager/.test(st) ? 'cm' : /ContentDirectory/.test(st) ? 'cd' : null;
            if (k && cu && !svcs[k]) svcs[k] = new URL(cu, base).href;
          }
          if (/MediaRenderer|MediaServer/.test(type)) devs.push({ type: /Renderer/.test(type) ? 'r' : 's', name: tag(d, 'friendlyName'), model: tag(d, 'modelName'), ip: new URL(loc).hostname, svcs });
        }
      } catch (e) {}
    }
    this.devices = devs.map(d => ({ type: d.type, name: d.name, model: d.model, ip: d.ip }));
    const want = d => (this.cfg.name ? (d.name || '').includes(this.cfg.name) : true) && (this.cfg.ip ? d.ip === this.cfg.ip : true);
    const r = devs.find(d => d.type === 'r' && d.svcs.avt && want(d)) || (!this.cfg.name && !this.cfg.ip && devs.find(d => d.type === 'r' && d.svcs.avt));
    if (r) { this.ctl = Object.assign({}, this.ctl, { avt: r.svcs.avt, rc: r.svcs.rc, cm: r.svcs.cm }); this.ip = r.ip; this.playerName = r.name; }
    const sv = devs.find(d => d.type === 's' && d.svcs.cd && d.ip === this.ip) || devs.find(d => d.type === 's' && d.svcs.cd && want(d));
    if (sv) this.ctl = Object.assign({}, this.ctl, { cd: sv.svcs.cd });
    if (r || sv) this.saveCtl();
    return { player: this.playerName, ip: this.ip, ctl: this.ctl, devices: this.devices };
  }
  saveCtl() { try { fs.writeFileSync(path.join(this.dataDir, 'player.json'), JSON.stringify({ ip: this.ip, name: this.playerName, ctl: this.ctl })); } catch (e) {} }
  // 控制地址：发现到的 > 配置里手写的 > 上次发现存下的
  rUrl(svc) {
    const k = { AVTransport: 'avt', RenderingControl: 'rc', ConnectionManager: 'cm' }[svc];
    const u = (this.ctl || {})[k] || (this.cfg.control || {})[k];
    if (!u) throw new Error('还没找到播放器：确认播放器开着 DLNA（A&K 叫 AK Connect）、亮着屏，和这台电脑在同一个 Wi-Fi');
    return u;
  }
  sUrl() {
    const u = (this.ctl || {}).cd || (this.cfg.control || {}).cd;
    if (!u) throw new Error('还没找到播放器的曲库服务（DLNA 媒体服务器）');
    return u;
  }
  /* ---------- SOAP（排队） ---------- */
  soap(url, st, action, args, timeout = 6000) {
    const run = async () => {
      const inner = Object.entries(args).map(([k, v]) => `<${k}>${esc(v)}</${k}>`).join('');
      const body = `<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action} xmlns:u="${st}">${inner}</u:${action}></s:Body></s:Envelope>`;
      const r = await req(url, { method: 'POST', timeout, body: Buffer.from(body, 'utf8'),
        headers: { 'Content-Type': 'text/xml; charset="utf-8"', SOAPACTION: `"${st}#${action}"`, 'Content-Length': Buffer.byteLength(body) } });
      const x = r.body.toString('utf8');
      if (r.status !== 200) throw new Error(`${action} 失败 ${r.status} ${tag(x, 'errorDescription')}`);
      return x;
    };
    const p = this.q.then(run, run);
    this.q = p.catch(() => {});
    return p;
  }
  av(action, args = {}, t) { return this.soap(this.rUrl('AVTransport'), AV, action, Object.assign({ InstanceID: 0 }, args), t); }
  rc(action, args = {}) { return this.soap(this.rUrl('RenderingControl'), RC, action, Object.assign({ InstanceID: 0, Channel: 'Master' }, args)); }

  /* ---------- 曲库 ---------- */
  async browse(id = '0', start = 0, count = 100) {
    const x = await this.soap(this.sUrl(), CD, 'Browse', { ObjectID: id, BrowseFlag: 'BrowseDirectChildren', Filter: '*', StartingIndex: start, RequestedCount: count, SortCriteria: '' }, 12000);
    const didl = tag(x, 'Result');
    const total = +tag(x, 'TotalMatches') || 0;
    const items = [];
    for (const m of didl.matchAll(/<(container|item)\s([^>]*)>([\s\S]*?)<\/\1>/g)) {
      const attr = k => unesc((new RegExp(k + '="([^"]*)"').exec(m[2]) || [])[1] || '');
      const b = m[3];
      if (m[1] === 'container') { const t = tag(b, 'title'); items.push({ kind: 'dir', id: attr('id'), title: t, titleS: T2S(t) !== t ? T2S(t) : undefined, count: +attr('childCount') || 0 }); continue; }
      const res = /<res\s([^>]*)>([^<]*)<\/res>/.exec(b) || [];
      const ra = k => unesc((new RegExp(k + '="([^"]*)"').exec(res[1] || '') || [])[1] || '');
      items.push({ kind: 'song', id: attr('id'), title: tag(b, 'title'), artist: tag(b, 'artist') || tag(b, 'creator'), album: tag(b, 'album'),
        art: tag(b, 'albumArtURI'), url: unesc(res[2] || ''), proto: ra('protocolInfo'), dur: hms(ra('duration')),
        sr: +ra('sampleFrequency') || 0, bits: +ra('bitsPerSample') || 0, size: +ra('size') || 0, didl: m[0] });
      { const it = items[items.length - 1]; const a = T2S(it.title), b = T2S(it.artist), c = T2S(it.album); if (a !== it.title) it.titleS = a; if (b !== it.artist) it.artistS = b; if (c !== it.album) it.albumS = c; }
    }
    return { id, start, total, items };
  }

  /* ---------- 播放 ---------- */
  meta(s) {
    return `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dc="http://purl.org/dc/elements/1.1/">${s.didl}</DIDL-Lite>`;
  }
  async startSong(i) {
    const s = this.queue[i]; if (!s) throw new Error('队列里没有这首');
    if (/dsf|dff|dsd/i.test(s.proto + s.url) && !this.cfg.allowDsd) throw new Error('DSD（dsf/dff）默认不推送（A&K CA1000T 实测会被卡死）；在播放器上直接放，或在配置里打开 allowDsd');
    // 先看文件还在不在：CA1000T 曲库没重新扫描时，挪过的文件会回 204（空）
    const code = await this.headCode(s.url);
    if (code === 204 || code === 404) throw new Error('这首歌的文件已经不在原来的位置（整理曲库挪过）。请在播放器上更新一次曲库（媒体扫描）再试');
    this.pos = i; this.cur = s; this.lastStart = Date.now(); this.wantPlay = true; this.saveQ();
    this.st.rel = 0; this.st.dur = s.dur; this.st.uri = s.url;
    await this.av('SetAVTransportURI', { CurrentURI: s.url, CurrentURIMetaData: this.meta(s) }, 8000);
    await this.av('Play', { Speed: 1 }, 8000);
    if (this.hist[this.hist.length - 1] !== i) { this.hist.push(i); if (this.hist.length > 200) this.hist.shift(); }
    this.lyrics(s).catch(() => {});
    this.armNext().catch(() => {});
    return this.view();
  }
  headCode(url) {
    return new Promise(ok => {
      const q = http.get(url, { headers: { Range: 'bytes=0-1023' }, timeout: 6000 }, res => { ok(res.statusCode); res.destroy(); });
      q.on('timeout', () => { q.destroy(); ok(0); });
      q.on('error', e => ok(/Duplicate Content-Length/i.test(e.message) ? 200 : 0));   // CA1000T 的文件服务会重复发 Content-Length，Node 认为不合法，但文件其实在
    });
  }
  async play({ items, index = 0 }) {
    const songs = (items || []).filter(x => x && x.kind === 'song' && x.url);
    if (!songs.length) throw new Error('没有可播放的歌');
    this.queue = songs; this.hist = []; return this.startSong(Math.min(index, songs.length - 1));
  }
  playable(i) { const x = this.queue[i]; return !!x && !(/dsf|dff|dsd/i.test((x.proto || '') + x.url) && !this.cfg.allowDsd); }
  // 按播放顺序算“下一首”的位置；auto = 自动接（单曲循环时接自己），手动点“下一首”时单曲循环也往后走
  nextIndex(auto) {
    const n = this.queue.length; if (!n) return -1;
    if (this.mode === 'one' && auto) return this.pos;
    if (this.mode === 'shuffle') {
      if (n === 1) return 0;
      const recent = new Set(this.hist.slice(-Math.min(n - 1, Math.floor(n / 2) + 1)));
      const pool = [...Array(n).keys()].filter(k => k !== this.pos && !recent.has(k) && this.playable(k));
      const all = [...Array(n).keys()].filter(k => k !== this.pos && this.playable(k));
      const from = pool.length ? pool : all; return from.length ? from[Math.floor(Math.random() * from.length)] : -1;
    }
    for (let k = 1; k <= n; k++) {
      let j = this.pos + k;
      if (j >= n) { if (this.mode === 'loop' || this.mode === 'one') j -= n; else return -1; }
      if (this.playable(j)) return j;
    }
    return -1;
  }
  // 无缝：提前把下一首告诉 CA1000T（SetNextAVTransportURI），它放完这首自己接上
  async armNext() {
    this.nextIdx = -1;
    if (this.sleep && this.sleep.endOfTrack) { await this.av('SetNextAVTransportURI', { NextURI: '', NextURIMetaData: '' }).catch(() => {}); return; }
    const i = this.nextIndex(true); if (i < 0) return;
    const x = this.queue[i];
    const code = await this.headCode(x.url); if (code === 204 || code === 404) return;
    await this.av('SetNextAVTransportURI', { NextURI: x.url, NextURIMetaData: this.meta(x) });
    this.nextIdx = i; this.armedFor = this.cur && this.cur.url;
  }
  async step(d) {
    if (!this.queue.length) throw new Error('队列是空的');
    for (let n = 0; n < 30; n++) {
      let i;
      if (d > 0) i = this.nextIndex(false);
      else if (this.mode === 'shuffle' && this.hist.length > 1) { this.hist.pop(); i = this.hist.pop(); }
      else { i = this.pos - 1; if (i < 0 && (this.mode === 'loop' || this.mode === 'one')) i = this.queue.length - 1; }
      if (i == null || i < 0 || i >= this.queue.length) break;
      try { return await this.startSong(i); }
      catch (e) { if (!/不在原来的位置|DSD/.test(e.message)) throw e; this.pos = i; }   // 找不到文件 / DSD 就跳过，接着试下一首
    }
    this.wantPlay = false; throw new Error(d > 0 ? '后面没有能放的歌了' : '前面没有能放的歌了');
  }
  async fastEnd() {
    this.endLoop = true; const t0 = Date.now(), url = this.cur && this.cur.url;
    try {
      while (this.wantPlay && this.cur && this.cur.url === url && Date.now() - t0 < 15000) {
        const x = await this.av('GetTransportInfo', {}, 3000).catch(() => '');
        const st = tag(x, 'CurrentTransportState');
        if (st === 'STOPPED' || st === 'NO_MEDIA_PRESENT') {
          if (this.sleep && this.sleep.endOfTrack) { this.sleep = null; this.wantPlay = false; break; }
          const i = this.nextIndex(true);
          if (i >= 0) { this.st.state = 'TRANSITIONING'; await this.startSong(i).catch(() => this.step(1).catch(() => { this.wantPlay = false; })); }
          else this.wantPlay = false;
          break;
        }
        await new Promise(r => setTimeout(r, 400));
      }
    } finally { this.endLoop = false; }
  }
  async setMode(m) {
    if (!['order', 'loop', 'one', 'shuffle'].includes(m)) throw new Error('不认识的播放顺序');
    this.mode = m; this.saveQ(); if (this.cur && this.wantPlay) await this.armNext().catch(() => {});
    return this.view();
  }
  /* ---------- 睡眠定时：到点前 20 秒慢慢调小音量，暂停后把音量调回来 ---------- */
  setSleep(b) {
    if (!b || b.off) { this.sleep = null; return this.view(); }
    if (b.endOfTrack) { this.sleep = { endOfTrack: true, url: this.cur && this.cur.url }; this.armNext().catch(() => {}); return this.view(); }
    const min = Math.max(1, Math.min(600, +b.min || 30));
    this.sleep = { until: Date.now() + min * 60e3, min };
    if (this.cur) this.armNext().catch(() => {});
    return this.view();
  }
  async sleepTick() {
    const z = this.sleep; if (!z || z.busy) return;
    if (z.endOfTrack) return;   // 在 watch 里处理（这首放完就停）
    const left = (z.until - Date.now()) / 1000;
    if (left > 20) return;
    z.busy = true;
    try {
      if (z.vol0 == null) { const x = await this.rc('GetVolume'); z.vol0 = +tag(x, 'CurrentVolume'); }
      if (left > 0) { await this.rc('SetVolume', { DesiredVolume: Math.round(z.vol0 * Math.max(0, left) / 20) }); }
      else { await this.av('Pause').catch(() => {}); this.wantPlay = false; await new Promise(r => setTimeout(r, 800)); await this.rc('SetVolume', { DesiredVolume: z.vol0 }); this.sleep = null; this.st.vol = z.vol0; }
    } finally { if (this.sleep) this.sleep.busy = false; }
  }
  async loudness(v) {
    if (v !== undefined) await this.rc('SetLoudness', { DesiredLoudness: v ? 1 : 0 });
    const x = await this.rc('GetLoudness'); this.loud = tag(x, 'CurrentLoudness') === '1'; return { loud: this.loud };
  }

  async status() {
    try {
      const t = await this.av('GetTransportInfo', {}, 5000);
      const p = await this.av('GetPositionInfo', {}, 5000);
      Object.assign(this.st, { state: tag(t, 'CurrentTransportState') || 'UNKNOWN', rel: hms(tag(p, 'RelTime')), dur: hms(tag(p, 'TrackDuration')) || this.st.dur,
        uri: tag(p, 'TrackURI'), at: Date.now(), online: true, err: '' });
      // 中台重启后：CA1000T 还在放我们队列里的这首 → 接着管（继续自动下一首 / 无缝）
      if (!this.wantPlay && !this.resumed && this.st.state === 'PLAYING' && this.cur && this.st.uri === this.cur.url) { this.resumed = true; this.wantPlay = true; this.armNext().catch(() => {}); }
      // 别人在 CA1000T 上换了歌：当前曲目对不上队列就清掉“当前”
      if (this.cur && this.st.uri && this.st.uri !== this.cur.url && this.nextIdx >= 0 && this.queue[this.nextIdx] && this.queue[this.nextIdx].url === this.st.uri) {
        // CA1000T 自己无缝接上了我们预先给的下一首
        const i = this.nextIdx; this.pos = i; this.cur = this.queue[i]; this.lastStart = Date.now(); this.saveQ();
        if (this.hist[this.hist.length - 1] !== i) this.hist.push(i);
        this.gapless = (this.gapless || 0) + 1; this.lyrics(this.cur).catch(() => {}); this.armNext().catch(() => {});
      }
      if (this.cur && this.st.uri && this.st.uri !== this.cur.url) {
        const k = this.queue.findIndex(x => x.url === this.st.uri);
        if (k >= 0) { this.pos = k; this.cur = this.queue[k]; } else { this.cur = null; this.wantPlay = false; }
      }
    } catch (e) { this.st.online = false; this.st.err = e.message; }
    return this.st;
  }
  async watch() {
    // 页面在看（30 秒内问过）或者在放队列时才去问 CA1000T，平时不打扰它
    if (!(Date.now() - (this.seen || 0) < 30e3 || this.wantPlay || this.sleep)) return;
    const prev = this.st.state;
    await this.status();
    const s = this.st;
    // 快到结尾：改成每 0.4 秒问一次，一停就马上推下一首（CA1000T 收下了“下一首”但实际不会自己接，实测停 1 秒 + 起播约 3 秒）
    if (this.wantPlay && this.cur && s.state === 'PLAYING' && s.dur && s.dur - s.rel < 6 && !this.endLoop) this.fastEnd();
    if (this.sleep && this.sleep.endOfTrack && this.sleep.url && (s.uri !== this.sleep.url || s.state === 'STOPPED')) {
      // “播完这首就停”：CA1000T 已经停了，或者自己接到了下一首（那就马上暂停）
      if (s.state === 'PLAYING') await this.av('Pause').catch(() => {});
      this.sleep = null; this.wantPlay = false; return;
    }
    if (this.wantPlay && this.cur && !this.endLoop && Date.now() - this.lastStart > 8000 && prev === 'PLAYING' && (s.state === 'STOPPED' || s.state === 'NO_MEDIA_PRESENT')) {
      // 没能无缝接上（CA1000T 没接受下一首）→ 我们自己推
      const i = this.nextIndex(true);
      if (i >= 0) await this.startSong(i).catch(() => this.step(1).catch(() => { this.wantPlay = false; }));
      else this.wantPlay = false;
    }
  }
  view() {
    const s = this.st, live = s.state === 'PLAYING' ? (Date.now() - s.at) / 1000 : 0;
    return { online: s.online, err: s.err, player: this.playerName || '', state: s.state, rel: Math.min(s.dur || 1e9, s.rel + live), dur: s.dur, ip: this.ip,
      cur: this.cur && { title: T2S(this.cur.title), artist: T2S(this.cur.artist), album: T2S(this.cur.album), art: this.cur.art ? '/api/music/art?u=' + encodeURIComponent(this.cur.art) : '', sr: this.cur.sr, bits: this.cur.bits, url: this.cur.url },
      pos: this.pos, queueLen: this.queue.length, vol: s.vol, mode: this.mode, loud: this.loud, gapless: this.gapless || 0,
      sleep: this.sleep ? (this.sleep.endOfTrack ? { endOfTrack: true } : { left: Math.max(0, Math.round((this.sleep.until - Date.now()) / 1000)), min: this.sleep.min }) : null };
  }

  /* ---------- 歌词（LRCLIB，实时查，只缓存在内存） ---------- */
  cleanTitle(t) {
    return String(t || '').replace(/\.(flac|mp3|wav|m4a|ogg|dsf|dff)$/i, '').replace(/^\s*\d{1,3}\s*[.\-_、 ]\s*/, '')
      .replace(/【[^】]*】|\[[^\]]*\]/g, ' ').replace(/[（(][^）)]*(版|ver|live|remix|伴奏|dj|翻自|cover)[^）)]*[）)]/ig, ' ').replace(/_/g, ' ').replace(/\s+/g, ' ').trim();
  }
  async lyrics(s) {
    if (!s) return null;
    if (this.cfg.lyrics === false) return { found: false, off: true };
    const key = s.url;
    if (this.lyr.has(key)) return this.lyr.get(key);
    let title = this.cleanTitle(s.title), artist = (s.artist || '').replace(/<unknown>/i, '').trim();
    if (!artist && / - /.test(title)) { const [a, b] = title.split(' - '); artist = a.trim(); title = b.trim(); }
    const UA = { 'User-Agent': 'starry-constellation/0.4 (personal music remote)' };
    const kana = t => (String(t).match(/[\u3040-\u30ff]/g) || []).length;
    const titleKana = kana(title) > 0;
    const pick = list => {
      let best = null;
      for (const x of list || []) {
        if (!x.syncedLyrics && !x.plainLyrics && !x.instrumental) continue;
        const d = s.dur ? Math.abs((x.duration || 0) - s.dur) : 0;
        const txt = x.syncedLyrics || x.plainLyrics || '';
        let sc = d + (x.syncedLyrics ? 0 : 5);
        if (!titleKana && /[\u4e00-\u9fff]/.test(title) && kana(txt) > 20) sc += 30;   // 中文歌配到日文翻唱
        if (!best || sc < best.sc) best = { sc, d, x };
      }
      return best && (!s.dur || best.d <= 4) ? best.x : null;
    };
    let hit = null, err = '';
    try {
      const all = [], seen = new Set();
      const get = async q => { const r = await req('https://lrclib.net/api/search?' + new URLSearchParams(q), { headers: UA, timeout: 10000 });
        for (const x of JSON.parse(r.body.toString('utf8')) || []) if (!seen.has(x.id)) { seen.add(x.id); all.push(x); } };
      if (artist) await get({ track_name: title, artist_name: artist });
      hit = pick(all);
      if (!hit || !hit.syncedLyrics) { await get({ track_name: title }); hit = pick(all); }
      if (!hit && artist) { await get({ q: artist + ' ' + title }); hit = pick(all); }
    } catch (e) { err = e.message; }
    const out = hit ? { found: true, synced: !!hit.syncedLyrics, instrumental: !!hit.instrumental, text: T2S(hit.syncedLyrics || hit.plainLyrics || ''), src: 'LRCLIB #' + hit.id }
      : { found: false, synced: false, text: '', q: { title, artist }, err };
    if (err) return out;
    if (this.lyr.size > 300) this.lyr.delete(this.lyr.keys().next().value);
    this.lyr.set(key, out);
    return out;
  }


  saveQ() { clearTimeout(this.qT); this.qT = setTimeout(() => { try { fs.writeFileSync(this.qFile, JSON.stringify({ queue: this.queue, pos: this.pos, mode: this.mode })); } catch (e) {} }, 500); }
  /* ---------- 队列编辑 ---------- */
  qAdd(items, mode) {
    const songs = (items || []).filter(x => x && x.kind === 'song' && x.url);
    if (!songs.length) throw new Error('没有可加的歌');
    if (mode === 'next' && this.pos >= 0) this.queue.splice(this.pos + 1, 0, ...songs);
    else this.queue.push(...songs);
    this.saveQ(); if (this.wantPlay) this.armNext().catch(() => {}); return { added: songs.length, queueLen: this.queue.length };
  }
  qRemove(i) {
    if (i < 0 || i >= this.queue.length) throw new Error('没有这一首');
    if (i === this.pos) throw new Error('正在放的这首不能移走，先切到别的歌');
    this.queue.splice(i, 1); if (i < this.pos) this.pos--;
    this.saveQ(); return { ok: 1 };
  }
  qMove(i, j) {
    const n = this.queue.length; if (i < 0 || i >= n || j < 0 || j >= n) throw new Error('位置不对');
    const [x] = this.queue.splice(i, 1); this.queue.splice(j, 0, x);
    if (this.pos === i) this.pos = j; else if (i < this.pos && j >= this.pos) this.pos--; else if (i > this.pos && j <= this.pos) this.pos++;
    this.saveQ(); return { ok: 1 };
  }
  qClear() { const cur = this.cur && this.pos >= 0 ? [this.queue[this.pos]] : []; this.queue = cur; this.pos = cur.length ? 0 : -1; this.saveQ(); return { ok: 1 }; }

  /* ---------- AK Connect 全部歌曲（按文件大小找对应） ---------- */
  async dmsAll(force) {
    const f = path.join(this.dataDir, 'music_dms.json');
    const mk = j => { const bySize = new Map(); for (const x of j.songs) if (x.size && !bySize.has(x.size)) bySize.set(x.size, x); return { ts: j.ts, songs: j.songs, bySize }; };
    if (!this.dms) { try { this.dms = mk(JSON.parse(fs.readFileSync(f, 'utf8'))); } catch (e) {} }
    const build = () => this.dmsBuilding || (this.dmsBuilding = (async () => {
      const songs = []; let st = 0, total = 1;
      while (st < total) { const j = await this.browse('1', st, 200); total = j.total; songs.push(...j.items.filter(x => x.kind === 'song')); st += 200; if (!j.items.length) break; }
      const j = { ts: Date.now(), songs }; try { fs.writeFileSync(f, JSON.stringify(j)); } catch (e) {}
      return (this.dms = mk(j));
    })().finally(() => { this.dmsBuilding = null; }));
    if (force || !this.dms) return build();
    if (Date.now() - this.dms.ts > 10 * 60e3) build().catch(() => {});   // 先用旧的，后台更新
    return this.dms;
  }


  /* ---------- 按“文件夹”视图建路径索引：AK Connect 的文件夹视图里歌名 = 文件名，内置存储和存储卡都有 ---------- */
  async pathIndex(force) {
    const f = path.join(this.dataDir, 'music_pathindex.json');
    if (!this.pidx) { try { this.pidx = this.mkPidx(JSON.parse(fs.readFileSync(f, 'utf8'))); } catch (e) {} }
    if (!force && this.pidx) { if (Date.now() - this.pidx.ts > 30 * 60e3 && !this.pidxBuilding) this.pathIndex(true).catch(() => {}); return this.pidx; }   // 先用旧的，后台更新
    if (this.pidxBuilding) return this.pidxBuilding;
    this.pidxBuilding = (async () => {
      const roots = (await this.browse('6', 0, 50)).items.filter(x => x.kind === 'dir');
      const entries = [];
      const walk = async (id, p, lv) => {
        for (let st = 0, total = 1; st < total; st += 200) {
          const j = await this.browse(id, st, 200); total = j.total; if (!j.items.length) break;
          for (const x of j.items) {
            if (x.kind === 'dir') { if (lv < 12 && !/^(Android|DCIM|Pictures|Movies|System Volume Information|AlbumArt)$/.test(x.title)) await walk(x.id, p + '/' + x.title, lv + 1); }
            else entries.push([p + '/' + x.title, x.url, x.size || 0, x.title, x.artist || '', x.album || '', x.dur || 0, x.id, x.sr || 0, x.bits || 0, x.proto || '', x.art || '', x.didl]);
          }
        }
      };
      for (const r of roots) await walk(r.id, /internal/i.test(r.title) ? this.pre() : (this.cfg.sdPath || '/storage/sdcard1'), 0);
      const j = { ts: Date.now(), entries }; fs.writeFileSync(f, JSON.stringify(j));
      return (this.pidx = this.mkPidx(j));
    })().finally(() => { this.pidxBuilding = null; });
    return this.pidxBuilding;
  }
  mkPidx(j) {
    const byPath = new Map(), byUrl = new Map();
    for (const e of j.entries) {
      const item = { kind: 'song', id: e[7], title: e[3], artist: e[4], album: e[5], dur: e[6], url: e[1], size: e[2], sr: e[8], bits: e[9], proto: e[10], art: e[11], didl: e[12] };
      byPath.set(e[0].toLowerCase(), item); byUrl.set(e[1], e[0]);
    }
    return { ts: j.ts, byPath, byUrl, n: j.entries.length };
  }

  /* ---------- File Drop（FTP）---------- */
  ftpOpt() { const f = this.cfg.ftp || {}; return { host: f.host || this.ip, port: f.port || 1212, user: f.user || 'admin', pass: f.pass || 'admin' }; }
  // 最多同时开 2 个 FTP 会话（CA1000T 的服务很脆）
  async withFtp(fn) {
    if (this.ftpBusy >= 2) await new Promise(r => this.ftpWait.push(r));
    this.ftpBusy++;
    let c;
    try { c = await Ftp.open(this.ftpOpt()); return await fn(c); }
    finally { if (c) await c.close(); this.ftpBusy--; const w = this.ftpWait.shift(); if (w) w(); }
  }
  // 全盘文件索引：路径 ↔ 大小（加歌到歌单时要知道文件在哪）
  async ftpIndex(force) {
    const f = path.join(this.dataDir, 'music_ftpindex.json');
    if (!this.fidx) { try { const j = JSON.parse(fs.readFileSync(f, 'utf8')); this.fidx = this.mkIdx(j); } catch (e) {} }
    if (!force && this.fidx) { if (Date.now() - this.fidx.ts > 30 * 60e3 && !this.fidxBuilding) this.ftpIndex(true).catch(() => {}); return this.fidx; }
    if (this.fidxBuilding) return this.fidxBuilding;
    this.fidxBuilding = this.withFtp(async c => {
      const files = [];
      const walk = async (d, lv) => { for (const e of await c.list(d)) { const p = (d === '/' ? '' : d) + '/' + e.name;
        if (e.dir) { if (lv < 10 && !/^\/(DCIM|AlbumArt|Android|\.)/.test(p)) await walk(p, lv + 1); }
        else if (/\.(flac|mp3|wav|m4a|aac|ogg|ape|aiff?|dsf|dff|wma|alac)$/i.test(e.name)) files.push([p, e.size]); } };
      await walk('/', 0);
      const j = { ts: Date.now(), files }; fs.writeFileSync(f, JSON.stringify(j));
      return (this.fidx = this.mkIdx(j));
    }).finally(() => { this.fidxBuilding = null; });
    return this.fidxBuilding;
  }
  mkIdx(j) { const bySize = new Map(), byPath = new Map(); for (const [p, n] of j.files) { if (!bySize.has(n)) bySize.set(n, p); byPath.set(p, n); } return { ts: j.ts, files: j.files, bySize, byPath }; }

  /* ---------- 歌单（CA1000T 的 /Playlists/*.m3u ⇄ 群星 data/playlists 镜像，两边都能改） ---------- */
  pre() { return this.cfg.internalPath || '/storage/emulated/0'; }   // 歌单 m3u 里内置存储的路径前缀（FTP 根目录对应这里）
  saveMeta() { fs.writeFileSync(this.metaFile, JSON.stringify(this.pmeta, null, 1)); }
  safeName(n) { n = String(n || '').trim().replace(/[\\/:*?"<>|]/g, '_'); if (!n) throw new Error('歌单名字不能空'); return n; }
  mirrorPath(n) { return path.join(this.pdir, n + '.m3u'); }
  parseM3u(buf) {
    const t = Buffer.isBuffer(buf) ? buf.toString('utf8').replace(/^﻿/, '') : String(buf);
    return t.split(/\r?\n/).map(x => x.trim()).filter(x => x && !x.startsWith('#'));
  }
  writeMirror(n, lines, dirty) {
    const f = this.mirrorPath(n);
    if (fs.existsSync(f)) fs.copyFileSync(f, path.join(this.pdir, '_history', n + '.' + Date.now() + '.m3u'));
    fs.writeFileSync(f, lines.join('\r\n') + '\r\n');
    this.pmeta[n] = Object.assign(this.pmeta[n] || {}, { dirty: !!dirty, at: Date.now() }); this.saveMeta();
  }
  async plList() {
    let ca = null, err = '';
    try { ca = (await this.withFtp(c => c.list('/Playlists'))).filter(e => !e.dir && /\.m3u8?$/i.test(e.name)).map(e => e.name.replace(/\.m3u8?$/i, '')); }
    catch (e) { err = e.message; }
    const local = fs.readdirSync(this.pdir).filter(f => f.endsWith('.m3u')).map(f => f.slice(0, -4));
    const names = [...new Set([...(ca || []), ...local])].filter(n => !(this.pmeta[n] || {}).deleted || (ca || []).includes(n));
    return { online: !!ca, err, items: names.sort((a, b) => a.localeCompare(b, 'zh')).map(n => ({ name: n, onCa: ca ? ca.includes(n) : null, dirty: !!(this.pmeta[n] || {}).dirty })) };
  }
  async plGet(name, wait) {
    name = this.safeName(name);
    if (!wait && fs.existsSync(this.mirrorPath(name)) && !(this.pmeta[name] || {}).dirty) {
      const lines = this.parseM3u(fs.readFileSync(this.mirrorPath(name)));
      return Object.assign({ name, from: 'mirror', fast: true, err: '', dirty: false, sig: lines.join('\n').length + ':' + lines.length }, await this.resolve(lines));
    }
    let lines = null, err = '', from = 'ca';
    const meta = this.pmeta[name] || {};
    if (meta.dirty) { await this.plPush(name).catch(e => { err = e.message; }); }
    if (!(this.pmeta[name] || {}).dirty) {
      try { const b = await this.withFtp(c => c.getText('/Playlists/' + name + '.m3u')); lines = this.parseM3u(b);
        // CA1000T 上的版本和镜像不一样（在机器上改过）→ 更新镜像
        const old = fs.existsSync(this.mirrorPath(name)) ? this.parseM3u(fs.readFileSync(this.mirrorPath(name))) : null;
        if (!old || old.join('\n') !== lines.join('\n')) this.writeMirror(name, lines, false);
      } catch (e) { err = err || (/\b550\b/.test(e.message) ? '没有这个歌单' : e.message); }
    }
    if (!lines) { if (!fs.existsSync(this.mirrorPath(name))) throw new Error(err || '没有这个歌单'); lines = this.parseM3u(fs.readFileSync(this.mirrorPath(name))); from = 'mirror'; }
    return Object.assign({ name, from, err, dirty: !!(this.pmeta[name] || {}).dirty, sig: lines.join('\n').length + ':' + lines.length }, await this.resolve(lines));
  }
  // 把 m3u 里的路径对到 AK Connect 曲库里能播放的歌
  async resolve(lines) {
    const P = this.pre();
    let pidx = null, dms = null, fidx = null;
    try { pidx = await this.pathIndex(); } catch (e) {}
    const entries = lines.map(p => ({ path: p, file: p.split('/').pop(), sd: !p.startsWith(P + '/'), item: pidx ? (pidx.byPath.get(p.toLowerCase()) || null) : null }));
    if (entries.some(e => !e.item)) {   // 文件夹视图里没找到（索引旧了）→ 退回按文件大小 / 歌名对
      try { dms = await this.dmsAll(); } catch (e) {}
      try { fidx = await this.ftpIndex(); } catch (e) {}
      const byTitle = new Map(); if (dms) for (const x of dms.songs) { const k = this.cleanTitle(x.title).toLowerCase(); if (!byTitle.has(k)) byTitle.set(k, x); }
      for (const e of entries) if (!e.item) {
        if (!e.sd && fidx && dms) { const n = fidx.byPath.get(e.path.slice(P.length)); if (n) e.item = dms.bySize.get(n) || null; }
        if (!e.item && dms) { const k = this.cleanTitle(e.file).toLowerCase(); e.item = byTitle.get(k) || byTitle.get(k.split(' - ').pop()) || null; }
      }
    }
    return { entries, playable: entries.filter(e => e.item).length };
  }
  async plPush(name) {
    const lines = this.parseM3u(fs.readFileSync(this.mirrorPath(name)));
    const buf = Buffer.concat([Buffer.from('﻿', 'utf8'), Buffer.from(lines.join('\r\n') + '\r\n', 'utf8')]);
    await this.withFtp(async c => { await c.mkd('/Playlists').catch(() => {}); await c.putBuffer('/Playlists/' + name + '.m3u', buf); });
    this.pmeta[name] = Object.assign(this.pmeta[name] || {}, { dirty: false, pushed: Date.now(), deleted: false }); this.saveMeta();
  }
  async plSyncDirty() { for (const [n, m] of Object.entries(this.pmeta)) if (m.dirty && !m.deleted && fs.existsSync(this.mirrorPath(n))) await this.plPush(n); }
  async plSave(name, paths) {
    name = this.safeName(name);
    this.writeMirror(name, paths.map(String).filter(Boolean), true);
    let pushed = true, err = '';
    try { await this.plPush(name); } catch (e) { pushed = false; err = '播放器暂时连不上，已先存在本地，连上后自动同步：' + e.message; }
    return { name, pushed, err };
  }
  async pathsFor(items) {
    const P = this.pre(), out = [], miss = [];
    let pidx = await this.pathIndex().catch(() => null), fidx = null, rebuilt = false;
    for (const x of items || []) {
      let p = pidx && pidx.byUrl.get(x.url);
      if (!p && !rebuilt) { rebuilt = true; pidx = await this.pathIndex(true).catch(() => pidx); p = pidx && pidx.byUrl.get(x.url); }
      if (!p && x.size) { fidx = fidx || await this.ftpIndex().catch(() => null); const q = fidx && fidx.bySize.get(x.size); if (q) p = P + q; }
      if (p) out.push(p); else miss.push(x.title);
    }
    return { paths: out, miss };
  }
  async plAdd(name, items) {
    name = this.safeName(name);
    let cur = [];
    try { cur = (await this.plGet(name)).entries.map(e => e.path); } catch (e) { if (!/没有这个歌单/.test(e.message)) throw e; }
    const { paths, miss } = await this.pathsFor(items);
    const add = paths.filter(p => !cur.includes(p));
    const r = await this.plSave(name, cur.concat(add));
    return Object.assign(r, { added: add.length, dup: paths.length - add.length, miss });
  }
  async plDelete(name) {
    name = this.safeName(name);
    if (fs.existsSync(this.mirrorPath(name))) fs.renameSync(this.mirrorPath(name), path.join(this.pdir, '_history', name + '.deleted.' + Date.now() + '.m3u'));
    this.pmeta[name] = { deleted: true, at: Date.now() }; this.saveMeta();
    let err = ''; try { await this.withFtp(c => c.dele('/Playlists/' + name + '.m3u')); } catch (e) { err = e.message; }
    return { ok: 1, err };
  }
  async plRename(from, to) {
    from = this.safeName(from); to = this.safeName(to);
    const g = await this.plGet(from);
    const r = await this.plSave(to, g.entries.map(e => e.path));
    await this.plDelete(from);
    return r;
  }

  /* ---------- 传文件（CA1000T ⇄ 手机/平板/电脑，经群星中转），带速度 ---------- */
  job(id, kind, name, total) {
    const j = { id: String(id || Date.now()), kind, name, total: total || 0, done: 0, state: 'run', err: '', t0: Date.now(), speed: 0, win: [] };
    this.jobs.set(j.id, j);
    if (this.jobs.size > 40) this.jobs.delete(this.jobs.keys().next().value);
    return j;
  }
  tickJob(j, n) {
    j.done += n; const t = Date.now(); j.win.push([t, j.done]);
    while (j.win.length > 2 && t - j.win[0][0] > 2000) j.win.shift();
    const a = j.win[0]; j.speed = t > a[0] ? (j.done - a[1]) / ((t - a[0]) / 1000) : 0;
  }
  jobView(j) { return { id: j.id, kind: j.kind, name: j.name, total: j.total, done: j.done, state: j.state, err: j.err, speed: Math.round(j.speed), avg: Math.round(j.done / Math.max(0.001, (Date.now() - j.t0) / 1000)), ms: Date.now() - j.t0 }; }
  cleanPath(p) { p = '/' + String(p || '/').replace(/\\/g, '/').split('/').filter(x => x && x !== '.' && x !== '..').join('/'); return p; }
  async ftpRoute(u, req, res, json) {
    this.seen = Date.now();
    const op = u.pathname.replace(/^\/api\/music\/ftp\/?/, '');
    try {
      if (op === 'ls') { const d = this.cleanPath(u.searchParams.get('dir')); const items = await this.withFtp(c => c.list(d));
        return json(res, 200, { ok: true, dir: d, items: items.sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name, 'zh')) }); }
      if (op === 'mkdir' && req.method === 'POST') { const d = this.cleanPath(u.searchParams.get('dir')); await this.withFtp(c => c.mkdirs(d)); return json(res, 200, { ok: true }); }
      if (op === 'tree') {
        // 整棵树（文件 + 文件夹），给整理用
        const root = this.cleanPath(u.searchParams.get('dir') || '/');
        const out = await this.withFtp(async c => { const files = [], dirs = [];
          const walk = async (d, lv) => { for (const e of await c.list(d)) { const p = (d === '/' ? '' : d) + '/' + e.name;
            if (e.dir) { dirs.push(p); if (lv < 12) await walk(p, lv + 1); } else files.push([p, e.size]); } };
          await walk(root, 0); return { files, dirs }; });
        return json(res, 200, Object.assign({ ok: true }, out));
      }
      if (op === 'batch' && req.method === 'POST') {
        // 批量整理：[{op:'mv',from,to} | {op:'rm',path} | {op:'rmdir',path}]，按顺序执行，返回每条结果
        let body = ''; for await (const c of req) { body += c; if (body.length > 5e6) throw new Error('太大'); }
        const ops = (JSON.parse(body || '{}').ops || []).slice(0, 5000);
        const results = await this.withFtp(async c => { const out = [], made = new Set();
          for (const o of ops) {
            try {
              if (o.op === 'mv') { const d = o.to.slice(0, o.to.lastIndexOf('/')); if (d && !made.has(d)) { await c.mkdirs(d).catch(() => {}); made.add(d); } await c.rename(this.cleanPath(o.from), this.cleanPath(o.to)); }
              else if (o.op === 'rm') await c.dele(this.cleanPath(o.path));
              else if (o.op === 'rmdir') { const d = this.cleanPath(o.path); const left = await c.list(d); if (left.length) throw new Error('文件夹不是空的（A&K 的 FTP 删文件夹会连里面的文件一起删，所以不删）：' + left.slice(0, 3).map(x => x.name).join('、')); await c.rmd(d); }
              else throw new Error('不认识的操作');
              out.push({ ok: 1 });
            } catch (e) { out.push({ ok: 0, err: e.message }); }
          }
          return out; });
        for (const k of ['fidx', 'dms', 'pidx']) if (this[k]) this[k].ts = 0;
        return json(res, 200, { ok: true, results, done: results.filter(r => r.ok).length, failed: results.filter(r => !r.ok).length });
      }
      if (op === 'jobs') return json(res, 200, { ok: true, items: [...this.jobs.values()].reverse().map(j => this.jobView(j)) });
      if (op === 'upload' && req.method === 'POST') {
        const dir = this.cleanPath(u.searchParams.get('dir')), name = String(u.searchParams.get('name') || '').replace(/[\\/:*?"<>|]/g, '_');
        if (!name) return json(res, 400, { ok: false, error: '缺文件名' });
        const j = this.job(u.searchParams.get('id'), 'up', name, +req.headers['content-length'] || 0); j.path = (dir === '/' ? '' : dir) + '/' + name;
        try {
          await this.withFtp(async c => { await c.mkdirs(dir).catch(() => {}); await c.stor((dir === '/' ? '' : dir) + '/' + name, req, n => this.tickJob(j, n)); });
          j.state = 'done'; if (this.fidx) this.fidx.ts = 0;
          if (this.imports && /\.(flac|mp3|m4a|ogg|wav|aac|ape|dsf|dff|wma|aiff?)$/i.test(name)) { this.imports = this.imports.filter(x => x !== this.pre() + j.path); this.imports.push(this.pre() + j.path); }
          return json(res, 200, { ok: true, job: this.jobView(j) });
        } catch (e) { j.state = 'err'; j.err = e.message; req.resume(); return json(res, 500, { ok: false, error: e.message, job: this.jobView(j) }); }
      }
      if (op === 'download') {
        const p = this.cleanPath(u.searchParams.get('path')), name = p.split('/').pop();
        const j = this.job(u.searchParams.get('id'), 'down', name, 0);
        let sent = false;
        res.on('error', () => {});   // 浏览器中途断开时别让中台崩
        // 同一个文件还在往 CA1000T 上传，等它传完再下
        for (let k = 0; k < 120 && [...this.jobs.values()].some(x => x.kind === 'up' && x.state === 'run' && x.path === p); k++) await new Promise(r => setTimeout(r, 500));
        try {
          await this.withFtp(async c => {
            j.total = await c.size(p).catch(() => -1);
            if (!(j.total > 0)) { const par = p.slice(0, p.lastIndexOf('/')) || '/'; const e = (await c.list(par).catch(() => [])).find(x => x.name === name); if (e) j.total = e.size; }
            let r0; try { r0 = await c.retr(p); } catch (e) { await new Promise(r => setTimeout(r, 1500)); r0 = await c.retr(p); }   // 刚上传完的文件 CA1000T 偶尔还占着，等一下再试
            const { stream, finish } = r0;
            const h = { 'Content-Type': 'application/octet-stream', 'Content-Disposition': "attachment; filename*=UTF-8''" + encodeURIComponent(name) };
            if (j.total > 0) h['Content-Length'] = j.total;
            res.writeHead(200, h); sent = true;
            await new Promise((ok, no) => {
              stream.on('data', b => { this.tickJob(j, b.length); if (!res.write(b)) { stream.pause(); res.once('drain', () => stream.resume()); } });
              stream.on('end', ok); stream.on('error', no);
              res.on('close', () => { if (j.state === 'run' && j.done < j.total) { stream.destroy(); no(new Error('下载被取消')); } });
            });
            await finish(); res.end(); j.state = 'done';
          });
        } catch (e) { j.state = 'err'; j.err = e.message; if (!sent) json(res, 500, { ok: false, error: e.message }); else res.destroy(); }
        return;
      }
      return json(res, 404, { ok: false, error: '没有这个接口' });
    } catch (e) { return json(res, 500, { ok: false, error: e.message }); }
  }

  /* ---------- HTTP 接口（hub.js 转进来） ---------- */
  async api(u, body) {
    this.seen = Date.now();
    const p = u.pathname.replace(/^\/api\/music\/?/, '');
    if (p === 'status') { if (Date.now() - this.st.at > 1500) await this.status(); return this.view(); }
    if (p === 'browse') return this.browse(u.searchParams.get('id') || '0', +u.searchParams.get('start') || 0, Math.min(200, +u.searchParams.get('count') || 100));
    if (p === 'play') return this.play(body);
    if (p === 'pause') { await this.av('Pause'); this.wantPlay = false; return this.view(); }
    if (p === 'resume') { await this.av('Play', { Speed: 1 }); this.wantPlay = !!this.cur; return this.view(); }
    if (p === 'stop') { await this.av('Stop'); this.wantPlay = false; return this.view(); }
    if (p === 'next') return this.step(1);
    if (p === 'prev') return this.step(-1);
    if (p === 'seek') { await this.av('Seek', { Unit: 'REL_TIME', Target: toHms(+body.t || 0) }); this.st.rel = +body.t || 0; this.st.at = Date.now(); return this.view(); }
    if (p === 'volume') {
      if (body && body.v !== undefined) await this.rc('SetVolume', { DesiredVolume: Math.max(0, Math.min(100, Math.round(+body.v))) });
      const x = await this.rc('GetVolume'); this.st.vol = +tag(x, 'CurrentVolume'); return { vol: this.st.vol };
    }
    if (p === 'lyrics') return (await this.lyrics(this.cur)) || { found: false };
    if (p === 'lyricsq') return this.lyrics({ url: 'q:' + u.searchParams.toString(), title: u.searchParams.get('title'), artist: u.searchParams.get('artist') || '', dur: +u.searchParams.get('dur') || 0 });
    if (p === 'mode') return this.setMode(body.mode);
    if (p === 'sleep') return this.setSleep(body);
    if (p === 'loudness') return this.loudness(body && body.on !== undefined ? !!body.on : undefined);
    if (p === 'qadd') return this.qAdd(body.items, body.mode);
    if (p === 'qremove') { const r = this.qRemove(+body.i); if (this.wantPlay) this.armNext().catch(() => {}); return r; }
    if (p === 'qmove') { const r = this.qMove(+body.i, +body.j); if (this.wantPlay) this.armNext().catch(() => {}); return r; }
    if (p === 'qclear') return this.qClear();
    if (p === 'pl/list') return this.plList();
    if (p === 'pl/get') return this.plGet(u.searchParams.get('name') || body.name, u.searchParams.get('wait') === '1');
    if (p === 'pl/save') return this.plSave(body.name, body.paths || []);
    if (p === 'pl/add') return this.plAdd(body.name, body.items);
    if (p === 'pl/delete') return this.plDelete(body.name);
    if (p === 'pl/rename') return this.plRename(body.from, body.to);
    if (p === 'pl/play') { const g = await this.plGet(body.name); const items = g.entries.filter(e => e.item).map(e => e.item); return this.play({ items, index: Math.max(0, items.indexOf((g.entries[+body.i || 0] || {}).item)) }); }
    if (p === 'reindex') { const a = await this.dmsAll(true); const c = await this.pathIndex(true); let n = 0; try { n = (await this.ftpIndex(true)).files.length; } catch (e) {} return { dms: a.songs.length, paths: c.n, files: n }; }
    if (p === 'queue') return { pos: this.pos, items: this.queue.map(x => ({ title: T2S(x.title), artist: T2S(x.artist), dur: x.dur })) };
    if (p === 'jump') return this.startSong(+body.i || 0);
    if (p === 'sink') { const x = await this.soap(this.rUrl('ConnectionManager'), 'urn:schemas-upnp-org:service:ConnectionManager:1', 'GetProtocolInfo', {}); return { sink: tag(x, 'Sink').split(',') }; }
    if (p === 'check') {
      // 逐个试读歌曲文件开头 1KB，看文件在不在（曲库没重扫时，挪过的文件会 404）
      const urls = (body.urls || []).slice(0, 300), out = [];
      for (const url of urls) {
        if (!this.sameHost(url)) { out.push({ url, code: 'bad' }); continue; }
        try { const r = await new Promise((ok, no) => { const q = http.get(url, { headers: { Range: 'bytes=0-1023' }, timeout: 6000 }, res => { ok({ status: res.statusCode, headers: res.headers }); res.destroy(); }); q.on('timeout', () => q.destroy(new Error('超时'))); q.on('error', no); }); out.push({ url, code: r.status, type: r.headers['content-type'] || '' }); }
        catch (e) { out.push({ url, code: 'ERR ' + e.message }); }
      }
      return { out };
    }
    if (p === 'rediscover') return this.discover();
    throw new Error('没有这个接口: ' + p);
  }
  // 封面代理（平板在 tailnet 外面时也能看到封面）：只代理 CA1000T 自己的 5501 端口
  sameHost(u) { try { return new URL(u).hostname === this.ip; } catch (e) { return false; } }
  // 封面代理（手机在外面走 Tailscale 时也能看到封面）：只代理播放器自己的地址
  async art(u, res) {
    const t = u.searchParams.get('u') || '';
    if (!/^http:\/\//.test(t) || !this.sameHost(t)) { res.writeHead(400); return res.end(); }
    try { const r = await req(t, { timeout: 8000 }); res.writeHead(200, { 'Content-Type': r.headers['content-type'] || 'image/jpeg', 'Cache-Control': 'max-age=86400' }); res.end(r.body); }
    catch (e) { res.writeHead(502); res.end(); }
  }

  async probe() {
    return { _note: this.st.online ? `${this.playerName || '播放器'} ${this.ip} ${this.st.state}` : `${this.playerName || '播放器'} ${this.ip || ''} 未连上` };
  }
}
require('./extras')(MusicCore, T2S);
require('./meta')(MusicCore, T2S);     // 歌曲信息补全、专辑缺歌检查   // 多来源歌词、单词点查、听歌统计、自动歌单、设置
module.exports = { MusicCore, T2S };
