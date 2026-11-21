// 群星音乐 · 导入文件夹（比如网易云下载、解码后放歌的文件夹）
// 盯着电脑上的几个文件夹：出现新的音乐文件 → 列出来（可设成自动）→ 先在本机补好歌名 / 歌手 / 专辑 / 封面
// → 用 FTP 传到播放器。先改再传，每首只传一趟。加密格式（.ncm 等）播放器放不了，列出来但不传。
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');

const AUDIO = /\.(flac|mp3|m4a|ogg|wav|aac|ape|aiff?|dsf|dff|wma)$/i;
const WRITABLE = /\.(flac|mp3|m4a|ogg|wav)$/i;
const ARCHIVE = /\.(zip|7z|rar)$/i;
const LOCKED = /\.(ncm|uc!|kgm|kgma|vpr|qmc\w*|mflac\w*|mgg\w*|kwm|tm\d|bkc\w*|xm)$/i;   // 各家 App 的加密格式
const IN_DEF = { dirs: [], auto: false, target: '/Music/待分类', tag: true, minConf: 'high' };

module.exports = function extend(MusicCore, T2S) {
  const P = MusicCore.prototype, baseInit = P.init, baseApi = P.api;

  P.init = async function (opts) {
    await baseInit.call(this, opts);
    this.inFile = path.join(this.dataDir, 'music_inbox.json');
    let j = {}; try { j = JSON.parse(fs.readFileSync(this.inFile, 'utf8')); } catch (e) {}
    this.inCfg = Object.assign({}, IN_DEF, (this.cfg.inbox || {}), j.cfg || {});
    this.inFiles = j.files || {};      // 本机路径 → { size, mtime, state: new|up|done|err|locked|skip, at, err, to }
    this.inBusy = false;
    setTimeout(() => this.inScan().catch(() => {}), 5000);
    this.inT = setInterval(() => this.inScan().catch(() => {}), 30e3);
  };
  P.inSave = function () { try { fs.writeFileSync(this.inFile, JSON.stringify({ cfg: this.inCfg, files: this.inFiles })); } catch (e) {} };

  /* ---------- 扫文件夹 ---------- */
  P.inScan = async function () {
    this.inLast = Date.now();
    const found = [];
    const walk = (d, lv) => {
      let L = []; try { L = fs.readdirSync(d, { withFileTypes: true }); } catch (e) { return; }
      for (const e of L) {
        const p = path.join(d, e.name);
        if (e.isDirectory()) { if (lv < 3 && !e.name.startsWith('.')) walk(p, lv + 1); }
        else if (AUDIO.test(e.name) || LOCKED.test(e.name) || ARCHIVE.test(e.name)) found.push(p);
      }
    };
    for (const d of this.inCfg.dirs || []) if (d) walk(d, 0);
    let changed = false;
    for (const p of found) {
      let st; try { st = fs.statSync(p); } catch (e) { continue; }
      const x = this.inFiles[p];
      if (x && x.size === st.size && x.mtime === st.mtimeMs) { if (x.state === 'wait') { x.state = LOCKED.test(p) ? 'locked' : ARCHIVE.test(p) ? 'zip' : 'new'; changed = true; } continue; }
      // 第一次看到、或者还在写：先记成 wait，下一轮大小不变了才算下完
      // 刚设好文件夹时里面原有的歌记成“原来就有”，不会被自动传；要传可以手动勾
      this.inFiles[p] = { size: st.size, mtime: st.mtimeMs, state: this.inBaseline ? 'old' : 'wait', at: Date.now() }; changed = true;
    }
    for (const p of Object.keys(this.inFiles)) if (!found.includes(p) && ['new', 'wait', 'locked', 'old', 'zip'].includes(this.inFiles[p].state)) { delete this.inFiles[p]; changed = true; }
    this.inBaseline = false;
    if (changed) this.inSave();
    // 压缩包：解到同一个文件夹下的「_解压/包名/」，解出来的歌下一轮当新歌处理（自动传开着就会自动传）
    for (const [p, x] of Object.entries(this.inFiles)) if (x.state === 'zip' && (this.inCfg.unzip !== false)) {
      x.state = 'unzipping'; this.inSave();
      const dst = path.join(path.dirname(p), '_解压', path.basename(p).replace(/\.(zip|7z|rar)$/i, ''));
      try { const r = await this.pyJson2('unpack.py', [p, dst]); x.state = 'unzipped'; x.to = dst; x.n = (r.files || []).length; x.err = x.n ? '' : '压缩包里没有歌'; }
      catch (e) { x.state = 'err'; x.err = '解压失败：' + e.message; }
      this.inSave(); changed = true;
    }
    if (this.inCfg.auto && !this.inBusy) { const L = Object.entries(this.inFiles).filter(([, x]) => x.state === 'new').map(([p]) => p); if (L.length) this.inUpload(L).catch(() => {}); }
  };
  P.inView = function () {
    const items = Object.entries(this.inFiles).map(([p, x]) => ({ path: p, name: path.basename(p), dir: path.dirname(p), size: x.size, state: x.state, at: x.at, err: x.err || '', to: x.to || '', tagged: x.tagged || null, why: x.why || '', step: x.step || '' }))
      .sort((a, b) => b.at - a.at).slice(0, 500);
    return { cfg: this.inCfg, busy: this.inBusy, step: this.inStep || '', lastScan: this.inLast || 0, now: Date.now(), items, counts: items.reduce((c, x) => (c[x.state] = (c[x.state] || 0) + 1, c), {}) };
  };

  P.pyJson2 = function (script, args) {
    return new Promise((ok, no) => {
      const pr = spawn(this.python(), [path.join(this.toolsDir(), script)].concat(args), { windowsHide: true, env: Object.assign({}, process.env, { PYTHONIOENCODING: 'utf-8' }) });
      let out = '', err = ''; pr.stdout.on('data', d => out += d); pr.stderr.on('data', d => err += d);
      pr.on('error', e => no(new Error('Python 没跑起来：' + e.message)));
      pr.on('close', c => { if (c) return no(new Error((err.trim().split('\n').pop() || 'Python 出错').slice(0, 200))); try { ok(JSON.parse(out)); } catch (e) { no(new Error('Python 回复看不懂')); } });
    });
  };
  /* ---------- 本机读 / 写标签（tools/tagfix.py 的本机模式） ---------- */
  P.pyJson = function (mode, arg) {
    return new Promise((ok, no) => {
      const T = this.toolsDir(), f = path.join(os.tmpdir(), 'sm_' + mode + '_' + Date.now() + '.json');
      fs.writeFileSync(f, JSON.stringify(arg));
      const pr = spawn(this.python(), [path.join(T, 'tagfix.py'), mode, f], { cwd: T, windowsHide: true, env: Object.assign({}, process.env, { PYTHONIOENCODING: 'utf-8' }) });
      let out = '', err = ''; pr.stdout.on('data', d => out += d); pr.stderr.on('data', d => err += d);
      pr.on('error', e => no(new Error('Python 没跑起来：' + e.message)));
      pr.on('close', c => { try { fs.unlinkSync(f); } catch (e) {} if (c) return no(new Error((err.trim().split('\n').pop() || 'Python 出错') + '（需要 pip install -r tools/requirements.txt）')); try { ok(JSON.parse(out)); } catch (e) { no(new Error('Python 回复看不懂')); } });
    });
  };

  /* ---------- 传到播放器（先补信息） ---------- */
  P.inUpload = async function (paths, opt) {
    if (this.inBusy) throw new Error('上一批还在传');
    opt = opt || {};
    const tag = opt.tag != null ? !!opt.tag : this.inCfg.tag, target = this.cleanPath(opt.target || this.inCfg.target || '/Music/待分类');
    const L = paths.filter(p => this.inFiles[p] && ['new', 'err', 'skip', 'old'].includes(this.inFiles[p].state));
    if (!L.length) throw new Error('没有可传的');
    this.inBusy = true;
    (async () => {
      try {
        // 1. 读本机标签，查信息；把握大的先写进本机的临时副本
        let plan = {};
        if (tag) {
          this.inStep = '读标签、查资料';
          let tags = [], tagErr = ''; try { tags = await this.pyJson('--local-read', L.filter(p => WRITABLE.test(p))); } catch (e) { tagErr = '没能补信息（原样传）：' + e.message; }
          this.inTagErr = tagErr;
          for (const t of tags) {
            if (t.err) continue;
            const g = this.fileGuess(t.path), tg = t.tags || {};
            const cur = { indexed: true, title: tg.title || g.title, artist: tg.artist || g.artist, album: tg.album || '', albumRaw: tg.album || '', art: '', dur: t.dur || 0 };
            const r = await this.metaPropose(t.path, cur).catch(e => ({ conf: 'none', err: e.message }));
            this.inFiles[t.path].why = r.conf === 'high' ? '' : (r.conf === 'none' ? '没查到' : '拿不准，没改') + (r.err ? '（' + r.err + '）' : '') + (r.cands && r.cands[0] ? ' · 最像：' + r.cands[0].title + ' / ' + r.cands[0].artist + '，时长差 ' + r.cands[0].d + ' 秒，分 ' + r.cands[0].score + '，本地时长 ' + cur.dur : '');
            if (r.conf !== 'high' && !(this.inCfg.minConf === 'mid' && r.conf === 'mid')) continue;
            const n = {}, pr = r.proposed;
            for (const k of ['title', 'artist', 'album']) if (pr[k] && pr[k] !== (tg[k] || '')) n[k] = pr[k];   // 跟文件里真正写着的比（没写的也补上）
            if (pr.track) n.track = String(pr.track); if (pr.year) n.year = String(pr.year); if (pr.cover) n.cover = pr.cover;
            if (Object.keys(n).length) plan[t.path] = n;
          }
        }
        this.inStep = '补标签'; const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sm_in_'));
        const copies = {};
        for (const [p, n] of Object.entries(plan)) { const c = path.join(tmp, Object.keys(copies).length + path.extname(p)); fs.copyFileSync(p, c); copies[p] = { copy: c, new: n }; }
        if (Object.keys(copies).length) {
          let res = []; try { res = await this.pyJson('--local-write', Object.values(copies).map(x => ({ path: x.copy, new: x.new }))); } catch (e) {}
          for (const [p, x] of Object.entries(copies)) { const r = res.find(y => y.path === x.copy); if (!r || !r.ok) delete copies[p]; }
        }
        // 2. 传：改好的传临时副本，没改的传原文件。文件名不变
        for (const p of L) {
          const x = this.inFiles[p]; if (!x) continue;
          x.state = 'up'; x.step = '传到播放器'; x.err = this.inTagErr || ''; this.inSave();
          const src = copies[p] ? copies[p].copy : p, name = path.basename(p).replace(/[\\/:*?"<>|]/g, '_'), to = (target === '/' ? '' : target) + '/' + name;
          const j = this.job('in' + Date.now() + Math.random().toString(36).slice(2, 5), 'up', name, fs.statSync(src).size); j.path = to;
          try {
            await this.withFtp(async c => { await c.mkdirs(target).catch(() => {}); await c.stor(to, fs.createReadStream(src), n => this.tickJob(j, n)); });
            j.state = 'done'; x.state = 'done'; x.step = ''; x.to = to; x.tagged = copies[p] ? copies[p].new : null; x.at = Date.now();
            this.imports = (this.imports || []).filter(y => y !== this.pre() + to); this.imports.push(this.pre() + to);
            if (copies[p]) this.metaApplied[this.pre() + to] = { at: Date.now(), new: copies[p].new };
          } catch (e) { j.state = 'err'; j.err = e.message; x.state = 'err'; x.step = ''; x.err = e.message; }
          this.inSave();
        }
        try { fs.rmSync(tmp, { recursive: true, force: true }); } catch (e) {}
        try { fs.writeFileSync(this.metaFile, JSON.stringify(this.metaApplied)); } catch (e) {}
        if (this.fidx) this.fidx.ts = 0;
      } finally { this.inBusy = false; this.inStep = ''; this.inSave(); if (this.set.autoFlac !== false && paths.some(k => /\.m4a$/i.test(k))) setTimeout(() => { try { this.flacStart(this.inCfg.target || '/Music/待分类'); } catch (e) {} }, 2000); }   // 传上去的有 m4a：播放器放不了，接着转成 flac
    })();
    return { started: L.length };
  };

  /* ---------- 接口 ---------- */
  P.api = async function (u, body) {
    const p = u.pathname.replace(/^\/api\/music\/?/, '');
    if (p === 'inbox') { this.seen = Date.now(); return this.inView(); }
    if (p === 'inbox/scan') { await this.inScan(); return this.inView(); }
    if (p === 'inbox/upload') { const L = body.all ? Object.entries(this.inFiles).filter(([, x]) => x.state === 'new').map(([k]) => k) : [].concat(body.paths || []); return this.inUpload(L, body); }
    if (p === 'inbox/ignore') { for (const k of [].concat(body.paths || [])) if (this.inFiles[k]) this.inFiles[k].state = 'skip'; this.inSave(); return this.inView(); }
    if (p === 'inbox/config') {
      const b = body || {};
      if (b.dirs) {
        const dirs = [].concat(b.dirs).map(x => String(x).trim()).filter(Boolean);
        for (const d of dirs) if (!fs.existsSync(d)) throw new Error('电脑上没有这个文件夹：' + d);
        if (dirs.some(d => !(this.inCfg.dirs || []).includes(d))) this.inBaseline = true;
        this.inCfg.dirs = dirs;
      }
      for (const k of ['auto', 'tag']) if (k in b) this.inCfg[k] = !!b[k];
      if (b.target) this.inCfg.target = this.cleanPath(b.target);
      if (b.minConf) this.inCfg.minConf = b.minConf === 'mid' ? 'mid' : 'high';
      this.inSave(); await this.inScan(); return this.inView();
    }
    return baseApi.call(this, u, body);
  };
};
