package com.starry.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 「转成 flac」：播放器上已有的 .m4a，在手机上转——不用电脑。
 * 规矩和 tran.bat 一样：递归找 .m4a → 拉到手机 → 无损转 flac（标签、封面带上）→ 传回同一个文件夹（先传成临时名、核对大小再改名）
 * → 删掉那个 .m4a（只删文件，不删文件夹）→ 文件名去掉开头的「06. 」；同目录已有对应 flac 的跳过。
 */
public class CaFlac {
    final Core core;
    volatile boolean running, stopReq, listOnly; volatile String err = "";
    final JSONObject st = new JSONObject();
    long lastAuto = 0;

    CaFlac(Core core) {
        this.core = core;
        // 自动转：每 15 分钟看一眼新歌文件夹，有 m4a 就转（设置里「自动转」开着才会）
        core.tick.scheduleWithFixedDelay(new Runnable() { public void run() {
            try { if (core.prefs.getBoolean("set_autoFlac", true) && !running && core.ip != null) start(core.inbox.target(), false, true); } catch (Throwable ignored) {}
        } }, 3, 15, TimeUnit.MINUTES);
    }

    synchronized JSONObject start(final String dir0, final boolean list, final boolean quiet) throws Exception {
        if (running) { if (quiet) return view(); throw new IOException("上一轮还在转，等它转完"); }
        if (core.ip == null) throw new IOException("还没连上播放器");
        final String dir = Core.cleanPath(dir0 == null || dir0.isEmpty() ? "/Music" : dir0);
        running = true; stopReq = false; listOnly = list; err = "";
        synchronized (st) { for (String k : new String[]{"total", "i", "done", "skip", "err", "bytes", "cur", "errs", "converted", "fin"}) st.remove(k); st.put("t0", System.currentTimeMillis() / 1000.0).put("dir", dir).put("total", 0).put("done", 0).put("skip", 0).put("err", 0).put("cur", "正在找 .m4a…").put("errs", new JSONArray()).put("converted", new JSONArray()).put("tool", "手机"); }
        core.bg.execute(new Runnable() { public void run() {
            File tmp = new File(core.ctx.getCacheDir(), "caflac"); tmp.mkdirs();
            try {
                final List<String[]> found = new ArrayList<String[]>(); final java.util.Map<String, Set<String>> names = new java.util.HashMap<String, Set<String>>();
                walk(dir, 0, found, names);
                set("total", found.size()); set("cur", "");
                if (list) return;
                int i = 0;
                for (String[] f : found) {
                    if (stopReq) break;
                    i++; final String d = f[0], n = f[1]; final long sz = Long.parseLong(f[2]);
                    final String stem = n.substring(0, n.lastIndexOf('.')), outName = Conv.cleanName(stem) + ".flac";
                    final String srcP = (d.equals("/") ? "" : d) + "/" + n, dstP = (d.equals("/") ? "" : d) + "/" + outName;
                    set("i", i); set("cur", srcP);
                    Set<String> here = names.get(d);
                    if (here != null && (here.contains(outName) || here.contains(stem + ".flac"))) { inc("skip"); continue; }
                    final File a = new File(tmp, "a.m4a"), b = new File(tmp, "b.flac"); a.delete(); b.delete();
                    try {
                        core.withFtp(new Core.FtpWork<Void>() { public Void run(Ftp c) throws Exception { OutputStream o = new FileOutputStream(a); try { c.retr(srcP, o, null); } finally { o.close(); } return null; } });
                        if (sz > 0 && a.length() != sz) throw new IOException("拉下来的大小不对");
                        Conv.toFlac(a, b, null);
                        core.withFtp(new Core.FtpWork<Void>() { public Void run(Ftp c) throws Exception {
                            InputStream in = new FileInputStream(b); try { c.stor(dstP + ".smtmp", in, b.length(), null); } finally { in.close(); }
                            long got = -1; for (Ftp.Entry e : c.list(d)) if (e.name.equals(outName + ".smtmp")) got = e.size;
                            if (got != b.length()) { try { c.dele(dstP + ".smtmp"); } catch (Exception ignored) {} throw new IOException("传上去的大小不对"); }
                            c.rename(dstP + ".smtmp", dstP); c.dele(srcP);   // 只删这一个 m4a 文件
                            return null; } });
                        if (here != null) here.add(outName);
                        inc("done"); synchronized (st) { st.put("bytes", st.optLong("bytes") + b.length()); push("converted", dstP, 200); }
                        synchronized (core.meta.imports) { core.meta.imports.add(core.pre() + dstP); }
                    } catch (Exception e) {
                        inc("err"); synchronized (st) { push("errs", new JSONObject().put("path", srcP).put("err", String.valueOf(e.getMessage())), 50); }
                    } finally { a.delete(); b.delete(); }
                }
            } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            finally { running = false; set("cur", ""); set("fin", true); }
        } });
        return view();
    }

    void walk(final String p, int depth, final List<String[]> out, final java.util.Map<String, Set<String>> names) throws Exception {
        if (depth > 8 || stopReq) return;
        List<Ftp.Entry> kids = core.withFtp(new Core.FtpWork<List<Ftp.Entry>>() { public List<Ftp.Entry> run(Ftp c) throws Exception { return c.list(p); } });
        Set<String> fs = new HashSet<String>(); for (Ftp.Entry e : kids) if (!e.dir) fs.add(e.name); names.put(p, fs);
        for (Ftp.Entry e : kids) {
            String q = (p.equals("/") ? "" : p) + "/" + e.name;
            if (e.dir) walk(q, depth + 1, out, names);
            else if (e.name.toLowerCase(Locale.ROOT).endsWith(".m4a")) out.add(new String[]{p, e.name, "" + e.size});
        }
    }

    void set(String k, Object v) { synchronized (st) { try { st.put(k, v); } catch (Exception ignored) {} } }
    void inc(String k) { synchronized (st) { try { st.put(k, st.optInt(k) + 1); } catch (Exception ignored) {} } }
    void push(String k, Object v, int max) { try { JSONArray a = st.optJSONArray(k); if (a == null) { a = new JSONArray(); st.put(k, a); } a.put(v); while (a.length() > max) a.remove(0); } catch (Exception ignored) {} }

    JSONObject view() throws Exception {
        JSONObject s; synchronized (st) { s = new JSONObject(st.toString()); }
        return new JSONObject().put("running", running).put("list", listOnly).put("err", err).put("status", s.length() == 0 ? JSONObject.NULL : s).put("auto", core.prefs.getBoolean("set_autoFlac", true));
    }

    JSONObject api(String p, Map<String, String> q, JSONObject body) throws Exception {
        if (p.equals("flac/job")) return view();
        if (p.equals("flac/start")) { String d = body != null && body.has("dir") ? body.optString("dir") : q.get("dir"); boolean l = (body != null && body.optBoolean("list")) || "1".equals(q.get("list")) || "true".equals(q.get("list")); return start(d, l, false); }
        if (p.equals("flac/stop")) { stopReq = true; return view(); }
        throw new IOException("没有这个接口：" + p);
    }
}
