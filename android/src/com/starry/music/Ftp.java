package com.starry.music;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 极简被动模式 FTP 客户端（给 A&K File Drop 用），和电脑版 core/ftp.js 同一套行为 */
public class Ftp {
    public static class Entry { public String name; public boolean dir; public long size; }
    public interface Progress { void bytes(int n); }

    private final String host; private final int port;
    private Socket ctl; private BufferedReader in; private OutputStream out;

    public Ftp(String host, int port) { this.host = host; this.port = port; }

    public static Ftp open(String host, int port, String user, String pass) throws IOException {
        Ftp f = new Ftp(host, port);
        f.connect(user, pass);
        return f;
    }

    private void connect(String user, String pass) throws IOException {
        ctl = new Socket();
        try { ctl.connect(new InetSocketAddress(host, port), 8000); }
        catch (IOException e) { throw new IOException("连不上 File Drop（" + host + ":" + port + "）：播放器息屏了或者 File Drop 没开"); }
        ctl.setSoTimeout(60000);
        in = new BufferedReader(new InputStreamReader(ctl.getInputStream(), Util.UTF8));
        out = ctl.getOutputStream();
        expect(read(), 220);
        String[] u = cmd("USER " + user);
        if (code(u) == 331) expect(cmd("PASS " + pass), 230, 202);
        else if (code(u) != 230) throw new IOException("FTP 登录失败：" + u[1]);
        try { cmd("OPTS UTF8 ON"); } catch (IOException ignored) {}
        expect(cmd("TYPE I"), 200);
    }
    private static int code(String[] r) { return Integer.parseInt(r[0]); }
    /** 读一条（可能多行的）回复：返回 {code, 全文} */
    private String[] read() throws IOException {
        String first = in.readLine();
        if (first == null) throw new IOException("FTP 连接断开");
        StringBuilder sb = new StringBuilder(first);
        if (first.length() > 3 && first.charAt(3) == '-') {
            String c = first.substring(0, 3), l;
            while ((l = in.readLine()) != null) { sb.append('\n').append(l); if (l.startsWith(c + " ")) break; }
        }
        return new String[]{first.substring(0, 3), sb.toString()};
    }
    private String[] cmd(String line) throws IOException {
        out.write((line + "\r\n").getBytes(Util.UTF8)); out.flush();
        return read();
    }
    private String[] expect(String[] r, int... codes) throws IOException {
        int c = code(r);
        for (int k : codes) if (k == c) return r;
        throw new IOException("FTP " + r[1].replace('\n', ' '));
    }
    private Socket pasv() throws IOException {
        String[] r = expect(cmd("PASV"), 227);
        Matcher m = Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").matcher(r[1]);
        if (!m.find()) throw new IOException("PASV 回复看不懂：" + r[1]);
        int p = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
        Socket d = new Socket();
        d.connect(new InetSocketAddress(host, p), 8000);   // 用控制连接的地址，不信 PASV 里的 IP
        d.setSoTimeout(60000);
        return d;
    }

    private static final Pattern UNIX = Pattern.compile("^([dl-])\\S*\\s+.*?(\\d+)\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{1,2})\\s+(\\d{1,2}:\\d{2}|\\d{4})\\s+(.+)$");
    private static final Pattern DOS = Pattern.compile("^(\\d{2}-\\d{2}-\\d{2,4})\\s+(\\d{1,2}:\\d{2}\\s*[AP]M)\\s+(<DIR>|\\d+)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    public List<Entry> list(String dir) throws IOException {
        Socket d = pasv();
        try {
            expect(cmd("LIST " + dir), 125, 150);
            String txt = new String(Util.readAll(d.getInputStream()), Util.UTF8);
            d.close();
            expect(read(), 226, 250);
            List<Entry> out = new ArrayList<Entry>();
            for (String ln : txt.split("\r?\n")) {
                if (ln.trim().isEmpty() || ln.startsWith("total ")) continue;
                Matcher m = UNIX.matcher(ln);
                if (m.find()) {
                    String name = m.group(6); if (m.group(1).equals("l")) name = name.split(" -> ")[0];
                    if (name.equals(".") || name.equals("..")) continue;
                    Entry e = new Entry(); e.name = name; e.dir = !m.group(1).equals("-"); e.size = Long.parseLong(m.group(2)); out.add(e); continue;
                }
                m = DOS.matcher(ln);
                if (m.find()) { Entry e = new Entry(); e.name = m.group(4); e.dir = m.group(3).equals("<DIR>"); e.size = e.dir ? 0 : Long.parseLong(m.group(3)); out.add(e); }
            }
            return out;
        } finally { try { d.close(); } catch (IOException ignored) {} }
    }
    public long size(String p) { try { String[] r = cmd("SIZE " + p); return code(r) == 213 ? Long.parseLong(r[1].substring(4).trim()) : -1; } catch (Exception e) { return -1; } }

    /** 下载：把内容写进 sink，返回字节数 */
    public long retr(String p, OutputStream sink, Progress pr) throws IOException {
        Socket d = pasv();
        try {
            try { expect(cmd("RETR " + p), 125, 150); } catch (IOException e) { d.close(); throw e; }
            InputStream is = d.getInputStream(); byte[] b = new byte[65536]; int n; long tot = 0;
            while ((n = is.read(b)) > 0) { sink.write(b, 0, n); tot += n; if (pr != null) pr.bytes(n); }
            d.close();
            expect(read(), 226, 250);
            return tot;
        } finally { try { d.close(); } catch (IOException ignored) {} }
    }
    public byte[] getBytes(String p) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        retr(p, bo, null); return bo.toByteArray();
    }
    /** 上传：从 src 读 len 字节（len<0 读到底） */
    public void stor(String p, InputStream src, long len, Progress pr) throws IOException {
        Socket d = pasv();
        try {
            try { expect(cmd("STOR " + p), 125, 150); } catch (IOException e) { d.close(); throw e; }
            OutputStream os = d.getOutputStream(); byte[] b = new byte[65536]; long left = len;
            while (left != 0) {
                int want = left < 0 ? b.length : (int) Math.min(b.length, left);
                int n = src.read(b, 0, want); if (n <= 0) break;
                os.write(b, 0, n); if (left > 0) left -= n; if (pr != null) pr.bytes(n);
            }
            os.flush(); d.shutdownOutput(); d.close();
            expect(read(), 226, 250);
        } finally { try { d.close(); } catch (IOException ignored) {} }
    }
    public void putBytes(String p, byte[] data) throws IOException { stor(p, new java.io.ByteArrayInputStream(data), data.length, null); }
    public void mkd(String p) { try { cmd("MKD " + p); } catch (IOException ignored) {} }
    public void mkdirs(String p) { String cur = ""; for (String x : p.split("/")) { if (x.isEmpty()) continue; cur += "/" + x; mkd(cur); } }
    public void rmd(String p) throws IOException { expect(cmd("RMD " + p), 250); }
    public void dele(String p) throws IOException { expect(cmd("DELE " + p), 250); }
    public void rename(String a, String b) throws IOException { expect(cmd("RNFR " + a), 350); expect(cmd("RNTO " + b), 250); }
    public void close() { try { out.write("QUIT\r\n".getBytes()); out.flush(); } catch (Exception ignored) {} try { ctl.close(); } catch (Exception ignored) {} }
}
