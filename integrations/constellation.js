// 群星中台（Constellation hub）的适配器外壳：中台的 adapters/music.js 直接 require 这个文件。
// 数据仍放在中台的 data/ 里，配置读中台 config.json 的 "music" 一节。
const path = require('path');
const { MusicCore } = require('../core/music');

class MusicAdapter extends MusicCore {
  constructor() { super(); this.id = 'music'; this.capabilities = ['music']; this.hub = null; }
  async init(hub) {
    this.hub = hub;
    const r = await super.init({ cfg: (hub.cfg && hub.cfg.music) || {}, dataDir: path.join(hub.root, 'data') });
    // 歌词里点英文单词：用群星自己的词典查，加进群星「单词复习」（和欧路同步来的词一起复习）
    const V = () => hub.adapter && hub.adapter('vocab');
    if (V()) this.wordHooks = { lookup: w => { const v = V(); return v && v.lookup(w); }, add: o => { const v = V(); if (!v || !v.addMine) throw new Error('单词复习没开'); return v.addMine(o); } };
    return r;
  }
  // 补写/统一歌曲标签：在电脑上跑 tools/tagfix.py（Python + 自带的 mutagen），后台慢慢做，进度看 tools/tag_status.json
  async api(u, body) {
    const p = u.pathname.replace(/^\/api\/music\/?/, '');
    if (p === 'tools/tagfix') {
      const fs = require('fs'), T = path.join(__dirname, '..', 'tools'), sf = path.join(T, 'tag_status.json');
      if (body && body.stop) { if (this.tagProc) { this.tagProc.kill(); this.tagProc = null; } return { stopped: true }; }
      if (body && body.plan) {
        if (this.tagProc) throw new Error('已经在跑了');
        const plan = path.join(T, path.basename(String(body.plan)));
        if (!fs.existsSync(plan)) throw new Error('没有这个计划文件');
        const py = this.python();
        const args = [path.join(T, 'tagfix.py'), plan, '--host', this.ip || ''].concat(body.limit ? ['--limit', String(+body.limit)] : []);
        const out = fs.openSync(path.join(T, 'tagfix.log'), 'a');
        const pr = require('child_process').spawn(py, args, { cwd: T, windowsHide: true, stdio: ['ignore', out, out], env: Object.assign({}, process.env, { PYTHONIOENCODING: 'utf-8' }) });
        this.tagProc = pr; pr.on('exit', c => { if (this.tagProc === pr) this.tagProc = null; this.tagExit = c; });
        return { started: true, py };
      }
      let st = null; try { st = JSON.parse(fs.readFileSync(sf, 'utf8')); } catch (e) {}
      return { running: !!this.tagProc, exit: this.tagExit, status: st };
    }
    return super.api(u, body);
  }
  // 中台每轮问一次：把听歌统计放进状态，「综合」页显示
  async probe() { try { return { _note: this.statsView(7) }; } catch (e) { return {}; } }
  actions() { return {}; }
}
module.exports = MusicAdapter;
