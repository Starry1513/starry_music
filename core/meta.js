// 群星音乐 · 歌曲信息补全（歌名 / 歌手 / 专辑 / 曲序 / 年份 / 封面）+ 专辑缺歌检查。
// 来源：网易云（中文歌最全，有专辑曲目表和封面）、Apple Music 目录（iTunes Search API，外文歌、高清封面）。
// 流程：选歌 → 查（每首给“改前 / 改后”和其他候选）→ 你确认 → 写进文件（电脑上用 tools/tagfix.py，音频数据不动，原标签留底可还原）。
// 歌词不写进文件：播放时在线查（见 extras.js 的换歌词）。
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const { spawn } = require('child_process');

function req(url, { method = 'GET', headers = {}, body = null, timeout = 10000 } = {}) {
  return new Promise((ok, no) => {
    const u = new URL(url), lib = u.protocol === 'https:' ? https : http;
    const r = lib.request(u, { method, headers, timeout }, res => { const b = []; res.on('data', x => b.push(x)); res.on('end', () => ok({ status: res.statusCode, body: Buffer.concat(b) })); });
    r.on('timeout', () => r.destroy(new Error('超时'))); r.on('error', no); if (body) r.write(body); r.end();
  });
}
const J = r => JSON.parse(r.body.toString('utf8'));
const UA = { 'User-Agent': 'Mozilla/5.0 (starry-music; personal library tagger)' };
const NE = Object.assign({ Referer: 'https://music.163.com' }, UA);
const kana = t => (String(t || '').match(/[぀-ヿ]/g) || []).length;
const SEP = /\s*(?:\s&\s|&|,\s*|，|\/|×|、|;|\sx\s|\sfeat\.?\s|\sft\.?\s)\s*/i;
const AUDIO = /\.(flac|mp3|m4a|ogg|wav|aac|ape|dsf|dff|wma|aiff?)$/i;
const WRITABLE = /\.(flac|mp3|m4a|ogg|wav)$/i;
// 这些“专辑名”其实是文件夹 / 歌单名（下载软件常把歌单名写进专辑）。自己的歌单名在配置 player.playlistAlbums 里加
const PLAYLISTY0 = ['待分类', '未分类', '纯音乐', '歌手', 'music', 'download', 'downloads', 'favorite songs', 'my favorite', '我喜欢的音乐', '其他', 'unknown album', '<unknown>'];

module.exports = function extend(MusicCore, T2S) {
  const P = MusicCore.prototype, baseInit = P.init, baseApi = P.api;
  const S = s => (kana(s) > 2 ? String(s || '') : T2S(String(s || '')));
  const norm = t => S(t).toLowerCase().replace(/[\s'’"“”·・.,，。!！?？()（）\[\]【】\-_~&/:：]+/g, '');
  const core = t => norm(String(t || '').replace(/[（(\[【][^）)\]】]*[）)\]】]/g, ''));

  P.init = async function (opts) {
    await baseInit.call(this, opts);
    this.aliasFile = path.join(this.dataDir, 'music_artist_alias.json');
    let base = {}; try { base = JSON.parse(fs.readFileSync(path.join(__dirname, 'artist_alias.json'), 'utf8')); } catch (e) {}
    let mine = {}; try { mine = JSON.parse(fs.readFileSync(this.aliasFile, 'utf8')); } catch (e) {}
    this.alias = Object.assign({}, base, mine); this.aliasMine = mine;
    this.metaFile = path.join(this.dataDir, 'music_meta_applied.json');
    try { this.metaApplied = JSON.parse(fs.readFileSync(this.metaFile, 'utf8')); } catch (e) { this.metaApplied = {}; }
    this.albumFile = path.join(this.dataDir, 'music_album_check.json');
    try { this.albumCheck = JSON.parse(fs.readFileSync(this.albumFile, 'utf8')); } catch (e) { this.albumCheck = {}; }
    this.imports = [];   // 最近传上去的歌（路径）
    this.playlisty = new Set(PLAYLISTY0.concat(this.cfg.playlistAlbums || []).map(x => String(x).toLowerCase()));
  };

  /* ---------- 歌手名统一 ---------- */
  P.canonName = function (n) {
    const k = String(n || '').trim(), lk = k.toLowerCase();
    const hit = this.alias[lk] || this.alias[lk.replace(/[\s.\-_']/g, '')];
    if (hit) return hit;
    return /[一-鿿]/.test(k) ? S(k) : k;
  };
  P.canonArtist = function (a, max) {
    const out = [];
    for (const n of String(a || '').replace(/<unknown>/i, '').split(SEP)) {
      if (!n.trim()) continue;
      for (const x of this.canonName(n).split(SEP)) if (x && !out.some(y => y.toLowerCase() === x.toLowerCase())) out.push(x);
    }
    return out.slice(0, max || 4).join(' & ');
  };
  P.sameArtist = function (a, b) {
    const A = new Set(this.canonArtist(a, 9).split(' & ').map(norm).filter(Boolean));
    return this.canonArtist(b, 9).split(' & ').map(norm).some(x => x && A.has(x));
  };

  /* ---------- 来源 ---------- */
  P.srcNetease = async function (q) {
    const r = await req('https://music.163.com/api/cloudsearch/pc?' + new URLSearchParams({ s: (q.title + ' ' + (q.artist || '')).trim(), type: 1, limit: 12 }), { method: 'POST', headers: NE });
    return (((J(r).result || {}).songs) || []).map(x => ({
      src: '163', id: String(x.id), title: x.name, artist: (x.ar || []).map(a => a.name).join(' & '), album: (x.al || {}).name || '', albumId: String((x.al || {}).id || ''),
      cover: (x.al || {}).picUrl ? x.al.picUrl.replace(/^http:/, 'https:') + '?param=800y800' : '', track: x.no || 0, year: x.publishTime ? new Date(x.publishTime).getFullYear() : 0, dur: (x.dt || 0) / 1000,
    }));
  };
  P.srcItunes = async function (q) {
    const term = (q.title + ' ' + (q.artist || '')).trim();
    const one = async country => { const r = await req('https://itunes.apple.com/search?' + new URLSearchParams({ term, entity: 'song', limit: 10, country }), { headers: UA }); return (J(r).results || []); };
    let L = await one('US'); if (!L.length && /[぀-鿿]/.test(term)) L = L.concat(await one('JP').catch(() => []), await one('TW').catch(() => []));
    return L.map(x => ({
      src: 'apple', id: String(x.trackId), title: x.trackName, artist: x.artistName, album: x.collectionName || '', albumId: String(x.collectionId || ''),
      cover: (x.artworkUrl100 || '').replace('100x100bb', '1000x1000bb'), track: x.trackNumber || 0, year: x.releaseDate ? +x.releaseDate.slice(0, 4) : 0, dur: (x.trackTimeMillis || 0) / 1000,
    }));
  };

  /* ---------- 一首歌：现在的信息 + 建议 ---------- */
  P.fileGuess = function (p) {
    let f = p.split('/').pop().replace(AUDIO, '').replace(/^\s*\d{1,3}\s*[.\-_、 ]\s*/, '');
    let m = /^(.+?)-(.+?)-\d{5,}$/.exec(f); if (m) return { title: m[1].replace(/_/g, ' ').trim(), artist: m[2].replace(/_/g, ' / ').trim() };
    m = /^(.+?) - (.+)$/.exec(f); if (m) return { title: m[2].trim(), artist: m[1].trim() };
    return { title: f.trim(), artist: '' };
  };
  // 曲库里现在显示的信息（AK Connect 的“全部歌曲”）；还没扫描进曲库的新文件按文件名猜
  P.curInfo = async function (p) {
    let dms = null, pidx = null; try { dms = await this.dmsAll(); } catch (e) {} try { pidx = await this.pathIndex(); } catch (e) {}
    const fe = pidx && pidx.byPath.get(p.toLowerCase()), d = fe && dms ? dms.songs.find(x => x.url === fe.url) : null;
    const folder = p.split('/').slice(-2, -1)[0] || '';
    if (d) return { indexed: true, title: d.title, artist: /unknown/i.test(d.artist || '') ? '' : d.artist, album: d.album === folder || this.playlisty.has(String(d.album).toLowerCase()) ? '' : d.album, albumRaw: d.album, art: d.art ? '/api/music/art?u=' + encodeURIComponent(d.art) : '', dur: d.dur || 0, size: d.size, url: d.url };
    const g = this.fileGuess(p);
    return { indexed: false, title: g.title, artist: g.artist, album: '', albumRaw: '', art: '', dur: 0, size: 0 };
  };
  P.problems = function (p, c) {
    const L = [], f = p.split('/').pop();
    if (!c.artist) L.push('没有歌手');
    if (!c.album) L.push(c.albumRaw ? '专辑是歌单名' : '没有专辑');
    if (c.title === f || AUDIO.test(c.title) || /^\d{1,3}\s*[.\-_、 ]/.test(c.title)) L.push('歌名是文件名');
    if (c.artist) { const A = c.artist.split(SEP).map(x => x.trim().toLowerCase()).filter(Boolean).sort().join('|'), B = this.canonArtist(c.artist, 9).split(' & ').map(x => x.trim().toLowerCase()).sort().join('|'); if (A !== B) L.push('歌手写法不统一'); }
    if (S(c.title) !== c.title) L.push('繁体');
    return L;
  };
  P.metaPropose = async function (p) {
    const c = await this.curInfo(p);
    const q = { title: String(c.title || '').replace(AUDIO, '').replace(/^\s*\d{1,3}\s*[.\-_、 ]\s*/, ''), artist: c.artist || this.fileGuess(p).artist, dur: c.dur };
    const qs = { title: q.title.replace(/[（(\[【][^）)\]】]*[）)\]】]/g, '').trim() || q.title, artist: (q.artist || '').split(SEP)[0] || '' };
    const [a, b] = await Promise.all([this.srcNetease(qs).catch(e => ({ err: e.message })), this.srcItunes(qs).catch(e => ({ err: e.message }))]);
    const errs = [a.err && '网易云：' + a.err, b.err && 'Apple：' + b.err].filter(Boolean);
    const cands = [].concat(Array.isArray(a) ? a : [], Array.isArray(b) ? b : []);
    const nt = norm(q.title), ct0 = core(q.title);
    for (const x of cands) {
      const tn = norm(x.title), tc = core(x.title);
      const tm = tn === nt ? 2 : (tc && tc === ct0) ? 1 : (tn.includes(ct0) || ct0.includes(tc)) && tc.length > 1 ? 0.5 : 0;
      const am = q.artist ? this.sameArtist(q.artist, x.artist) : null;
      const d = q.dur && x.dur ? Math.abs(x.dur - q.dur) : null;
      let sc = (2 - tm) * 10 + (am === false ? 12 : am === null ? 4 : 0) + (d == null ? 3 : Math.min(30, d * 2)) + (x.src === 'apple' && /[一-鿿]/.test(q.title) ? 2 : 0);
      if (!kana(q.title) && kana(x.title) > 0) sc += 20;
      const COMP = /精选|极品|合集|选辑|全记录|金曲|经典|greatest|best of|collection|hits|anthology|essential|最爱|珍藏|纪念|典藏|热歌|榜/i;
      if (COMP.test(x.album) && !COMP.test(q.title)) sc += 4;   // 精选集不如原专辑
      const VER = /live|现场|演唱会|伴奏|inst|remix|dj|cover|翻唱|karaoke|demo|钢琴版|piano ver/i;
      if (VER.test(x.title + ' ' + x.album) && !VER.test(q.title)) sc += 6;
      x.score = Math.round(sc * 10) / 10; x.tm = tm; x.am = am; x.d = d == null ? null : Math.round(d);
    }
    // 同一首歌（歌名、歌手都对上）出现在好几张专辑：越早发行越可能是原专辑
    const ys = cands.filter(x => x.tm >= 1 && x.am !== false && x.year).map(x => x.year), y0 = ys.length ? Math.min(...ys) : 0;
    if (y0) for (const x of cands) if (x.tm >= 1 && x.am !== false && x.year) x.score = Math.round((x.score + Math.min(4, (x.year - y0) / 4)) * 10) / 10;
    cands.sort((x, y) => x.score - y.score);
    const best = cands[0] || null;
    let conf = !best ? 'none' : best.tm >= 1 && best.am !== false && (best.d == null || best.d <= 3) && best.score <= 10 ? 'high' : best.tm >= 0.5 && best.score <= 26 ? 'mid' : 'low';
    if (conf === 'high' && !q.artist && best.d == null) conf = 'mid';
    if (best && best.am === false) conf = 'low';   // 歌手对不上：多半是翻唱，不改   // 歌手、时长都不知道：只按歌名对，拿不准
    return { path: p, cur: c, problems: this.problems(p, c), q, cands: cands.slice(0, 12), pick: best && conf !== 'low' ? 0 : -1, conf, err: errs.join('；'), writable: WRITABLE.test(p), proposed: best && conf !== 'low' ? this.proposal(c, best) : this.proposal(c, null) };
  };
  // 根据候选生成“改后”。歌名：候选歌名（繁转简）；拿不准就保留现在的（去掉序号、扩展名）
  P.proposal = function (c, x) {
    const keepTitle = S(String(c.title || '').replace(AUDIO, '').replace(/^\s*\d{1,3}\s*[.\-_、 ]\s*/, '')).trim();
    if (!x) return { title: keepTitle, artist: c.artist ? this.canonArtist(c.artist) : '', album: c.album ? S(c.album) : '', track: 0, year: 0, cover: '' };
    const sameT = core(x.title) === core(keepTitle);
    return { title: sameT && /[（(\[【]/.test(keepTitle) && !/[（(\[【]/.test(x.title) ? keepTitle : S(x.title), artist: this.canonArtist(x.artist, 3), album: S(x.album), track: x.track || 0, year: x.year || 0, cover: x.cover || '', src: x.src, srcId: x.id, albumId: x.albumId };
  };

  /* ---------- 选歌：新导入 / 缺信息 / 某个文件夹 ---------- */
  P.metaList = async function (kind, dir) {
    let dms = null, pidx = null; try { dms = await this.dmsAll(); } catch (e) {} try { pidx = await this.pathIndex(); } catch (e) {}
    const byUrl = new Map(); if (dms) for (const x of dms.songs) byUrl.set(x.url, x);
    let paths = [];
    if (kind === 'imports') paths = this.imports.slice(-200).reverse();
    else if (kind === 'dir') {
      const d = this.cleanPath(dir || '/Music/待分类');
      const L = await this.withFtp(c => c.list(d));
      paths = L.filter(e => !e.dir && AUDIO.test(e.name)).map(e => this.pre() + (d === '/' ? '' : d) + '/' + e.name);
    } else if (pidx) {
      const all = []; for (const [u, p] of pidx.byUrl) all.push(p);
      paths = all.filter(p => p.startsWith(this.pre() + '/Music/'));
    }
    const out = [];
    for (const p of paths) {
      const c = await this.curInfo(p), pr = this.problems(p, c), ap = this.metaApplied[p];
      if (kind === 'missing' && (!pr.length || ap)) continue;
      out.push({ path: p, cur: c, problems: pr, applied: ap ? ap.at : 0, writable: WRITABLE.test(p) });
      if (out.length >= 1500) break;
    }
    return { kind, items: out };
  };

  /* ---------- 写进文件（电脑上跑 tools/tagfix.py） ---------- */
  P.toolsDir = function () { return this.cfg.toolsDir || path.join(__dirname, '..', 'tools'); };
  P.python = function () {
    if (this.cfg.python) return this.cfg.python;
    return process.platform === 'win32' ? 'py' : 'python3';   // 需要装 mutagen：pip install -r tools/requirements.txt
  };
  P.metaApply = async function (items) {
    if (this.tagProc) throw new Error('上一批还在写，等它写完');
    const plan = [];
    for (const it of items || []) {
      const p = String(it.path || ''); if (!p.startsWith(this.pre() + '/') || !WRITABLE.test(p)) continue;
      const n = it.new || {}, o = {};
      for (const k of ['title', 'artist', 'album']) if (n[k] != null && String(n[k]).trim()) o[k] = String(n[k]).trim();
      if (+n.track > 0) o.track = String(+n.track); if (+n.year > 1800) o.year = String(+n.year);
      if (/^https:\/\//.test(n.cover || '')) o.cover = n.cover;
      if (Object.keys(o).length) plan.push({ path: p, new: o });
      // 记下你认可的歌手写法：以后同一个名字自动改成这样
      if (it.from && it.from.artist && o.artist && it.from.artist !== o.artist && !/[&,/、]/.test(it.from.artist) && !/&/.test(o.artist)) { this.aliasMine[it.from.artist.toLowerCase()] = o.artist; this.alias[it.from.artist.toLowerCase()] = o.artist; }
    }
    if (!plan.length) throw new Error('没有要改的');
    fs.writeFileSync(this.aliasFile, JSON.stringify(this.aliasMine, null, 1));
    const T = this.toolsDir(), name = 'plan_ui_' + Date.now() + '.json';
    fs.writeFileSync(path.join(T, name), JSON.stringify(plan));
    const sf = path.join(T, 'tag_status.json'); try { fs.renameSync(sf, sf + '.' + Date.now()); } catch (e) {}
    try { fs.renameSync(path.join(T, 'tag_done.txt'), path.join(T, 'tag_done.' + Date.now() + '.txt')); } catch (e) {}
    const out = fs.openSync(path.join(T, 'tagfix.log'), 'a');
    const pr = spawn(this.python(), [path.join(T, 'tagfix.py'), path.join(T, name)].concat(this.ip ? ['--host', this.ip] : []), { cwd: T, windowsHide: true, stdio: ['ignore', out, out], env: Object.assign({}, process.env, { PYTHONIOENCODING: 'utf-8' }) });
    this.tagProc = pr; this.tagPlan = plan;
    pr.on('error', e => { this.tagErr = e.message; if (this.tagProc === pr) this.tagProc = null; });
    pr.on('exit', c => {
      if (this.tagProc === pr) this.tagProc = null; this.tagExit = c;
      // 写成功的记下来（机器的曲库要重新扫描才看得到）
      let done = []; try { done = fs.readFileSync(path.join(T, 'tag_done.txt'), 'utf8').split('\n').filter(Boolean); } catch (e) {}
      for (const x of plan) if (done.includes(x.path)) this.metaApplied[x.path] = { at: Date.now(), new: x.new };
      try { fs.writeFileSync(this.metaFile, JSON.stringify(this.metaApplied)); } catch (e) {}
    });
    return { started: plan.length, plan: name };
  };
  P.metaJob = function () {
    let st = null; try { st = JSON.parse(fs.readFileSync(path.join(this.toolsDir(), 'tag_status.json'), 'utf8')); } catch (e) {}
    return { running: !!this.tagProc, exit: this.tagExit, err: this.tagErr || '', status: st };
  };

  /* ---------- 专辑缺哪几首 ---------- */
  P.metaAlbums = async function () {
    const dms = await this.dmsAll(), m = new Map();
    for (const x of dms.songs) {
      const al = String(x.album || '').trim(); if (!al || this.playlisty.has(al.toLowerCase())) continue;
      const k = norm(al); const g = m.get(k) || { album: S(al), artists: {}, songs: [] };
      const a = this.canonArtist(x.artist) || '未知'; g.artists[a] = (g.artists[a] || 0) + 1; g.songs.push(S(String(x.title).replace(/^\s*\d{1,3}\s*[.\-_、 ]\s*/, '')));
      m.set(k, g);
    }
    const out = [];
    for (const [k, g] of m) {
      if (g.songs.length > 80) continue;   // 歌单当专辑名的那种
      const artist = Object.entries(g.artists).sort((a, b) => b[1] - a[1])[0][0], ck = this.albumCheck[k];
      const mix = Object.keys(g.artists).length >= 4 && g.songs.length >= 6;   // 好多歌手混在一起：多半是歌单名当了专辑名
      out.push({ key: k, album: g.album, artist, have: g.songs.length, mix, total: ck ? ck.total : null, missing: ck ? ck.missing.length : null, at: ck ? ck.at : 0 });
    }
    out.sort((a, b) => (a.mix - b.mix) || (b.missing || 0) - (a.missing || 0) || b.have - a.have);
    return { items: out };
  };
  P.metaAlbum = async function (album, artist) {
    const dms = await this.dmsAll(), k = norm(album);
    const mine = dms.songs.filter(x => norm(x.album) === k).map(x => ({ title: S(String(x.title).replace(AUDIO, '').replace(/^\s*\d{1,3}\s*[.\-_、 ]\s*/, '')), artist: x.artist, url: x.url }));
    // 网易云：搜专辑 → 取曲目表；对不上再试 Apple
    let best = null, tracks = [], src = '', cover = '';
    try {
      const r = J(await req('https://music.163.com/api/cloudsearch/pc?' + new URLSearchParams({ s: (album + ' ' + (artist || '')).trim(), type: 10, limit: 8 }), { method: 'POST', headers: NE }));
      for (const a of ((r.result || {}).albums || [])) {
        const sc = (norm(a.name) === k ? 0 : core(a.name) === core(album) ? 2 : 10) + (artist && this.sameArtist(artist, (a.artists || [a.artist || {}]).map(x => x.name).join(' & ')) ? 0 : 5);
        if (!best || sc < best.sc) best = { sc, id: a.id, name: a.name, pic: a.picUrl };
      }
      if (best && best.sc <= 7) {
        const d = J(await req('https://music.163.com/api/v1/album/' + best.id, { headers: NE }));
        tracks = (d.songs || []).map(s => ({ no: s.no, title: S(s.name), artist: (s.ar || []).map(a => a.name).join(' & '), dur: (s.dt || 0) / 1000 }));
        src = '网易云'; cover = (best.pic || '').replace(/^http:/, 'https:');
      }
    } catch (e) {}
    if (!tracks.length) try {
      const r = J(await req('https://itunes.apple.com/search?' + new URLSearchParams({ term: (album + ' ' + (artist || '')).trim(), entity: 'album', limit: 5, country: 'US' }), { headers: UA }));
      const a = (r.results || []).find(x => core(x.collectionName) === core(album)) || null;
      if (a) {
        const d = J(await req('https://itunes.apple.com/lookup?' + new URLSearchParams({ id: a.collectionId, entity: 'song' }), { headers: UA }));
        tracks = (d.results || []).filter(x => x.wrapperType === 'track').map(x => ({ no: x.trackNumber, disc: x.discNumber, title: x.trackName, artist: x.artistName, dur: (x.trackTimeMillis || 0) / 1000 }));
        src = 'Apple Music'; cover = (a.artworkUrl100 || '').replace('100x100bb', '600x600bb');
      }
    } catch (e) {}
    const have = new Set(mine.map(x => core(x.title)));
    const list = tracks.map(t => Object.assign({}, t, { have: have.has(core(t.title)) }));
    const missing = list.filter(t => !t.have);
    const extra = mine.filter(x => !tracks.some(t => core(t.title) === core(x.title)));
    if (tracks.length) { this.albumCheck[k] = { at: Date.now(), total: tracks.length, missing: missing.map(t => t.title), src }; try { fs.writeFileSync(this.albumFile, JSON.stringify(this.albumCheck)); } catch (e) {} }
    return { album, artist, src, cover, found: !!tracks.length, tracks: list, missing, extra, mine };
  };

  /* ---------- 接口 ---------- */
  P.api = async function (u, body) {
    const p = u.pathname.replace(/^\/api\/music\/?/, ''), Q = k => u.searchParams.get(k);
    if (p === 'meta/list') { this.seen = Date.now(); return this.metaList(Q('kind') || 'missing', Q('dir')); }
    if (p === 'meta/propose') {
      const paths = [].concat(body.paths || []).slice(0, 60), out = [];
      let i = 0; const run = async () => { while (i < paths.length) { const p0 = paths[i++]; out.push(await this.metaPropose(p0).catch(e => ({ path: p0, err: e.message, cands: [], cur: {}, proposed: {} }))); } };
      await Promise.all([run(), run(), run()]);
      return { items: paths.map(x => out.find(o => o.path === x)) };
    }
    if (p === 'meta/search') { const q = { title: Q('title') || '', artist: Q('artist') || '' }; const [a, b] = await Promise.all([this.srcNetease(q).catch(() => []), this.srcItunes(q).catch(() => [])]); return { items: a.concat(b) }; }
    if (p === 'meta/apply') return this.metaApply(body.items);
    if (p === 'meta/job') return this.metaJob();
    if (p === 'meta/stop') { if (this.tagProc) this.tagProc.kill(); return { stopped: true }; }
    if (p === 'meta/albums') return this.metaAlbums();
    if (p === 'meta/album') return this.metaAlbum(Q('album') || '', Q('artist') || '');
    return baseApi.call(this, u, body);
  };
};
