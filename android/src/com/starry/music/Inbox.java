package com.starry.music;

import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 手机上的导入文件夹：比如网易云的下载文件夹（或者你解码后放歌的文件夹）。
 * 系统媒体库一发现那里多了新歌（网易云下载完会通知媒体库），群星音乐就在后台：读标签 → 查信息 → 先在手机上改好 → 传到播放器。
 * 接口和电脑版 core/inbox.js 一样（inbox、inbox/scan、inbox/upload、inbox/ignore、inbox/config）。
 */
public class Inbox {
    final Core core; final Meta meta;
    final Map<String, JSONObject> files = new LinkedHashMap<String, JSONObject>();   // 本机路径 → { size, mtime, state, at, err, to, tagged, why }
    volatile boolean busy, baseline;
    public interface Notify { void done(int ok, int tagged, int err, String target); }
    List<String> pending() { List<String> L = new ArrayList<String>(); if (!"ask".equals(mode())) return L; synchronized (files) { for (Map.Entry<String, JSONObject> e : files.entrySet()) { JSONObject x = e.getValue(); if (x.optString("state").matches("new|zip") && !x.optBoolean("ok")) L.add(e.getKey()); } } return L; }
    /** 你点了“传到播放器”：这些（默认全部待问的）放行，接着解压 / 上传 */
    void approve(List<String> L) throws Exception {
        if (L == null) L = pending();
        synchronized (files) { for (String k : L) { JSONObject x = files.get(k); if (x != null) x.put("ok", true); } }
        save(); if (ask != null) ask.clear();
        core.bg.execute(new Runnable() { public void run() { try { scan(); } catch (Exception ignored) {} } });
    }
    void decline(List<String> L) throws Exception {
        if (L == null) L = pending();
        synchronized (files) { for (String k : L) { JSONObject x = files.get(k); if (x != null) x.put("state", "skip"); } }
        save(); if (ask != null) ask.clear();
    }
    /** 有新下载、还没问过：弹一次通知 */
    void askNew() {
        if (!"ask".equals(mode()) || ask == null) return;
        int fresh = 0; StringBuilder names = new StringBuilder(); int total = 0;
        synchronized (files) { for (Map.Entry<String, JSONObject> e : files.entrySet()) { JSONObject x = e.getValue(); if (!x.optString("state").matches("new|zip") || x.optBoolean("ok")) continue; total++;
            if (!x.optBoolean("asked")) { fresh++; try { x.put("asked", true); } catch (Exception ignored) {} String k = e.getKey(); String nm = x.optString("name", k.substring(k.lastIndexOf('/') + 1)).replaceAll("\\.[A-Za-z0-9]{2,5}$", ""); if (names.length() < 60) names.append(names.length() > 0 ? "、" : "").append(nm); } } }
        if (fresh > 0) { save(); ask.ask(total, names.toString()); }
    }
    public Notify notify;
    final Handler h = new Handler(Looper.getMainLooper());
    ContentObserver obs;

    public Inbox(Core c, Meta m) {
        core = c; meta = m;
        baseline = !core.prefs.getBoolean("inSeen", false);   // 第一次：媒体库里原有的歌都记成“原来就有”，不会被自动传
        try { JSONObject j = new JSONObject(Core.read(new File(core.dir, "inbox.json"))); for (String k : Core.keys(j)) { JSONObject x = j.getJSONObject(k); String st = x.optString("state"); if (st.equals("up")) x.put("state", "new").put("step", ""); else if (st.equals("unzipping")) x.put("state", "zip"); files.put(k, x); }   /* 上次没做完就被关了：重新排队 */ } catch (Exception ignored) {}
        core.bg.execute(new Runnable() { public void run() { try { rmEmpty(new File(core.ctx.getExternalFilesDir(null), "unzip")); } catch (Throwable ignored) {} } });   /* 以前解压失败留下的空文件夹 */
        core.tick.scheduleWithFixedDelay(new Runnable() { public void run() { try { scan(); } catch (Throwable ignored) {} } }, 20, 60, TimeUnit.SECONDS);
        // 媒体库一变（网易云下完一首）就马上看一眼；等 8 秒让文件写完
        obs = new ContentObserver(h) {
            final Runnable later = new Runnable() { public void run() { core.bg.execute(new Runnable() { public void run() { try { scan(); } catch (Throwable ignored) {} } }); } };
            @Override public void onChange(boolean self) { h.removeCallbacks(later); h.postDelayed(later, 8000); }
        };
        try { core.ctx.getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs); } catch (Exception ignored) {}
    }
    public void close() { try { core.ctx.getContentResolver().unregisterContentObserver(obs); } catch (Exception ignored) {} }

    /* ---------- 设置 ---------- */
    // 网易云的下载文件夹：有的手机在 netease/…，有的（比如 OPPO / ColorOS 新版）在 Download/netease/…，默认两个都看
    // 百度网盘 App 的下载文件夹也默认看着（BaiduNetdisk/ 或 Download/BaiduNetdisk/）
    static final String DEF_DIRS = "netease/cloudmusic/Music ; Download/netease/cloudmusic/Music ; BaiduNetdisk ; Download/BaiduNetdisk";
    String inDirsRaw() { String s = core.prefs.getString("inDirs", DEF_DIRS).trim(); return s.equals("netease/cloudmusic/Music") || s.equals("netease/cloudmusic/Music ; Download/netease/cloudmusic/Music") ? DEF_DIRS : s; }
    String[] dirs() { String s = inDirsRaw(); List<String> o = new ArrayList<String>(); for (String x : s.split("\\s*;\\s*")) { x = x.trim().replaceAll("^/+|/+$", ""); if (x.startsWith("storage/emulated/0/")) x = x.substring(19); if (x.startsWith("sdcard/")) x = x.substring(7); if (!x.isEmpty()) o.add(x); } return o.toArray(new String[0]); }
    // 三种：auto 下完就传；ask 下完先问我（弹通知 / App 里弹窗）；list 只列出来
    String mode() { String m = core.prefs.getString("inMode", ""); if (m.isEmpty()) m = "ask"; return m; }
    boolean auto() { return "auto".equals(mode()); }
    /** 这一项可以自己往下走（解压 / 传）：自动模式，或者你点过“传” */
    boolean go(JSONObject x) { return auto() || x.optBoolean("ok"); }
    public interface Ask { void ask(int n, String names); void clear(); }
    public Ask ask;
    boolean tag() { return core.prefs.getBoolean("inTag", true); }
    String target() { String t = core.prefs.getString("inTarget", ""); return t.isEmpty() ? "/Music/待分类" : t; }
    boolean canRead() { String perm = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO" : "android.permission.READ_EXTERNAL_STORAGE"; return core.ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }
    void save() { JSONObject j = new JSONObject(); synchronized (files) { for (Map.Entry<String, JSONObject> e : files.entrySet()) try { j.put(e.getKey(), e.getValue()); } catch (JSONException ignored) {} } Core.write(new File(core.dir, "inbox.json"), j.toString()); }

    /* ---------- 扫：问系统媒体库这些文件夹里有哪些歌 ---------- */
    volatile long lastScan;
    synchronized void scan() throws Exception {
        lastScan = System.currentTimeMillis();
        String[] ds = dirs(); if (ds.length == 0 || !canRead()) return;
        boolean soft = false;   // 只是看的文件夹变了（不是第一次）：开始看以后才下的歌照样算新歌
        if (core.prefs.getBoolean("inSeen", false) && !inDirsRaw().equals(core.prefs.getString("inSeenDirs", inDirsRaw()))) { baseline = true; soft = true; }
        long since = core.prefs.getLong("inSince", 0); if (since == 0) since = System.currentTimeMillis() / 1000 - 86400;   // 老版本没记开始时间：按一天内下的算   // 看的文件夹变了（包括新版默认多看一个）：那边原有的歌也记成“原来就有”，不会一下全传
        if (!core.prefs.getBoolean("inFix151", false)) {   // 1.5.0 有一版把新多看的文件夹里刚下的歌也记成了“原来就有”：改回来（只做一次）
            synchronized (files) { for (JSONObject x : files.values()) if ("old".equals(x.optString("state")) && x.optLong("mtime") > since) x.put("state", "wait"); }
            core.prefs.edit().putBoolean("inFix151", true).apply(); save();
        }
        synchronized (files) { for (JSONObject x : files.values()) if ("err".equals(x.optString("state")) && x.optString("err").contains("密码")) x.put("state", "pw"); }
        if (!core.prefs.contains("inMode")) {   // 1.9：多了“先问我”。升级时已经排着队的照常处理，以后新下的先问
            synchronized (files) { for (JSONObject x : files.values()) if (x.optString("state").matches("new|zip|wait|unzipping")) { x.put("ok", true); x.put("asked", true); } }
            core.prefs.edit().putString("inMode", "ask").apply(); save();
        }
        if (!core.prefs.getBoolean("inFix180", false)) {   // 1.7.1 的几个测试版转 m4a 失败了（系统解码器不认）：现在有自己的解码器了，重新排队
            synchronized (files) { for (JSONObject x : files.values()) if ("err".equals(x.optString("state")) && x.optString("err").matches("(?s).*(ALAC|Released|csd|解码器|一帧太大).*")) x.put("state", "new").put("err", ""); }
            core.prefs.edit().putBoolean("inFix180", true).apply();
        }
        if (!core.prefs.getBoolean("inFix161", false)) {   // 1.6.1 第一版试密码有 bug（密码对了也说不对）：存过密码的，把卡住的重新排队一次
            if (!passwords().isEmpty()) synchronized (files) { for (JSONObject x : files.values()) if ("pw".equals(x.optString("state"))) x.put("state", "zip").put("err", ""); }
            core.prefs.edit().putBoolean("inFix161", true).apply();
        }   // 旧版记成“失败”的带密码压缩包：归到“要解压密码”
        Map<String, long[]> found = new HashMap<String, long[]>();
        StringBuilder sel = new StringBuilder(); List<String> args = new ArrayList<String>();
        for (String d : ds) { if (sel.length() > 0) sel.append(" OR "); sel.append(MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"); args.add(d + "/%"); }
        Cursor c = core.ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED},
            "(" + sel + ") AND " + MediaStore.MediaColumns.IS_PENDING + "=0", args.toArray(new String[0]), null);
        if (c != null) try { while (c.moveToNext()) { String p = c.getString(0); if (p != null) found.put(p, new long[]{c.getLong(1), c.getLong(2)}); } } finally { c.close(); }
        boolean changed = false;
        synchronized (files) {
            for (Map.Entry<String, long[]> e : found.entrySet()) {
                String p = e.getKey(); long size = new File(p).length(); if (size <= 0) size = e.getValue()[0];
                JSONObject x = files.get(p);
                if (x != null && x.optLong("size") == size && x.optLong("mtime") == e.getValue()[1]) { if ("wait".equals(x.optString("state"))) { x.put("state", "new"); changed = true; } continue; }
                files.put(p, new JSONObject().put("size", size).put("mtime", e.getValue()[1]).put("state", baseline && !(soft && e.getValue()[1] > since) ? "old" : "wait").put("at", System.currentTimeMillis())); changed = true;
            }
            for (String p : new ArrayList<String>(files.keySet())) { String s = files.get(p).optString("state"); if (!found.containsKey(p) && !files.get(p).optBoolean("x") && !p.startsWith("saf:") && (s.equals("new") || s.equals("wait") || s.equals("old"))) { files.remove(p); changed = true; } }
        }
        try { if (scanTree(soft, since)) changed = true; } catch (Exception e) { treeErr = e.getMessage(); }
        if (baseline) { android.content.SharedPreferences.Editor ed = core.prefs.edit().putBoolean("inSeen", true).putString("inSeenDirs", inDirsRaw()); if (!core.prefs.contains("inSince")) ed.putLong("inSince", System.currentTimeMillis() / 1000); ed.apply(); }
        baseline = false;
        if (changed) save();
        askNew();
        if (!busy) { List<String> L = new ArrayList<String>(); synchronized (files) { for (Map.Entry<String, JSONObject> e : files.entrySet()) if ("new".equals(e.getValue().optString("state")) && go(e.getValue())) L.add(e.getKey()); } if (!L.isEmpty()) upload(L, null); }
    }
    JSONObject view() throws JSONException {
        List<JSONObject> L = new ArrayList<JSONObject>(); Map<String, Integer> cnt = new HashMap<String, Integer>();
        synchronized (files) { for (Map.Entry<String, JSONObject> e : files.entrySet()) { JSONObject x = e.getValue(); String p = e.getKey(); String nm = x.optString("name", p.substring(p.lastIndexOf('/') + 1)), dd = x.has("from") ? "从压缩包「" + x.optString("from") + "」解出" : x.has("dir") ? x.optString("dir") : p.substring(0, Math.max(0, p.lastIndexOf('/'))); L.add(new JSONObject().put("path", p).put("name", nm).put("dir", dd).put("n", x.optInt("n", -1)).put("size", x.optLong("size")).put("state", x.optString("state")).put("step", x.optString("step")).put("conv", x.optBoolean("conv")).put("ok", x.optBoolean("ok")).put("at", x.optLong("at")).put("err", x.optString("err")).put("to", x.optString("to")).put("tagged", x.opt("tagged") == null ? JSONObject.NULL : x.opt("tagged")).put("why", x.optString("why"))); Integer n = cnt.get(x.optString("state")); cnt.put(x.optString("state"), n == null ? 1 : n + 1); } }
        java.util.Collections.sort(L, new java.util.Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("at"), a.optLong("at")); } });
        JSONArray a = new JSONArray(); for (int i = 0; i < Math.min(500, L.size()); i++) a.put(L.get(i));
        JSONArray ds = new JSONArray(); for (String d : dirs()) ds.put(d);
        JSONArray pend = new JSONArray(); for (String k : pending()) { JSONObject x; synchronized (files) { x = files.get(k); } if (x == null) continue; pend.put(new JSONObject().put("path", k).put("name", x.optString("name", k.substring(k.lastIndexOf('/') + 1))).put("zip", "zip".equals(x.optString("state")))); if (pend.length() >= 50) break; }
        return new JSONObject().put("pending", pend).put("pendingN", pending().size()).put("cfg", new JSONObject().put("dirs", ds).put("auto", auto()).put("mode", mode()).put("tag", tag()).put("target", target()).put("phone", true)).put("busy", busy).put("items", a).put("counts", new JSONObject(cnt)).put("needPerm", !canRead()).put("tree", treeName()).put("pwN", passwords().size()).put("baidu", baiduInstalled()).put("lastScan", lastScan).put("now", System.currentTimeMillis());
    }

    /* ---------- 传：先在手机上补好信息再传，每首只传一趟 ---------- */
    JSONObject upload(final List<String> paths, JSONObject opt) throws Exception {
        if (busy) throw new IOException("上一批还在传");
        final boolean doTag = opt != null && opt.has("tag") ? opt.optBoolean("tag") : tag();
        final String tgt = Core.cleanPath(opt != null && !opt.optString("target").isEmpty() ? opt.optString("target") : target());
        final List<String> L = new ArrayList<String>();
        synchronized (files) { for (String p : paths) { JSONObject x = files.get(p); if (x != null && !p.startsWith("saf:") && x.optString("state").matches("new|err|skip|old")) L.add(p); } }
        if (L.isEmpty()) throw new IOException("没有可传的");
        busy = true;
        core.bg.execute(new Runnable() { public void run() {
            int ok = 0, tagged = 0, er = 0; File tmp = new File(core.ctx.getCacheDir(), "inbox"); tmp.mkdirs();
            try {
                for (String p : L) {
                    JSONObject x; synchronized (files) { x = files.get(p); } if (x == null) continue;
                    File src = new File(p), copy = null, conv = null; JSONObject nw = null; String sp = p;
                    try {
                        x.put("state", "up").put("err", "").put("why", "").put("conv", false);
                        // 播放器放不了 m4a：先在手机上无损转成 flac（文件名去掉开头的「06. 」），之后按 flac 补信息、上传
                        if (Conv.needs(p)) {
                            x.put("step", "转成 flac"); save();
                            String stem = src.getName().substring(0, src.getName().lastIndexOf('.'));
                            conv = new File(tmp, Conv.cleanName(stem).replaceAll("[\\\\/:*?\"<>|]", "_") + ".flac"); conv.delete();
                            final JSONObject xx = x; Conv.toFlac(src, conv, new Conv.Prog() { public void at(int pct) { try { xx.put("step", "转成 flac " + pct + "%"); } catch (Exception ignored) {} } });
                            sp = conv.getAbsolutePath(); x.put("conv", true);
                        }
                        x.put("step", doTag && Tagger.writable(sp) ? "查资料" : "传到播放器"); save();
                        if (doTag && Tagger.writable(sp)) try {
                            Map<String, String> tg = Tagger.read(sp); String[] g = Meta.fileGuess(p);
                            String t0 = tg.containsKey("title") ? tg.get("title") : g[0], a0 = tg.containsKey("artist") ? tg.get("artist") : g[1];
                            JSONObject cur = new JSONObject().put("indexed", true).put("title", t0).put("artist", a0).put("album", tg.containsKey("album") ? tg.get("album") : "").put("albumRaw", "").put("art", "").put("dur", tg.containsKey("dur") ? Double.parseDouble(tg.get("dur")) : 0);
                            JSONObject r = meta.propose(p, cur); JSONObject pr = r.getJSONObject("proposed");
                            if ("high".equals(r.optString("conf"))) {
                                Map<String, String> m = new LinkedHashMap<String, String>(); nw = new JSONObject();
                                for (String k : new String[]{"title", "artist", "album"}) { String v = pr.optString(k); String have = tg.containsKey(k) ? tg.get(k) : ""; if (!v.isEmpty() && !v.equals(have)) { m.put(k, v); nw.put(k, v); } }
                                if (pr.optInt("track") > 0) { m.put("track", "" + pr.optInt("track")); nw.put("track", "" + pr.optInt("track")); }
                                if (pr.optInt("year") > 0) { m.put("year", "" + pr.optInt("year")); nw.put("year", "" + pr.optInt("year")); }
                                if ("Apple Music".equalsIgnoreCase(tg.get("albumartist"))) m.put("albumartist", (m.containsKey("artist") ? m.get("artist") : a0).split(" & ")[0]);
                                byte[] cv = pr.optString("cover").startsWith("https://") ? Tagger.fetch(pr.optString("cover")) : null; if (cv != null) nw.put("cover", pr.optString("cover"));
                                if (!m.isEmpty() || cv != null) { x.put("step", "补标签"); save(); copy = new File(tmp, "t" + sp.substring(sp.lastIndexOf('.'))); Tagger.write(new File(sp), copy, m, cv); tagged++; } else nw = null;
                            } else x.put("why", ("none".equals(r.optString("conf")) ? "没查到" : "拿不准，没改"));
                        } catch (Exception e) { copy = null; nw = null; x.put("why", "没能补信息（原样传）：" + e.getMessage()); }
                        x.put("step", "传到播放器"); save();
                        File up = copy != null ? copy : new File(sp); String name = new File(sp).getName().replaceAll("[\\\\/:*?\"<>|]", "_");
                        InputStream in = new FileInputStream(up);
                        try { core.upload(tgt, name, "in" + System.currentTimeMillis(), in, up.length()); } finally { in.close(); }
                        String to = (tgt.equals("/") ? "" : tgt) + "/" + name;
                        x.put("state", "done").put("step", "").put("to", to).put("tagged", nw == null ? JSONObject.NULL : nw).put("at", System.currentTimeMillis()); ok++;
                        synchronized (meta.imports) { meta.imports.remove(core.pre() + to); meta.imports.add(core.pre() + to); }
                    } catch (Exception e) { try { x.put("state", "err").put("step", "").put("err", String.valueOf(e.getMessage())); } catch (JSONException ignored) {} er++; }
                    finally { if (copy != null) copy.delete(); if (conv != null) conv.delete(); try { if (x.optBoolean("x") && "done".equals(x.optString("state"))) { src.delete(); File root = new File(core.ctx.getExternalFilesDir(null), "unzip"); for (File d = src.getParentFile(); d != null && !d.equals(root) && d.getAbsolutePath().startsWith(root.getAbsolutePath()); d = d.getParentFile()) { String[] k = d.list(); if (k != null && k.length == 0) d.delete(); else break; } } } catch (Exception ignored) {} save(); }   // 解压出来的歌传完就删（原压缩包不动）
                }
            } finally { busy = false; save();
                if (!"list".equals(mode())) h.postDelayed(new Runnable() { public void run() { core.bg.execute(new Runnable() { public void run() { try { scan(); } catch (Exception ignored) {} } }); } }, 3000); }   // 这一批传完马上接着解下一批
            Notify n = notify; if (n != null) try { n.done(ok, tagged, er, tgt); } catch (Throwable ignored) {}
        } });
        return new JSONObject().put("started", L.size());
    }

    /* ---------- 压缩包：网盘下下来的 zip。安卓不让普通 App 随便读非音乐文件，要你在设置里选一次下载文件夹（系统的“授权文件夹”） ---------- */
    static final java.util.regex.Pattern ARC = java.util.regex.Pattern.compile("(?i).*\\.(zip|7z|rar)$"), AUD = java.util.regex.Pattern.compile("(?i).*\\.(flac|mp3|m4a|ogg|opus|wav|aac|ape|aiff?|dsf|dff|wma|alac)$");
    volatile String treeErr = "";
    String treeName() { String t = core.prefs.getString("inTree", ""); if (t.isEmpty()) return ""; try { String id = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(t)); return id.replaceFirst("^primary:", ""); } catch (Exception e) { return t; } }
    static void rmEmpty(File d) { if (d == null || !d.isDirectory()) return; File[] k = d.listFiles(); if (k != null) for (File f : k) rmEmpty(f); String[] left = d.list(); if (left != null && left.length == 0) d.delete(); }
    List<String> passwords() { List<String> o = new ArrayList<String>(); for (String x : core.prefs.getString("inPw", "").split("[\\r\\n]+")) { x = x.trim(); if (!x.isEmpty() && !o.contains(x)) o.add(x); } return o; }
    boolean baiduInstalled() { try { core.ctx.getPackageManager().getPackageInfo("com.baidu.netdisk", 0); return true; } catch (Exception e) { return false; } }
    boolean scanTree(boolean soft, long since) throws Exception {
        String t = core.prefs.getString("inTree", ""); if (t.isEmpty()) return false;
        android.net.Uri tree = android.net.Uri.parse(t); boolean changed = false; treeErr = "";
        boolean tb = baseline || !t.equals(core.prefs.getString("inTreeSeen", ""));   // 刚选的文件夹：里面早就有的压缩包记成“原来就有”，开始看以后下的才自动解
        Map<String, long[]> arcs = new HashMap<String, long[]>(); Map<String, String> names = new HashMap<String, String>(), dirsOf = new HashMap<String, String>();
        walk(tree, android.provider.DocumentsContract.getTreeDocumentId(tree), "", 0, arcs, names, dirsOf);
        synchronized (files) {
            for (Map.Entry<String, long[]> e : arcs.entrySet()) {
                String k = "saf:" + e.getKey(); JSONObject x = files.get(k); long size = e.getValue()[0], mt = e.getValue()[1];
                if (x != null && x.optLong("size") == size && x.optLong("mtime") == mt) { if ("wait".equals(x.optString("state"))) { x.put("state", names.get(e.getKey()).toLowerCase(Locale.ROOT).endsWith(".rar") ? "zip7" : "zip"); if ("zip7".equals(x.optString("state"))) x.put("err", "手机上解不了 rar：在百度网盘里点开压缩包「解压到网盘」后下载里面的歌，或者放到电脑版的导入文件夹（装了 7-Zip 能解）"); changed = true; } continue; }
                files.put(k, new JSONObject().put("size", size).put("mtime", mt).put("name", names.get(e.getKey())).put("dir", dirsOf.get(e.getKey())).put("state", tb && !(mt > since) ? "old" : "wait").put("at", System.currentTimeMillis())); changed = true;
            }
            for (String p : new ArrayList<String>(files.keySet())) if (p.startsWith("saf:") && !arcs.containsKey(p.substring(4)) && files.get(p).optString("state").matches("wait|zip|zip7|old|pw")) { files.remove(p); changed = true; }
        }
        if (tb) core.prefs.edit().putString("inTreeSeen", t).apply();
        // 能解的 zip：解出里面的歌（到 App 自己的文件夹），解出来的按新歌处理
        List<String> todo = new ArrayList<String>(); synchronized (files) { for (Map.Entry<String, JSONObject> e : files.entrySet()) if ("zip".equals(e.getValue().optString("state")) && core.prefs.getBoolean("inUnzip", true) && go(e.getValue())) todo.add(e.getKey()); }
        // 一次只解几个：解出来的歌传完就删。一口气全解开，几百个压缩包会把存储塞满
        for (String k : todo) {
            int pending = 0; synchronized (files) { for (JSONObject y : files.values()) if (y.optBoolean("x") && y.optString("state").matches("new|up|wait|err")) pending++; }
            if (pending >= 6) break;
            unzip(k); changed = true;
        }
        return changed;
    }
    void walk(android.net.Uri tree, String docId, String rel, int depth, Map<String, long[]> out, Map<String, String> names, Map<String, String> dirsOf) {
        if (depth > 4) return;
        android.net.Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = core.ctx.getContentResolver().query(kids, new String[]{"document_id", "_display_name", "mime_type", "_size", "last_modified"}, null, null, null);
        if (c == null) return;
        try { while (c.moveToNext()) {
            String id = c.getString(0), nm = c.getString(1), mime = c.getString(2);
            if ("vnd.android.document/directory".equals(mime)) { if (!nm.startsWith(".")) walk(tree, id, rel.isEmpty() ? nm : rel + "/" + nm, depth + 1, out, names, dirsOf); }
            else if (nm != null && ARC.matcher(nm).matches()) { out.put(id, new long[]{c.getLong(3), c.getLong(4) / 1000}); names.put(id, nm); dirsOf.put(id, treeName() + (rel.isEmpty() ? "" : "/" + rel)); }
        } } finally { c.close(); }
    }
    void unzip(String key) throws JSONException {
        JSONObject x; synchronized (files) { x = files.get(key); } if (x == null) return;
        x.put("state", "unzipping").put("err", ""); save();
        android.net.Uri tree = android.net.Uri.parse(core.prefs.getString("inTree", ""));
        android.net.Uri doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, key.substring(4));
        String base = x.optString("name").replaceAll("(?i)\\.(zip|7z)$", "").replaceAll("[\\\\/:*?\"<>|]", "_");
        File out = new File(core.ctx.getExternalFilesDir(null), "unzip/" + base); out.mkdirs();
        List<File> got = null; String er = "";
        try { got = x.optString("name").toLowerCase(Locale.ROOT).endsWith(".7z") ? un7z(doc, out) : unzipWith(doc, out); }
        catch (Exception e) { er = String.valueOf(e.getMessage()); String el = er.toLowerCase(Locale.ROOT); if (e instanceof org.apache.commons.compress.PasswordRequiredException || el.contains("encrypt") || el.contains("password") || (!passwords().isEmpty() && (el.contains("checksum") || el.contains("crc") || el.contains("corrupt") || el.contains("data error")))) { rmEmpty(out); x.put("state", "pw").put("err", passwords().isEmpty() ? "要解压密码：在「百度网盘」卡片的「解压密码」里填上，会自动重试" : "填过的解压密码都不对：再加一个试试"); save(); return; } }
        if (got == null || got.isEmpty()) rmEmpty(out);   // 没解出东西：别留空文件夹
        if (got == null) { x.put("state", "err").put("err", "解压失败：" + er); save(); return; }
        synchronized (files) {
            for (File f : got) files.put(f.getAbsolutePath(), new JSONObject().put("size", f.length()).put("mtime", f.lastModified() / 1000).put("state", "new").put("x", true).put("ok", true).put("from", x.optString("name")).put("at", System.currentTimeMillis()));
        }
        x.put("state", "unzipped").put("n", got.size()).put("err", got.isEmpty() ? "压缩包里没有歌" : ""); save();
    }
    // 压缩包里的文件名：标了 UTF-8 的照用；没标的先拿原始字节，是合法 UTF-8 就按 UTF-8，不是就按 GBK（Windows 压的中文名）
    // （安卓的 ZipInputStream 遇到不合法的 UTF-8 不报错，直接换成乱码，所以不能靠“读失败再换”）
    static String zipName(String raw) {
        for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) > 0xFF) return raw;   // 已经按 UTF-8 标记解好了
        byte[] b = raw.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try { return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString(); }
        catch (Exception e) { try { return new String(b, "GBK"); } catch (Exception e2) { return raw; } }
    }
    // 7z：要能前后跳着读（目录在文件末尾），用系统给的文件描述符拿一个可跳的通道，不用先拷一份
    List<File> un7z(android.net.Uri doc, File out) throws Exception {
        List<File> got = new ArrayList<File>();
        // 先不带密码开；要密码的，挨个试你在「解压密码」里填过的。每试一次都重新打开文件：
        // SevenZFile 关掉时会顺手把底下的文件也关了，拿同一个去试下一个密码会报“已关闭”（之前密码对了也解不开就是这个）
        List<String> pws = new ArrayList<String>(); pws.add(null); for (String pw : passwords()) pws.add(pw);
        org.apache.commons.compress.archivers.sevenz.SevenZFile z = null; Exception last = null;
        android.os.ParcelFileDescriptor pfd = null; java.io.FileInputStream fin = null;
        for (String pw : pws) {
            pfd = core.ctx.getContentResolver().openFileDescriptor(doc, "r"); fin = new java.io.FileInputStream(pfd.getFileDescriptor());
            try { z = pw == null ? new org.apache.commons.compress.archivers.sevenz.SevenZFile(fin.getChannel()) : new org.apache.commons.compress.archivers.sevenz.SevenZFile(fin.getChannel(), pw.toCharArray()); break; }
            catch (Exception e) { last = e; z = null; try { fin.close(); } catch (Exception ignored) {} try { pfd.close(); } catch (Exception ignored) {} }
        }
        if (z == null) throw last instanceof org.apache.commons.compress.PasswordRequiredException || pws.size() > 1 ? new org.apache.commons.compress.PasswordRequiredException(pws.size() > 1 ? "填过的解压密码都不对" : "要密码") : last;
        try {
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e; byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName(); if (n == null || e.isDirectory() || n.contains("__MACOSX") || !AUD.matcher(n).matches()) continue;
                String rel = n.replace('\\', '/'); if (rel.contains("../")) continue;
                File f = new File(out, rel); f.getParentFile().mkdirs();
                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                try { int r; while ((r = z.read(buf)) > 0) o.write(buf, 0, r); } finally { o.close(); }
                got.add(f);
            }
        } finally { z.close(); fin.close(); pfd.close(); }
        return got;
    }
    List<File> unzipWith(android.net.Uri doc, File out) throws Exception {
        List<File> got = new ArrayList<File>();
        InputStream in = core.ctx.getContentResolver().openInputStream(doc);
        java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(in, 1 << 16), java.nio.charset.StandardCharsets.ISO_8859_1);
        try {
            java.util.zip.ZipEntry e; byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String n = zipName(e.getName()); if (e.isDirectory() || n.contains("__MACOSX") || !AUD.matcher(n).matches()) continue;
                String rel = n.replace('\\', '/'); if (rel.contains("../")) continue;   // 不安全的路径
                File f = new File(out, rel); f.getParentFile().mkdirs();
                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                try { int r; while ((r = z.read(buf)) > 0) o.write(buf, 0, r); } finally { o.close(); }
                got.add(f);
            }
        } finally { z.close(); }
        return got;
    }

    /* ---------- 百度网盘分享链接：整理成网盘 App 认得的样子，复制好再打开网盘 App（它一打开就会弹“打开这个分享”） ---------- */
    JSONObject baidu(String text) throws Exception {
        String t = text == null ? "" : text.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://pan\\.baidu\\.com/(?:s/([\\w-]+)|share/init\\?surl=([\\w-]+))").matcher(t);
        if (!m.find()) throw new IOException("没认出百度网盘的分享链接（应该像 https://pan.baidu.com/s/1xxxx）");
        String code = m.group(1) != null ? m.group(1) : "1" + m.group(2);
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("(?:[?&]pwd=|提取码|密码|验证码|code)[\\s:：=]*([A-Za-z0-9]{4})").matcher(t);
        String pwd = pm.find() ? pm.group(1) : "";
        final String link = "https://pan.baidu.com/s/" + code + (pwd.isEmpty() ? "" : "?pwd=" + pwd);
        final String clip = "链接: " + link + (pwd.isEmpty() ? "" : " 提取码: " + pwd);
        final boolean has = baiduInstalled();
        h.post(new Runnable() { public void run() {
            try { ((android.content.ClipboardManager) core.ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("百度网盘分享", clip)); } catch (Exception ignored) {}
            try {
                android.content.Intent i = has ? core.ctx.getPackageManager().getLaunchIntentForPackage("com.baidu.netdisk") : new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link));
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); core.ctx.startActivity(i);
            } catch (Exception ignored) {}
        } });
        return new JSONObject().put("link", link).put("pwd", pwd).put("app", has);
    }

    /* ---------- 接口 ---------- */
    JSONObject api(String p, Map<String, String> q, JSONObject body) throws Exception {
        if (p.equals("inbox")) return view();
        if (p.equals("inbox/scan")) { scan(); return view(); }
        if (p.equals("inbox/baidu")) return baidu(body.optString("text"));
        if (p.equals("inbox/pw")) {   // 解压密码只存在这台设备上；改了就把卡在“要密码”的压缩包重新排队
            core.prefs.edit().putString("inPw", core.prefs.getString("inPw", "") + "\n" + body.optString("pw")).commit();   // 加进去，不覆盖以前的
            synchronized (files) { for (JSONObject x : files.values()) if ("pw".equals(x.optString("state")) || ("err".equals(x.optString("state")) && x.optString("err").contains("密码"))) x.put("state", "zip").put("err", ""); }
            save(); core.bg.execute(new Runnable() { public void run() { try { scan(); } catch (Exception ignored) {} } }); return view();
        }
        if (p.equals("inbox/unzip")) { final JSONArray a = body.optJSONArray("paths"); core.bg.execute(new Runnable() { public void run() { if (a != null) for (int i = 0; i < a.length(); i++) try { unzip(a.getString(i)); } catch (Exception ignored) {} try { if (auto()) scan(); } catch (Exception ignored) {} } }); return view(); }
        if (p.equals("inbox/tree/clear")) { core.prefs.edit().remove("inTree").commit(); return view(); }
        if (p.equals("inbox/upload")) {
            List<String> L = new ArrayList<String>();
            if (body.optBoolean("all")) { synchronized (files) { for (Map.Entry<String, JSONObject> e : files.entrySet()) if ("new".equals(e.getValue().optString("state"))) L.add(e.getKey()); } }
            else { JSONArray a = body.optJSONArray("paths"); if (a != null) for (int i = 0; i < a.length(); i++) L.add(a.getString(i)); }
            return upload(L, body);
        }
        if (p.equals("inbox/ignore")) { JSONArray a = body.optJSONArray("paths"); if (a != null) synchronized (files) { for (int i = 0; i < a.length(); i++) { JSONObject x = files.get(a.getString(i)); if (x != null) x.put("state", "skip"); } } save(); return view(); }
        if (p.equals("inbox/approve") || p.equals("inbox/decline")) {
            JSONArray a = body.optJSONArray("paths"); List<String> L = null; if (a != null) { L = new ArrayList<String>(); for (int i = 0; i < a.length(); i++) L.add(a.getString(i)); }
            if (p.equals("inbox/approve")) approve(L); else decline(L); return view();
        }
        if (p.equals("inbox/config")) {
            android.content.SharedPreferences.Editor e = core.prefs.edit();
            if (body.has("dirs")) {
                JSONArray a = body.optJSONArray("dirs"); StringBuilder s = new StringBuilder(); if (a != null) for (int i = 0; i < a.length(); i++) s.append(i > 0 ? ";" : "").append(a.getString(i));
                if (!s.toString().equals(inDirsRaw())) baseline = true;   // 新设的文件夹里原有的歌：记成“原来就有”
                e.putString("inDirs", s.toString());
            }
            if (body.has("auto")) { e.putBoolean("inAuto", body.optBoolean("auto")); e.putString("inMode", body.optBoolean("auto") ? "auto" : "list"); }
            if (body.has("mode") && body.optString("mode").matches("auto|ask|list")) e.putString("inMode", body.optString("mode"));
            if (body.has("tag")) e.putBoolean("inTag", body.optBoolean("tag"));
            if (body.has("target")) e.putString("inTarget", Core.cleanPath(body.optString("target")));
            e.commit(); scan(); return view();
        }
        throw new IOException("没有这个接口: " + p);
    }
}
