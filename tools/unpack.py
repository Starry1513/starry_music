# 群星音乐 · 解压导入文件夹里的压缩包（zip 自带支持；7z / rar 用电脑上装的 7-Zip）
# 用法：python unpack.py <压缩包> <解压到的文件夹>   → 标准输出 JSON：{"files": [...解出来的文件...]}
# zip 里的中文文件名常是 GBK 编码（Windows 压的），按 GBK 改回来，不出乱码。
import sys, os, json, zipfile, shutil, subprocess

AUDIO = ('.flac', '.mp3', '.m4a', '.ogg', '.wav', '.aac', '.ape', '.aiff', '.aif', '.dsf', '.dff', '.wma', '.jpg', '.png', '.lrc', '.cue')

def safe_join(root, name):
    p = os.path.normpath(os.path.join(root, name))
    if not p.startswith(os.path.normpath(root) + os.sep): raise ValueError('压缩包里有不安全的路径：' + name)
    return p

def unzip(src, dst):
    out = []
    with zipfile.ZipFile(src) as z:
        for i in z.infolist():
            name = i.filename
            if not (i.flag_bits & 0x800):   # 没标 UTF-8：多半是 GBK
                try: name = name.encode('cp437').decode('gbk')
                except Exception: pass
            if name.endswith('/') or '__MACOSX' in name: continue
            if not name.lower().endswith(AUDIO): continue
            p = safe_join(dst, name); os.makedirs(os.path.dirname(p), exist_ok=True)
            with z.open(i) as a, open(p, 'wb') as b: shutil.copyfileobj(a, b, 1 << 20)
            out.append(p)
    return out

def seven(src, dst):
    exe = next((x for x in [os.environ.get('SEVENZIP', ''), r'C:\Program Files\7-Zip\7z.exe', r'C:\Program Files (x86)\7-Zip\7z.exe', shutil.which('7z') or '', shutil.which('7za') or ''] if x and os.path.exists(x)), None)
    if not exe: raise SystemExit('7z / rar 需要电脑上装 7-Zip（https://www.7-zip.org），zip 不用')
    before = set()
    for r, _, fs in os.walk(dst):
        for f in fs: before.add(os.path.join(r, f))
    p = subprocess.run([exe, 'x', '-y', '-o' + dst, src], capture_output=True)
    if p.returncode not in (0, 1): raise SystemExit('7-Zip 解压失败（可能有密码或文件坏了）')
    out = []
    for r, _, fs in os.walk(dst):
        for f in fs:
            q = os.path.join(r, f)
            if q not in before and f.lower().endswith(AUDIO): out.append(q)
    return out

if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    files = unzip(src, dst) if src.lower().endswith('.zip') else seven(src, dst)
    sys.stdout.write(json.dumps({'files': files}, ensure_ascii=False))
