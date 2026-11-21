package com.starry.music;

import java.io.IOException;

/**
 * 苹果无损（ALAC）解码器，纯 Java，照着苹果开源的参考实现（Apache 2.0）写的。
 * 有的手机自带的 ALAC 解码器不认苹果音乐下的文件（报 “invalid ALAC CSD”），所以自己解，不靠系统。
 * 支持 16 / 24 位，单声道和立体声。
 */
public class AlacDec {
    public final int frameLength, bitDepth, pb, mb, kb, numChannels, maxRun, sampleRate;
    int[] pred, mixU, mixV; int[] shiftBuf; final short[] coefsU = new short[32], coefsV = new short[32];

    /** cookie：MediaExtractor 给的 csd-0（可能带 12 字节的 'alac' 盒子头） */
    public AlacDec(byte[] c) throws IOException {
        int o = 0;
        if (c.length >= 36 && c[4] == 'a' && c[5] == 'l' && c[6] == 'a' && c[7] == 'c') o = 12;
        if (c.length < o + 24) throw new IOException("ALAC 参数太短");
        frameLength = be32(c, o); bitDepth = c[o + 5] & 0xff; pb = c[o + 6] & 0xff; mb = c[o + 7] & 0xff; kb = c[o + 8] & 0xff;
        numChannels = c[o + 9] & 0xff; maxRun = ((c[o + 10] & 0xff) << 8) | (c[o + 11] & 0xff); sampleRate = be32(c, o + 20);
        if (bitDepth != 16 && bitDepth != 24) throw new IOException("ALAC 位深不支持：" + bitDepth);
        if (numChannels < 1 || numChannels > 2) throw new IOException("ALAC 声道数不支持：" + numChannels);
        if (frameLength <= 0 || frameLength > (1 << 20)) throw new IOException("ALAC 帧长不对：" + frameLength);
        pred = new int[frameLength]; mixU = new int[frameLength]; mixV = new int[frameLength]; shiftBuf = new int[frameLength * 2];
    }
    static int be32(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }

    /* ---------- 按位读（高位在前） ---------- */
    static final class Bits {
        final byte[] b; final int len; long pos;
        Bits(byte[] b, int len) { this.b = b; this.len = len; }
        int byteAt(long i) { return i < len ? b[(int) i] & 0xff : 0; }
        /** 读 n 位（n ≤ 32），无符号 */
        int read(int n) { if (n == 0) return 0; long v = peek(n); pos += n; return (int) v; }
        long peek(int n) {
            long bi = pos >> 3; int off = (int) (pos & 7); long acc = 0;
            for (int i = 0; i < 5; i++) acc = (acc << 8) | byteAt(bi + i);   // 40 位够用（off ≤ 7，n ≤ 32）
            return (acc >>> (40 - off - n)) & ((1L << n) - 1);
        }
        void skip(long n) { pos += n; }
        /** 数开头连续几个 1（最多 max 个） */
        int ones(int max) { int n = 0; while (n < max && peek(1) == 1) { pos++; n++; } return n; }
    }

    /** 解一个包，输出交错的采样（有符号，bitDepth 位），返回采样帧数 */
    public int decode(byte[] pkt, int len, int[] out) throws IOException {
        Bits bits = new Bits(pkt, len); int ch = 0, frames = 0;
        while (true) {
            int tag = bits.read(3);
            if (tag == 7) break;   // ID_END
            if (tag == 0 || tag == 3) { frames = sce(bits, out, ch); ch += 1; }          // 单声道 / LFE
            else if (tag == 1) { frames = cpe(bits, out, ch); ch += 2; }                 // 一对声道
            else if (tag == 6) { int cnt = bits.read(4); if (cnt == 15) cnt += bits.read(8) - 1; bits.skip(cnt * 8L); }   // 填充
            else if (tag == 4) { bits.read(4); int al = bits.read(1); int cnt = bits.read(8); if (cnt == 255) cnt += bits.read(8); if (al == 1) bits.pos = (bits.pos + 7) & ~7L; bits.skip(cnt * 8L); }   // 数据
            else throw new IOException("ALAC 里有不认识的块：" + tag);
            if (ch >= numChannels) break;
            if (bits.pos >= (long) len * 8) break;
        }
        return frames;
    }

    int sce(Bits bits, int[] out, int ch) throws IOException {
        bits.read(4); bits.read(12);
        int head = bits.read(4), partial = head >> 3, bytesShifted = (head >> 1) & 3, escape = head & 1;
        int n = frameLength; if (partial != 0) { n = bits.read(16) << 16; n |= bits.read(16); }
        if (n > frameLength || n < 0) throw new IOException("ALAC 帧大小不对");
        int shift = bytesShifted * 8, chanBits = bitDepth - shift;
        if (escape == 0) {
            bits.read(8); bits.read(8);   // 单声道也有 mixBits / mixRes 两个字节（用不上）
            int h = bits.read(8), modeU = h >> 4, denU = h & 15; h = bits.read(8); int pbf = h >> 5, numU = h & 31;
            for (int i = 0; i < numU; i++) coefsU[i] = (short) bits.read(16);
            Bits sb = null; if (shift != 0) { sb = new Bits(bits.b, bits.len); sb.pos = bits.pos; bits.skip((long) shift * n); }
            dynDecomp(bits, pred, n, chanBits, (pb * pbf) / 4);
            if (modeU == 0) unpc(pred, mixU, n, coefsU, numU, chanBits, denU);
            else { unpc(pred, pred, n, null, 31, chanBits, 0); unpc(pred, mixU, n, coefsU, numU, chanBits, denU); }
            if (shift != 0) for (int i = 0; i < n; i++) shiftBuf[i] = sb.read(shift);
        } else {
            chanBits = bitDepth; shift = 0; readRaw(bits, mixU, n, chanBits);
        }
        int st = numChannels;
        for (int i = 0; i < n; i++) { int v = mixU[i]; if (shift != 0) v = (v << shift) | shiftBuf[i]; out[i * st + ch] = v; }
        return n;
    }

    int cpe(Bits bits, int[] out, int ch) throws IOException {
        bits.read(4); bits.read(12);
        int head = bits.read(4), partial = head >> 3, bytesShifted = (head >> 1) & 3, escape = head & 1;
        int n = frameLength; if (partial != 0) { n = bits.read(16) << 16; n |= bits.read(16); }
        if (n > frameLength || n < 0) throw new IOException("ALAC 帧大小不对");
        int shift = bytesShifted * 8, chanBits = bitDepth - shift + 1, mixBits = 0, mixRes = 0;
        if (escape == 0) {
            mixBits = bits.read(8); mixRes = (byte) bits.read(8);
            int h = bits.read(8), modeU = h >> 4, denU = h & 15; h = bits.read(8); int pbU = h >> 5, numU = h & 31;
            for (int i = 0; i < numU; i++) coefsU[i] = (short) bits.read(16);
            h = bits.read(8); int modeV = h >> 4, denV = h & 15; h = bits.read(8); int pbV = h >> 5, numV = h & 31;
            for (int i = 0; i < numV; i++) coefsV[i] = (short) bits.read(16);
            Bits sb = null; if (shift != 0) { sb = new Bits(bits.b, bits.len); sb.pos = bits.pos; bits.skip((long) shift * 2 * n); }
            dynDecomp(bits, pred, n, chanBits, (pb * pbU) / 4);
            if (modeU == 0) unpc(pred, mixU, n, coefsU, numU, chanBits, denU);
            else { unpc(pred, pred, n, null, 31, chanBits, 0); unpc(pred, mixU, n, coefsU, numU, chanBits, denU); }
            dynDecomp(bits, pred, n, chanBits, (pb * pbV) / 4);
            if (modeV == 0) unpc(pred, mixV, n, coefsV, numV, chanBits, denV);
            else { unpc(pred, pred, n, null, 31, chanBits, 0); unpc(pred, mixV, n, coefsV, numV, chanBits, denV); }
            if (shift != 0) for (int i = 0; i < n * 2; i += 2) { shiftBuf[i] = sb.read(shift); shiftBuf[i + 1] = sb.read(shift); }
        } else {
            int cb = bitDepth; shift = 0;
            for (int i = 0; i < n; i++) { mixU[i] = raw1(bits, cb); mixV[i] = raw1(bits, cb); }
        }
        int st = numChannels;
        for (int j = 0, k = 0; j < n; j++, k += 2) {
            int l, r;
            if (mixRes != 0) { l = mixU[j] + mixV[j] - ((mixRes * mixV[j]) >> mixBits); r = l - mixV[j]; }
            else { l = mixU[j]; r = mixV[j]; }
            if (shift != 0) { l = (l << shift) | shiftBuf[k]; r = (r << shift) | shiftBuf[k + 1]; }
            if (bitDepth == 16) { l = (short) l; r = (short) r; } else { l = (l << 8) >> 8; r = (r << 8) >> 8; }
            out[j * st + ch] = l; out[j * st + ch + 1] = r;
        }
        return n;
    }

    static int raw1(Bits b, int cb) { int v = b.read(cb); return (v << (32 - cb)) >> (32 - cb); }
    static void readRaw(Bits b, int[] dst, int n, int cb) { for (int i = 0; i < n; i++) dst[i] = raw1(b, cb); }

    /* ---------- 自适应 Golomb 解码（ag_dec） ---------- */
    static final int QBSHIFT = 9, QB = 1 << QBSHIFT, MMULSHIFT = 2, MDENSHIFT = QBSHIFT - MMULSHIFT - 1, MOFF = 1 << (MDENSHIFT - 2), BITOFF = 24, MAX_PREFIX_16 = 9, MAX_PREFIX_32 = 9, MAX_DATATYPE_BITS_16 = 16;
    static int lead(int x) { return Integer.numberOfLeadingZeros(x); }
    static int lg3a(int x) { return 31 - lead(x + 3); }

    void dynDecomp(Bits bits, int[] pc, int n, int maxSize, int pbv) throws IOException {
        int mbv = mb, wb = (1 << kb) - 1, zmode = 0, c = 0;
        while (c < n) {
            int m = mbv >>> QBSHIFT;
            int k = Math.min(lg3a(m), kb);
            m = (1 << k) - 1;
            long nn = get32(bits, m, k, maxSize);
            long nd = nn + zmode;
            int mult = (int) (-(nd & 1)) | 1;
            int del = (int) ((nd + 1) >> 1) * mult;
            pc[c++] = del;
            mbv = (int) (pbv * (nn + zmode) + mbv - (((long) pbv * mbv) >> QBSHIFT));
            if (nn > 0xffff) mbv = 0xffff;
            zmode = 0;
            if (((mbv << MMULSHIFT) < QB) && (c < n)) {
                zmode = 1;
                k = lead(mbv) - BITOFF + ((mbv + MOFF) >> MDENSHIFT);
                int mz = ((1 << k) - 1) & wb;
                int z = get16(bits, mz, k);
                if (c + z > n) throw new IOException("ALAC 数据不对（零太多）");
                for (int j = 0; j < z; j++) pc[c++] = 0;
                if (z >= 65535) zmode = 0;
                mbv = 0;
            }
        }
    }
    static long get32(Bits b, int m, int k, int maxbits) {
        int pre = b.ones(MAX_PREFIX_32);
        if (pre >= MAX_PREFIX_32) return b.read(maxbits) & 0xffffffffL;
        b.skip(1);   // 前缀后面的 0
        long result = pre;
        if (k != 1) {
            int v = b.read(k);
            result = (long) pre * m;
            if (v >= 2) result += v - 1; else b.pos -= 1;
        }
        return result;
    }
    static int get16(Bits b, int m, int k) {
        int pre = b.ones(MAX_PREFIX_16);
        if (pre >= MAX_PREFIX_16) return b.read(MAX_DATATYPE_BITS_16);
        b.skip(1);
        int v = b.read(k);
        int result = pre * m + v - 1;
        if (v < 2) { result -= (v - 1); b.pos -= 1; }
        return result;
    }

    /* ---------- 预测还原（dp_dec 的 unpc_block） ---------- */
    static int sgn(int i) { return (int) (((long) (-i) & 0xffffffffL) >>> 31) | (i >> 31); }
    static void unpc(int[] pc1, int[] out, int num, short[] coefs, int numactive, int chanbits, int denshift) {
        int chanshift = 32 - chanbits, denhalf = denshift > 0 ? 1 << (denshift - 1) : 0;
        out[0] = pc1[0];
        if (numactive == 0) { if (num > 1 && pc1 != out) System.arraycopy(pc1, 1, out, 1, num - 1); return; }
        if (numactive == 31) { int prev = out[0]; for (int j = 1; j < num; j++) { int del = pc1[j] + prev; prev = (del << chanshift) >> chanshift; out[j] = prev; } return; }
        for (int j = 1; j <= numactive && j < num; j++) { int del = pc1[j] + out[j - 1]; out[j] = (del << chanshift) >> chanshift; }
        int lim = numactive + 1;
        for (int j = lim; j < num; j++) {
            int sum1 = 0, p = j - 1, top = out[j - lim];
            for (int k = 0; k < numactive; k++) sum1 += coefs[k] * (out[p - k] - top);
            int del = pc1[j], del0 = del, sg = sgn(del);
            del += top + ((sum1 + denhalf) >> denshift);
            out[j] = (del << chanshift) >> chanshift;
            if (sg > 0) {
                for (int k = numactive - 1; k >= 0; k--) { int dd = top - out[p - k], s = sgn(dd); coefs[k] -= s; del0 -= (numactive - k) * ((s * dd) >> denshift); if (del0 <= 0) break; }
            } else if (sg < 0) {
                for (int k = numactive - 1; k >= 0; k--) { int dd = top - out[p - k], s = sgn(dd); coefs[k] += s; del0 -= (numactive - k) * ((-s * dd) >> denshift); if (del0 >= 0) break; }
            }
        }
    }
}
