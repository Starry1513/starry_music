// 群星音乐 · 扩展：多来源歌词 + 手动换歌词、歌词里英文单词点查/加生词本、听歌统计、自动歌单、设置。
// 歌词只在播放时在线查、缓存在内存；本地只记“这首歌用哪一版”（来源 + 编号），不存歌词文字。
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');

function req(url, { method = 'GET', headers = {}, body = null, timeout = 8000 } = {}) {
  return new Promise((ok, no) => {
    const u = new URL(url), lib = u.protocol === 'https:' ? https : http;
    const r = lib.request(u, { method, headers, timeout }, res => { const b = []; res.on('data', x => b.push(x)); res.on('end', () => ok({ status: res.statusCode, body: Buffer.concat(b) })); });
    r.on('timeout', () => r.destroy(new Error('超时'))); r.on('error', no); if (body) r.write(body); r.end();
  });
}
const J = r => JSON.parse(r.body.toString('utf8'));
const UA = { 'User-Agent': 'Mozilla/5.0 (starry-music; personal music remote)' };
const kana = t => (String(t || '').match(/[぀-ヿ]/g) || []).length;
const norm = t => String(t || '').toLowerCase().replace(/[\s'’"“”·・.,，。!！?？()（）\[\]【】\-_~&/]+/g, '');
const SET_DEF = { wordTap: true, showTrans: true, autoLists: true, lyricSources: ['lrclib', '163'] };
const AUTO = ['最近常听', '很久没听'];

module.exports = function extend(MusicCore, T2S) {
  const P = MusicCore.prototype, baseInit = P.init, baseStatus = P.status, baseWatch = P.watch, basePlList = P.plList, basePlGet = P.plGet;

  P.init = async function (opts) {
    await baseInit.call(this, opts);
    const D = this.dataDir;
    this.setFile = path.join(D, 'music_settings.json'); this.lcFile = path.join(D, 'music_lyrics_choice.json'); this.stFile = path.join(D, 'music_stats.json');
    try { this.set = Object.assign({}, SET_DEF, JSON.parse(fs.readFileSync(this.setFile, 'utf8'))); } catch (e) { this.set = Object.assign({}, SET_DEF); }
    try { this.lchoice = JSON.parse(fs.readFileSync(this.lcFile, 'utf8')); } catch (e) { this.lchoice = {}; }
    try { this.stats = JSON.parse(fs.readFileSync(this.stFile, 'utf8')); } catch (e) { this.stats = { v: 1, days: {}, songs: {} }; }
    if (this.stats.v !== 3) {   // 旧版的键（歌名|歌手）→ 新键（只按歌名）
      const S = this.stats, mv = {}, songs = {};
      for (const [k, x] of Object.entries(S.songs)) { const nk = this.statKey({ title: x.t + (x.a && !x.t.includes(' - ') ? '' : ''), artist: x.a }); mv[k] = nk;
        const y = songs[nk]; if (!y) songs[nk] = Object.assign({}, x, { t: this.lyrQuery({ title: x.t, artist: x.a }).title, a: x.a || this.lyrQuery({ title: x.t }).artist });
        else { y.ms += x.ms; y.plays += x.plays; y.last = Math.max(y.last || 0, x.last || 0); y.first = Math.min(y.first || 1e15, x.first || 1e15); if (!y.a) y.a = x.a || this.lyrQuery({ title: x.t }).artist; } }
      for (const D of Object.values(S.days)) { const n = {}; for (const [k, v] of Object.entries(D.songs)) { const nk = mv[k] || k; n[nk] = n[nk] || [0, 0]; n[nk][0] += v[0]; n[nk][1] += v[1]; } D.songs = n; }
      S.songs = songs; S.v = 3; this._sDirty = true;
    }
    this.lyrTmp = new Map();
    this.statT = setInterval(() => this.statSave(), 30e3);
    setTimeout(() => this.autoListsTick().catch(() => {}), 60e3); setInterval(() => this.autoListsTick().catch(() => {}), 3 * 3600e3);
  };

  /* ---------- 设置 ---------- */
  P.settings = function (b) {
    if (b && Object.keys(b).length) {
      for (const k of Object.keys(SET_DEF)) if (k in b) this.set[k] = Array.isArray(SET_DEF[k]) ? [].concat(b[k]).map(String) : !!b[k];
      fs.writeFileSync(this.setFile, JSON.stringify(this.set, null, 1));
      this.lyr.clear();
    }
    return { settings: this.set, vocab: !!this.wordHooks || !!(this.cfg.hub && this.cfg.hub.url) };
  };

  /* ---------- 歌词 ---------- */
  P.lyrKey = function (s) { return (this.cleanTitle(s.title) + '|' + String(s.artist || '').replace(/<unknown>/i, '')).toLowerCase().trim(); };
  P.lyrQuery = function (s) {
    let title = this.cleanTitle(s.title), artist = String(s.artist || '').replace(/<unknown>/i, '').trim();
    if (/ - /.test(title)) { const [a, b] = title.split(' - ', 2).map(x => x.trim()); if (!artist || norm(a) === norm(artist)) { artist = artist || a; title = b; } else if (norm(b) === norm(artist)) title = a; }
    // 网易云下载的文件名：歌名-歌手-编号
    const m = !artist && /^(.+?)-(.+?)-\d{5,}$/.exec(title); if (m) { title = m[1].trim(); artist = m[2].trim(); }
    return { title, artist, dur: +s.dur || 0 };
  };
  // 两个来源一起搜，只要列表（不含歌词正文）
  P.lyrCands = async function (q) {
    const src = this.set.lyricSources || ['lrclib', '163'], out = [], errs = [];
    const jobs = [];
    if (src.includes('lrclib')) jobs.push((async () => {
      const seen = new Set();
      const get = async o => { const r = await req('https://lrclib.net/api/search?' + new URLSearchParams(o), { headers: UA, timeout: 10000 });
        for (const x of J(r) || []) if (!seen.has(x.id)) { seen.add(x.id); this.lyrTmp.set('lrclib:' + x.id, x);
          out.push({ src: 'lrclib', id: String(x.id), title: x.trackName, artist: x.artistName, album: x.albumName, dur: x.duration || 0, synced: !!x.syncedLyrics, instrumental: !!x.instrumental, has: !!(x.syncedLyrics || x.plainLyrics) }); } };
      if (q.artist) await get({ track_name: q.title, artist_name: q.artist });
      await get({ track_name: q.title });
      if (q.artist && seen.size < 3) await get({ q: q.artist + ' ' + q.title });
    })().catch(e => errs.push('LRCLIB：' + e.message)));
    if (src.includes('163')) jobs.push((async () => {
      const r = await req('https://music.163.com/api/cloudsearch/pc?' + new URLSearchParams({ s: (q.title + ' ' + (q.artist || '')).trim(), type: 1, limit: 12 }), { method: 'POST', headers: Object.assign({ Referer: 'https://music.163.com' }, UA), timeout: 10000 });
      const j = J(r); for (const x of ((j.result || {}).songs || [])) out.push({ src: '163', id: String(x.id), title: x.name, artist: (x.ar || []).map(a => a.name).join(' / '), album: (x.al || {}).name || '', dur: (x.dt || 0) / 1000, synced: null, has: null });
    })().catch(e => errs.push('网易云：' + e.message)));
    await Promise.all(jobs);
    const nt = norm(T2S(q.title)), na = norm(T2S(q.artist));
    for (const c of out) {
      let sc = q.dur ? Math.min(60, Math.abs(c.dur - q.dur)) : 0;
      const ct = norm(T2S(c.title)), ca = norm(T2S(c.artist));
      c.tok = ct === nt; if (ct !== nt) sc += ct.includes(nt) || nt.includes(ct) ? 4 : 20;
      if (na && !(ca.includes(na) || na.includes(ca))) sc += 8;
      if (c.synced === false) sc += 5; if (c.has === false && !c.instrumental) sc += 40;
      if (!kana(q.title) && /[一-鿿]/.test(q.title) && kana(c.title) > 0) sc += 30;
      c.score = Math.round(sc * 10) / 10;
    }
    out.sort((a, b) => a.score - b.score);
    return { items: out, err: errs.join('；') };
  };
  P.lyrFetch = async function (src, id) {
    if (src === 'lrclib') {
      let x = this.lyrTmp.get('lrclib:' + id);
      if (!x) x = J(await req('https://lrclib.net/api/get/' + encodeURIComponent(id), { headers: UA, timeout: 10000 }));
      return { text: x.syncedLyrics || x.plainLyrics || '', synced: !!x.syncedLyrics, instrumental: !!x.instrumental, trans: '', src: 'LRCLIB #' + x.id };
    }
    if (src === '163') {
      const j = J(await req('https://music.163.com/api/song/lyric?' + new URLSearchParams({ id, lv: 1, tv: -1 }), { headers: Object.assign({ Referer: 'https://music.163.com' }, UA), timeout: 10000 }));
      const t = ((j.lrc || {}).lyric || '').trim(), tr = ((j.tlyric || {}).lyric || '').trim();
      const body = t.split(/\r?\n/).filter(l => /\]\s*\S/.test(l) && !/\]\s*(作词|作曲|编曲|制作人|词|曲)\s*[:：]/.test(l));
      return { text: t, synced: /\[\d+:\d+/.test(t), instrumental: !!j.pureMusic || (!body.length && !!j.nolyric), trans: tr, src: '网易云 #' + id };
    }
    throw new Error('不认识的歌词来源');
  };
  P.lyrics = async function (s) {
    if (!s) return null;
    if (this.cfg.lyrics === false) return { found: false, off: true };
    const key = s.url; if (this.lyr.has(key)) return this.lyr.get(key);
    const q = this.lyrQuery(s), ch = this.lchoice[this.lyrKey(s)];
    let out = null, err = '';
    try {
      if (ch && ch.none) out = { found: false, hidden: true, q };
      else if (ch && ch.src) { const r = await this.lyrFetch(ch.src, ch.id); out = Object.assign({ found: true, chosen: true }, r); }
      else {
        const { items, err: e2 } = await this.lyrCands(q); err = e2;
        // 先找时长对得上的；没有的话，歌名完全一样、时长差 30 秒以内的也先用着（标出来，可以在“换歌词”里换）
        const ok1 = items.filter(c => !q.dur || Math.abs(c.dur - q.dur) <= 4).filter(c => c.score <= 30).slice(0, 4);
        const ok2 = items.filter(c => c.tok && q.dur && Math.abs(c.dur - q.dur) > 4 && Math.abs(c.dur - q.dur) <= 30).sort((a, b) => Math.abs(a.dur - q.dur) - Math.abs(b.dur - q.dur)).slice(0, 3);
        for (const c of ok1.concat(ok2)) {
          const r = await this.lyrFetch(c.src, c.id).catch(() => null);
          if (r && (r.text || r.instrumental)) { out = Object.assign({ found: true }, r); const dd = q.dur ? Math.round(c.dur - q.dur) : 0; if (Math.abs(dd) > 4) out.approx = dd; break; }
        }
        if (!out) out = { found: false, q, err };
      }
    } catch (e) { err = e.message; out = { found: false, q, err }; }
    if (out.text) out.text = T2S(out.text); if (out.trans) out.trans = T2S(out.trans);
    out.q = q;
    if (!err || out.found) { if (this.lyr.size > 300) this.lyr.delete(this.lyr.keys().next().value); this.lyr.set(key, out); }
    return out;
  };
  P.lyrUse = async function (s, b) {
    if (!s) throw new Error('现在没有在放的歌');
    const k = this.lyrKey(s);
    if (b.auto) delete this.lchoice[k];
    else if (b.none) this.lchoice[k] = { none: true, at: Date.now() };
    else { if (!b.src || !b.id) throw new Error('缺来源'); this.lchoice[k] = { src: String(b.src), id: String(b.id), at: Date.now() }; }
    fs.writeFileSync(this.lcFile, JSON.stringify(this.lchoice));
    this.lyr.delete(s.url);
    return this.lyrics(s);
  };

  /* ---------- 英文单词：查释义、加生词本 ---------- */
  P.wordLookup = async function (w) {
    w = String(w || '').trim().replace(/[^A-Za-z'\-]/g, '').slice(0, 40); if (!w) throw new Error('没有单词');
    if (this.wordHooks) { const r = this.wordHooks.lookup(w); if (r) return Object.assign({ word: w, from: '群星词典' }, r); }
    const H = this.cfg.hub;
    if (H && H.url) try { const r = J(await req(H.url.replace(/\/$/, '') + '/api/vocab/lookup?w=' + encodeURIComponent(w), { headers: { 'X-Starry-Token': H.token || '' }, timeout: 5000 })); if (r.ok && r.zh) return Object.assign({ word: w, from: '群星词典' }, r); } catch (e) {}
    const j = J(await req('https://dict.youdao.com/jsonapi_s?' + new URLSearchParams({ doctype: 'json', jsonversion: 4, q: w }), { headers: UA, timeout: 6000 }));
    const W = ((j.ec || {}).word) || {}, trs = W.trs || [];
    const zh = trs.map(t => ((t.pos || '') + ' ' + (t.tran || '')).trim()).join('\n') || ((j.web_trans || []).join('；'));
    return { word: w, ph: W.ukphone || W.usphone || '', zh, lemma: W.prototype && W.prototype.toLowerCase() !== w.toLowerCase() ? W.prototype : undefined, from: zh ? '有道' : '' };
  };
  P.wordAdd = async function (b) {
    const word = String(b.word || '').trim().slice(0, 40); if (!/^[A-Za-z][A-Za-z'\-]*$/.test(word)) throw new Error('只能加英文单词');
    const o = { word, context: String(b.context || '').slice(0, 300), src: '群星音乐' + (b.title ? ' · ' + b.title : ''), zh: b.zh || '' };
    if (this.wordHooks) return Object.assign({ to: '群星单词复习' }, this.wordHooks.add(o));
    const H = this.cfg.hub;
    if (H && H.url) { const r = J(await req(H.url.replace(/\/$/, '') + '/api/vocab/add', { method: 'POST', headers: { 'X-Starry-Token': H.token || '', 'Content-Type': 'application/json' }, body: JSON.stringify(o), timeout: 6000 })); if (!r.ok) throw new Error(r.error); return { to: '群星单词复习', added: r.added }; }
    const f = path.join(this.dataDir, 'music_words.json'); let L = []; try { L = JSON.parse(fs.readFileSync(f, 'utf8')); } catch (e) {}
    if (!L.some(x => x.word.toLowerCase() === word.toLowerCase())) L.push(Object.assign({ at: Date.now() }, o));
    fs.writeFileSync(f, JSON.stringify(L, null, 1)); return { to: '本机生词本（music_words.json）', added: true, total: L.length };
  };

  /* ---------- 听歌统计 ---------- */
  // 统计用的歌曲键：只按歌名（去掉序号、后缀、括号说明，繁转简）；“歌手 - 歌名”这种也拆开
  P.statKey = function (s) { const q = this.lyrQuery(s); return norm(T2S(q.title)) || norm(s.title); };
  P.dayKey = function (t) { const d = new Date((t || Date.now()) - 4 * 3600e3); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
  P.nowSong = function (p) {
    const u = this.st.uri; if (!u) return null;
    if (this.cur && this.cur.url === u) return this.cur;
    if (this.dms) { const x = this.dms.songs.find(y => y.url === u); if (x) return x; }
    const md = p ? (/<(?:\w+:)?TrackMetaData>([\s\S]*?)<\/(?:\w+:)?TrackMetaData>/.exec(p) || [])[1] : '';
    if (md) { const d = md.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&'); const g = t => ((new RegExp('<(?:\\w+:)?' + t + '[^>]*>([^<]*)<').exec(d)) || [])[1] || '';
      if (g('title')) return { title: g('title'), artist: g('artist') || g('creator'), album: g('album'), url: u, dur: this.st.dur }; }
    return null;
  };
  P.status = async function () {
    const r = await baseStatus.call(this);
    try { this.statTick(); } catch (e) {}
    return r;
  };
  P.watch = async function () {
    // 没人看、也不是我们在放的时候，每 20 秒轻轻问一次（为了记录直接在机器上点的歌）
    if (!(Date.now() - (this.seen || 0) < 30e3 || this.wantPlay || this.sleep)) { if (Date.now() - (this.st.at || 0) < 20e3) return; return this.status(); }
    return baseWatch.call(this);
  };
  P.statTick = function () {
    const now = Date.now(), playing = this.st.online && this.st.state === 'PLAYING' && !!this.st.uri;
    if (playing && this._sPlaying && this._sUri === this.st.uri && this._sLast) {
      if (this._sSong && this.cur && this.cur.url === this.st.uri && this._sSong !== this.cur) this._sSong = this.cur;
      const d = Math.min(now - this._sLast, 25e3), s = this._sSong || (this._sSong = this.nowSong());
      if (s) {
        const k = this.statKey(s), S = this.stats, day = this.dayKey(now), q = this.lyrQuery(s);
        const so = S.songs[k] || (S.songs[k] = { t: T2S(q.title), a: '', plays: 0, ms: 0, first: now });
        if (q.artist && !so.a) so.a = T2S(q.artist);
        so.url = s.url; so.last = now; so.ms += d; if (s.dur) so.dur = s.dur;
        const D = S.days[day] || (S.days[day] = { ms: 0, plays: 0, songs: {} }); D.ms += d;
        const ds = D.songs[k] || (D.songs[k] = [0, 0]); ds[0] += d;
        this._sCur = (this._sCur || 0) + d;
        const need = Math.min(30e3, (s.dur || 60) * 500);
        if (!this._sCounted && this._sCur >= need) { this._sCounted = true; so.plays++; D.plays++; ds[1]++; }
        this._sDirty = true;
      }
    }
    if (this.st.uri !== this._sUri || (this._sUri && this.st.rel < 3 && this._sRel > 10)) { this._sCur = 0; this._sCounted = false; this._sSong = null; }
    this._sUri = this.st.uri; this._sPlaying = playing; this._sLast = now; this._sRel = this.st.rel;
  };
  P.statSave = function () {
    if (!this._sDirty) return; this._sDirty = false;
    const S = this.stats, keys = Object.keys(S.days).sort(); while (keys.length > 400) delete S.days[keys.shift()];
    try { fs.writeFileSync(this.stFile + '.tmp', JSON.stringify(S)); fs.renameSync(this.stFile + '.tmp', this.stFile); } catch (e) {}
  };
  P.statsView = function (n) {
    const S = this.stats, today = this.dayKey(), D = S.days[today] || { ms: 0, plays: 0, songs: {} };
    const songOf = k => S.songs[k] || { t: k.split('|')[0], a: '' };
    const top = o => Object.entries(o).sort((a, b) => b[1][0] - a[1][0]);
    const days = []; for (let i = (n || 7) - 1; i >= 0; i--) { const k = this.dayKey(Date.now() - i * 864e5); const x = S.days[k]; days.push({ day: k, ms: x ? x.ms : 0, plays: x ? x.plays : 0 }); }
    const wk = {}, art = {};
    for (const d of days) { const x = S.days[d.day]; if (!x) continue; for (const [k, v] of Object.entries(x.songs)) { (wk[k] = wk[k] || [0, 0]); wk[k][0] += v[0]; wk[k][1] += v[1]; const a = songOf(k).a || '未知歌手'; art[a] = (art[a] || 0) + v[0]; } }
    return {
      today: { day: today, ms: D.ms, plays: D.plays, songs: top(D.songs).slice(0, 8).map(([k, v]) => ({ t: songOf(k).t, a: songOf(k).a, ms: v[0], plays: v[1] })) },
      days, weekTop: top(wk).slice(0, 10).map(([k, v]) => ({ t: songOf(k).t, a: songOf(k).a, ms: v[0], plays: v[1] })),
      artists: Object.entries(art).sort((a, b) => b[1] - a[1]).slice(0, 6).map(([a, ms]) => ({ a, ms })),
      total: { songs: Object.keys(S.songs).length, ms: Object.values(S.songs).reduce((t, x) => t + x.ms, 0) },
      now: this.st.state === 'PLAYING' && this._sSong ? (k => ({ t: songOf(k).t, a: songOf(k).a }))(this.statKey(this._sSong)) : null,
    };
  };

  /* ---------- 自动歌单 ---------- */
  P.autoList = async function (name) {
    const S = this.stats, now = Date.now(), L = [];
    if (name === '最近常听') {
      const sc = {}; for (let i = 0; i < 14; i++) { const x = S.days[this.dayKey(now - i * 864e5)]; if (x) for (const [k, v] of Object.entries(x.songs)) { sc[k] = sc[k] || [0, 0]; sc[k][0] += v[1]; sc[k][1] += v[0]; } }
      for (const [k] of Object.entries(sc).filter(([, v]) => v[0] >= 1).sort((a, b) => b[1][0] - a[1][0] || b[1][1] - a[1][1]).slice(0, 40)) L.push(k);
    } else if (name === '很久没听') {
      for (const [k] of Object.entries(S.songs).filter(([, x]) => x.plays >= 2 && now - x.last > 30 * 864e5).sort((a, b) => b[1].plays - a[1].plays).slice(0, 40)) L.push(k);
    }
    let dms = null; try { dms = await this.dmsAll(); } catch (e) {}
    const byUrl = new Map(), byKey = new Map(); if (dms) for (const x of dms.songs) { byUrl.set(x.url, x); const k = this.statKey(x); if (!byKey.has(k)) byKey.set(k, x); }
    let entries = L.map(k => { const so = S.songs[k]; const item = byUrl.get(so.url) || byKey.get(k) || null; return { path: '', file: so.t + (so.a ? ' - ' + so.a : ''), sd: false, item, plays: so.plays }; });
    let note = '';
    if (name === '很久没听' && entries.length < 15 && dms) {   // 数据还少：补一些从来没在群星里放过的歌
      const heard = new Set(Object.keys(S.songs)), pool = dms.songs.filter(x => !heard.has(this.statKey(x)) && !/\.(dsf|dff)$/i.test(x.title));
      for (let i = 0; i < 30 && pool.length; i++) { const x = pool.splice(Math.floor(Math.random() * pool.length), 1)[0]; entries.push({ path: '', file: x.title, sd: false, item: x, fill: true }); }
      note = '听歌记录还不到一个月，后面补了一些还没在群星里放过的歌';
    }
    if (name === '最近常听' && !entries.length) note = '最近两周还没有听歌记录（从现在开始记）';
    try { const pidx = await this.pathIndex(); for (const e of entries) if (e.item) e.path = pidx.byUrl.get(e.item.url) || ''; } catch (e) {}
    return { name, auto: true, from: 'auto', err: '', note, dirty: false, sig: entries.map(e => e.file).join('|').length + ':' + entries.length, entries, playable: entries.filter(e => e.item).length };
  };
  P.plList = async function () {
    const r = await basePlList.call(this);
    const items = r.items.filter(x => !AUTO.includes(x.name));
    return Object.assign(r, { items: AUTO.map(n => ({ name: n, auto: true, onCa: (r.items.find(x => x.name === n) || {}).onCa || false })).concat(items) });
  };
  P.plGet = async function (name, wait) { if (AUTO.includes(String(name))) return this.autoList(String(name)); return basePlGet.call(this, name, wait); };
  // 每天把自动歌单写到播放器的 /Playlists 里一次（机器上也能直接点）
  P.autoListsTick = async function () {
    if (!this.set.autoLists) return;
    const day = this.dayKey(); if (this._autoDay === day) return;
    for (const n of AUTO) { const g = await this.autoList(n); const paths = g.entries.filter(e => e.path && !e.fill).map(e => e.path); if (paths.length) await this.plSave(n, paths); }
    this._autoDay = day;
  };

  /* ---------- 接口 ---------- */
  const baseApi = P.api;
  P.api = async function (u, body) {
    const p = u.pathname.replace(/^\/api\/music\/?/, ''), Q = k => u.searchParams.get(k);
    if (p === 'settings') { this.seen = Date.now(); return this.settings(body); }
    if (p === 'lyrics/search') { const s = this.cur || {}; const q0 = this.lyrQuery(s); return this.lyrCands({ title: Q('title') || q0.title, artist: Q('artist') != null ? Q('artist') : q0.artist, dur: +Q('dur') || q0.dur }); }
    if (p === 'lyrics/use') return this.lyrUse(this.cur, body || {});
    if (p === 'word/lookup') return this.wordLookup(Q('w'));
    if (p === 'word/add') return this.wordAdd(Object.assign({ title: this.cur ? T2S(this.cur.title) : '' }, body));
    if (p === 'stats') return this.statsView(+Q('days') || 7);
    return baseApi.call(this, u, body);
  };
};
