// 极简 FTP 客户端（被动模式），给群星音乐连 A&K File Drop 用。零依赖。
// 用法：const c = await Ftp.open({host, port, user, pass}); await c.list('/Music'); … await c.close();
const net = require('net');

const MON = 'Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec';
const RX_UNIX = new RegExp(`^([dl-])\\S*\\s+.*?(\\d+)\\s+(${MON})\\s+(\\d{1,2})\\s+(\\d{1,2}:\\d{2}|\\d{4})\\s+(.+)$`);
const RX_DOS = /^(\d{2}-\d{2}-\d{2,4})\s+(\d{1,2}:\d{2}\s*[AP]M)\s+(<DIR>|\d+)\s+(.+)$/i;

function parseList(txt) {
  const out = [];
  for (const ln of String(txt).split(/\r?\n/)) {
    if (!ln.trim() || /^total\s/i.test(ln)) continue;
    let m = RX_UNIX.exec(ln);
    if (m) { let name = m[6]; if (m[1] === 'l') name = name.split(' -> ')[0]; if (name === '.' || name === '..') continue;
      out.push({ name, dir: m[1] === 'd' || m[1] === 'l', size: +m[2] }); continue; }
    m = RX_DOS.exec(ln);
    if (m) { out.push({ name: m[4], dir: m[3] === '<DIR>', size: m[3] === '<DIR>' ? 0 : +m[3] }); continue; }
  }
  return out;
}

class Ftp {
  constructor(o) { this.o = o; this.buf = ''; this.replies = []; this.waiters = []; this.dead = null; }
  static async open(o) { const c = new Ftp(o); await c.connect(); return c; }

  connect() {
    const { host, port = 21, user = 'anonymous', pass = '' } = this.o;
    return new Promise((ok, no) => {
      const s = net.connect({ host, port });
      this.s = s; s.setEncoding('utf8');
      const t = setTimeout(() => s.destroy(new Error('连不上 File Drop（' + host + ':' + port + '）：CA1000T 息屏了或者 File Drop 没开')), 8000);
      s.on('data', d => { this.buf += d; this.parse(); });
      s.on('error', e => this.fail(e));
      s.on('close', () => this.fail(new Error('FTP 连接断开')));
      s.once('connect', async () => {
        clearTimeout(t); s.setTimeout(60000, () => s.destroy(new Error('FTP 超时')));
        try {
          await this.expect(this.read(), [220]);
          const u = await this.cmd('USER ' + user);
          if (u.code === 331) await this.expect(this.cmd('PASS ' + pass), [230, 202]);
          else if (u.code !== 230) throw new Error('FTP 登录失败：' + u.text);
          await this.cmd('OPTS UTF8 ON').catch(() => {});
          await this.expect(this.cmd('TYPE I'), [200]);
          ok();
        } catch (e) { no(e); }
      });
      s.once('error', no);
    });
  }
  fail(e) { if (this.dead) return; this.dead = e; for (const w of this.waiters.splice(0)) w.no(e); }
  parse() {
    for (;;) {
      const lines = this.buf.split('\r\n'); if (lines.length < 2) return;
      const first = lines[0]; const code = first.slice(0, 3);
      let end = 0;
      if (first[3] === '-') { end = lines.findIndex((l, i) => i > 0 && l.startsWith(code + ' ')); if (end < 0 || end >= lines.length - 1) return; }
      const text = lines.slice(0, end + 1).join('\n');
      this.buf = lines.slice(end + 1).join('\r\n');
      const r = { code: +code, text };
      const w = this.waiters.shift(); if (w) w.ok(r); else this.replies.push(r);
    }
  }
  read() {
    if (this.replies.length) return Promise.resolve(this.replies.shift());
    if (this.dead) return Promise.reject(this.dead);
    return new Promise((ok, no) => this.waiters.push({ ok, no }));
  }
  async expect(p, codes) { const r = await p; if (!codes.includes(r.code)) throw new Error('FTP ' + r.text.replace(/\n/g, ' ')); return r; }
  cmd(line) { if (this.dead) return Promise.reject(this.dead); this.s.write(line + '\r\n'); return this.read(); }

  async pasv() {
    const r = await this.expect(this.cmd('PASV'), [227]);
    const m = /(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)/.exec(r.text);
    if (!m) throw new Error('PASV 回复看不懂：' + r.text);
    const port = +m[5] * 256 + +m[6];
    return new Promise((ok, no) => {
      const d = net.connect({ host: this.o.host, port });       // 用控制连接的地址，不信 PASV 里的 IP
      const t = setTimeout(() => d.destroy(new Error('FTP 数据连接超时')), 8000);
      let up = false;
      // 一直挂着一个 error 监听：数据连接被对方重置（比如 RETR 一个刚写完、还被占着的文件）时不能让整个中台崩掉
      d.on('error', e => { d._err = e; if (!up) { clearTimeout(t); no(e); } });
      d.once('connect', () => { up = true; clearTimeout(t); ok(d); });
    });
  }
  async list(dir) {
    const d = await this.pasv(); const bufs = [];
    if (d._err) throw d._err;
    const done = new Promise((ok, no) => { d.on('data', b => bufs.push(b)); d.on('end', ok); d.on('error', no); });
    await this.expect(this.cmd('LIST ' + dir), [125, 150]);
    await done; await this.expect(this.read(), [226, 250]);
    return parseList(Buffer.concat(bufs).toString('utf8'));
  }
  async size(p) { const r = await this.cmd('SIZE ' + p); return r.code === 213 ? +r.text.slice(4).trim() : -1; }
  // 下载：返回数据流；调用方读完后要 await finish()
  async retr(p) {
    const d = await this.pasv();
    try { await this.expect(this.cmd('RETR ' + p), [125, 150]); } catch (e) { d.destroy(); throw e; }
    return { stream: d, finish: () => this.expect(this.read(), [226, 250]) };
  }
  async getText(p) {
    const { stream, finish } = await this.retr(p); const bufs = [];
    await new Promise((ok, no) => { stream.on('data', b => bufs.push(b)); stream.on('end', ok); stream.on('error', no); });
    await finish(); return Buffer.concat(bufs);
  }
  // 上传：src 是可读流；onBytes(n) 每写出一块回调一次
  async stor(p, src, onBytes) {
    const d = await this.pasv();
    try { await this.expect(this.cmd('STOR ' + p), [125, 150]); } catch (e) { d.destroy(); throw e; }
    await new Promise((ok, no) => {
      src.on('data', b => { onBytes && onBytes(b.length); if (!d.write(b)) { src.pause(); d.once('drain', () => src.resume()); } });
      src.on('end', () => d.end());
      src.on('error', e => { d.destroy(); no(e); });
      d.on('error', no); d.on('close', ok);
    });
    await this.expect(this.read(), [226, 250]);
  }
  async putBuffer(p, buf) { const { Readable } = require('stream'); await this.stor(p, Readable.from([buf])); }
  mkd(p) { return this.cmd('MKD ' + p); }
  async mkdirs(p) { let cur = ''; for (const x of p.split('/').filter(Boolean)) { cur += '/' + x; await this.mkd(cur); } }
  async rename(a, b) { await this.expect(this.cmd('RNFR ' + a), [350]); await this.expect(this.cmd('RNTO ' + b), [250]); }
  async rmd(p) { await this.expect(this.cmd('RMD ' + p), [250]); }
  async dele(p) { await this.expect(this.cmd('DELE ' + p), [250]); }
  async close() { try { await Promise.race([this.cmd('QUIT'), new Promise(r => setTimeout(r, 1500))]); } catch (e) {} try { this.s.destroy(); } catch (e) {} }
}
module.exports = { Ftp, parseList };
