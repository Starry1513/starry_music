# 群星音乐 · 补写/统一歌曲标签（在电脑上跑）。
# 对计划里的每个文件：从 CA1000T（AK File Drop / FTP）下到临时目录 → 改标签（只改 歌名/歌手/专辑）→ 检查音频数据没变
# → 传回去：先传成 .tagtmp，原文件改名 .tagbak，.tagtmp 改成原名，最后删 .tagbak（任何一步失败都能找回）
# 原来的标签全部记进 tag_backup.jsonl，可以用 --undo 恢复。
# 用法：python tagfix.py plan.json --host <播放器 IP> [--limit N]    /    python tagfix.py --undo tag_backup.jsonl
import sys, os, json, time, hashlib, ftplib, struct, argparse, traceback
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'vendor'))
import mutagen
from mutagen.flac import FLAC
from mutagen.id3 import ID3, TIT2, TPE1, TALB, TPE2, TRCK, TDRC, APIC, ID3NoHeaderError
from mutagen.flac import Picture
from mutagen.mp4 import MP4Cover
import base64, urllib.request
from mutagen.mp4 import MP4
from mutagen.oggvorbis import OggVorbis
from mutagen.wave import WAVE

HERE = os.path.dirname(os.path.abspath(__file__))
PRE = '/storage/emulated/0'
JUNK_AA = {'apple music', 'qq音乐', '网易云音乐', 'netease cloud music', 'kugou', '酷狗音乐', 'various artists', 'va'}

def flac_audio_off(p):
    with open(p, 'rb') as f:
        if f.read(4) != b'fLaC': raise ValueError('不是 FLAC')
        while True:
            h = f.read(4); last = h[0] & 0x80; n = struct.unpack('>I', b'\0' + h[1:])[0]; f.seek(n, 1)
            if last: return f.tell()
def mp3_range(p):
    sz = os.path.getsize(p)
    with open(p, 'rb') as f:
        h = f.read(10); st = 0
        if h[:3] == b'ID3': st = 10 + ((h[6] & 0x7f) << 21 | (h[7] & 0x7f) << 14 | (h[8] & 0x7f) << 7 | (h[9] & 0x7f)) + (10 if h[5] & 0x10 else 0)
        f.seek(sz - 128); end = sz - 128 if f.read(3) == b'TAG' else sz
    return st, end
def md5_range(p, a, b):
    m = hashlib.md5()
    with open(p, 'rb') as f:
        f.seek(a); left = b - a
        while left > 0:
            c = f.read(min(1 << 20, left))
            if not c: break
            m.update(c); left -= len(c)
    return m.hexdigest()
def audio_sig(p, ext):
    if ext == 'flac': a = flac_audio_off(p); return 'md5', md5_range(p, a, os.path.getsize(p))
    if ext == 'mp3': a, b = mp3_range(p); return 'md5', md5_range(p, a, b)
    f = mutagen.File(p); return 'len', round(f.info.length, 2)

def read_tags(p, ext):
    o = {}
    if ext == 'flac' or ext == 'ogg':
        f = FLAC(p) if ext == 'flac' else OggVorbis(p)
        for k in ('title', 'artist', 'album', 'albumartist'): o[k] = list(f.get(k, []))
    elif ext in ('mp3', 'wav'):
        try: t = ID3(p) if ext == 'mp3' else (WAVE(p).tags or {})
        except ID3NoHeaderError: t = {}
        for k, fid in (('title', 'TIT2'), ('artist', 'TPE1'), ('album', 'TALB'), ('albumartist', 'TPE2')): o[k] = [str(x) for x in t[fid].text] if fid in t else []
    elif ext == 'm4a':
        f = MP4(p)
        for k, a in (('title', '\xa9nam'), ('artist', '\xa9ART'), ('album', '\xa9alb'), ('albumartist', 'aART')): o[k] = list(f.tags.get(a, [])) if f.tags else []
    else: raise ValueError('这种格式不改：' + ext)
    return o
def fetch_cover(url):
    r = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (starry-music)'}), timeout=20)
    b = r.read()
    if len(b) < 1000 or len(b) > 8_000_000: raise IOError('封面图片不对')
    return b, ('image/png' if b[:4] == b'\x89PNG' else 'image/jpeg')
def write_extra(p, ext, new):
    tr, yr, cv = new.get('track'), new.get('year'), new.get('_cover')
    if ext in ('flac', 'ogg'):
        f = FLAC(p) if ext == 'flac' else OggVorbis(p)
        if tr: f['tracknumber'] = [str(tr)]
        if yr: f['date'] = [str(yr)]
        if cv:
            pic = Picture(); pic.type = 3; pic.mime = cv[1]; pic.desc = 'Cover'; pic.data = cv[0]
            if ext == 'flac': f.clear_pictures(); f.add_picture(pic)
            else: f['metadata_block_picture'] = [base64.b64encode(pic.write()).decode('ascii')]
        f.save()
    elif ext in ('mp3', 'wav'):
        if ext == 'mp3':
            try: t = ID3(p)
            except ID3NoHeaderError: t = ID3()
        else:
            w = WAVE(p)
            if w.tags is None: w.add_tags()
            t = w.tags
        if tr: t.setall('TRCK', [TRCK(encoding=3, text=[str(tr)])])
        if yr: t.setall('TDRC', [TDRC(encoding=3, text=[str(yr)])])
        if cv: t.delall('APIC'); t.add(APIC(encoding=3, mime=cv[1], type=3, desc='Cover', data=cv[0]))
        if ext == 'mp3': t.save(p, v2_version=3, v1=0)
        else: w.save()
    elif ext == 'm4a':
        f = MP4(p)
        if f.tags is None: f.add_tags()
        if tr: f.tags['trkn'] = [(int(tr), 0)]
        if yr: f.tags['\xa9day'] = [str(yr)]
        if cv: f.tags['covr'] = [MP4Cover(cv[0], imageformat=MP4Cover.FORMAT_PNG if cv[1] == 'image/png' else MP4Cover.FORMAT_JPEG)]
        f.save()
def write_tags(p, ext, new):
    def want(k): return k in new and new[k] is not None
    if ext in ('flac', 'ogg'):
        f = FLAC(p) if ext == 'flac' else OggVorbis(p)
        if f.tags is None: f.add_tags()
        for k in ('title', 'artist', 'album', 'albumartist'):
            if want(k): f[k] = new[k] if isinstance(new[k], list) else [new[k]]
        f.save()
    elif ext == 'mp3':
        try: t = ID3(p)
        except ID3NoHeaderError: t = ID3()
        for k, C in (('title', TIT2), ('artist', TPE1), ('album', TALB), ('albumartist', TPE2)):
            if want(k): t.setall(C.__name__, [C(encoding=3, text=new[k] if isinstance(new[k], list) else [new[k]])])
        t.save(p, v2_version=3, v1=0)
    elif ext == 'wav':
        f = WAVE(p)
        if f.tags is None: f.add_tags()
        for k, C in (('title', TIT2), ('artist', TPE1), ('album', TALB), ('albumartist', TPE2)):
            if want(k): f.tags.setall(C.__name__, [C(encoding=3, text=new[k] if isinstance(new[k], list) else [new[k]])])
        f.save()
    elif ext == 'm4a':
        f = MP4(p)
        if f.tags is None: f.add_tags()
        for k, a in (('title', '\xa9nam'), ('artist', '\xa9ART'), ('album', '\xa9alb'), ('albumartist', 'aART')):
            if want(k): f.tags[a] = new[k] if isinstance(new[k], list) else [new[k]]
        f.save()
    else: raise ValueError('这种格式不改：' + ext)

class CA:
    def __init__(s, host, port, user, pw): s.a = (host, port, user, pw); s.c = None
    def conn(s):
        if s.c:
            try: s.c.voidcmd('NOOP'); return s.c
            except Exception: s.close()
        c = ftplib.FTP(); c.encoding = 'utf-8'; c.connect(s.a[0], s.a[1], timeout=60); c.login(s.a[2], s.a[3]); c.set_pasv(True); s.c = c; return c
    def close(s):
        try: s.c and s.c.quit()
        except Exception: pass
        s.c = None
    def size(s, path):
        d, n = path.rsplit('/', 1); lines = []
        s.conn().retrlines('LIST ' + (d or '/'), lines.append)
        for l in lines:
            parts = l.split(None, 8)
            if len(parts) == 9 and parts[8] == n: return int(parts[4])
        return -1

def run(plan, host, limit, status_file):
    bk = open(os.path.join(HERE, 'tag_backup.jsonl'), 'a', encoding='utf-8')
    tmp = os.path.join(HERE, '_tmp'); os.makedirs(tmp, exist_ok=True)
    ca = CA(host, 1212, 'admin', 'admin')
    done_f = os.path.join(HERE, 'tag_done.txt'); done = set(open(done_f, encoding='utf-8').read().split('\n')) if os.path.exists(done_f) else set()
    todo = [x for x in plan if x['path'] not in done][:limit or None]
    st = {'total': len(todo), 'ok': 0, 'skip': 0, 'err': 0, 'bytes': 0, 't0': time.time(), 'cur': '', 'errs': []}
    def save_st():
        st['elapsed'] = round(time.time() - st['t0']); json.dump(st, open(status_file, 'w', encoding='utf-8'), ensure_ascii=False)
    for i, x in enumerate(todo):
        rp = x['path'][len(PRE):] if x['path'].startswith(PRE) else None
        st['cur'] = rp; save_st()
        if not rp: st['skip'] += 1; continue
        ext = rp.rsplit('.', 1)[-1].lower(); lp = os.path.join(tmp, 'f.' + ext)
        try:
            # 上次中途被打断：原文件还叫 .tagbak → 先改回来
            c0 = ca.conn()
            if ca.size(rp) < 0 and ca.size(rp + '.tagbak') >= 0: c0.rename(rp + '.tagbak', rp)
            if ca.size(rp + '.tagtmp') >= 0: c0.delete(rp + '.tagtmp')
            for attempt in range(3):
                try:
                    c = ca.conn(); size0 = ca.size(rp)
                    if size0 < 0: raise FileNotFoundError('播放器上没找到这个文件')
                    with open(lp, 'wb') as f: c.retrbinary('RETR ' + rp, f.write, 1 << 16)
                    if os.path.getsize(lp) != size0: raise IOError('下载不完整')
                    break
                except (ftplib.error_temp, OSError, EOFError) as e:
                    if isinstance(e, FileNotFoundError) or attempt == 2: raise
                    ca.close(); time.sleep(5)
            old = read_tags(lp, ext)
            new = {k: v for k, v in x['new'].items() if v and (k not in old or old.get(k) != [v])}
            # 专辑歌手是“Apple Music”这种下载软件留下的 → 改成歌手（第一位）
            aa = (old.get('albumartist') or [''])[0]
            if aa.strip().lower() in JUNK_AA:
                art = new.get('artist') or (old.get('artist') or [''])[0]
                if art: new['albumartist'] = art.split(' & ')[0].strip()
            if not new: st['skip'] += 1; open(done_f, 'a', encoding='utf-8').write(x['path'] + '\n'); continue
            sig0 = audio_sig(lp, ext)
            cover_url = new.pop('cover', None)
            if cover_url:
                try: new['_cover'] = fetch_cover(cover_url)
                except Exception as e: st['errs'].append([rp, '封面没下到（其他照改）：' + str(e)[:80]])
            write_tags(lp, ext, {k: v for k, v in new.items() if k in ('title', 'artist', 'album', 'albumartist')})
            if any(k in new for k in ('track', 'year', '_cover')): write_extra(lp, ext, new)
            if '_cover' in new: new['_cover'] = True
            sig1 = audio_sig(lp, ext)
            if sig0 != sig1: raise IOError('改完标签后音频数据对不上，放弃（原文件没动）')
            got = read_tags(lp, ext)
            for k, v in new.items():
                if k in ('title', 'artist', 'album', 'albumartist') and got.get(k) != [v]: raise IOError('标签没写进去：' + k)
            bk.write(json.dumps({'path': x['path'], 'old': old, 'new': new, 'at': time.time()}, ensure_ascii=False) + '\n'); bk.flush()
            # 传回去
            c = ca.conn(); t1, bak = rp + '.tagtmp', rp + '.tagbak'
            with open(lp, 'rb') as f: c.storbinary('STOR ' + t1, f, 1 << 16)
            if ca.size(t1) != os.path.getsize(lp): raise IOError('上传不完整（原文件没动，删掉了临时文件）')
            c.rename(rp, bak); c.rename(t1, rp)
            if ca.size(rp) != os.path.getsize(lp): raise IOError('改名后大小不对，原文件留在 ' + bak)
            c.delete(bak)
            st['ok'] += 1; st['bytes'] += size0 + os.path.getsize(lp)
            open(done_f, 'a', encoding='utf-8').write(x['path'] + '\n')
        except Exception as e:
            st['err'] += 1; st['errs'].append([rp, str(e)[:200]])
            try:
                c = ca.conn()
                if ca.size(rp + '.tagtmp') >= 0: c.delete(rp + '.tagtmp')
            except Exception: pass
        finally:
            try: os.remove(lp)
            except Exception: pass
        save_st()
    st['cur'] = ''; st['fin'] = True; save_st(); ca.close()

if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('plan'); ap.add_argument('--host', required=True); ap.add_argument('--limit', type=int, default=0); ap.add_argument('--undo', action='store_true')
    a = ap.parse_args()
    if a.undo:   # 用备份里的旧标签生成一个“还原计划”
        L = [json.loads(l) for l in open(a.plan, encoding='utf-8')]
        plan = [{'path': x['path'], 'new': {k: (x['old'].get(k) or [''])[0] for k in x['new']}} for x in L]
        run(plan, a.host, a.limit, os.path.join(HERE, 'tag_status.json'))
    else: run(json.load(open(a.plan, encoding='utf-8')), a.host, a.limit, os.path.join(HERE, 'tag_status.json'))
