package com.starry.music;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「整理」在手机上直接做（不用电脑 / 群星）：查歌名、歌手、专辑、曲序、年份、封面（网易云 + Apple Music），
 * 给出改前改后，确认后用 FTP 把歌拉到手机、改好标签（只支持 flac、mp3）、再传回播放器。还有专辑缺歌检查。
 * 和电脑版 core/meta.js 同一套接口。
 */
public class Meta {
    final Core core;
    final Map<Character, Character> t2s = new HashMap<Character, Character>();
    final Map<String, String> alias = new HashMap<String, String>();
    public final List<String> imports = Collections.synchronizedList(new ArrayList<String>());
    static final Pattern AUDIO = Pattern.compile("(?i)\\.(flac|mp3|m4a|ogg|wav|aac|ape|dsf|dff|wma|aiff?)$");
    static final String[] PLAYLISTY0 = {"待分类", "未分类", "纯音乐", "歌手", "music", "download", "downloads", "favorite songs", "my favorite", "我喜欢的音乐", "其他", "unknown album", "<unknown>"};
    static final String SEP = "\\s*(?:\\s&\\s|&|,\\s*|，|/|×|、|;|\\sx\\s|\\sfeat\\.?\\s|\\sft\\.?\\s)\\s*";

    public Meta(Core c) {
        core = c;
        try { String s = new String(Util.readAll(c.ctx.getAssets().open("t2s.txt")), Util.UTF8); int[] cp = s.codePoints().toArray(); for (int i = 0; i + 1 < cp.length; i += 2) if (cp[i] < 0x10000 && cp[i + 1] < 0x10000) t2s.put((char) cp[i], (char) cp[i + 1]);   /* 按“字”成对读：表里有 4 字节的生僻字，按 char 读会错位（松→胡） */ } catch (Exception ignored) {}
        try { JSONObject a = new JSONObject(new String(Util.readAll(c.ctx.getAssets().open("artist_alias.json")), Util.UTF8)); for (String k : Core.keys(a)) alias.put(k, a.getString(k)); } catch (Exception ignored) {}
        try { JSONObject a = new JSONObject(c.prefs.getString("aliasMine", "{}")); for (String k : Core.keys(a)) alias.put(k, a.getString(k)); } catch (Exception ignored) {}
    }

    /* ---------- 文字处理 ---------- */
    String S(String s) {
        if (s == null) return ""; if (Core.kana(s) > 2) return s;
        StringBuilder b = new StringBuilder(s.length()); for (int i = 0; i < s.length(); i++) { Character r = t2s.get(s.charAt(i)); b.append(r == null ? s.charAt(i) : r); } return b.toString();
    }
    String norm(String t) { return S(t).toLowerCase(Locale.ROOT).replaceAll("[\\s'’\"“”·・.,，。!！?？()（）\\[\\]【】\\-_~&/:：]+", ""); }
    String core(String t) { return norm(String.valueOf(t == null ? "" : t).replaceAll("[（(\\[【][^）)\\]】]*[）)\\]】]", "")); }
    String canonName(String n) {
        String k = n.trim(), lk = k.toLowerCase(Locale.ROOT);
        String h = alias.get(lk); if (h == null) h = alias.get(lk.replaceAll("[\\s.\\-_']", "")); if (h != null) return h;
        return k.matches("(?s).*[\\u4e00-\\u9fff].*") ? S(k) : k;
    }
    String canonArtist(String a, int max) {
        List<String> out = new ArrayList<String>();
        for (String n : String.valueOf(a == null ? "" : a).replaceAll("(?i)<unknown>", "").split(SEP)) {
            if (n.trim().isEmpty()) continue;
            for (String x : canonName(n).split(SEP)) { boolean dup = false; for (String y : out) if (y.equalsIgnoreCase(x)) dup = true; if (!x.isEmpty() && !dup) out.add(x); }
        }
        StringBuilder b = new StringBuilder(); for (int i = 0; i < Math.min(max, out.size()); i++) b.append(i > 0 ? " & " : "").append(out.get(i)); return b.toString();
    }
    boolean sameArtist(String a, String b) {
        Set<String> A = new HashSet<String>(); for (String x : canonArtist(a, 9).split(" & ")) if (!norm(x).isEmpty()) A.add(norm(x));
        for (String x : canonArtist(b, 9).split(" & ")) if (!norm(x).isEmpty() && A.contains(norm(x))) return true;
        return false;
    }
    Set<String> playlisty() {
        Set<String> s = new HashSet<String>(); for (String x : PLAYLISTY0) s.add(x.toLowerCase(Locale.ROOT));
        for (String x : core.cfg("playlistAlbums", "").split("\\s*[;；,，]\\s*")) if (!x.isEmpty()) s.add(x.toLowerCase(Locale.ROOT));
        return s;
    }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    /* ---------- 来源 ---------- */
    List<JSONObject> srcNetease(String title, String artist) throws Exception {
        JSONObject j = new JSONObject(new String(Util.http("POST", "https://music.163.com/api/cloudsearch/pc?s=" + enc((title + " " + artist).trim()) + "&type=1&limit=12", Core.UA(), new byte[0], 10000).body, Util.UTF8));
        List<JSONObject> o = new ArrayList<JSONObject>(); JSONArray a = j.optJSONObject("result") == null ? null : j.optJSONObject("result").optJSONArray("songs");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i), al = x.optJSONObject("al"); StringBuilder ar = new StringBuilder(); JSONArray as = x.optJSONArray("ar");
            if (as != null) for (int k = 0; k < as.length(); k++) ar.append(k > 0 ? " & " : "").append(as.getJSONObject(k).optString("name"));
            String pic = al == null ? "" : al.optString("picUrl", "").replaceFirst("^http:", "https:");
            long pt = x.optLong("publishTime", 0);
            o.add(new JSONObject().put("src", "163").put("id", "" + x.optLong("id")).put("title", x.optString("name")).put("artist", ar.toString()).put("album", al == null ? "" : al.optString("name"))
                .put("albumId", al == null ? "" : "" + al.optLong("id")).put("cover", pic.isEmpty() ? "" : pic + "?param=800y800").put("track", x.optInt("no", 0))
                .put("year", pt > 0 ? yearOf(pt) : 0).put("dur", x.optDouble("dt", 0) / 1000));
        }
        return o;
    }
    static int yearOf(long ms) { java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(ms); return c.get(java.util.Calendar.YEAR); }
    List<JSONObject> srcItunes(String title, String artist) throws Exception {
        List<JSONObject> o = new ArrayList<JSONObject>(); String term = (title + " " + artist).trim();
        for (String country : new String[]{"US", "JP", "TW"}) {
            if (!o.isEmpty() || (!country.equals("US") && !term.matches("(?s).*[\\u3040-\\u9fff].*"))) break;
            JSONObject j = new JSONObject(new String(Util.http("GET", "https://itunes.apple.com/search?term=" + enc(term) + "&entity=song&limit=10&country=" + country, Core.UA(), null, 10000).body, Util.UTF8));
            JSONArray a = j.optJSONArray("results"); if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i); String rd = x.optString("releaseDate", "");
                o.add(new JSONObject().put("src", "apple").put("id", "" + x.optLong("trackId")).put("title", x.optString("trackName")).put("artist", x.optString("artistName")).put("album", x.optString("collectionName"))
                    .put("albumId", "" + x.optLong("collectionId")).put("cover", x.optString("artworkUrl100", "").replace("100x100bb", "1000x1000bb")).put("track", x.optInt("trackNumber", 0))
                    .put("year", rd.length() >= 4 ? Core.parseInt(rd.substring(0, 4)) : 0).put("dur", x.optDouble("trackTimeMillis", 0) / 1000));
            }
        }
        return o;
    }

    /* ---------- 一首歌：现在的信息 + 建议 ---------- */
    static String stripNo(String t) { return String.valueOf(t).replaceAll("(?i)\\.(flac|mp3|m4a|ogg|wav|aac|ape|dsf|dff|wma|aiff?)$", "").replaceFirst("^\\s*\\d{1,3}\\s*[.\\-_、 ]\\s*", ""); }
    static String[] fileGuess(String p) {
        String f = stripNo(p.substring(Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1));
        Matcher m = Pattern.compile("^(.+?)-(.+?)-\\d{5,}$").matcher(f); if (m.find()) return new String[]{m.group(1).replace('_', ' ').trim(), m.group(2).replace("_", " / ").trim()};
        m = Pattern.compile("^(.+?) - (.+)$").matcher(f); if (m.find()) return new String[]{m.group(2).trim(), m.group(1).trim()};
        return new String[]{f.trim(), ""};
    }
    volatile Map<String, JSONObject> dmsByUrl; volatile long dmsTs;
    synchronized Map<String, JSONObject> dms(boolean force) throws Exception {
        File f = new File(core.dir, "dms.json");
        if (dmsByUrl == null && !force) try { mkDms(new JSONObject(Core.read(f))); } catch (Exception ignored) {}
        if (dmsByUrl != null && !force) { if (System.currentTimeMillis() - dmsTs > 10 * 60000L) core.bg.execute(new Runnable() { public void run() { try { dms(true); } catch (Exception ignored) {} } }); return dmsByUrl; }
        JSONArray all = new JSONArray(); int st = 0, total = 1;
        while (st < total) { JSONObject j = core.browse("1", st, 200); total = j.getInt("total"); JSONArray it = j.getJSONArray("items"); if (it.length() == 0) break; for (int i = 0; i < it.length(); i++) { JSONObject x = it.getJSONObject(i); x.remove("didl"); if ("song".equals(x.optString("kind"))) all.put(x); } st += 200; }
        JSONObject j = new JSONObject().put("ts", System.currentTimeMillis()).put("songs", all); Core.write(f, j.toString()); mkDms(j); return dmsByUrl;
    }
    void mkDms(JSONObject j) throws JSONException { Map<String, JSONObject> m = new HashMap<String, JSONObject>(); JSONArray a = j.getJSONArray("songs"); for (int i = 0; i < a.length(); i++) m.put(a.getJSONObject(i).optString("url"), a.getJSONObject(i)); dmsByUrl = m; dmsTs = j.getLong("ts"); }
    JSONObject curInfo(String p) throws Exception {
        try { core.pathIndex(false); } catch (Exception ignored) {}
        JSONObject fe = core.pidx == null ? null : core.pidx.get(p.toLowerCase(Locale.ROOT));
        if (fe == null && core.pidxSd != null && !p.startsWith(core.pre() + "/")) fe = core.pidxSd.get(Core.sdRest(p));
        Map<String, JSONObject> D = null; try { D = dms(false); } catch (Exception ignored) {}
        JSONObject d = fe != null && D != null ? D.get(fe.optString("url")) : null;
        String folder = p.split("/").length > 1 ? p.split("/")[p.split("/").length - 2] : "";
        if (d != null) {
            String al = d.optString("album"); boolean pl = al.equals(folder) || playlisty().contains(al.toLowerCase(Locale.ROOT));
            String art = d.optString("art");
            return new JSONObject().put("indexed", true).put("title", d.optString("title")).put("artist", d.optString("artist").toLowerCase(Locale.ROOT).contains("unknown") ? "" : d.optString("artist"))
                .put("album", pl ? "" : al).put("albumRaw", al).put("art", art.isEmpty() ? "" : "/api/music/art?u=" + enc(art)).put("dur", d.optDouble("dur", 0)).put("size", d.optLong("size")).put("url", d.optString("url"));
        }
        String[] g = fileGuess(p);
        return new JSONObject().put("indexed", false).put("title", g[0]).put("artist", g[1]).put("album", "").put("albumRaw", "").put("art", "").put("dur", 0).put("size", 0);
    }
    JSONArray problems(String p, JSONObject c) {
        JSONArray L = new JSONArray(); String f = p.substring(p.lastIndexOf('/') + 1), t = c.optString("title"), a = c.optString("artist");
        if (a.isEmpty()) L.put("没有歌手");
        if (c.optString("album").isEmpty()) L.put(c.optString("albumRaw").isEmpty() ? "没有专辑" : "专辑是歌单名");
        if (t.equals(f) || AUDIO.matcher(t).find() || t.matches("^\\d{1,3}\\s*[.\\-_、 ].*")) L.put("歌名是文件名");
        if (!a.isEmpty()) {
            List<String> A = new ArrayList<String>(), B = new ArrayList<String>();
            for (String x : a.split(SEP)) if (!x.trim().isEmpty()) A.add(x.trim().toLowerCase(Locale.ROOT));
            for (String x : canonArtist(a, 9).split(" & ")) if (!x.trim().isEmpty()) B.add(x.trim().toLowerCase(Locale.ROOT));
            Collections.sort(A); Collections.sort(B); if (!A.equals(B)) L.put("歌手写法不统一");
        }
        if (!S(t).equals(t)) L.put("繁体");
        return L;
    }
    static final Pattern VER = Pattern.compile("(?i)live|现场|演唱会|伴奏|inst|remix|dj|cover|翻唱|karaoke|demo|钢琴版|piano ver");
    static final Pattern COMP = Pattern.compile("(?i)精选|极品|合集|选辑|全记录|金曲|经典|greatest|best of|collection|hits|anthology|essential|最爱|珍藏|纪念|典藏|热歌|榜");
    public JSONObject propose(String p, JSONObject cur0) throws Exception {
        JSONObject c = cur0 != null ? cur0 : curInfo(p);
        String qt = stripNo(c.optString("title")), qa = c.optString("artist").isEmpty() ? fileGuess(p)[1] : c.optString("artist"); double qd = c.optDouble("dur", 0);
        String st = qt.replaceAll("[（(\\[【][^）)\\]】]*[）)\\]】]", "").trim(); if (st.isEmpty()) st = qt; String sa = qa.split(SEP).length > 0 ? qa.split(SEP)[0] : "";
        List<JSONObject> cands = new ArrayList<JSONObject>(); StringBuilder err = new StringBuilder();
        try { cands.addAll(srcNetease(st, sa)); } catch (Exception e) { err.append("网易云：").append(e.getMessage()).append("；"); }
        try { cands.addAll(srcItunes(st, sa)); } catch (Exception e) { err.append("Apple：").append(e.getMessage()).append("；"); }
        String nt = norm(qt), ct0 = core(qt);
        for (JSONObject x : cands) {
            String tn = norm(x.optString("title")), tc = core(x.optString("title"));
            double tm = tn.equals(nt) ? 2 : (!tc.isEmpty() && tc.equals(ct0)) ? 1 : ((tn.contains(ct0) || ct0.contains(tc)) && tc.length() > 1) ? 0.5 : 0;
            Boolean am = qa.isEmpty() ? null : sameArtist(qa, x.optString("artist"));
            Double d = qd > 0 && x.optDouble("dur", 0) > 0 ? Math.abs(x.optDouble("dur") - qd) : null;
            double sc = (2 - tm) * 10 + (Boolean.FALSE.equals(am) ? 12 : am == null ? 4 : 0) + (d == null ? 3 : Math.min(30, d * 2)) + ("apple".equals(x.optString("src")) && qt.matches("(?s).*[\\u4e00-\\u9fff].*") ? 2 : 0);
            if (Core.kana(qt) == 0 && Core.kana(x.optString("title")) > 0) sc += 20;
            if (COMP.matcher(x.optString("album")).find() && !COMP.matcher(qt).find()) sc += 4;
            if (VER.matcher(x.optString("title") + " " + x.optString("album")).find() && !VER.matcher(qt).find()) sc += 6;
            x.put("score", Math.round(sc * 10) / 10.0).put("base", Math.round(sc * 10) / 10.0).put("tm", tm).put("am", am == null ? JSONObject.NULL : am).put("d", d == null ? JSONObject.NULL : (Object) Math.round(d));
        }
        int y0 = 0; for (JSONObject x : cands) if (x.optDouble("tm") == 2 && !Boolean.FALSE.equals(x.opt("am")) && x.optInt("year") > 0 && (x.isNull("d") || x.optDouble("d") <= 2)) y0 = y0 == 0 ? x.optInt("year") : Math.min(y0, x.optInt("year"));
        if (y0 > 0) for (JSONObject x : cands) if (x.optDouble("tm") == 2 && !Boolean.FALSE.equals(x.opt("am")) && x.optInt("year") > 0 && (x.isNull("d") || x.optDouble("d") <= 2)) x.put("score", Math.round((x.optDouble("score") + Math.min(2, (x.optInt("year") - y0) / 5.0)) * 10) / 10.0);
        // 文件里本来写着的专辑也在候选里：优先留着它（同一首歌常收在好几张专辑里，别随手换掉）
        String keepAl = norm(c.optString("album")); if (!keepAl.isEmpty()) for (JSONObject x : cands) if (x.optDouble("tm") >= 1 && !Boolean.FALSE.equals(x.opt("am")) && norm(x.optString("album")).equals(keepAl)) x.put("score", x.optDouble("score") - 3);
        Collections.sort(cands, new Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) { return Double.compare(a.optDouble("score"), b.optDouble("score")); } });
        JSONObject best = cands.isEmpty() ? null : cands.get(0);
        String conf = best == null ? "none" : best.optDouble("tm") >= 1 && !Boolean.FALSE.equals(best.opt("am")) && (best.isNull("d") || best.optDouble("d") <= 3) && best.optDouble("base") <= 10 ? "high" : best.optDouble("tm") >= 0.5 && best.optDouble("base") <= 26 ? "mid" : "low";
        if ("high".equals(conf) && qa.isEmpty() && best.isNull("d")) conf = "mid";
        if (best != null && Boolean.FALSE.equals(best.opt("am"))) conf = "low";
        JSONArray ca = new JSONArray(); for (int i = 0; i < Math.min(12, cands.size()); i++) ca.put(cands.get(i));
        boolean ok = best != null && !"low".equals(conf);
        return new JSONObject().put("path", p).put("cur", c).put("problems", problems(p, c)).put("q", new JSONObject().put("title", qt).put("artist", qa).put("dur", qd))
            .put("cands", ca).put("pick", ok ? 0 : -1).put("conf", conf).put("err", err.toString()).put("writable", Tagger.writable(p)).put("proposed", proposal(c, ok ? best : null));
    }
    JSONObject proposal(JSONObject c, JSONObject x) throws JSONException {
        String keep = S(stripNo(c.optString("title"))).trim();
        if (x == null) return new JSONObject().put("title", keep).put("artist", c.optString("artist").isEmpty() ? "" : canonArtist(c.optString("artist"), 4)).put("album", S(c.optString("album"))).put("track", 0).put("year", 0).put("cover", "");
        boolean sameT = core(x.optString("title")).equals(core(keep));
        String t = sameT && keep.matches("(?s).*[（(\\[【].*") && !x.optString("title").matches("(?s).*[（(\\[【].*") ? keep : S(x.optString("title"));
        return new JSONObject().put("title", t).put("artist", canonArtist(x.optString("artist"), 3)).put("album", S(x.optString("album"))).put("track", x.optInt("track")).put("year", x.optInt("year"))
            .put("cover", x.optString("cover")).put("src", x.optString("src")).put("srcId", x.optString("id")).put("albumId", x.optString("albumId"));
    }

    /* ---------- 选歌 ---------- */
    JSONObject applied() { try { return new JSONObject(Core.read(new File(core.dir, "meta_applied.json"))); } catch (Exception e) { return new JSONObject(); } }
    JSONObject list(String kind, String dir) throws Exception {
        List<String> paths = new ArrayList<String>();
        if ("imports".equals(kind)) { synchronized (imports) { for (int i = imports.size() - 1; i >= 0; i--) paths.add(imports.get(i)); } }
        else if ("dir".equals(kind)) {
            final String d = Core.cleanPath(dir == null || dir.isEmpty() ? "/Music/待分类" : dir);
            List<Ftp.Entry> L = core.withFtp(new Core.FtpWork<List<Ftp.Entry>>() { public List<Ftp.Entry> run(Ftp c) throws Exception { return c.list(d); } });
            for (Ftp.Entry e : L) if (!e.dir && AUDIO.matcher(e.name).find()) paths.add(core.pre() + (d.equals("/") ? "" : d) + "/" + e.name);
        } else {
            try { core.pathIndex(false); } catch (Exception ignored) {}
            if (core.pidxUrl != null) for (String p : core.pidxUrl.values()) if (p.startsWith(core.pre() + "/Music/")) paths.add(p);
            Collections.sort(paths);
        }
        JSONObject ap = applied(); JSONArray out = new JSONArray();
        for (String p : paths) {
            JSONObject c = curInfo(p); JSONArray pr = problems(p, c);
            if ("missing".equals(kind) && (pr.length() == 0 || ap.has(p))) continue;
            out.put(new JSONObject().put("path", p).put("cur", c).put("problems", pr).put("applied", ap.has(p) ? ap.getJSONObject(p).optLong("at") : 0).put("writable", Tagger.writable(p)));
            if (out.length() >= 1500) break;
        }
        return new JSONObject().put("kind", kind).put("items", out);
    }

    /* ---------- 写进文件：拉到手机 → 改标签 → 传回（先传成临时名，再换名，任何一步失败都能找回） ---------- */
    volatile Thread job; volatile boolean stop; final JSONObject st = new JSONObject();
    JSONObject apply(JSONArray items) throws Exception {
        if (job != null && job.isAlive()) throw new IOException("上一批还在写，等它写完");
        final List<JSONObject> plan = new ArrayList<JSONObject>();
        JSONObject mine = new JSONObject(core.prefs.getString("aliasMine", "{}"));
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i); String p = it.optString("path"); if (!p.startsWith(core.pre() + "/") || !Tagger.writable(p)) continue;
            JSONObject n = it.optJSONObject("new"); if (n == null || n.length() == 0) continue;
            plan.add(new JSONObject().put("path", p).put("new", n));
            JSONObject fr = it.optJSONObject("from"); String fa = fr == null ? "" : fr.optString("artist"), na = n.optString("artist");
            if (!fa.isEmpty() && !na.isEmpty() && !fa.equals(na) && !fa.matches(".*[&,/、].*") && !na.contains("&")) { mine.put(fa.toLowerCase(Locale.ROOT), na); alias.put(fa.toLowerCase(Locale.ROOT), na); }
        }
        if (plan.isEmpty()) throw new IOException("没有要改的");
        core.prefs.edit().putString("aliasMine", mine.toString()).apply();
        synchronized (st) { for (String k : Core.keys(st)) st.remove(k); st.put("total", plan.size()).put("ok", 0).put("skip", 0).put("err", 0).put("bytes", 0).put("t0", System.currentTimeMillis() / 1000.0).put("cur", "").put("errs", new JSONArray()).put("fin", false); }
        stop = false;
        job = new Thread(new Runnable() { public void run() { runPlan(plan); } }, "starry-tag"); job.start();
        return new JSONObject().put("started", plan.size());
    }
    void runPlan(List<JSONObject> plan) {
        File tmp = new File(core.ctx.getCacheDir(), "tag"); tmp.mkdirs();
        for (JSONObject x : plan) {
            if (stop) break;
            final String p = x.optString("path"), rp = p.substring(core.pre().length()), ext = p.substring(p.lastIndexOf('.'));
            synchronized (st) { try { st.put("cur", rp).put("elapsed", Math.round(System.currentTimeMillis() / 1000.0 - st.optDouble("t0"))); } catch (JSONException ignored) {} }
            final File a = new File(tmp, "a" + ext), b = new File(tmp, "b" + ext);
            try {
                final long[] size0 = {0};
                core.withFtp(new Core.FtpWork<Void>() { public Void run(Ftp c) throws Exception {
                    if (c.size(rp) <= 0 && c.size(rp + ".tagbak") > 0) c.rename(rp + ".tagbak", rp);   // 上次中途断了：先改回来
                    OutputStream o = new FileOutputStream(a); try { c.retr(rp, o, null); } finally { o.close(); }
                    size0[0] = a.length(); return null; } });
                JSONObject n = x.getJSONObject("new"); Map<String, String> m = new LinkedHashMap<String, String>();
                for (String k : new String[]{"title", "artist", "album", "track", "year"}) { String v = n.optString(k, ""); if (!v.isEmpty() && !v.equals("0")) m.put(k, v); }
                Map<String, String> old = Tagger.read(a.getPath());
                if ("Apple Music".equalsIgnoreCase(old.get("albumartist")) && (m.containsKey("artist") || old.containsKey("artist"))) m.put("albumartist", (m.containsKey("artist") ? m.get("artist") : old.get("artist")).split(" & ")[0]);
                byte[] cover = n.optString("cover").startsWith("https://") ? Tagger.fetch(n.optString("cover")) : null;
                Tagger.write(a, b, m, cover);
                core.withFtp(new Core.FtpWork<Void>() { public Void run(Ftp c) throws Exception {
                    InputStream in = new FileInputStream(b); try { c.stor(rp + ".tagtmp", in, b.length(), null); } finally { in.close(); }
                    if (c.size(rp + ".tagtmp") > 0 && c.size(rp + ".tagtmp") != b.length()) { c.dele(rp + ".tagtmp"); throw new IOException("上传不完整（原文件没动）"); }
                    c.rename(rp, rp + ".tagbak"); c.rename(rp + ".tagtmp", rp); c.dele(rp + ".tagbak"); return null; } });
                JSONObject ap = applied(); ap.put(p, new JSONObject().put("at", System.currentTimeMillis()).put("new", n)); Core.write(new File(core.dir, "meta_applied.json"), ap.toString());
                synchronized (st) { st.put("ok", st.optInt("ok") + 1).put("bytes", st.optLong("bytes") + size0[0] + b.length()); }
            } catch (Exception e) {
                synchronized (st) { try { st.put("err", st.optInt("err") + 1); st.getJSONArray("errs").put(new JSONArray().put(rp).put(String.valueOf(e.getMessage()))); } catch (JSONException ignored) {} }
                try { core.withFtp(new Core.FtpWork<Void>() { public Void run(Ftp c) throws Exception { if (c.size(rp + ".tagtmp") > 0) c.dele(rp + ".tagtmp"); return null; } }); } catch (Exception ignored) {}
            } finally { a.delete(); b.delete(); }
        }
        synchronized (st) { try { st.put("cur", "").put("fin", true).put("elapsed", Math.round(System.currentTimeMillis() / 1000.0 - st.optDouble("t0"))); } catch (JSONException ignored) {} }
        core.pidx = null; core.changed();
    }
    JSONObject jobView() throws JSONException { JSONObject s; synchronized (st) { s = new JSONObject(st.toString()); } return new JSONObject().put("running", job != null && job.isAlive()).put("err", "").put("status", s.length() == 0 ? JSONObject.NULL : s); }

    /* ---------- 专辑缺哪几首 ---------- */
    JSONObject albums() throws Exception {
        Map<String, JSONObject> D = dms(false); Map<String, JSONObject> g = new LinkedHashMap<String, JSONObject>(); Set<String> pl = playlisty();
        JSONObject chk; try { chk = new JSONObject(Core.read(new File(core.dir, "album_check.json"))); } catch (Exception e) { chk = new JSONObject(); }
        for (JSONObject x : D.values()) {
            String al = x.optString("album").trim(); if (al.isEmpty() || pl.contains(al.toLowerCase(Locale.ROOT))) continue;
            String k = norm(al); JSONObject e = g.get(k); if (e == null) { e = new JSONObject().put("album", S(al)).put("artists", new JSONObject()).put("n", 0); g.put(k, e); }
            String a = canonArtist(x.optString("artist"), 4); if (a.isEmpty()) a = "未知"; e.getJSONObject("artists").put(a, e.getJSONObject("artists").optInt(a) + 1); e.put("n", e.optInt("n") + 1);
        }
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (Map.Entry<String, JSONObject> en : g.entrySet()) {
            JSONObject e = en.getValue(); if (e.optInt("n") > 80) continue;
            String best = "未知"; int bn = -1; for (String a : Core.keys(e.getJSONObject("artists"))) if (e.getJSONObject("artists").getInt(a) > bn) { bn = e.getJSONObject("artists").getInt(a); best = a; }
            boolean mix = e.getJSONObject("artists").length() >= 4 && e.optInt("n") >= 6; JSONObject ck = chk.optJSONObject(en.getKey());
            out.add(new JSONObject().put("key", en.getKey()).put("album", e.optString("album")).put("artist", best).put("have", e.optInt("n")).put("mix", mix)
                .put("total", ck == null ? JSONObject.NULL : (Object) ck.optInt("total")).put("missing", ck == null ? JSONObject.NULL : (Object) ck.optJSONArray("missing").length()));
        }
        Collections.sort(out, new Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) {
            int c = Boolean.compare(a.optBoolean("mix"), b.optBoolean("mix")); if (c != 0) return c;
            c = Integer.compare(b.optInt("missing", 0), a.optInt("missing", 0)); return c != 0 ? c : Integer.compare(b.optInt("have"), a.optInt("have")); } });
        JSONArray a = new JSONArray(); for (JSONObject x : out) a.put(x); return new JSONObject().put("items", a);
    }
    JSONObject album(String album, String artist) throws Exception {
        Map<String, JSONObject> D = dms(false); String k = norm(album); List<JSONObject> mine = new ArrayList<JSONObject>();
        for (JSONObject x : D.values()) if (norm(x.optString("album")).equals(k)) mine.add(new JSONObject().put("title", S(stripNo(x.optString("title")))).put("artist", x.optString("artist")).put("url", x.optString("url")));
        JSONArray tracks = new JSONArray(); String src = "", cover = "";
        try {
            JSONObject r = new JSONObject(new String(Util.http("POST", "https://music.163.com/api/cloudsearch/pc?s=" + enc((album + " " + artist).trim()) + "&type=10&limit=8", Core.UA(), new byte[0], 10000).body, Util.UTF8));
            JSONArray al = r.optJSONObject("result") == null ? null : r.optJSONObject("result").optJSONArray("albums"); JSONObject best = null; double bs = 1e9;
            if (al != null) for (int i = 0; i < al.length(); i++) {
                JSONObject a = al.getJSONObject(i); StringBuilder an = new StringBuilder(); JSONArray as = a.optJSONArray("artists"); if (as != null) for (int j = 0; j < as.length(); j++) an.append(j > 0 ? " & " : "").append(as.getJSONObject(j).optString("name"));
                double sc = (norm(a.optString("name")).equals(k) ? 0 : core(a.optString("name")).equals(core(album)) ? 2 : 10) + (!artist.isEmpty() && sameArtist(artist, an.toString()) ? 0 : 5);
                if (sc < bs) { bs = sc; best = a; }
            }
            if (best != null && bs <= 7) {
                JSONObject d = new JSONObject(new String(Util.http("GET", "https://music.163.com/api/v1/album/" + best.optLong("id"), Core.UA(), null, 10000).body, Util.UTF8));
                JSONArray ss = d.optJSONArray("songs"); if (ss != null) for (int i = 0; i < ss.length(); i++) { JSONObject s = ss.getJSONObject(i); StringBuilder an = new StringBuilder(); JSONArray as = s.optJSONArray("ar"); if (as != null) for (int j = 0; j < as.length(); j++) an.append(j > 0 ? " & " : "").append(as.getJSONObject(j).optString("name"));
                    tracks.put(new JSONObject().put("no", s.optInt("no")).put("title", S(s.optString("name"))).put("artist", an.toString()).put("dur", s.optDouble("dt", 0) / 1000)); }
                src = "网易云"; cover = best.optString("picUrl", "").replaceFirst("^http:", "https:");
            }
        } catch (Exception ignored) {}
        Set<String> have = new HashSet<String>(); for (JSONObject x : mine) have.add(core(x.optString("title")));
        JSONArray missing = new JSONArray(), extra = new JSONArray(), mlist = new JSONArray();
        for (int i = 0; i < tracks.length(); i++) { JSONObject t = tracks.getJSONObject(i); t.put("have", have.contains(core(t.optString("title")))); if (!t.optBoolean("have")) { missing.put(t); mlist.put(t.optString("title")); } }
        for (JSONObject x : mine) { boolean f = false; for (int i = 0; i < tracks.length(); i++) if (core(tracks.getJSONObject(i).optString("title")).equals(core(x.optString("title")))) f = true; if (!f) extra.put(x); }
        if (tracks.length() > 0) { JSONObject chk; try { chk = new JSONObject(Core.read(new File(core.dir, "album_check.json"))); } catch (Exception e) { chk = new JSONObject(); } chk.put(k, new JSONObject().put("at", System.currentTimeMillis()).put("total", tracks.length()).put("missing", mlist).put("src", src)); Core.write(new File(core.dir, "album_check.json"), chk.toString()); }
        return new JSONObject().put("album", album).put("artist", artist).put("src", src).put("cover", cover).put("found", tracks.length() > 0).put("tracks", tracks).put("missing", missing).put("extra", extra);
    }

    /* ---------- 接口 ---------- */
    JSONObject api(String p, Map<String, String> q, JSONObject body) throws Exception {
        if (p.equals("meta/list")) return list(q.containsKey("kind") ? q.get("kind") : "missing", q.get("dir"));
        if (p.equals("meta/propose")) {
            final JSONArray paths = body.optJSONArray("paths") == null ? new JSONArray() : body.optJSONArray("paths");
            final int n = Math.min(60, paths.length()); final JSONObject[] out = new JSONObject[n]; final int[] next = {0};
            Thread[] ts = new Thread[3];
            for (int t = 0; t < 3; t++) { ts[t] = new Thread(new Runnable() { public void run() { while (true) { int i; synchronized (next) { i = next[0]++; } if (i >= n) return; String pp = paths.optString(i);
                try { out[i] = propose(pp, null); } catch (Exception e) { try { out[i] = new JSONObject().put("path", pp).put("err", String.valueOf(e.getMessage())).put("cands", new JSONArray()).put("cur", new JSONObject()).put("proposed", new JSONObject()); } catch (JSONException ignored) {} } } } }); ts[t].start(); }
            for (Thread t : ts) t.join(180000);
            JSONArray a = new JSONArray(); for (JSONObject x : out) if (x != null) a.put(x); return new JSONObject().put("items", a);
        }
        if (p.equals("meta/search")) { List<JSONObject> L = new ArrayList<JSONObject>(); String t = q.containsKey("title") ? q.get("title") : "", ar = q.containsKey("artist") ? q.get("artist") : ""; try { L.addAll(srcNetease(t, ar)); } catch (Exception ignored) {} try { L.addAll(srcItunes(t, ar)); } catch (Exception ignored) {} JSONArray a = new JSONArray(); for (JSONObject x : L) a.put(x); return new JSONObject().put("items", a); }
        if (p.equals("meta/apply")) return apply(body.optJSONArray("items") == null ? new JSONArray() : body.optJSONArray("items"));
        if (p.equals("meta/job")) return jobView();
        if (p.equals("meta/stop")) { stop = true; return new JSONObject().put("stopped", true); }
        if (p.equals("meta/albums")) return albums();
        if (p.equals("meta/album")) return album(q.containsKey("album") ? q.get("album") : "", q.containsKey("artist") ? q.get("artist") : "");
        throw new IOException("没有这个接口: " + p);
    }
}
