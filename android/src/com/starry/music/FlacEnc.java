package com.starry.music;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

/**
 * 自己写的 FLAC 编码器（纯 Java，不靠系统编码器）：无损。
 * 每块 4096 个采样；每个声道在「常数 / 原样 / 固定预测 0~4 阶」里挑最省的，残差用 Rice 编码（分区、自动挑参数）；
 * 双声道再在 左右 / 左+差 / 差+右 / 中+差 里挑最省的。压缩率大概相当于 flac 命令行的 -2～-3。
 * 文件开头写 STREAMINFO（总采样数、MD5 在结束时回填），标签和封面之后由 Tagger 加。
 */
public class FlacEnc {
    static final int BS = 4096;
    final RandomAccessFile f; final int rate, ch, bps;
    final int[][] buf; int fill = 0; long frameNo = 0, total = 0; int minFrame = Integer.MAX_VALUE, maxFrame = 0;
    final MessageDigest md5; final byte[] mdb;

    public FlacEnc(File out, int rate, int ch, int bps) throws IOException {
        if (ch < 1 || ch > 8) throw new IOException("声道数不支持：" + ch);
        if (bps != 16 && bps != 24 && bps != 8) throw new IOException("位深不支持：" + bps);
        this.rate = rate; this.ch = ch; this.bps = bps;
        buf = new int[ch][BS];
        try { md5 = MessageDigest.getInstance("MD5"); } catch (Exception e) { throw new IOException(e); }
        mdb = new byte[BS * ch * (bps / 8)];
        f = new RandomAccessFile(out, "rw"); f.setLength(0);
        f.write(new byte[]{'f', 'L', 'a', 'C'});
        f.write(streamInfo(true));
    }

    /** 送一段交错的采样（每个采样已是 bps 位的有符号整数） */
    public void write(int[] inter, int frames) throws IOException {
        int i = 0;
        while (i < frames) {
            int n = Math.min(frames - i, BS - fill);
            for (int k = 0; k < n; k++) for (int c = 0; c < ch; c++) buf[c][fill + k] = inter[(i + k) * ch + c];
            fill += n; i += n;
            if (fill == BS) flush();
        }
    }

    public void finish() throws IOException {
        if (fill > 0) flush();
        f.seek(4); f.write(streamInfo(true)); f.close();
    }

    byte[] streamInfo(boolean last) throws IOException {
        BitW w = new BitW();
        w.bits(last ? 1 : 0, 1); w.bits(0, 7); w.bits(34, 24);
        w.bits(BS, 16); w.bits(BS, 16);
        w.bits(minFrame == Integer.MAX_VALUE ? 0 : minFrame, 24); w.bits(maxFrame, 24);
        w.bits(rate, 20); w.bits(ch - 1, 3); w.bits(bps - 1, 5); w.lbits(total, 36);
        byte[] h = new byte[16];
        try { if (total > 0) h = ((MessageDigest) md5.clone()).digest(); } catch (CloneNotSupportedException e) { h = new byte[16]; }
        for (byte b : h) w.bits(b & 0xff, 8);
        return w.bytes();
    }

    void flush() throws IOException {
        int n = fill; fill = 0;
        // MD5：原始采样，小端、交错
        int bb = bps / 8, p = 0;
        for (int k = 0; k < n; k++) for (int c = 0; c < ch; c++) { int v = buf[c][k]; for (int b = 0; b < bb; b++) mdb[p++] = (byte) (v >> (8 * b)); }
        md5.update(mdb, 0, p);
        total += n;
        byte[] fr = frame(n);
        f.write(fr);
        minFrame = Math.min(minFrame, fr.length); maxFrame = Math.max(maxFrame, fr.length);
        frameNo++;
    }

    byte[] frame(int n) {
        // 选声道组合
        int mode = ch - 1; int[][] sig = new int[ch][]; int[] sbps = new int[ch];
        for (int c = 0; c < ch; c++) { sig[c] = java.util.Arrays.copyOf(buf[c], n); sbps[c] = bps; }
        if (ch == 2) {
            int[] L = sig[0], R = sig[1], S = new int[n], M = new int[n];
            for (int k = 0; k < n; k++) { S[k] = L[k] - R[k]; M[k] = (L[k] + R[k]) >> 1; }
            long cl = est(L, n), cr = est(R, n), cs = est(S, n), cm = est(M, n);
            long[] cost = {cl + cr, cl + cs, cs + cr, cm + cs};
            int best = 0; for (int k = 1; k < 4; k++) if (cost[k] < cost[best]) best = k;
            if (best == 1) { mode = 8; sig = new int[][]{L, S}; sbps = new int[]{bps, bps + 1}; }
            else if (best == 2) { mode = 9; sig = new int[][]{S, R}; sbps = new int[]{bps + 1, bps}; }
            else if (best == 3) { mode = 10; sig = new int[][]{M, S}; sbps = new int[]{bps, bps + 1}; }
        }
        BitW w = new BitW();
        // 帧头
        w.bits(0xFFF8, 16);
        int bsCode, srCode = srCode(rate);
        if (n == BS) bsCode = 12; else if (n <= 256) bsCode = 6; else bsCode = 7;
        w.bits(bsCode, 4); w.bits(srCode, 4); w.bits(mode, 4);
        w.bits(bps == 16 ? 4 : bps == 24 ? 6 : 1, 3); w.bits(0, 1);
        utf8(w, frameNo);
        if (bsCode == 6) w.bits(n - 1, 8); else if (bsCode == 7) w.bits(n - 1, 16);
        byte[] head = w.bytes(); w.bits(crc8(head, head.length), 8);
        for (int c = 0; c < sig.length; c++) subframe(w, sig[c], n, sbps[c]);
        w.align();
        byte[] body = w.bytes();
        int crc = crc16(body, body.length);
        w.bits(crc, 16);
        return w.bytes();
    }

    static int srCode(int r) {
        switch (r) { case 88200: return 1; case 176400: return 2; case 192000: return 3; case 8000: return 4; case 16000: return 5; case 22050: return 6; case 24000: return 7; case 32000: return 8; case 44100: return 9; case 48000: return 10; case 96000: return 11; default: return 0; }
    }

    /* ---------- 子帧 ---------- */
    static long[] res(int[] x, int n, int order) {
        long[] r = new long[n - order];
        for (int i = order; i < n; i++) {
            long p;
            switch (order) {
                case 0: p = 0; break;
                case 1: p = x[i - 1]; break;
                case 2: p = 2L * x[i - 1] - x[i - 2]; break;
                case 3: p = 3L * x[i - 1] - 3L * x[i - 2] + x[i - 3]; break;
                default: p = 4L * x[i - 1] - 6L * x[i - 2] + 4L * x[i - 3] - x[i - 4];
            }
            r[i - order] = x[i] - p;
        }
        return r;
    }
    /** 粗估：最好的固定阶数下大概要多少位 */
    static long est(int[] x, int n) {
        long best = Long.MAX_VALUE;
        for (int o = 0; o <= 4 && o < n; o++) {
            long[] r = res(x, n, o); long s = 0; for (long v : r) s += Math.abs(v);
            double mean = r.length == 0 ? 0 : (double) s / r.length; int k = mean < 1 ? 0 : (int) Math.floor(Math.log(mean) / Math.log(2)); if (k > 30) k = 30;
            long bits = (long) r.length * (k + 1); for (long v : r) bits += zig(v) >>> k;
            if (bits < best) best = bits;
        }
        return best;
    }
    static long zig(long v) { return v >= 0 ? v << 1 : ((-v) << 1) - 1; }

    void subframe(BitW w, int[] x, int n, int sb) {
        boolean same = true; for (int i = 1; i < n; i++) if (x[i] != x[0]) { same = false; break; }
        if (same) { w.bits(0, 1); w.bits(0, 6); w.bits(0, 1); w.sbits(x[0], sb); return; }
        long verb = (long) n * sb; int bestO = -1; long bestC = verb; Plan bestP = null;
        for (int o = 0; o <= 4 && o < n; o++) {
            Plan pl = plan(res(x, n, o), n, o); long c = (long) o * sb + pl.bits;
            if (c < bestC) { bestC = c; bestO = o; bestP = pl; }
        }
        if (bestO < 0) { w.bits(0, 1); w.bits(1, 6); w.bits(0, 1); for (int i = 0; i < n; i++) w.sbits(x[i], sb); return; }
        w.bits(0, 1); w.bits(8 | bestO, 6); w.bits(0, 1);
        for (int i = 0; i < bestO; i++) w.sbits(x[i], sb);
        writeRes(w, bestP);
    }

    static class Plan { long[] r; int order, po, method; int[] k; int[] esc; long bits; }

    /** 残差的 Rice 分区：分区阶数 0~6 里挑，每个分区挑参数（太大的用“原样”逃逸） */
    static Plan plan(long[] r, int n, int order) {
        Plan best = null;
        for (int po = 0; po <= 6; po++) {
            int parts = 1 << po; if (n % parts != 0) break; int ps = n / parts; if (ps <= order) break;
            int[] ks = new int[parts], esc = new int[parts]; long bits = 2 + 4; boolean big = false;
            int off = 0;
            for (int pi = 0; pi < parts; pi++) {
                int cnt = pi == 0 ? ps - order : ps;
                long bestc = Long.MAX_VALUE; int bk = 0;
                long maxAbs = 0; for (int i = 0; i < cnt; i++) maxAbs = Math.max(maxAbs, Math.abs(r[off + i]));
                // 参数从平均值估一个，再左右各试两个（全试 0~30 太慢）
                long sum = 0; for (int i = 0; i < cnt; i++) sum += zig(r[off + i]);
                int k0 = 0; if (cnt > 0) { long m = sum / cnt; while (k0 < 30 && (1L << (k0 + 1)) <= m) k0++; }
                for (int k = Math.max(0, k0 - 2); k <= Math.min(30, k0 + 2); k++) {
                    long c = (long) cnt * (k + 1); for (int i = 0; i < cnt; i++) { c += zig(r[off + i]) >>> k; if (c >= bestc) break; }
                    if (c < bestc) { bestc = c; bk = k; }
                }
                int eb = 64 - Long.numberOfLeadingZeros(maxAbs) + 1; if (eb > 31) eb = 31;
                long ec = 5 + (long) cnt * eb;
                if (ec < bestc) { esc[pi] = eb; ks[pi] = -1; bits += ec; } else { ks[pi] = bk; bits += bestc; if (bk > 14) big = true; }
                off += cnt;
            }
            bits += (long) parts * (big ? 5 : 4);
            if (best == null || bits < best.bits) { best = new Plan(); best.r = r; best.order = order; best.po = po; best.k = ks; best.esc = esc; best.bits = bits; best.method = big ? 1 : 0; }
        }
        return best;
    }

    static void writeRes(BitW w, Plan p) {
        int pb = p.method == 1 ? 5 : 4, escCode = p.method == 1 ? 31 : 15;
        w.bits(p.method, 2); w.bits(p.po, 4);
        int parts = 1 << p.po, off = 0, n = p.r.length + p.order, ps = n / parts;
        for (int pi = 0; pi < parts; pi++) {
            int cnt = pi == 0 ? ps - p.order : ps;
            if (p.k[pi] < 0) { w.bits(escCode, pb); w.bits(p.esc[pi], 5); for (int i = 0; i < cnt; i++) w.sbits(p.r[off + i], p.esc[pi]); }
            else { int k = p.k[pi]; w.bits(k, pb); for (int i = 0; i < cnt; i++) { long u = zig(p.r[off + i]); long q = u >>> k; w.unary(q); if (k > 0) w.lbits(u & ((1L << k) - 1), k); } }
            off += cnt;
        }
    }

    static void utf8(BitW w, long v) {
        if (v < 0x80) { w.bits((int) v, 8); return; }
        int n = v < 0x800 ? 2 : v < 0x10000 ? 3 : v < 0x200000 ? 4 : v < 0x4000000 ? 5 : 6;
        int lead = (0xFF00 >> n) & 0xFF; w.bits(lead | (int) (v >> (6 * (n - 1))), 8);
        for (int i = n - 2; i >= 0; i--) w.bits(0x80 | (int) ((v >> (6 * i)) & 0x3F), 8);
    }
    static int crc8(byte[] b, int len) { int c = 0; for (int i = 0; i < len; i++) { c ^= b[i] & 0xff; for (int j = 0; j < 8; j++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xff : (c << 1) & 0xff; } return c; }
    static int crc16(byte[] b, int len) { int c = 0; for (int i = 0; i < len; i++) { c ^= (b[i] & 0xff) << 8; for (int j = 0; j < 8; j++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x8005) & 0xffff : (c << 1) & 0xffff; } return c; }

    /** 按位写 */
    static class BitW {
        final ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 16); long acc = 0; int nb = 0;
        void bits(int v, int n) { lbits(v & 0xffffffffL, n); }
        void lbits(long v, int n) {
            while (n > 0) {
                int take = Math.min(n, 56 - nb); if (take <= 0) { drain(); continue; }
                acc = (acc << take) | ((v >>> (n - take)) & ((1L << take) - 1)); nb += take; n -= take; drain();
            }
        }
        void sbits(long v, int n) { lbits(v & ((1L << n) - 1), n); }
        void unary(long q) { while (q >= 32) { bits(0, 32); q -= 32; } lbits(1, (int) q + 1); }
        void drain() { while (nb >= 8) { o.write((int) (acc >>> (nb - 8)) & 0xff); nb -= 8; } acc &= (1L << nb) - 1; }
        void align() { if (nb > 0) { acc <<= (8 - nb); nb = 8; drain(); } }
        byte[] bytes() { return o.toByteArray(); }   // 调用前要对齐（帧头、整帧都是对齐的）
    }
}
