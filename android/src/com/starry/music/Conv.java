package com.starry.music;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手机上把 .m4a（苹果无损 ALAC、也能是 AAC）转成 .flac，不用电脑：
 * 系统解码器解出原始采样 → 自己的 FlacEnc 无损编码 → 歌名 / 歌手 / 专辑 / 曲序 / 年份 / 封面 用 Tagger 写回去。
 * ALAC 转出来和原来一模一样（无损对无损）；AAC 本来就是有损的，转出来不会变好，只是播放器能放了。
 */
public class Conv {
    public static boolean needs(String name) { String n = name.toLowerCase(java.util.Locale.ROOT); return n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".alac"); }

    /** 和 tran.bat 一样：去掉文件名开头的「数字 + 一个点 + 空格」，"06. 秋梦" → "秋梦" */
    public static String cleanName(String stem) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*\\d+\\s*\\.\\s*(.+)$").matcher(stem);
        String s = m.find() ? m.group(1).trim() : stem; return s.isEmpty() ? stem : s;
    }

    public interface Prog { void at(int pct); }

    static final Object LOCK = new Object();
    public static void toFlac(File src, File dst, Prog prog) throws Exception {
        File raw = new File(dst.getPath() + ".raw.flac");
        // 一次只转一首：两个解码器同时开，系统会把其中一个收回去（报 “Released state”）
        synchronized (LOCK) {
            try {
                // ALAC 的 csd（解码参数）各家解码器要的样子不一样：有的要带 12 字节的 'alac' 盒子头，有的不要。按顺序试
                // ALAC（苹果无损）用自己写的解码器：有的手机自带的 ALAC 解码器不认苹果音乐的文件
                if (!decodeAlac(src, raw, prog)) {
                    Exception last = null; boolean ok = false;
                    for (int v = 0; v < 3 && !ok; v++) {
                        try { decodeTo(src, raw, prog, v); ok = true; }
                        catch (CsdErr e) { if (last == null || !e.getMessage().startsWith("跳过")) last = e; raw.delete(); }
                    }
                    if (!ok) throw last;
                }
                copyTags(src, raw, dst);
            }
            finally { raw.delete(); }
        }
    }
    /** 优先用软件解码器：硬件解码器个数有限，会被系统收回；软件的稳定 */
    static MediaCodec pickDecoder(String mime) throws IOException {
        android.media.MediaCodecList L = new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
        String sw = null, any = null;
        for (android.media.MediaCodecInfo ci : L.getCodecInfos()) {
            if (ci.isEncoder()) continue; boolean ok = false; for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) ok = true; if (!ok) continue;
            String n = ci.getName(); if (any == null) any = n;
            boolean soft = android.os.Build.VERSION.SDK_INT >= 29 ? ci.isSoftwareOnly() : (n.startsWith("OMX.google") || n.startsWith("c2.android") || n.contains(".sw."));
            if (soft && sw == null) sw = n;
        }
        String use = sw != null ? sw : any; if (use == null) throw new IOException("这台设备解不了这种格式：" + mime);
        return MediaCodec.createByCodecName(use);
    }

    /** 是 ALAC 就自己解，返回 true；不是 ALAC 返回 false（交给系统解码器） */
    static boolean decodeAlac(File src, File out, Prog prog) throws Exception {
        MediaExtractor ex = new MediaExtractor(); ex.setDataSource(src.getPath());
        try {
            int tr = -1; MediaFormat f = null;
            for (int i = 0; i < ex.getTrackCount(); i++) { MediaFormat x = ex.getTrackFormat(i); if ("audio/alac".equals(x.getString(MediaFormat.KEY_MIME))) { tr = i; f = x; break; } }
            if (tr < 0 || !f.containsKey("csd-0")) return false;
            ex.selectTrack(tr);
            ByteBuffer cb = f.getByteBuffer("csd-0").duplicate(); byte[] ck = new byte[cb.remaining()]; cb.get(ck);
            AlacDec d = new AlacDec(ck);
            long durUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            FlacEnc enc = new FlacEnc(out, d.sampleRate > 0 ? d.sampleRate : f.getInteger(MediaFormat.KEY_SAMPLE_RATE), d.numChannels, d.bitDepth);
            int[] pcm = new int[d.frameLength * d.numChannels]; ByteBuffer buf = ByteBuffer.allocate(Math.max(1 << 16, d.frameLength * d.numChannels * 4 + 1024)); int lastPct = -1;
            try {
                while (true) {
                    long ss = ex.getSampleSize(); if (ss < 0) break;
                    if (ss > buf.capacity()) buf = ByteBuffer.allocate((int) ss * 2);
                    buf.clear(); int n = ex.readSampleData(buf, 0); if (n < 0) break;
                    byte[] a = buf.array();
                    int fr = d.decode(a, n, pcm);
                    if (fr > 0) enc.write(pcm, fr);
                    if (prog != null && durUs > 0) { int pct = (int) Math.min(99, ex.getSampleTime() * 100 / durUs); if (pct != lastPct) { lastPct = pct; prog.at(pct); } }
                    ex.advance();
                }
                enc.finish(); enc = null;
            } finally { if (enc != null) try { enc.finish(); } catch (Exception ignored) {} }
            return true;
        } finally { ex.release(); }
    }

    static class CsdErr extends IOException { CsdErr(String m) { super(m); } }
    /** v=0 原样；v=1 去掉 'alac' 盒子头只留 24 字节；v=2 补上盒子头 */
    static boolean fixCsd(MediaFormat f, int v) {
        if (v == 0 || !"audio/alac".equals(f.getString(MediaFormat.KEY_MIME)) || !f.containsKey("csd-0")) return v == 0;
        ByteBuffer b = f.getByteBuffer("csd-0").duplicate(); byte[] a = new byte[b.remaining()]; b.get(a);
        boolean hasHead = a.length >= 36 && a[4] == 'a' && a[5] == 'l' && a[6] == 'a' && a[7] == 'c';
        byte[] cfg = hasHead ? java.util.Arrays.copyOfRange(a, 12, Math.min(a.length, 36)) : java.util.Arrays.copyOf(a, Math.min(a.length, 24));
        byte[] out;
        if (v == 1) { if (!hasHead && a.length == 24) return false; out = cfg; }
        else { if (hasHead) return false; out = new byte[12 + cfg.length]; out[3] = (byte) (12 + cfg.length); out[4] = 'a'; out[5] = 'l'; out[6] = 'a'; out[7] = 'c'; System.arraycopy(cfg, 0, out, 12, cfg.length); }
        f.setByteBuffer("csd-0", ByteBuffer.wrap(out)); return true;
    }
    static String hex(MediaFormat f) { try { ByteBuffer b = f.getByteBuffer("csd-0").duplicate(); StringBuilder sb = new StringBuilder(); while (b.hasRemaining() && sb.length() < 100) sb.append(String.format("%02x", b.get() & 0xff)); return sb.toString(); } catch (Exception e) { return "-"; } }

    static void decodeTo(File src, File out, Prog prog, int csdVar) throws Exception {
        MediaExtractor ex = new MediaExtractor(); ex.setDataSource(src.getPath());
        int tr = -1; MediaFormat inF = null;
        for (int i = 0; i < ex.getTrackCount(); i++) { MediaFormat f = ex.getTrackFormat(i); String m = f.getString(MediaFormat.KEY_MIME); if (m != null && m.startsWith("audio/")) { tr = i; inF = f; break; } }
        if (tr < 0) { ex.release(); throw new IOException("文件里没有声音"); }
        ex.selectTrack(tr);
        String mime = inF.getString(MediaFormat.KEY_MIME);
        if (!fixCsd(inF, csdVar)) { ex.release(); throw new CsdErr("跳过这种参数写法"); }
        long durUs = inF.containsKey(MediaFormat.KEY_DURATION) ? inF.getLong(MediaFormat.KEY_DURATION) : 0;
        int srcBits = alacBits(inF);
        MediaCodec dec;
        try { dec = pickDecoder(mime); } catch (Exception e) { ex.release(); throw new IOException("这台设备解不了这种格式：" + mime); }
        // ALAC 一帧可能很大：系统默认的输入缓冲装不下会直接崩（native 里越界拷贝），先把缓冲开够
        int need = 1 << 20; try { if (inF.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) need = Math.max(need, inF.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)); } catch (Exception ignored) {}
        inF.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, need);
        dec.configure(inF, null, null, 0); dec.start();
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        FlacEnc enc = null; int ch = 0, enc0 = 2, bps = 16; int[] tmp = new int[0]; long encFrames = 0;
        boolean inEnd = false, outEnd = false; int lastPct = -1; ByteBuffer own = ByteBuffer.allocateDirect(8 << 20);
        try {
            while (!outEnd) {
                if (!inEnd) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        // 先读进自己开的缓冲（按这一帧的大小开），再拷给解码器：直接读进解码器的缓冲，有的机器会在系统里越界崩掉
                        long ss = ex.getSampleSize(); if (ss * 2 > own.capacity()) own = ByteBuffer.allocateDirect((int) Math.max(ss * 2, own.capacity() * 2L));
                        own.clear(); int n = ex.readSampleData(own, 0);
                        ByteBuffer b = dec.getInputBuffer(ii); b.clear();
                        if (n > 0) { if (n > b.remaining()) throw new IOException("一帧太大（" + n + " 字节），这台设备的解码器装不下"); own.position(0); own.limit(n); b.put(own); }
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true; }
                        else { long t = ex.getSampleTime(); dec.queueInputBuffer(ii, 0, n, t, 0); ex.advance();
                            if (prog != null && durUs > 0) { int pct = (int) Math.min(99, t * 100 / durUs); if (pct != lastPct) { lastPct = pct; prog.at(pct); } } }
                    }
                }
                int oi = dec.dequeueOutputBuffer(bi, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (oi >= 0 && enc == null)) {
                    MediaFormat of = dec.getOutputFormat();
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT); int rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    enc0 = of.containsKey("pcm-encoding") ? of.getInteger("pcm-encoding") : 2;
                    bps = enc0 == 2 ? 16 : enc0 == 3 ? 8 : (srcBits > 0 && srcBits <= 16 ? 16 : 24);
                    if (enc == null) enc = new FlacEnc(out, rate, ch, bps);
                    if (oi < 0) continue;
                }
                if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi); ob.position(bi.offset); ob.limit(bi.offset + bi.size); ob.order(ByteOrder.LITTLE_ENDIAN);
                    int bytesPer = enc0 == 2 ? 2 : enc0 == 3 ? 1 : enc0 == 21 ? 3 : 4;
                    int ns = bi.size / bytesPer; if (tmp.length < ns) tmp = new int[ns];
                    for (int i = 0; i < ns; i++) {
                        int v;
                        switch (enc0) {
                            case 2: v = ob.getShort(); break;
                            case 3: v = (ob.get() & 0xff) - 128; break;
                            case 4: { float fl = ob.getFloat(); double d = fl * (double) (1 << (bps - 1)); long L = Math.round(d); long mx = (1L << (bps - 1)) - 1, mn = -(1L << (bps - 1)); v = (int) Math.max(mn, Math.min(mx, L)); break; }
                            case 21: { int b0 = ob.get() & 0xff, b1 = ob.get() & 0xff, b2 = ob.get(); v = (b2 << 16) | (b1 << 8) | b0; if (bps == 16) v >>= 8; break; }
                            default: { int x = ob.getInt(); v = x >> (32 - bps); }
                        }
                        tmp[i] = v;
                    }
                    if (ns > 0) { enc.write(tmp, ns / ch); encFrames += ns / ch; }
                    dec.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEnd = true;
                }
            }
            if (enc == null) throw new IOException("没解出声音");
            enc.finish(); enc = null;
        } catch (IllegalStateException e) {
            // 解码器一开始就报错（多半是 ALAC 参数写法不合它的口味）：换一种写法再来
            if (enc == null || encFrames == 0) throw new CsdErr("解码器不认这个 ALAC 参数（csd=" + hex(inF) + "）：" + e.getMessage());
            throw e;
        } finally {
            try { dec.stop(); } catch (Exception ignored) {} dec.release(); ex.release();
            if (enc != null) try { enc.finish(); } catch (Exception ignored) {}
        }
    }

    /** ALAC 的位深在“magic cookie”（csd-0）里 */
    static int alacBits(MediaFormat f) {
        try {
            if (!"audio/alac".equals(f.getString(MediaFormat.KEY_MIME)) || !f.containsKey("csd-0")) return 0;
            ByteBuffer b = f.getByteBuffer("csd-0").duplicate(); byte[] a = new byte[b.remaining()]; b.get(a);
            int o = 0; if (a.length >= 36 && a[4] == 'a' && a[5] == 'l' && a[6] == 'a' && a[7] == 'c') o = 12;   // 前面可能带一个 12 字节的 'alac' 盒子头
            return a.length > o + 5 ? a[o + 5] & 0xff : 0;
        } catch (Exception e) { return 0; }
    }

    /** 把原文件的歌名、歌手、专辑、曲序、年份、封面带到 flac 上 */
    static void copyTags(File src, File raw, File dst) throws IOException {
        Map<String, String> m = new LinkedHashMap<String, String>(); byte[] cover = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(src.getPath());
            put(m, "title", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            put(m, "artist", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            put(m, "album", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            put(m, "albumartist", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            String tn = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER); if (tn != null) put(m, "track", tn.split("/")[0].trim());
            String y = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
            if (y == null) { String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE); if (d != null && d.length() >= 4 && d.substring(0, 4).matches("\\d{4}")) y = d.substring(0, 4); }
            put(m, "year", y);
            cover = r.getEmbeddedPicture();
        } catch (Exception ignored) {
        } finally { try { r.release(); } catch (Exception ignored) {} }
        if (m.isEmpty() && cover == null) { if (!raw.renameTo(dst)) throw new IOException("改名失败"); return; }
        Tagger.write(raw, dst, m, cover);
    }
    static void put(Map<String, String> m, String k, String v) { if (v != null && !v.trim().isEmpty()) m.put(k, v.trim()); }
}
