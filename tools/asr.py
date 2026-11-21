# 群星音乐 · 自动听写歌词（找不到歌词、又不是纯音乐的歌）
# 用 sherpa-onnx 的 SenseVoice（中 / 英 / 日 / 韩 / 粤）+ silero VAD，在本机离线识别，不上传任何音频。
# 用法：python asr.py <音频文件> <模型文件夹>      → 标准输出一行 JSON：[{"t": 开始秒, "e": 结束秒, "text": "…"}]
# 模型文件夹里要有 silero_vad.onnx 和 sherpa-onnx-sense-voice-*/model.int8.onnx、tokens.txt（群星“实时字幕”装过的那份就行）
import sys, os, json, re, glob

def load_audio(path, sr=16000, stereo=False):
    import av, numpy as np
    c = av.open(path); s = c.streams.audio[0]
    rs = av.audio.resampler.AudioResampler(format='fltp', layout='stereo' if stereo else 'mono', rate=sr)
    out = []
    for fr in c.decode(s):
        for x in rs.resample(fr): out.append(x.to_ndarray())
    for x in rs.resample(None): out.append(x.to_ndarray())
    a = np.concatenate(out, axis=1).astype('float32') if out else np.zeros((2 if stereo else 1, 0), dtype='float32')
    return a if stereo else a[0]

def vocals(path, models):
    # 先把人声从伴奏里分出来（spleeter 2 声部），识别准很多；没有分离模型就直接用原曲
    import numpy as np, sherpa_onnx
    d = os.path.join(models, 'sherpa-onnx-spleeter-2stems-fp16')
    if not os.path.exists(os.path.join(d, 'vocals.fp16.onnx')): return None
    sep = sherpa_onnx.OfflineSourceSeparation(sherpa_onnx.OfflineSourceSeparationConfig(model=sherpa_onnx.OfflineSourceSeparationModelConfig(
        spleeter=sherpa_onnx.OfflineSourceSeparationSpleeterModelConfig(vocals=os.path.join(d, 'vocals.fp16.onnx'), accompaniment=os.path.join(d, 'accompaniment.fp16.onnx')),
        num_threads=max(1, (os.cpu_count() or 4) // 2))))
    a = load_audio(path, 44100, stereo=True)
    v, step, pad, N = [], 44100 * 60, 44100 * 2, a.shape[1]   # 一分钟一段省内存；每段前后多带 2 秒再切掉（分离模型在段头会冒一下爆音）
    for i in range(0, N, step):
        lo, hi = max(0, i - pad), min(N, i + step + pad)
        o = np.asarray(sep.process(44100, np.ascontiguousarray(a[:, lo:hi])).stems[0].data, dtype='float32').mean(axis=0)
        v.append(o[i - lo: i - lo + min(step, N - i)])
    v = np.concatenate(v) if v else np.zeros(0, dtype='float32')
    v[:min(len(v), 44100)] *= np.linspace(0, 1, min(len(v), 44100))   # 开头也淡入
    # 44.1k → 16k
    n = int(len(v) * 16000 / 44100)
    return np.interp(np.linspace(0, len(v) - 1, n), np.arange(len(v)), v).astype('float32') if n else v

def clean(t):
    t = re.sub(r'<\|[^|]*\|>', '', t).strip()
    if t and t.upper() == t and any('A' <= c <= 'Z' for c in t): t = t.lower(); t = t[0].upper() + t[1:]
    t = re.sub(r'(?<=[^\x00-\x7f]) +(?=[^\x00-\x7f])', '', t)
    return t.strip(' ，,。.')

def main():
    import numpy as np, sherpa_onnx
    path, models = sys.argv[1], sys.argv[2]
    sv = sorted(glob.glob(os.path.join(models, 'sherpa-onnx-sense-voice*')))
    if not sv: raise SystemExit('没找到 SenseVoice 模型：' + models)
    def mk(lang):
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(model=os.path.join(sv[-1], 'model.int8.onnx'), tokens=os.path.join(sv[-1], 'tokens.txt'),
                                                              num_threads=max(1, (os.cpu_count() or 4) // 2), use_itn=True, language=lang)
    rec = mk('auto')
    SR = 16000
    a = vocals(path, models)
    if a is None: a = load_audio(path, SR)
    pk = float(np.percentile(np.abs(a), 99.9)) if len(a) else 0   # 按 99.9% 的响度拉齐，个别爆音不拖累整体
    if pk > 0: a = np.clip(a * (0.7 / pk), -1, 1).astype('float32')
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = os.path.join(models, 'silero_vad.onnx')
    cfg.silero_vad.threshold = 0.4
    cfg.silero_vad.min_silence_duration = 0.4    # 歌里换气就断成一句
    cfg.silero_vad.min_speech_duration = 0.4
    cfg.silero_vad.max_speech_duration = 9       # 一句最长 9 秒，太长的切开，歌词行才不会太长
    cfg.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=600)
    W = cfg.silero_vad.window_size
    chunks = []
    def take():
        while not vad.empty():
            s = vad.front; st = s.start / SR; smp = np.array(s.samples, dtype='float32'); vad.pop()
            if len(smp) >= SR * 0.4: chunks.append((st, smp))
    for i in range(0, len(a) - W + 1, W):
        vad.accept_waveform(a[i:i + W]); take()
    vad.flush(); take()
    def run(r):
        out = []
        for st, smp in chunks:
            stream = r.create_stream(); stream.accept_waveform(SR, smp); r.decode_stream(stream)
            out.append((st, len(smp) / SR, stream.result.lang, clean(stream.result.text)))
        return out
    res = run(rec)
    # 一首歌基本一种语言：按识别出的字数投票，再用这种语言整首重听一遍，免得中英乱跳
    votes = {}
    for _, _, lg, t in res:
        lg = re.sub(r'[<|>]', '', lg or '')
        if lg in ('zh', 'en', 'ja', 'ko', 'yue'): votes[lg] = votes.get(lg, 0) + len(t)
    if votes:
        best = max(votes, key=votes.get)
        if votes[best] < sum(votes.values()) * 0.85: res = run(mk(best))
    segs = []
    for st, du, _, t in res:
        cjk = len(re.findall(r'[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]', t)); words = len(re.findall(r'[A-Za-z\']+', t))
        n = cjk + words * 2                             # 一个英文词≈两个汉字的分量
        if n < 3 or (du > 5 and n < 4): continue      # 太短的多半是哼唱 / 和声 / 一个 "Yeah"，扔掉
        segs.append({'t': round(st, 2), 'e': round(st + du, 2), 'text': t})
    sys.stdout.write(json.dumps(segs, ensure_ascii=False))

if __name__ == '__main__':
    main()
