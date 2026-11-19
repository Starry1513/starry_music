#!/usr/bin/env node
// 群星音乐 · 电脑版：一个零依赖的小服务器。node server.js 就能跑。
//  · 浏览器打开 http://<这台电脑的 IP>:8790/?k=<口令>（本机直接 http://127.0.0.1:8790/）
//  · 配置：config.json（没有就用 config.example.json 的默认值）；数据：data/
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { MusicCore } = require('./core/music');

const ROOT = __dirname;
const DATA = path.join(ROOT, 'data');
fs.mkdirSync(DATA, { recursive: true });
const readJson = f => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch (e) { return null; } };
const cfg = readJson(path.join(ROOT, 'config.json')) || readJson(path.join(ROOT, 'config.example.json')) || {};
const S = Object.assign({ host: '0.0.0.0', port: 8790, token: '' }, cfg.server || {});
const log = (...a) => console.log(new Date().toTimeString().slice(0, 8), ...a);
process.on('uncaughtException', e => { log('[错误] 已拦下：', (e && e.stack) || e); try { fs.appendFileSync(path.join(DATA, 'crash.log'), new Date().toISOString() + ' ' + ((e && e.stack) || e) + '\n'); } catch (x) {} });

// 口令：配置里没写就生成一个存到 data/token.txt
let TOKEN = S.token;
if (!TOKEN) { const f = path.join(DATA, 'token.txt'); TOKEN = (fs.existsSync(f) && fs.readFileSync(f, 'utf8').trim()) || crypto.randomBytes(9).toString('base64url'); fs.writeFileSync(f, TOKEN); }

const core = new MusicCore();
const UI = path.join(ROOT, 'ui', 'music.html');
const json = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)); };
const isLocal = ip => ip === '::1' || ip.startsWith('127.');
const cookie = (req, n) => (String(req.headers.cookie || '').split(/;\s*/).find(x => x.startsWith(n + '=')) || '').slice(n.length + 1);

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x'), ip = String(req.socket.remoteAddress || '').replace(/^::ffff:/, '');
  const tok = req.headers['x-starry-token'] || u.searchParams.get('k') || decodeURIComponent(cookie(req, 'sm_k'));
  const ok = isLocal(ip) || tok === TOKEN;
  if (!ok) { res.writeHead(401, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end('需要口令：在地址后面加 ?k=<口令>（口令在电脑上 data/token.txt 或启动时的提示里）'); }
  if (u.pathname === '/' || u.pathname === '/music') {
    const h = { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' };
    if (u.searchParams.get('k') === TOKEN) h['Set-Cookie'] = `sm_k=${encodeURIComponent(TOKEN)}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict`;
    res.writeHead(200, h); return fs.createReadStream(UI).pipe(res);
  }
  if (!u.pathname.startsWith('/api/music')) { res.writeHead(404); return res.end(); }
  // 跨站保护：会改东西的请求要带 X-Starry 头（页面自己会带）
  if (req.method === 'POST' && req.headers['x-starry'] !== '1' && !req.headers['x-starry-token']) return json(res, 403, { ok: false, error: '缺少 X-Starry 头' });
  if (u.pathname === '/api/music/art') return core.art(u, res);
  if (u.pathname.startsWith('/api/music/ftp/')) return core.ftpRoute(u, req, res, json);
  let body = '';
  req.on('data', c => { body += c; if (body.length > 2e6) req.destroy(); });
  req.on('end', async () => {
    try { json(res, 200, Object.assign({ ok: true }, await core.api(u, body ? JSON.parse(body) : {}))); }
    catch (e) { json(res, 400, { ok: false, error: e.message }); }
  });
});

(async () => {
  await core.init({ cfg: cfg.player || {}, dataDir: DATA, log });
  server.listen(S.port, S.host, () => {
    const ips = Object.values(os.networkInterfaces()).flat().filter(n => n && n.family === 'IPv4' && !n.internal).map(n => n.address);
    log(`群星音乐已启动：本机 http://127.0.0.1:${S.port}/`);
    for (const a of ips) log(`  手机/平板：http://${a}:${S.port}/?k=${TOKEN}`);
    core.discover().then(r => log(r.player ? `找到播放器：${r.player}（${r.ip}）` : '还没找到播放器（打开播放器的 DLNA，保持亮屏）')).catch(() => {});
  });
})();
