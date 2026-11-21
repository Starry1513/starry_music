package com.starry.music;

import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 写歌曲信息（歌名 / 歌手 / 专辑 / 专辑歌手 / 曲序 / 年份 / 封面），只支持 FLAC 和 MP3。
 * 写到一个新文件里；写完比对音频数据（标签后面的部分）md5，不一样就当失败，原文件不动。
 */
public class Tagger {
    public static boolean writable(String name) { String n = name.toLowerCase(Locale.ROOT); return n.endsWith(".flac") || n.endsWith(".mp3"); }

    /** 读：歌名、歌手、专辑、时长（秒）。用系统自带的解析器，什么格式都行 */
    public static Map<String, String> read(String path) {
        Map<String, String> m = new HashMap<String, String>();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            put(m, "title", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            put(m, "artist", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            put(m, "album", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            put(m, "albumartist", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) m.put("dur", String.valueOf(Long.parseLong(d) / 1000));
        } catch (Exception ignored) {
        } finally { try { r.release(); } catch (Exception ignored) {} }
        return m;
    }
    static void put(Map<String, String> m, String k, String v) { if (v != null && !v.trim().isEmpty()) m.put(k, v.trim()); }

    /** 写：src → dst。nw 里有的键才改（title artist album albumartist track year）；cover 可以为 null */
    public static void write(File src, File dst, Map<String, String> nw, byte[] cover) throws IOException {
        String n = src.getName().toLowerCase(Locale.ROOT);
        long[] r0 = audioRange(src);
        if (n.endsWith(".flac")) writeFlac(src, dst, nw, cover);
        else if (n.endsWith(".mp3")) writeMp3(src, dst, nw, cover);
        else throw new IOException("这种格式在手机上不能写（只支持 flac、mp3）");
        if (!md5(src, r0[0], r0[1]).equals(md5(dst, audioRange(dst)[0], audioRange(dst)[1]))) { dst.delete(); throw new IOException("写完后音频数据对不上，放弃（原文件没动）"); }
    }

    /* ---------- 音频数据在文件里的位置（标签之外的部分） ---------- */
    static long[] audioRange(File f) throws IOException {
        String n = f.getName().toLowerCase(Locale.ROOT);
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            long len = r.length();
            if (n.endsWith(".flac")) {
                byte[] h = new byte[4]; r.readFully(h); if (!new String(h, "ISO-8859-1").equals("fLaC")) throw new IOException("不是 FLAC 文件");
                while (true) { int b = r.read(); int sz = (r.read() << 16) | (r.read() << 8) | r.read(); r.seek(r.getFilePointer() + sz); if ((b & 0x80) != 0) break; }
                return new long[]{r.getFilePointer(), len};
            }
            long st = 0;
            byte[] h = new byte[10]; r.readFully(h);
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') st = 10 + syncsafe(h, 6) + ((h[5] & 0x10) != 0 ? 10 : 0);
            long end = len;
            if (len > 128) { r.seek(len - 128); byte[] t = new byte[3]; r.readFully(t); if (t[0] == 'T' && t[1] == 'A' && t[2] == 'G') end = len - 128; }
            return new long[]{st, end};
        } finally { r.close(); }
    }
    static String md5(File f, long a, long b) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            RandomAccessFile r = new RandomAccessFile(f, "r");
            try { r.seek(a); byte[] buf = new byte[1 << 16]; long left = b - a; while (left > 0) { int k = r.read(buf, 0, (int) Math.min(buf.length, left)); if (k <= 0) break; md.update(buf, 0, k); left -= k; } }
            finally { r.close(); }
            StringBuilder sb = new StringBuilder(); for (byte x : md.digest()) sb.append(String.format("%02x", x)); return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    static int syncsafe(byte[] b, int o) { return ((b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14) | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f); }
    static void copyRange(File f, long a, long b, OutputStream out) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try { r.seek(a); byte[] buf = new byte[1 << 16]; long left = b - a; while (left > 0) { int k = r.read(buf, 0, (int) Math.min(buf.length, left)); if (k <= 0) break; out.write(buf, 0, k); left -= k; } }
        finally { r.close(); }
    }
    static String mime(byte[] img) { return img.length > 4 && (img[0] & 0xff) == 0x89 && img[1] == 'P' ? "image/png" : "image/jpeg"; }

    /* ---------- FLAC：改 VORBIS_COMMENT，换封面（PICTURE 类型 3） ---------- */
    static final Map<String, String> VC = new HashMap<String, String>();
    static { VC.put("title", "TITLE"); VC.put("artist", "ARTIST"); VC.put("album", "ALBUM"); VC.put("albumartist", "ALBUMARTIST"); VC.put("track", "TRACKNUMBER"); VC.put("year", "DATE"); }
    static void writeFlac(File src, File dst, Map<String, String> nw, byte[] cover) throws IOException {
        long audio = audioRange(src)[0];
        List<int[]> types = new ArrayList<int[]>(); List<byte[]> datas = new ArrayList<byte[]>();
        RandomAccessFile r = new RandomAccessFile(src, "r");
        try {
            r.seek(4);
            while (r.getFilePointer() < audio) { int b = r.read(); int sz = (r.read() << 16) | (r.read() << 8) | r.read(); byte[] d = new byte[sz]; r.readFully(d); types.add(new int[]{b & 0x7f}); datas.add(d); if ((b & 0x80) != 0) break; }
        } finally { r.close(); }
        // 注释块
        String vendor = "starry-music"; List<String> cm = new ArrayList<String>(); int vcAt = -1;
        for (int i = 0; i < types.size(); i++) if (types.get(i)[0] == 4) {
            vcAt = i; java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(datas.get(i)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            byte[] v = new byte[bb.getInt()]; bb.get(v); vendor = new String(v, Util.UTF8);
            int n = bb.getInt(); for (int k = 0; k < n; k++) { byte[] c = new byte[bb.getInt()]; bb.get(c); cm.add(new String(c, Util.UTF8)); }
            break;
        }
        Set<String> drop = new HashSet<String>(); for (String k : nw.keySet()) if (VC.containsKey(k)) drop.add(VC.get(k));
        List<String> keep = new ArrayList<String>();
        for (String c : cm) { int e = c.indexOf('='); String k = e < 0 ? c.toUpperCase(Locale.ROOT) : c.substring(0, e).toUpperCase(Locale.ROOT); if (!drop.contains(k)) keep.add(c); }
        for (Map.Entry<String, String> e : nw.entrySet()) if (VC.containsKey(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) keep.add(VC.get(e.getKey()) + "=" + e.getValue());
        ByteArrayOutputStream vc = new ByteArrayOutputStream(); byte[] vb = vendor.getBytes(Util.UTF8); le32(vc, vb.length); vc.write(vb); le32(vc, keep.size());
        for (String c : keep) { byte[] x = c.getBytes(Util.UTF8); le32(vc, x.length); vc.write(x); }
        // 重新排块：STREAMINFO 在最前；去掉旧的注释、旧的填充、（有新封面时）旧的封面
        List<int[]> ot = new ArrayList<int[]>(); List<byte[]> od = new ArrayList<byte[]>();
        for (int i = 0; i < types.size(); i++) {
            int t = types.get(i)[0];
            if (t == 1 || t == 4) continue;
            if (t == 6 && cover != null && datas.get(i).length >= 4 && datas.get(i)[3] == 3 && datas.get(i)[0] == 0) continue;
            ot.add(new int[]{t}); od.add(datas.get(i));
            if (i == 0 && t == 0) { ot.add(new int[]{4}); od.add(vc.toByteArray()); }
        }
        if (vcAt < 0 && !(types.size() > 0 && types.get(0)[0] == 0)) throw new IOException("FLAC 文件头不对");
        if (cover != null) {
            ByteArrayOutputStream pb = new ByteArrayOutputStream(); byte[] mt = mime(cover).getBytes(Util.UTF8);
            be32(pb, 3); be32(pb, mt.length); pb.write(mt); be32(pb, 0); be32(pb, 0); be32(pb, 0); be32(pb, 0); be32(pb, 0); be32(pb, cover.length); pb.write(cover);
            ot.add(new int[]{6}); od.add(pb.toByteArray());
        }
        ot.add(new int[]{1}); od.add(new byte[4096]);
        OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(dst), 1 << 16);
        try {
            out.write(new byte[]{'f', 'L', 'a', 'C'});
            for (int i = 0; i < ot.size(); i++) {
                byte[] d = od.get(i); if (d.length >= (1 << 24)) throw new IOException("封面太大");
                out.write((ot.get(i)[0] & 0x7f) | (i == ot.size() - 1 ? 0x80 : 0)); out.write((d.length >> 16) & 0xff); out.write((d.length >> 8) & 0xff); out.write(d.length & 0xff); out.write(d);
            }
            copyRange(src, audio, src.length(), out);
        } finally { out.close(); }
    }
    static void le32(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >> 8) & 0xff); o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff); }
    static void be32(ByteArrayOutputStream o, int v) { o.write((v >> 24) & 0xff); o.write((v >> 16) & 0xff); o.write((v >> 8) & 0xff); o.write(v & 0xff); }

    /* ---------- MP3：ID3v2（保持原来的 2.3 / 2.4 版本；没有就写 2.3），别的帧原样保留 ---------- */
    static void writeMp3(File src, File dst, Map<String, String> nw, byte[] cover) throws IOException {
        long[] ar = audioRange(src); int ver = 3; List<String> ids = new ArrayList<String>(); List<byte[]> fr = new ArrayList<byte[]>(); List<byte[]> fl = new ArrayList<byte[]>();
        if (ar[0] > 0) {
            RandomAccessFile r = new RandomAccessFile(src, "r");
            try {
                byte[] h = new byte[10]; r.readFully(h); ver = h[3]; int flags = h[5] & 0xff; int size = syncsafe(h, 6);
                byte[] tag = new byte[size]; r.readFully(tag);
                boolean unsync = (flags & 0x80) != 0;
                if (!unsync && (ver == 3 || ver == 4)) {
                    int p = 0;
                    if ((flags & 0x40) != 0) { if (ver == 3) p = 4 + ((tag[0] & 0xff) << 24 | (tag[1] & 0xff) << 16 | (tag[2] & 0xff) << 8 | (tag[3] & 0xff)); else p = syncsafe(tag, 0); }
                    while (p + 10 <= tag.length) {
                        if (tag[p] == 0) break;
                        String id = new String(tag, p, 4, "ISO-8859-1");
                        int fs = ver == 4 ? syncsafe(tag, p + 4) : ((tag[p + 4] & 0xff) << 24 | (tag[p + 5] & 0xff) << 16 | (tag[p + 6] & 0xff) << 8 | (tag[p + 7] & 0xff));
                        if (fs < 0 || p + 10 + fs > tag.length) break;
                        byte[] d = new byte[fs]; System.arraycopy(tag, p + 10, d, 0, fs);
                        ids.add(id); fr.add(d); fl.add(new byte[]{tag[p + 8], tag[p + 9]});
                        p += 10 + fs;
                    }
                }
                if (ver != 3 && ver != 4) ver = 3;
            } finally { r.close(); }
        }
        Map<String, String> TF = new HashMap<String, String>();
        TF.put("title", "TIT2"); TF.put("artist", "TPE1"); TF.put("album", "TALB"); TF.put("albumartist", "TPE2"); TF.put("track", "TRCK"); TF.put("year", ver == 4 ? "TDRC" : "TYER");
        Set<String> drop = new HashSet<String>(); for (String k : nw.keySet()) if (TF.containsKey(k)) drop.add(TF.get(k));
        if (nw.containsKey("year")) { drop.add("TYER"); drop.add("TDRC"); }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (drop.contains(id) || (cover != null && id.equals("APIC") && apicType(fr.get(i)) == 3)) continue;
            frame(body, ver, id, fr.get(i), fl.get(i));
        }
        for (Map.Entry<String, String> e : nw.entrySet()) {
            if (!TF.containsKey(e.getKey()) || e.getValue() == null || e.getValue().isEmpty()) continue;
            frame(body, ver, TF.get(e.getKey()), text(ver, e.getValue()), new byte[2]);
        }
        if (cover != null) {
            ByteArrayOutputStream a = new ByteArrayOutputStream(); a.write(0); byte[] mt = mime(cover).getBytes("ISO-8859-1"); a.write(mt); a.write(0); a.write(3); a.write(0); a.write(cover);
            frame(body, ver, "APIC", a.toByteArray(), new byte[2]);
        }
        body.write(new byte[2048]);   // 留点空位
        byte[] b = body.toByteArray(); int sz = b.length;
        OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(dst), 1 << 16);
        try {
            out.write(new byte[]{'I', 'D', '3', (byte) ver, 0, 0, (byte) ((sz >> 21) & 0x7f), (byte) ((sz >> 14) & 0x7f), (byte) ((sz >> 7) & 0x7f), (byte) (sz & 0x7f)});
            out.write(b);
            copyRange(src, ar[0], src.length(), out);   // 音频 + 原来的 ID3v1（如果有）
        } finally { out.close(); }
    }
    static int apicType(byte[] d) {
        try { int enc = d[0]; int p = 1; while (d[p] != 0) p++; p++; return d[p] & 0xff; } catch (Exception e) { return -1; }
    }
    static byte[] text(int ver, String s) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (ver == 4) { o.write(3); o.write(s.getBytes(Util.UTF8)); }
        else { o.write(1); o.write(0xff); o.write(0xfe); o.write(s.getBytes("UTF-16LE")); }
        return o.toByteArray();
    }
    static void frame(ByteArrayOutputStream o, int ver, String id, byte[] d, byte[] flags) throws IOException {
        o.write(id.getBytes("ISO-8859-1")); int n = d.length;
        if (ver == 4) { o.write((n >> 21) & 0x7f); o.write((n >> 14) & 0x7f); o.write((n >> 7) & 0x7f); o.write(n & 0x7f); }
        else { o.write((n >> 24) & 0xff); o.write((n >> 16) & 0xff); o.write((n >> 8) & 0xff); o.write(n & 0xff); }
        o.write(flags); o.write(d);
    }

    public static byte[] fetch(String url) {
        try { Util.Resp r = Util.http("GET", url, Core.UA(), null, 20000); return r.code == 200 && r.body.length > 1000 && r.body.length < 8000000 ? r.body : null; } catch (Exception e) { return null; }
    }
}
