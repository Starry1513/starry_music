# 群星音乐 · 播放器上的 .m4a 转成 .flac（CA1000T 放不了 m4a）
# 跟 tran.bat（convert_m4a_to_flac.bat）一样的规矩，只是文件在播放器上，经 FTP 拉下来转、再传回去：
#   1. 递归找 <文件夹> 下所有 .m4a
#   2. 用 ffmpeg 无损转成 .flac（歌名、歌手、专辑、封面都带上）
#   3. 同目录已经有对应 .flac 的跳过
#   4. 新的 .flac 传好、核对大小以后，才删掉播放器上的 .m4a
#   5. 输出文件名去掉开头的「数字 + 一个点 + 空格」（"06. 秋梦.m4a" → "秋梦.flac"）
# 用法：python m4a2flac.py --host 192.168.0.138 [--dir /Music] [--ffmpeg 路径] [--list]
#   --list 只数一下有多少首，不转
# 进度写在同目录 m4a_status.json；只删文件，从不删文件夹。
import sys, os, json, time, ftplib, argparse, tempfile, subprocess, shutil, re, glob

HERE = os.path.dirname(os.path.abspath(__file__))
ST = os.path.join(HERE, 'm4a_status.json')

def status(**k):
    st.update(k); st['elapsed'] = round(time.time() - st['t0'])
    with open(ST + '.n', 'w', encoding='utf-8') as f: json.dump(st, f, ensure_ascii=False)
    os.replace(ST + '.n', ST)

def find_ffmpeg(arg):
    c = [arg, os.environ.get('FFMPEG', ''), r'D:\coding\ffmpeg-6.0-essentials_build\ffmpeg-6.0-essentials_build\bin\ffmpeg.exe', shutil.which('ffmpeg') or '']
    c += glob.glob(r'D:\coding\ffmpeg*\**\bin\ffmpeg.exe', recursive=True) + glob.glob(r'C:\ffmpeg*\**\bin\ffmpeg.exe', recursive=True)
    for x in c:
        if x and os.path.exists(x): return x
    return ''

def convert(ff, src, dst):
    if ff:
        # 音频转 flac；封面（m4a 里是一路“图片流”）原样带过去当 flac 的封面
        p = subprocess.run([ff, '-v', 'error', '-y', '-i', src, '-map', '0:a:0', '-map', '0:v?', '-c:a', 'flac', '-c:v', 'copy', '-disposition:v', 'attached_pic', '-map_metadata', '0', dst], capture_output=True)
        if p.returncode != 0 or not os.path.exists(dst):   # 有的封面格式 flac 收不下：不带封面再来
            p = subprocess.run([ff, '-v', 'error', '-y', '-i', src, '-map', '0:a:0', '-c:a', 'flac', '-map_metadata', '0', dst], capture_output=True)
        if p.returncode != 0: raise RuntimeError((p.stderr.decode('utf-8', 'replace').strip().splitlines() or ['ffmpeg 出错'])[-1][:200])
        return
    # 没有 ffmpeg.exe：用 Python 的 av（群星实时字幕的环境里有）来转，只转声音和文字标签
    import av
    i = av.open(src); a = i.streams.audio[0]
    o = av.open(dst, 'w', format='flac'); s = o.add_stream('flac', rate=a.rate); s.layout = a.layout.name
    s.format = 's32' if (a.format.bits or 16) > 16 else 's16'
    for k, v in (i.metadata or {}).items(): o.metadata[k] = v
    for fr in i.decode(a):
        for pk in s.encode(fr): o.mux(pk)
    for pk in s.encode(None): o.mux(pk)
    o.close(); i.close()

def clean_name(stem):
    # 和 bat 一样：去掉开头的数字、一个点和前导空格（"06. 秋梦" → "秋梦"）
    m = re.match(r'^\s*\d+\s*\.\s*(.+)$', stem)
    return (m.group(1).strip() if m else stem) or stem

def main():
    global st
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', required=True); ap.add_argument('--port', type=int, default=1212)
    ap.add_argument('--dir', default='/Music'); ap.add_argument('--ffmpeg', default='')
    ap.add_argument('--list', action='store_true'); ap.add_argument('--limit', type=int, default=0)
    a = ap.parse_args()
    st = {'t0': time.time(), 'dir': a.dir, 'total': 0, 'done': 0, 'skip': 0, 'err': 0, 'bytes': 0, 'cur': '', 'errs': [], 'converted': []}
    f = ftplib.FTP(); f.connect(a.host, a.port, timeout=60); f.login('admin', 'admin'); f.encoding = 'utf-8'
    def ls(p):
        L = []
        try: f.retrlines('LIST ' + p, L.append)
        except ftplib.error_perm: return []
        out = []
        for l in L:
            x = l.split(None, 8)
            if len(x) == 9 and x[8] not in ('.', '..'): out.append((l[0] == 'd', x[8], int(x[4]) if x[4].isdigit() else 0))
        return out
    found, names = [], {}
    def walk(p, depth):
        if depth > 8: return
        kids = ls(p); names[p] = {n for d, n, _ in kids if not d}
        for d, n, sz in kids:
            q = p.rstrip('/') + '/' + n
            if d: walk(q, depth + 1)
            elif n.lower().endswith('.m4a'): found.append((p, n, sz))
    status(cur='正在找 .m4a…'); walk(a.dir, 0)
    status(total=len(found), cur='')
    if a.list: print(json.dumps({'total': len(found), 'files': [d.rstrip('/') + '/' + n for d, n, _ in found][:500]}, ensure_ascii=False)); status(fin=True); return
    ff = find_ffmpeg(a.ffmpeg)
    if not ff:
        try: import av  # noqa
        except Exception: status(fin=True, fatal='找不到 ffmpeg：装一个，或者在设置里填 ffmpeg.exe 的路径'); sys.exit(2)
    status(tool=ff or 'PyAV')
    tmp = tempfile.mkdtemp(prefix='sm_m4a_')
    try:
        for i, (d, n, sz) in enumerate(found):
            if a.limit and st['done'] >= a.limit: break
            stem = n[:-4]; out_name = clean_name(stem) + '.flac'
            src_p, dst_p = d.rstrip('/') + '/' + n, d.rstrip('/') + '/' + out_name
            status(cur=src_p, i=i + 1)
            if out_name in names.get(d, set()) or (stem + '.flac') in names.get(d, set()):
                st['skip'] += 1; status(); continue          # 已经有 flac 了：不动它（m4a 也留着，交给你决定）
            ls_, lf = os.path.join(tmp, 'a.m4a'), os.path.join(tmp, 'a.flac')
            try:
                with open(ls_, 'wb') as fh: f.retrbinary('RETR ' + src_p, fh.write, 1 << 16)
                if sz and os.path.getsize(ls_) != sz: raise RuntimeError('拉下来的大小不对')
                if os.path.exists(lf): os.remove(lf)
                convert(ff, ls_, lf)
                up = dst_p + '.smtmp'
                with open(lf, 'rb') as fh: f.storbinary('STOR ' + up, fh, 1 << 16)
                got = [s for dd, nn, s in ls(d) if nn == out_name + '.smtmp']
                if not got or got[0] != os.path.getsize(lf): raise RuntimeError('传上去的大小不对')
                f.rename(up, dst_p); f.delete(src_p)            # 只删这一个 m4a 文件
                names.setdefault(d, set()).add(out_name)
                st['done'] += 1; st['bytes'] += os.path.getsize(lf); st['converted'].append(dst_p); st['converted'] = st['converted'][-200:]
            except Exception as e:
                st['err'] += 1; st['errs'].append({'path': src_p, 'err': str(e)[:200]}); st['errs'] = st['errs'][-50:]
                try: f.delete(dst_p + '.smtmp')
                except Exception: pass
                try: f.voidcmd('NOOP')
                except Exception:
                    f = ftplib.FTP(); f.connect(a.host, a.port, timeout=60); f.login('admin', 'admin'); f.encoding = 'utf-8'
            status()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    status(cur='', fin=True)
    try: f.quit()
    except Exception: pass

if __name__ == '__main__':
    main()
