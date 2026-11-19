package com.starry.music;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 群星音乐 · 安卓版核心。和电脑版 core/music.js 一一对应，接口一样（/api/music/...），界面共用一个。
 * 播放器的服务很脆：SOAP 一个一个发（Upnp.soap 里加了锁），FTP 最多同时 2 个会话。
 */
public class Core {
    public interface Listener { void changed(); }

    final Context ctx; final SharedPreferences prefs; final File dir;
    public Listener listener;
    final ExecutorService bg = Executors.newCachedThreadPool();
    final ScheduledExecutorService tick = Executors.newScheduledThreadPool(2);
    final Semaphore ftpSem = new Semaphore(2);
    final Random rnd = new Random();

    // 播放器
    volatile String ip, playerName = "";
    final Map<String, String> ctl = new HashMap<String, String>();
    List<Upnp.Dev> devices = new ArrayList<Upnp.Dev>();
    // 状态
    volatile String state = "UNKNOWN", uri = "", err = ""; volatile double rel, dur; volatile long at; volatile boolean online; volatile Integer vol;
    final List<JSONObject> queue = new ArrayList<JSONObject>(); volatile int pos = -1; volatile JSONObject cur;
    volatile String mode = "order"; final List<Integer> hist = new ArrayList<Integer>();
    volatile boolean wantPlay, resumed, endLoop; volatile long lastStart, seen; volatile int nextIdx = -1, gapless;
    volatile Boolean loud;
    // 睡眠：until（毫秒）或 endOfTrack
    volatile long sleepUntil; volatile int sleepMin; volatile boolean sleepEnd, sleepBusy; volatile String sleepUrl; volatile Integer sleepVol0;
    final Map<String, JSONObject> lyr = new LinkedHashMap<String, JSONObject>();
    final Map<String, Job> jobs = new LinkedHashMap<String, Job>();

    public Core(Context c) {
        ctx = c; prefs = c.getSharedPreferences("cfg", 0); dir = new File(c.getFilesDir(), "music"); dir.mkdirs();
        new File(dir, "playlists/_history").mkdirs();
        loadQueue();
        try { JSONObject p = new JSONObject(read(new File(dir, "player.json"))); ip = p.optString("ip", null); playerName = p.optString("name", ""); JSONObject c2 = p.optJSONObject("ctl"); if (c2 != null) for (String k : keys(c2)) ctl.put(k, c2.getString(k)); } catch (Exception ignored) {}
        bg.execute(new Runnable() { public void run() { discover(); } });
        tick.scheduleWithFixedDelay(new Runnable() { public void run() { try { watch(); } catch (Throwable ignored) {} } }, 2, 2, TimeUnit.SECONDS);
        tick.scheduleWithFixedDelay(new Runnable() { public void run() { try { sleepTick(); } catch (Throwable ignored) {} } }, 1, 1, TimeUnit.SECONDS);
        tick.scheduleWithFixedDelay(new Runnable() { public void run() { try { discover(); } catch (Throwable ignored) {} } }, 10, 10, TimeUnit.MINUTES);
        tick.scheduleWithFixedDelay(new Runnable() { public void run() { try { plSyncDirty(); } catch (Throwable ignored) {} } }, 60, 60, TimeUnit.SECONDS);
    }
    public void shutdown() { tick.shutdownNow(); bg.shutdownNow(); }

    /* ---------- 配置（App 里在“设置”里改，存在 SharedPreferences） ---------- */
    String cfg(String k, String d) { return prefs.getString(k, d); }
    boolean cfgB(String k) { return prefs.getBoolean(k, false); }
    String pre() { String s = cfg("internalPath", ""); return s.isEmpty() ? "/storage/emulated/0" : s; }
    String sdPath() { String s = cfg("sdPath", ""); return s.isEmpty() ? "/storage/sdcard1" : s; }
    JSONObject cfgJson() throws JSONException {
        JSONObject o = new JSONObject();
        for (String k : new String[]{"name", "ip", "sdPath", "internalPath", "ftpPort", "ftpUser", "ftpPass", "uploadDir", "hubUrl", "hubToken"}) o.put(k, cfg(k, ""));
        o.put("allowDsd", cfgB("allowDsd")); o.put("lyrics", prefs.getBoolean("lyrics", true));
        return o;
    }
    JSONObject setCfg(JSONObject b) throws JSONException {
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new String[]{"name", "ip", "sdPath", "internalPath", "ftpPort", "ftpUser", "ftpPass", "uploadDir", "hubUrl", "hubToken"}) if (b.has(k)) e.putString(k, b.optString(k, "").trim());
        if (b.has("allowDsd")) e.putBoolean("allowDsd", b.optBoolean("allowDsd"));
        if (b.has("lyrics")) e.putBoolean("lyrics", b.optBoolean("lyrics", true));
        e.apply();
        pidx = null; fidx = null;
        bg.execute(new Runnable() { public void run() { discover(); } });
        return cfgJson();
    }

    /* ---------- 发现 ---------- */
    public synchronized JSONObject discover() {
        List<Upnp.Dev> devs = Upnp.discover();
        devices = devs;
        String wantName = cfg("name", ""), wantIp = cfg("ip", "");
        Upnp.Dev r = null, sv = null;
        for (Upnp.Dev d : devs) if (d.type.equals("r") && d.svcs.containsKey("avt") && (wantName.isEmpty() || (d.name != null && d.name.contains(wantName))) && (wantIp.isEmpty() || wantIp.equals(d.ip))) { r = d; break; }
        if (r != null) { ip = r.ip; playerName = r.name; ctl.put("avt", r.svcs.get("avt")); if (r.svcs.containsKey("rc")) ctl.put("rc", r.svcs.get("rc")); if (r.svcs.containsKey("cm")) ctl.put("cm", r.svcs.get("cm")); }
        for (Upnp.Dev d : devs) if (d.type.equals("s") && d.svcs.containsKey("cd") && d.ip.equals(ip)) { sv = d; break; }
        if (sv != null) ctl.put("cd", sv.svcs.get("cd"));
        JSONObject o = new JSONObject();
        try {
            if (r != null || sv != null) { JSONObject p = new JSONObject(); p.put("ip", ip); p.put("name", playerName); p.put("ctl", new JSONObject(ctl)); write(new File(dir, "player.json"), p.toString()); }
            o.put("player", playerName); o.put("ip", ip); o.put("ctl", new JSONObject(ctl));
            JSONArray a = new JSONArray(); for (Upnp.Dev d : devs) { JSONObject j = new JSONObject(); j.put("type", d.type); j.put("name", d.name); j.put("model", d.model); j.put("ip", d.ip); a.put(j); }
            o.put("devices", a);
        } catch (Exception ignored) {}
        return o;
    }
    String rUrl(String k) throws IOException { String u = ctl.get(k); if (u == null) throw new IOException("还没找到播放器：确认播放器开着 DLNA（A&K 叫 AK Connect）、亮着屏，和手机在同一个 Wi-Fi"); return u; }
    String av(String action, String[]... a) throws IOException { return Upnp.soap(rUrl("avt"), Upnp.AV, action, plus(new String[][]{{"InstanceID", "0"}}, a), 8000); }
    String rc(String action, String[]... a) throws IOException { return Upnp.soap(rUrl("rc"), Upnp.RC, action, plus(new String[][]{{"InstanceID", "0"}, {"Channel", "Master"}}, a), 8000); }
    static String[][] plus(String[][] a, String[][] b) { String[][] o = new String[a.length + b.length][]; System.arraycopy(a, 0, o, 0, a.length); System.arraycopy(b, 0, o, a.length, b.length); return o; }

    /* ---------- 曲库 ---------- */
    static final Pattern ITEM = Pattern.compile("<(container|item)\\s([^>]*)>([\\s\\S]*?)</\\1>");
    static final Pattern RES = Pattern.compile("<res\\s([^>]*)>([^<]*)</res>");
    public JSONObject browse(String id, int start, int count) throws Exception {
        String u = ctl.get("cd"); if (u == null) throw new IOException("还没找到播放器的曲库服务（DLNA 媒体服务器）");
        String x = Upnp.soap(u, Upnp.CD, "Browse", new String[][]{{"ObjectID", id}, {"BrowseFlag", "BrowseDirectChildren"}, {"Filter", "*"}, {"StartingIndex", "" + start}, {"RequestedCount", "" + count}, {"SortCriteria", ""}}, 15000);
        String didl = Util.tag(x, "Result");
        JSONArray items = new JSONArray(); Matcher m = ITEM.matcher(didl);
        while (m.find()) {
            String at = m.group(2), b = m.group(3); JSONObject o = new JSONObject();
            if (m.group(1).equals("container")) { o.put("kind", "dir"); o.put("id", Util.attr(at, "id")); o.put("title", Util.tag(b, "title")); o.put("count", parseInt(Util.attr(at, "childCount"))); items.put(o); continue; }
            Matcher r = RES.matcher(b); String ra = "", url = ""; if (r.find()) { ra = r.group(1); url = Util.unesc(r.group(2)); }
            String artist = Util.tag(b, "artist"); if (artist.isEmpty()) artist = Util.tag(b, "creator");
            o.put("kind", "song"); o.put("id", Util.attr(at, "id")); o.put("title", Util.tag(b, "title")); o.put("artist", artist); o.put("album", Util.tag(b, "album"));
            o.put("art", Util.tag(b, "albumArtURI")); o.put("url", url); o.put("proto", Util.attr(ra, "protocolInfo")); o.put("dur", Util.hms(Util.attr(ra, "duration")));
            o.put("sr", parseInt(Util.attr(ra, "sampleFrequency"))); o.put("bits", parseInt(Util.attr(ra, "bitsPerSample"))); o.put("size", parseLong(Util.attr(ra, "size"))); o.put("didl", m.group(0));
            items.put(o);
        }
        JSONObject o = new JSONObject(); o.put("id", id); o.put("start", start); o.put("total", parseInt(Util.tag(x, "TotalMatches"))); o.put("items", items);
        return o;
    }

    /* ---------- 播放 ---------- */
    static String meta(JSONObject s) { return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" + s.optString("didl") + "</DIDL-Lite>"; }
    boolean isDsd(JSONObject x) { return (x.optString("proto") + x.optString("url")).toLowerCase().matches("(?s).*(dsf|dff|dsd).*") && !cfgB("allowDsd"); }
    boolean playable(int i) { return i >= 0 && i < queue.size() && !isDsd(queue.get(i)); }
    int headCode(String url) {
        try { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(6000); c.setReadTimeout(6000); c.setRequestProperty("Range", "bytes=0-1023"); int code = c.getResponseCode(); c.disconnect(); return code; }
        catch (Exception e) { return 0; }
    }
    public JSONObject startSong(int i) throws Exception {
        if (i < 0 || i >= queue.size()) throw new IOException("队列里没有这首");
        JSONObject s = queue.get(i);
        if (isDsd(s)) throw new IOException("DSD（dsf/dff）默认不推送（A&K CA1000T 实测会被卡死）；在播放器上直接放，或在设置里打开");
        int code = headCode(s.optString("url"));
        if (code == 204 || code == 404) throw new IOException("这首歌的文件已经不在原来的位置（整理曲库挪过）。请在播放器上更新一次曲库（媒体扫描）再试");
        pos = i; cur = s; lastStart = System.currentTimeMillis(); wantPlay = true; saveQueue();
        rel = 0; dur = s.optDouble("dur", 0); uri = s.optString("url");
        av("SetAVTransportURI", new String[]{"CurrentURI", s.optString("url")}, new String[]{"CurrentURIMetaData", meta(s)});
        av("Play", new String[]{"Speed", "1"});
        synchronized (hist) { if (hist.isEmpty() || hist.get(hist.size() - 1) != i) { hist.add(i); if (hist.size() > 200) hist.remove(0); } }
        final JSONObject song = s;
        bg.execute(new Runnable() { public void run() { try { lyrics(song); } catch (Exception ignored) {} try { armNext(); } catch (Exception ignored) {} } });
        changed();
        return view();
    }
    public JSONObject play(JSONArray items, int index) throws Exception {
        List<JSONObject> songs = new ArrayList<JSONObject>();
        for (int k = 0; k < items.length(); k++) { JSONObject x = items.optJSONObject(k); if (x != null && "song".equals(x.optString("kind")) && !x.optString("url").isEmpty()) songs.add(x); }
        if (songs.isEmpty()) throw new IOException("没有可播放的歌");
        synchronized (queue) { queue.clear(); queue.addAll(songs); }
        synchronized (hist) { hist.clear(); }
        return startSong(Math.min(index, songs.size() - 1));
    }
    int nextIndex(boolean auto) {
        int n = queue.size(); if (n == 0) return -1;
        if (mode.equals("one") && auto) return pos;
        if (mode.equals("shuffle")) {
            if (n == 1) return 0;
            Set<Integer> recent = new HashSet<Integer>();
            synchronized (hist) { int keep = Math.min(n - 1, n / 2 + 1); for (int k = Math.max(0, hist.size() - keep); k < hist.size(); k++) recent.add(hist.get(k)); }
            List<Integer> pool = new ArrayList<Integer>(), all = new ArrayList<Integer>();
            for (int k = 0; k < n; k++) if (k != pos && playable(k)) { all.add(k); if (!recent.contains(k)) pool.add(k); }
            List<Integer> from = pool.isEmpty() ? all : pool; return from.isEmpty() ? -1 : from.get(rnd.nextInt(from.size()));
        }
        for (int k = 1; k <= n; k++) {
            int j = pos + k;
            if (j >= n) { if (mode.equals("loop") || mode.equals("one")) j -= n; else return -1; }
            if (playable(j)) return j;
        }
        return -1;
    }
    void armNext() throws IOException {
        nextIdx = -1;
        if (sleepEnd) { av("SetNextAVTransportURI", new String[]{"NextURI", ""}, new String[]{"NextURIMetaData", ""}); return; }
        int i = nextIndex(true); if (i < 0) return;
        JSONObject x = queue.get(i); int code = headCode(x.optString("url")); if (code == 204 || code == 404) return;
        av("SetNextAVTransportURI", new String[]{"NextURI", x.optString("url")}, new String[]{"NextURIMetaData", meta(x)});
        nextIdx = i;
    }
    public JSONObject step(int d) throws Exception {
        if (queue.isEmpty()) throw new IOException("队列是空的");
        for (int n = 0; n < 30; n++) {
            Integer i;
            if (d > 0) i = nextIndex(false);
            else if (mode.equals("shuffle") && hist.size() > 1) { synchronized (hist) { hist.remove(hist.size() - 1); i = hist.remove(hist.size() - 1); } }
            else { i = pos - 1; if (i < 0 && (mode.equals("loop") || mode.equals("one"))) i = queue.size() - 1; }
            if (i == null || i < 0 || i >= queue.size()) break;
            try { return startSong(i); }
            catch (IOException e) { String m = String.valueOf(e.getMessage()); if (!m.contains("不在原来的位置") && !m.contains("DSD")) throw e; pos = i; }
        }
        wantPlay = false; throw new IOException(d > 0 ? "后面没有能放的歌了" : "前面没有能放的歌了");
    }
    public JSONObject status() {
        try {
            String t = av("GetTransportInfo"), p = av("GetPositionInfo");
            state = Util.tag(t, "CurrentTransportState"); if (state.isEmpty()) state = "UNKNOWN";
            rel = Util.hms(Util.tag(p, "RelTime")); double d = Util.hms(Util.tag(p, "TrackDuration")); if (d > 0) dur = d;
            uri = Util.tag(p, "TrackURI"); at = System.currentTimeMillis(); online = true; err = "";
            if (!wantPlay && !resumed && "PLAYING".equals(state) && cur != null && uri.equals(cur.optString("url"))) { resumed = true; wantPlay = true; bg.execute(new Runnable() { public void run() { try { armNext(); } catch (Exception ignored) {} } }); }
            if (cur != null && !uri.isEmpty() && !uri.equals(cur.optString("url")) && nextIdx >= 0 && nextIdx < queue.size() && queue.get(nextIdx).optString("url").equals(uri)) {
                pos = nextIdx; cur = queue.get(pos); lastStart = System.currentTimeMillis(); gapless++; saveQueue();
                bg.execute(new Runnable() { public void run() { try { armNext(); } catch (Exception ignored) {} } });
            }
            if (cur != null && !uri.isEmpty() && !uri.equals(cur.optString("url"))) {
                int k = -1; for (int j = 0; j < queue.size(); j++) if (queue.get(j).optString("url").equals(uri)) { k = j; break; }
                if (k >= 0) { pos = k; cur = queue.get(k); } else { cur = null; wantPlay = false; }
            }
        } catch (Exception e) { online = false; err = String.valueOf(e.getMessage()); }
        changed();
        return view();
    }
    void watch() throws Exception {
        if (!(System.currentTimeMillis() - seen < 30000 || wantPlay || sleepEnd || sleepUntil > 0 || listener != null)) return;
        String prev = state;
        status();
        if (wantPlay && cur != null && "PLAYING".equals(state) && dur > 0 && dur - rel < 6 && !endLoop) {
            bg.execute(new Runnable() { public void run() { fastEnd(); } });
        }
        if (sleepEnd && sleepUrl != null && (!uri.equals(sleepUrl) || "STOPPED".equals(state))) {
            if ("PLAYING".equals(state)) try { av("Pause"); } catch (Exception ignored) {}
            sleepEnd = false; wantPlay = false; return;
        }
        if (wantPlay && cur != null && !endLoop && System.currentTimeMillis() - lastStart > 8000 && "PLAYING".equals(prev) && ("STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state))) {
            int i = nextIndex(true);
            if (i >= 0) { try { startSong(i); } catch (Exception e) { try { step(1); } catch (Exception e2) { wantPlay = false; } } }
            else wantPlay = false;
        }
    }
    /** 快到结尾：每 0.4 秒问一次，一停就推下一首（A&K 收下“下一首”但不会自己接） */
    void fastEnd() {
        if (endLoop) return; endLoop = true;
        try {
            String url = cur == null ? null : cur.optString("url"); long t0 = System.currentTimeMillis();
            while (wantPlay && cur != null && cur.optString("url").equals(url) && System.currentTimeMillis() - t0 < 15000) {
                String st; try { st = Util.tag(av("GetTransportInfo"), "CurrentTransportState"); } catch (Exception e) { st = ""; }
                if (st.equals("STOPPED") || st.equals("NO_MEDIA_PRESENT")) {
                    if (sleepEnd) { sleepEnd = false; wantPlay = false; break; }
                    int i = nextIndex(true);
                    if (i >= 0) { state = "TRANSITIONING"; try { startSong(i); } catch (Exception e) { try { step(1); } catch (Exception e2) { wantPlay = false; } } }
                    else wantPlay = false;
                    break;
                }
                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
            }
        } finally { endLoop = false; }
    }
    public JSONObject view() {
        JSONObject o = new JSONObject();
        try {
            double live = "PLAYING".equals(state) ? (System.currentTimeMillis() - at) / 1000.0 : 0;
            o.put("online", online); o.put("err", err); o.put("player", playerName == null ? "" : playerName); o.put("state", state);
            o.put("rel", Math.min(dur > 0 ? dur : 1e9, rel + live)); o.put("dur", dur); o.put("ip", ip == null ? "" : ip);
            if (cur != null) {
                JSONObject c = new JSONObject(); c.put("title", cur.optString("title")); c.put("artist", cur.optString("artist")); c.put("album", cur.optString("album"));
                String art = cur.optString("art"); c.put("art", art.isEmpty() ? "" : "/api/music/art?u=" + URLEncoder.encode(art, "UTF-8"));
                c.put("sr", cur.optInt("sr")); c.put("bits", cur.optInt("bits")); c.put("url", cur.optString("url")); o.put("cur", c);
            } else o.put("cur", JSONObject.NULL);
            o.put("pos", pos); o.put("queueLen", queue.size()); o.put("vol", vol == null ? JSONObject.NULL : vol); o.put("mode", mode);
            o.put("loud", loud == null ? JSONObject.NULL : loud); o.put("gapless", gapless);
            if (sleepEnd) o.put("sleep", new JSONObject().put("endOfTrack", true));
            else if (sleepUntil > 0) o.put("sleep", new JSONObject().put("left", Math.max(0, (sleepUntil - System.currentTimeMillis()) / 1000)).put("min", sleepMin));
            else o.put("sleep", JSONObject.NULL);
        } catch (Exception ignored) {}
        return o;
    }
    public JSONObject setMode(String m) throws Exception {
        if (!"order loop one shuffle".contains(m) || m.isEmpty()) throw new IOException("不认识的播放顺序");
        mode = m; saveQueue(); if (cur != null && wantPlay) bg.execute(new Runnable() { public void run() { try { armNext(); } catch (Exception ignored) {} } });
        changed(); return view();
    }
    public JSONObject setSleep(JSONObject b) {
        sleepEnd = false; sleepUntil = 0; sleepVol0 = null;
        if (b.optBoolean("off")) { changed(); return view(); }
        if (b.optBoolean("endOfTrack")) { sleepEnd = true; sleepUrl = cur == null ? null : cur.optString("url"); }
        else { sleepMin = Math.max(1, Math.min(600, b.optInt("min", 30))); sleepUntil = System.currentTimeMillis() + sleepMin * 60000L; }
        bg.execute(new Runnable() { public void run() { try { armNext(); } catch (Exception ignored) {} } });
        changed(); return view();
    }
    void sleepTick() throws Exception {
        if (sleepUntil <= 0 || sleepBusy) return;
        double left = (sleepUntil - System.currentTimeMillis()) / 1000.0;
        if (left > 20) return;
        sleepBusy = true;
        try {
            if (sleepVol0 == null) sleepVol0 = parseInt(Util.tag(rc("GetVolume"), "CurrentVolume"));
            if (left > 0) rc("SetVolume", new String[]{"DesiredVolume", "" + Math.round(sleepVol0 * Math.max(0, left) / 20)});
            else { try { av("Pause"); } catch (Exception ignored) {} wantPlay = false; Thread.sleep(800); rc("SetVolume", new String[]{"DesiredVolume", "" + sleepVol0}); vol = sleepVol0; sleepUntil = 0; sleepVol0 = null; changed(); }
        } finally { sleepBusy = false; }
    }
    public JSONObject loudness(Boolean on) throws Exception {
        if (on != null) rc("SetLoudness", new String[]{"DesiredLoudness", on ? "1" : "0"});
        loud = "1".equals(Util.tag(rc("GetLoudness"), "CurrentLoudness"));
        return new JSONObject().put("loud", loud);
    }
    public JSONObject volume(Integer v) throws Exception {
        if (v != null) rc("SetVolume", new String[]{"DesiredVolume", "" + Math.max(0, Math.min(100, v))});
        vol = parseInt(Util.tag(rc("GetVolume"), "CurrentVolume")); changed();
        return new JSONObject().put("vol", vol);
    }

    /* ---------- 队列 ---------- */
    void loadQueue() {
        try { JSONObject q = new JSONObject(read(new File(dir, "queue.json"))); JSONArray a = q.getJSONArray("queue"); for (int i = 0; i < a.length(); i++) queue.add(a.getJSONObject(i)); pos = q.optInt("pos", -1); mode = q.optString("mode", "order"); if (pos >= 0 && pos < queue.size()) cur = queue.get(pos); } catch (Exception ignored) {}
    }
    void saveQueue() {
        try { JSONObject q = new JSONObject(); JSONArray a = new JSONArray(); synchronized (queue) { for (JSONObject x : queue) a.put(x); } q.put("queue", a); q.put("pos", pos); q.put("mode", mode); write(new File(dir, "queue.json"), q.toString()); } catch (Exception ignored) {}
    }
    JSONObject qAdd(JSONArray items, String m) throws Exception {
        List<JSONObject> songs = new ArrayList<JSONObject>();
        for (int k = 0; k < items.length(); k++) { JSONObject x = items.optJSONObject(k); if (x != null && "song".equals(x.optString("kind")) && !x.optString("url").isEmpty()) songs.add(x); }
        if (songs.isEmpty()) throw new IOException("没有可加的歌");
        synchronized (queue) { if ("next".equals(m) && pos >= 0) queue.addAll(pos + 1, songs); else queue.addAll(songs); }
        saveQueue(); rearm();
        return new JSONObject().put("added", songs.size()).put("queueLen", queue.size());
    }
    void rearm() { if (wantPlay) bg.execute(new Runnable() { public void run() { try { armNext(); } catch (Exception ignored) {} } }); }
    JSONObject queueView() throws JSONException {
        JSONArray a = new JSONArray();
        for (JSONObject x : new ArrayList<JSONObject>(queue)) a.put(new JSONObject().put("title", x.optString("title")).put("artist", x.optString("artist")).put("dur", x.optDouble("dur", 0)));
        return new JSONObject().put("pos", pos).put("items", a);
    }

    /* ---------- 歌词（LRCLIB，播放时实时查，只缓存在内存；繁转简在页面里做） ---------- */
    static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("(?i)\\.(flac|mp3|wav|m4a|ogg|dsf|dff)$", "").replaceAll("^\\s*\\d{1,3}\\s*[.\\-_、 ]\\s*", "")
            .replaceAll("【[^】]*】|\\[[^\\]]*\\]", " ").replaceAll("(?i)[（(][^）)]*(版|ver|live|remix|伴奏|dj|翻自|cover)[^）)]*[）)]", " ").replace('_', ' ').replaceAll("\\s+", " ").trim();
    }
    static int kana(String t) { int n = 0; for (char c : t.toCharArray()) if (c >= 0x3040 && c <= 0x30ff) n++; return n; }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
    static String norm(String t) { return String.valueOf(t == null ? "" : t).toLowerCase().replaceAll("[\\s'’\"“”·・.,，。!！?？()（）\\[\\]【】\\-_~&/]+", ""); }
    JSONObject lyrQuery(JSONObject s) throws JSONException {
        String title = cleanTitle(s.optString("title")), artist = s.optString("artist").replaceAll("(?i)<unknown>", "").trim();
        if (title.contains(" - ")) { String[] p = title.split(" - ", 2); String a = p[0].trim(), b = p[1].trim(); if (artist.isEmpty() || norm(a).equals(norm(artist))) { if (artist.isEmpty()) artist = a; title = b; } else if (norm(b).equals(norm(artist))) title = a; }
        Matcher m = Pattern.compile("^(.+?)-(.+?)-\\d{5,}$").matcher(title); if (artist.isEmpty() && m.find()) { title = m.group(1).trim(); artist = m.group(2).trim(); }
        return new JSONObject().put("title", title).put("artist", artist).put("dur", s.optDouble("dur", 0));
    }
    String lyrKey(JSONObject s) { return (cleanTitle(s.optString("title")) + "|" + s.optString("artist").replaceAll("(?i)<unknown>", "")).toLowerCase().trim(); }
    final Map<String, JSONObject> lyrTmp = new HashMap<String, JSONObject>();
    JSONObject lchoice() { try { return new JSONObject(read(new File(dir, "lyrics_choice.json"))); } catch (Exception e) { return new JSONObject(); } }
    static Map<String, String> UA() { Map<String, String> h = new HashMap<String, String>(); h.put("User-Agent", "Mozilla/5.0 (starry-music-android; personal music remote)"); h.put("Referer", "https://music.163.com"); return h; }
    JSONObject lyrCands(final String title, final String artist, final double dur) throws Exception {
        final List<JSONObject> out = java.util.Collections.synchronizedList(new ArrayList<JSONObject>()); final StringBuffer errs = new StringBuffer();
        Thread t1 = new Thread(new Runnable() { public void run() { try {
            Set<Long> seen = new HashSet<Long>(); List<String> qs = new ArrayList<String>();
            if (!artist.isEmpty()) qs.add("track_name=" + enc(title) + "&artist_name=" + enc(artist)); qs.add("track_name=" + enc(title)); if (!artist.isEmpty()) qs.add("q=" + enc(artist + " " + title));
            for (String q0 : qs) {
                JSONArray a = new JSONArray(new String(Util.http("GET", "https://lrclib.net/api/search?" + q0, UA(), null, 10000).body, Util.UTF8));
                for (int i = 0; i < a.length(); i++) { JSONObject x = a.getJSONObject(i); if (!seen.add(x.optLong("id"))) continue; synchronized (lyrTmp) { lyrTmp.put("lrclib:" + x.optLong("id"), x); }
                    String sl = x.optString("syncedLyrics", ""), pl = x.optString("plainLyrics", ""); if ("null".equals(sl)) sl = ""; if ("null".equals(pl)) pl = "";
                    out.add(new JSONObject().put("src", "lrclib").put("id", "" + x.optLong("id")).put("title", x.optString("trackName")).put("artist", x.optString("artistName")).put("album", x.optString("albumName")).put("dur", x.optDouble("duration", 0)).put("synced", !sl.isEmpty()).put("instrumental", x.optBoolean("instrumental")).put("has", !sl.isEmpty() || !pl.isEmpty())); }
                if (seen.size() >= 3 && q0.startsWith("track_name=" + enc(title) + "&")) continue;
            }
        } catch (Exception e) { errs.append("LRCLIB：").append(e.getMessage()).append("；"); } } });
        Thread t2 = new Thread(new Runnable() { public void run() { try {
            JSONObject j = new JSONObject(new String(Util.http("POST", "https://music.163.com/api/cloudsearch/pc?s=" + enc((title + " " + artist).trim()) + "&type=1&limit=12", UA(), new byte[0], 10000).body, Util.UTF8));
            JSONArray a = j.optJSONObject("result") == null ? null : j.optJSONObject("result").optJSONArray("songs");
            if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject x = a.getJSONObject(i); StringBuilder ar = new StringBuilder(); JSONArray as = x.optJSONArray("ar"); if (as != null) for (int k = 0; k < as.length(); k++) ar.append(k > 0 ? " / " : "").append(as.getJSONObject(k).optString("name"));
                out.add(new JSONObject().put("src", "163").put("id", "" + x.optLong("id")).put("title", x.optString("name")).put("artist", ar.toString()).put("album", x.optJSONObject("al") == null ? "" : x.optJSONObject("al").optString("name")).put("dur", x.optDouble("dt", 0) / 1000).put("synced", JSONObject.NULL).put("has", JSONObject.NULL)); }
        } catch (Exception e) { errs.append("网易云：").append(e.getMessage()).append("；"); } } });
        t1.start(); t2.start(); t1.join(25000); t2.join(25000);
        String nt = norm(title), na = norm(artist);
        List<JSONObject> l = new ArrayList<JSONObject>(out);
        for (JSONObject c : l) {
            double sc = dur > 0 ? Math.min(60, Math.abs(c.optDouble("dur") - dur)) : 0;
            String ct = norm(c.optString("title")), ca = norm(c.optString("artist"));
            c.put("tok", ct.equals(nt)); if (!ct.equals(nt)) sc += ct.contains(nt) || nt.contains(ct) ? 4 : 20;
            if (!na.isEmpty() && !(ca.contains(na) || na.contains(ca))) sc += 8;
            if (Boolean.FALSE.equals(c.opt("synced"))) sc += 5; if (Boolean.FALSE.equals(c.opt("has")) && !c.optBoolean("instrumental")) sc += 40;
            if (kana(title) == 0 && title.matches("(?s).*[\\u4e00-\\u9fff].*") && kana(c.optString("title")) > 0) sc += 30;
            c.put("score", Math.round(sc * 10) / 10.0);
        }
        java.util.Collections.sort(l, new java.util.Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) { return Double.compare(a.optDouble("score"), b.optDouble("score")); } });
        JSONArray a = new JSONArray(); for (JSONObject c : l) a.put(c);
        return new JSONObject().put("items", a).put("err", errs.toString());
    }
    JSONObject lyrFetch(String src, String id) throws Exception {
        if (src.equals("lrclib")) {
            JSONObject x; synchronized (lyrTmp) { x = lyrTmp.get("lrclib:" + id); }
            if (x == null) x = new JSONObject(new String(Util.http("GET", "https://lrclib.net/api/get/" + enc(id), UA(), null, 10000).body, Util.UTF8));
            String sl = x.optString("syncedLyrics", ""), pl = x.optString("plainLyrics", ""); if ("null".equals(sl)) sl = ""; if ("null".equals(pl)) pl = "";
            return new JSONObject().put("text", sl.isEmpty() ? pl : sl).put("synced", !sl.isEmpty()).put("instrumental", x.optBoolean("instrumental")).put("trans", "").put("src", "LRCLIB #" + x.optLong("id"));
        }
        if (src.equals("163")) {
            JSONObject j = new JSONObject(new String(Util.http("GET", "https://music.163.com/api/song/lyric?id=" + enc(id) + "&lv=1&tv=-1", UA(), null, 10000).body, Util.UTF8));
            String t = j.optJSONObject("lrc") == null ? "" : j.optJSONObject("lrc").optString("lyric", "").trim(), tr = j.optJSONObject("tlyric") == null ? "" : j.optJSONObject("tlyric").optString("lyric", "").trim();
            boolean body = false; for (String l : t.split("\r?\n")) if (l.matches(".*\\]\\s*\\S.*") && !l.matches(".*\\]\\s*(作词|作曲|编曲|制作人|词|曲)\\s*[:：].*")) { body = true; break; }
            return new JSONObject().put("text", t).put("synced", t.matches("(?s).*\\[\\d+:\\d+.*")).put("instrumental", j.optBoolean("pureMusic") || (!body && j.optBoolean("nolyric"))).put("trans", tr).put("src", "网易云 #" + id);
        }
        throw new IOException("不认识的歌词来源");
    }
    public JSONObject lyrics(JSONObject s) throws Exception {
        if (s == null) return new JSONObject().put("found", false);
        if (!prefs.getBoolean("lyrics", true)) return new JSONObject().put("found", false).put("off", true);
        String key = s.optString("url");
        synchronized (lyr) { if (lyr.containsKey(key)) return lyr.get(key); }
        JSONObject q = lyrQuery(s), ch = lchoice().optJSONObject(lyrKey(s)), out = null; String er = "";
        double dur = q.optDouble("dur", 0);
        try {
            if (ch != null && ch.optBoolean("none")) out = new JSONObject().put("found", false).put("hidden", true);
            else if (ch != null && ch.has("src")) out = lyrFetch(ch.getString("src"), ch.getString("id")).put("found", true).put("chosen", true);
            else {
                JSONObject r = lyrCands(q.getString("title"), q.getString("artist"), dur); er = r.optString("err"); JSONArray it = r.getJSONArray("items");
                List<JSONObject> try1 = new ArrayList<JSONObject>(), try2 = new ArrayList<JSONObject>();
                for (int i = 0; i < it.length(); i++) { JSONObject c = it.getJSONObject(i); double d = dur > 0 ? Math.abs(c.optDouble("dur") - dur) : 0;
                    if (d <= 4 && c.optDouble("score") <= 30 && try1.size() < 4) try1.add(c); else if (c.optBoolean("tok") && d > 4 && d <= 30 && try2.size() < 3) try2.add(c); }
                try1.addAll(try2);
                for (JSONObject c : try1) {
                    JSONObject f; try { f = lyrFetch(c.getString("src"), c.getString("id")); } catch (Exception e) { continue; }
                    if (!f.optString("text").isEmpty() || f.optBoolean("instrumental")) { out = f.put("found", true); long dd = dur > 0 ? Math.round(c.optDouble("dur") - dur) : 0; if (Math.abs(dd) > 4) out.put("approx", dd); break; }
                }
                if (out == null) out = new JSONObject().put("found", false).put("err", er);
            }
        } catch (Exception e) { er = String.valueOf(e.getMessage()); out = new JSONObject().put("found", false).put("err", er); }
        out.put("q", q).put("needT2S", true);
        if (er.isEmpty() || out.optBoolean("found")) synchronized (lyr) { if (lyr.size() > 300) lyr.remove(lyr.keySet().iterator().next()); lyr.put(key, out); }
        return out;
    }
    JSONObject lyrUse(JSONObject s, JSONObject b) throws Exception {
        if (s == null) throw new IOException("现在没有在放的歌");
        JSONObject c = lchoice(); String k = lyrKey(s);
        if (b.optBoolean("auto")) c.remove(k);
        else if (b.optBoolean("none")) c.put(k, new JSONObject().put("none", true));
        else { if (b.optString("src").isEmpty()) throw new IOException("缺来源"); c.put(k, new JSONObject().put("src", b.optString("src")).put("id", b.optString("id"))); }
        write(new File(dir, "lyrics_choice.json"), c.toString());
        synchronized (lyr) { lyr.remove(s.optString("url")); }
        return lyrics(s);
    }

    /* ---------- 设置、单词、统计（统计由电脑上的群星记；填了群星地址就转过去） ---------- */
    JSONObject settings(JSONObject b) throws JSONException {
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new String[]{"wordTap", "showTrans", "autoLists"}) if (b.has(k)) e.putBoolean("set_" + k, b.optBoolean(k));
        e.apply();
        JSONObject s = new JSONObject(); for (String k : new String[]{"wordTap", "showTrans", "autoLists"}) s.put(k, prefs.getBoolean("set_" + k, true));
        return new JSONObject().put("settings", s).put("vocab", !cfg("hubUrl", "").isEmpty());
    }
    JSONObject hub(String method, String p, JSONObject body) throws Exception {
        String u = cfg("hubUrl", ""); if (u.isEmpty()) return null;
        Map<String, String> h = new HashMap<String, String>(); h.put("X-Starry-Token", cfg("hubToken", "")); h.put("X-Starry", "1"); h.put("Content-Type", "application/json");
        Util.Resp r = Util.http(method, u.replaceAll("/+$", "") + p, h, body == null ? null : body.toString().getBytes(Util.UTF8), p.contains("/meta/") ? 150000 : 8000);
        JSONObject j = new JSONObject(new String(r.body, Util.UTF8)); if (!j.optBoolean("ok", true)) throw new IOException(j.optString("error")); return j;
    }
    JSONObject wordLookup(String w) throws Exception {
        w = String.valueOf(w == null ? "" : w).trim().replaceAll("[^A-Za-z'\\-]", ""); if (w.isEmpty()) throw new IOException("没有单词");
        try { JSONObject r = hub("GET", "/api/vocab/lookup?w=" + enc(w), null); if (r != null && !r.optString("zh").isEmpty()) return r.put("word", w).put("from", "群星词典"); } catch (Exception ignored) {}
        JSONObject j = new JSONObject(new String(Util.http("GET", "https://dict.youdao.com/jsonapi_s?doctype=json&jsonversion=4&q=" + enc(w), UA(), null, 6000).body, Util.UTF8));
        JSONObject W = j.optJSONObject("ec") == null ? new JSONObject() : j.optJSONObject("ec").optJSONObject("word"); if (W == null) W = new JSONObject();
        StringBuilder zh = new StringBuilder(); JSONArray trs = W.optJSONArray("trs");
        if (trs != null) for (int i = 0; i < trs.length(); i++) { JSONObject t = trs.getJSONObject(i); if (zh.length() > 0) zh.append('\n'); zh.append((t.optString("pos") + " " + t.optString("tran")).trim()); }
        if (zh.length() == 0 && j.optJSONArray("web_trans") != null) { JSONArray a = j.optJSONArray("web_trans"); for (int i = 0; i < a.length(); i++) zh.append(i > 0 ? "；" : "").append(a.optString(i)); }
        String ph = W.optString("ukphone", W.optString("usphone", "")), pro = W.optString("prototype", "");
        JSONObject o = new JSONObject().put("word", w).put("ph", ph).put("zh", zh.toString()).put("from", zh.length() > 0 ? "有道" : "");
        if (!pro.isEmpty() && !pro.equalsIgnoreCase(w)) o.put("lemma", pro);
        return o;
    }
    JSONObject wordAdd(JSONObject b) throws Exception {
        String w = b.optString("word").trim(); if (!w.matches("[A-Za-z][A-Za-z'\\-]*")) throw new IOException("只能加英文单词");
        JSONObject o = new JSONObject().put("word", w).put("context", b.optString("context")).put("zh", b.optString("zh")).put("src", "群星音乐" + (cur != null ? " · " + cur.optString("title") : ""));
        JSONObject r = null; try { r = hub("POST", "/api/vocab/add", o); } catch (Exception e) { if (!cfg("hubUrl", "").isEmpty()) throw new IOException("群星连不上：" + e.getMessage()); }
        if (r != null) return r.put("to", "群星单词复习");
        File f = new File(dir, "words.json"); JSONArray a; try { a = new JSONArray(read(f)); } catch (Exception e) { a = new JSONArray(); }
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optString("word").equalsIgnoreCase(w)) return new JSONObject().put("added", false).put("to", "App 里的生词本");
        a.put(o.put("at", System.currentTimeMillis())); write(f, a.toString());
        return new JSONObject().put("added", true).put("to", "App 里的生词本（设置里填了群星地址后会进单词复习）");
    }

    /* ---------- 文件夹视图建路径索引（歌名 = 文件名，内置存储和存储卡都有） ---------- */
    volatile Map<String, JSONObject> pidx; volatile Map<String, String> pidxUrl; volatile long pidxTs;
    volatile boolean pidxBuilding;
    void pathIndex(boolean force) throws Exception {
        if (!force && pidx == null) { try { mkPidx(new JSONObject(read(new File(dir, "pathindex.json")))); } catch (Exception ignored) {} }
        if (!force && pidx != null) {
            if (System.currentTimeMillis() - pidxTs > 30 * 60000L && !pidxBuilding) bg.execute(new Runnable() { public void run() { try { pathIndex(true); } catch (Exception ignored) {} } });
            return;
        }
        buildPidx();
    }
    synchronized void buildPidx() throws Exception {
        pidxBuilding = true;
        try { buildPidx0(); } finally { pidxBuilding = false; }
    }
    void buildPidx0() throws Exception {
        JSONArray roots = browse("6", 0, 50).getJSONArray("items");
        JSONArray entries = new JSONArray();
        for (int i = 0; i < roots.length(); i++) {
            JSONObject r = roots.getJSONObject(i); if (!"dir".equals(r.optString("kind"))) continue;
            walk(r.getString("id"), r.optString("title").toLowerCase().contains("internal") ? pre() : sdPath(), 0, entries);
        }
        JSONObject j = new JSONObject().put("ts", System.currentTimeMillis()).put("entries", entries);
        write(new File(dir, "pathindex.json"), j.toString()); mkPidx(j);
    }
    void walk(String id, String p, int lv, JSONArray out) throws Exception {
        for (int st = 0, total = 1; st < total; st += 200) {
            JSONObject j = browse(id, st, 200); total = j.getInt("total"); JSONArray it = j.getJSONArray("items"); if (it.length() == 0) break;
            for (int k = 0; k < it.length(); k++) {
                JSONObject x = it.getJSONObject(k);
                if ("dir".equals(x.optString("kind"))) { if (lv < 12 && !x.optString("title").matches("Android|DCIM|Pictures|Movies|System Volume Information|AlbumArt")) walk(x.getString("id"), p + "/" + x.optString("title"), lv + 1, out); }
                else { x.put("_path", p + "/" + x.optString("title")); out.put(x); }
            }
        }
    }
    void mkPidx(JSONObject j) throws JSONException {
        Map<String, JSONObject> bp = new HashMap<String, JSONObject>(); Map<String, String> bu = new HashMap<String, String>();
        JSONArray a = j.getJSONArray("entries");
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.getJSONObject(i); String p = x.optString("_path"); bp.put(p.toLowerCase(), x); bu.put(x.optString("url"), p); }
        pidx = bp; pidxUrl = bu; pidxTs = j.getLong("ts");
    }
    volatile Map<Long, String> fidx;   // FTP 文件大小 → 路径（存储卡上的歌 FTP 看不到）

    /* ---------- File Drop（FTP） ---------- */
    public interface FtpWork<T> { T run(Ftp c) throws Exception; }
    public <T> T withFtp(FtpWork<T> w) throws Exception {
        ftpSem.acquire(); Ftp c = null;
        try {
            String host = ip; if (host == null) throw new IOException("还没找到播放器");
            int port = parseInt(cfg("ftpPort", "")); if (port <= 0) port = 1212;
            String u = cfg("ftpUser", ""), p = cfg("ftpPass", "");
            c = Ftp.open(host, port, u.isEmpty() ? "admin" : u, p.isEmpty() ? "admin" : p);
            return w.run(c);
        } finally { if (c != null) c.close(); ftpSem.release(); }
    }
    static String cleanPath(String p) {
        StringBuilder sb = new StringBuilder();
        for (String x : String.valueOf(p == null ? "/" : p).replace('\\', '/').split("/")) { if (x.isEmpty() || x.equals(".") || x.equals("..")) continue; sb.append('/').append(x); }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /* ---------- 歌单（播放器 /Playlists/*.m3u ⇄ App 里的镜像，两边都能改） ---------- */
    File pdir() { return new File(dir, "playlists"); }
    JSONObject pmeta() { try { return new JSONObject(read(new File(pdir(), "_meta.json"))); } catch (Exception e) { return new JSONObject(); } }
    void savePmeta(JSONObject m) { write(new File(pdir(), "_meta.json"), m.toString()); }
    static String safeName(String n) throws IOException { n = String.valueOf(n == null ? "" : n).trim().replaceAll("[\\\\/:*?\"<>|]", "_"); if (n.isEmpty()) throw new IOException("歌单名字不能空"); return n; }
    static List<String> parseM3u(byte[] b) {
        String t = new String(b, Util.UTF8); if (t.startsWith("﻿")) t = t.substring(1);
        List<String> o = new ArrayList<String>(); for (String l : t.split("\r?\n")) { l = l.trim(); if (!l.isEmpty() && !l.startsWith("#")) o.add(l); } return o;
    }
    void writeMirror(String n, List<String> lines, boolean dirty) throws JSONException {
        File f = new File(pdir(), n + ".m3u");
        if (f.exists()) f.renameTo(new File(pdir(), "_history/" + n + "." + System.currentTimeMillis() + ".m3u"));
        StringBuilder sb = new StringBuilder(); for (String l : lines) sb.append(l).append("\r\n");
        write(f, sb.toString());
        JSONObject m = pmeta(); JSONObject e = m.optJSONObject(n); if (e == null) e = new JSONObject(); e.put("dirty", dirty).put("at", System.currentTimeMillis()); m.put(n, e); savePmeta(m);
    }
    JSONObject plList() throws Exception {
        List<String> ca = null; String er = "";
        try { ca = withFtp(new FtpWork<List<String>>() { public List<String> run(Ftp c) throws Exception { List<String> o = new ArrayList<String>(); for (Ftp.Entry e : c.list("/Playlists")) if (!e.dir && e.name.toLowerCase().matches(".*\\.m3u8?$")) o.add(e.name.replaceAll("(?i)\\.m3u8?$", "")); return o; } }); }
        catch (Exception e) { er = String.valueOf(e.getMessage()); }
        JSONObject m = pmeta(); Set<String> names = new java.util.TreeSet<String>();
        if (ca != null) names.addAll(ca);
        File[] fs = pdir().listFiles(); if (fs != null) for (File f : fs) if (f.getName().endsWith(".m3u")) names.add(f.getName().substring(0, f.getName().length() - 4));
        JSONArray a = new JSONArray();
        for (String n : names) {
            JSONObject e = m.optJSONObject(n);
            if (e != null && e.optBoolean("deleted") && (ca == null || !ca.contains(n))) continue;
            a.put(new JSONObject().put("name", n).put("onCa", ca == null ? JSONObject.NULL : (Object) ca.contains(n)).put("dirty", e != null && e.optBoolean("dirty")));
        }
        return new JSONObject().put("online", ca != null).put("err", er).put("items", a);
    }
    JSONObject plGet(String name) throws Exception { return plGet(name, false); }
    JSONObject plGet(String name, boolean wait) throws Exception {
        final String n = safeName(name); List<String> lines = null; String er = "", from = "ca";
        File mf0 = new File(pdir(), n + ".m3u"); JSONObject em = pmeta().optJSONObject(n);
        if (!wait && mf0.exists() && (em == null || !em.optBoolean("dirty"))) {
            List<String> l = parseM3u(readBytes(mf0)); int len = 0; for (String x : l) len += x.length() + 1;
            return resolve(l).put("name", n).put("from", "mirror").put("fast", true).put("err", "").put("dirty", false).put("sig", Math.max(0, len - 1) + ":" + l.size());
        }
        JSONObject e0 = pmeta().optJSONObject(n);
        if (e0 != null && e0.optBoolean("dirty")) try { plPush(n); } catch (Exception e) { er = String.valueOf(e.getMessage()); }
        e0 = pmeta().optJSONObject(n);
        if (e0 == null || !e0.optBoolean("dirty")) {
            try {
                byte[] b = withFtp(new FtpWork<byte[]>() { public byte[] run(Ftp c) throws Exception { return c.getBytes("/Playlists/" + n + ".m3u"); } });
                lines = parseM3u(b);
                File mf = new File(pdir(), n + ".m3u");
                List<String> old = mf.exists() ? parseM3u(readBytes(mf)) : null;
                if (old == null || !old.equals(lines)) writeMirror(n, lines, false);
            } catch (Exception e) { String m = String.valueOf(e.getMessage()); if (er.isEmpty()) er = m.matches("(?s).*\\b550\\b.*") ? "没有这个歌单" : m; }
        }
        if (lines == null) { File mf = new File(pdir(), n + ".m3u"); if (!mf.exists()) throw new IOException(er.isEmpty() ? "没有这个歌单" : er); lines = parseM3u(readBytes(mf)); from = "mirror"; }
        JSONObject o = resolve(lines); JSONObject e1 = pmeta().optJSONObject(n);
        int len = 0; for (String x : lines) len += x.length() + 1;
        o.put("name", n).put("from", from).put("err", er).put("dirty", e1 != null && e1.optBoolean("dirty")).put("sig", Math.max(0, len - 1) + ":" + lines.size());
        return o;
    }
    JSONObject resolve(List<String> lines) throws Exception {
        try { pathIndex(false); } catch (Exception ignored) {}
        JSONArray entries = new JSONArray(); int ok = 0;
        for (String p : lines) {
            JSONObject it = pidx == null ? null : pidx.get(p.toLowerCase());
            String file = p.substring(p.lastIndexOf('/') + 1);
            JSONObject e = new JSONObject().put("path", p).put("file", file).put("sd", !p.startsWith(pre() + "/")).put("item", it == null ? JSONObject.NULL : it);
            if (it != null) ok++;
            entries.put(e);
        }
        return new JSONObject().put("entries", entries).put("playable", ok);
    }
    void plPush(final String name) throws Exception {
        List<String> lines = parseM3u(readBytes(new File(pdir(), name + ".m3u")));
        StringBuilder sb = new StringBuilder("﻿"); for (String l : lines) sb.append(l).append("\r\n");
        final byte[] buf = sb.toString().getBytes(Util.UTF8);
        withFtp(new FtpWork<Void>() { public Void run(Ftp c) throws Exception { c.mkd("/Playlists"); c.putBytes("/Playlists/" + name + ".m3u", buf); return null; } });
        JSONObject m = pmeta(); JSONObject e = m.optJSONObject(name); if (e == null) e = new JSONObject();
        e.put("dirty", false).put("pushed", System.currentTimeMillis()).put("deleted", false); m.put(name, e); savePmeta(m);
    }
    void plSyncDirty() { JSONObject m = pmeta(); for (String n : keys(m)) { JSONObject e = m.optJSONObject(n); if (e != null && e.optBoolean("dirty") && !e.optBoolean("deleted") && new File(pdir(), n + ".m3u").exists()) try { plPush(n); } catch (Exception ignored) {} } }
    JSONObject plSave(String name, JSONArray paths) throws Exception {
        String n = safeName(name); List<String> l = new ArrayList<String>();
        for (int i = 0; i < paths.length(); i++) { String p = paths.optString(i, ""); if (!p.isEmpty()) l.add(p); }
        writeMirror(n, l, true);
        boolean pushed = true; String er = "";
        try { plPush(n); } catch (Exception e) { pushed = false; er = "播放器暂时连不上，已先存在手机里，连上后自动同步：" + e.getMessage(); }
        return new JSONObject().put("name", n).put("pushed", pushed).put("err", er);
    }
    JSONObject plAdd(String name, JSONArray items) throws Exception {
        String n = safeName(name); List<String> cur0 = new ArrayList<String>();
        try { JSONArray e = plGet(n).getJSONArray("entries"); for (int i = 0; i < e.length(); i++) cur0.add(e.getJSONObject(i).getString("path")); }
        catch (Exception e) { if (!String.valueOf(e.getMessage()).contains("没有这个歌单")) throw e; }
        try { pathIndex(false); } catch (Exception ignored) {}
        List<String> add = new ArrayList<String>(); JSONArray miss = new JSONArray(); int dup = 0; boolean rebuilt = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject x = items.getJSONObject(i); String p = pidxUrl == null ? null : pidxUrl.get(x.optString("url"));
            if (p == null && !rebuilt) { rebuilt = true; try { pathIndex(true); } catch (Exception ignored) {} p = pidxUrl == null ? null : pidxUrl.get(x.optString("url")); }
            if (p == null) { miss.put(x.optString("title")); continue; }
            if (cur0.contains(p) || add.contains(p)) dup++; else add.add(p);
        }
        JSONArray all = new JSONArray(); for (String p : cur0) all.put(p); for (String p : add) all.put(p);
        return plSave(n, all).put("added", add.size()).put("dup", dup).put("miss", miss);
    }
    JSONObject plDelete(String name) throws Exception {
        final String n = safeName(name);
        File f = new File(pdir(), n + ".m3u"); if (f.exists()) f.renameTo(new File(pdir(), "_history/" + n + ".deleted." + System.currentTimeMillis() + ".m3u"));
        JSONObject m = pmeta(); m.put(n, new JSONObject().put("deleted", true).put("at", System.currentTimeMillis())); savePmeta(m);
        String er = ""; try { withFtp(new FtpWork<Void>() { public Void run(Ftp c) throws Exception { c.dele("/Playlists/" + n + ".m3u"); return null; } }); } catch (Exception e) { er = String.valueOf(e.getMessage()); }
        return new JSONObject().put("ok", 1).put("err", er);
    }
    JSONObject plRename(String a, String b) throws Exception {
        JSONArray e = plGet(a).getJSONArray("entries"); JSONArray paths = new JSONArray();
        for (int i = 0; i < e.length(); i++) paths.put(e.getJSONObject(i).getString("path"));
        JSONObject r = plSave(b, paths); plDelete(a); return r;
    }

    /* ---------- 传输任务 ---------- */
    public static class Job {
        public String id, kind, name, state = "run", err = "", path; public long total, done, t0 = System.currentTimeMillis(); public double speed;
        final List<long[]> win = new ArrayList<long[]>();
        synchronized void tickBytes(int n) { done += n; long t = System.currentTimeMillis(); win.add(new long[]{t, done}); while (win.size() > 2 && t - win.get(0)[0] > 2000) win.remove(0); long[] a = win.get(0); speed = t > a[0] ? (done - a[1]) / ((t - a[0]) / 1000.0) : 0; }
        synchronized JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("kind", kind).put("name", name).put("total", total).put("done", done).put("state", state).put("err", err)
                .put("speed", Math.round(speed)).put("avg", Math.round(done / Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0))).put("ms", System.currentTimeMillis() - t0).put("path", path == null ? "" : path);
        }
    }
    public Job job(String id, String kind, String name, long total) {
        Job j = new Job(); j.id = id == null || id.isEmpty() ? "j" + System.currentTimeMillis() : id; j.kind = kind; j.name = name; j.total = total;
        synchronized (jobs) { jobs.put(j.id, j); if (jobs.size() > 40) jobs.remove(jobs.keySet().iterator().next()); }
        return j;
    }
    JSONObject jobsView() throws JSONException { JSONArray a = new JSONArray(); List<Job> l; synchronized (jobs) { l = new ArrayList<Job>(jobs.values()); } for (int i = l.size() - 1; i >= 0; i--) a.put(l.get(i).json()); return new JSONObject().put("items", a); }
    public JSONObject ftpLs(final String d) throws Exception {
        final String dd = cleanPath(d);
        List<Ftp.Entry> l = withFtp(new FtpWork<List<Ftp.Entry>>() { public List<Ftp.Entry> run(Ftp c) throws Exception { return c.list(dd); } });
        java.util.Collections.sort(l, new java.util.Comparator<Ftp.Entry>() { public int compare(Ftp.Entry a, Ftp.Entry b) { if (a.dir != b.dir) return a.dir ? -1 : 1; return java.text.Collator.getInstance(java.util.Locale.CHINA).compare(a.name, b.name); } });
        JSONArray a = new JSONArray(); for (Ftp.Entry e : l) a.put(new JSONObject().put("name", e.name).put("dir", e.dir).put("size", e.size));
        return new JSONObject().put("dir", dd).put("items", a);
    }
    /** 上传：流式写到播放器 */
    public JSONObject upload(String dir0, String name, String id, final InputStream in, final long len) throws Exception {
        final String d = cleanPath(dir0), nm = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (nm.isEmpty()) throw new IOException("缺文件名");
        final Job j = job(id, "up", nm, len); j.path = (d.equals("/") ? "" : d) + "/" + nm;
        try {
            withFtp(new FtpWork<Void>() { public Void run(Ftp c) throws Exception { c.mkdirs(d); c.stor(j.path, in, len, new Ftp.Progress() { public void bytes(int n) { j.tickBytes(n); } }); return null; } });
            j.state = "done"; fidx = null; pidx = null;
        } catch (Exception e) { j.state = "err"; j.err = String.valueOf(e.getMessage()); throw e; }
        return new JSONObject().put("job", j.json());
    }
    /** 下载：sink 由调用方给（HTTP 响应流，或存到手机“下载/群星音乐”） */
    public void download(final String p0, String id, final OutputStream sink, Runnable beforeData) throws Exception {
        final String p = cleanPath(p0); final Job j = job(id, "down", p.substring(p.lastIndexOf('/') + 1), 0); j.path = p;
        for (int k = 0; k < 120; k++) { boolean busy = false; synchronized (jobs) { for (Job x : jobs.values()) if ("up".equals(x.kind) && "run".equals(x.state) && p.equals(x.path)) busy = true; } if (!busy) break; Thread.sleep(500); }
        if (beforeData != null) beforeData.run();
        try {
            withFtp(new FtpWork<Void>() { public Void run(Ftp c) throws Exception {
                j.total = c.size(p);
                if (j.total <= 0) { String par = p.substring(0, Math.max(1, p.lastIndexOf('/'))), nm = p.substring(p.lastIndexOf('/') + 1); for (Ftp.Entry e : c.list(par)) if (e.name.equals(nm)) j.total = e.size; }
                Ftp.Progress pr = new Ftp.Progress() { public void bytes(int n) { j.tickBytes(n); } };
                try { c.retr(p, sink, pr); } catch (IOException e) { if (j.done > 0) throw e; Thread.sleep(1500); c.retr(p, sink, pr); }
                return null; } });
            j.state = "done";
        } catch (Exception e) { j.state = "err"; j.err = String.valueOf(e.getMessage()); throw e; }
    }
    /** 存到手机：下载/群星音乐/ */
    public JSONObject saveToPhone(String p0, String id) throws Exception {
        String p = cleanPath(p0), name = p.substring(p.lastIndexOf('/') + 1);
        OutputStream os; Uri uri0 = null; File f = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues(); v.put(MediaStore.Downloads.DISPLAY_NAME, name); v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/群星音乐"); v.put(MediaStore.Downloads.IS_PENDING, 1);
            uri0 = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            os = ctx.getContentResolver().openOutputStream(uri0);
        } else { File dd = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "群星音乐"); dd.mkdirs(); f = new File(dd, name); os = new FileOutputStream(f); }
        try { download(p, id, os, null); os.close(); }
        catch (Exception e) { try { os.close(); } catch (Exception ignored) {} if (uri0 != null) ctx.getContentResolver().delete(uri0, null, null); throw e; }
        if (uri0 != null) { ContentValues v = new ContentValues(); v.put(MediaStore.Downloads.IS_PENDING, 0); ctx.getContentResolver().update(uri0, v, null, null); }
        Job j; synchronized (jobs) { j = jobs.get(id); }
        return new JSONObject().put("saved", "下载/群星音乐/" + name).put("job", j == null ? JSONObject.NULL : j.json());
    }
    JSONObject batch(final JSONArray ops) throws Exception {
        final JSONArray res = new JSONArray();
        withFtp(new FtpWork<Void>() { public Void run(Ftp c) throws Exception {
            Set<String> made = new HashSet<String>();
            for (int i = 0; i < Math.min(5000, ops.length()); i++) {
                JSONObject o = ops.getJSONObject(i);
                try {
                    String op = o.optString("op");
                    if (op.equals("mv")) { String to = cleanPath(o.getString("to")); String d = to.substring(0, to.lastIndexOf('/')); if (!d.isEmpty() && made.add(d)) c.mkdirs(d); c.rename(cleanPath(o.getString("from")), to); }
                    else if (op.equals("rm")) c.dele(cleanPath(o.getString("path")));
                    else if (op.equals("rmdir")) { String d = cleanPath(o.getString("path")); List<Ftp.Entry> left = c.list(d); if (!left.isEmpty()) throw new IOException("文件夹不是空的（A&K 的 FTP 删文件夹会连里面的文件一起删，所以不删）"); c.rmd(d); }
                    else throw new IOException("不认识的操作");
                    res.put(new JSONObject().put("ok", 1));
                } catch (Exception e) { res.put(new JSONObject().put("ok", 0).put("err", String.valueOf(e.getMessage()))); }
            }
            return null; } });
        pidx = null; int done = 0; for (int i = 0; i < res.length(); i++) if (res.getJSONObject(i).optInt("ok") == 1) done++;
        return new JSONObject().put("results", res).put("done", done).put("failed", res.length() - done);
    }

    /* ---------- 接口分发（和电脑版同名同参数） ---------- */
    public JSONObject api(String p, Map<String, String> q, JSONObject body) throws Exception {
        seen = System.currentTimeMillis();
        if (p.equals("status")) return System.currentTimeMillis() - at > 1500 ? status() : view();
        if (p.equals("browse")) return browse(q.containsKey("id") ? q.get("id") : "0", parseInt(q.get("start")), Math.min(200, q.containsKey("count") ? parseInt(q.get("count")) : 100));
        if (p.equals("play")) return play(body.optJSONArray("items") == null ? new JSONArray() : body.optJSONArray("items"), body.optInt("index", 0));
        if (p.equals("pause")) { av("Pause"); wantPlay = false; return status(); }
        if (p.equals("resume")) { av("Play", new String[]{"Speed", "1"}); wantPlay = cur != null; return status(); }
        if (p.equals("stop")) { av("Stop"); wantPlay = false; return status(); }
        if (p.equals("next")) return step(1);
        if (p.equals("prev")) return step(-1);
        if (p.equals("seek")) { double t = body.optDouble("t", 0); av("Seek", new String[]{"Unit", "REL_TIME"}, new String[]{"Target", Util.toHms(t)}); rel = t; at = System.currentTimeMillis(); return view(); }
        if (p.equals("volume")) return volume(body.has("v") ? body.optInt("v") : null);
        if (p.equals("loudness")) return loudness(body.has("on") ? body.optBoolean("on") : null);
        if (p.equals("mode")) return setMode(body.optString("mode"));
        if (p.equals("sleep")) return setSleep(body);
        if (p.startsWith("meta/")) {   // 整理（补全信息）在电脑上的群星里做：转过去
            if (cfg("hubUrl", "").isEmpty()) throw new IOException("整理歌曲信息要用电脑上的群星：在设置里填群星地址和口令");
            StringBuilder qs = new StringBuilder(); for (Map.Entry<String, String> e : q.entrySet()) { if (e.getKey().equals("k")) continue; qs.append(qs.length() == 0 ? "?" : "&").append(enc(e.getKey())).append('=').append(enc(e.getValue())); }
            JSONObject r = hub(body.length() > 0 || p.equals("meta/stop") ? "POST" : "GET", "/api/music/" + p + qs, body.length() > 0 || p.equals("meta/stop") ? body : null);
            r.remove("ok"); return r;
        }
        if (p.equals("lyrics")) return cur == null ? new JSONObject().put("found", false) : lyrics(cur);
        if (p.equals("lyrics/search")) { JSONObject q0 = cur == null ? new JSONObject().put("title", "").put("artist", "").put("dur", 0) : lyrQuery(cur); return lyrCands(q.containsKey("title") ? q.get("title") : q0.optString("title"), q.containsKey("artist") ? q.get("artist") : q0.optString("artist"), q.containsKey("dur") && parseInt(q.get("dur")) > 0 ? parseInt(q.get("dur")) : q0.optDouble("dur", 0)); }
        if (p.equals("lyrics/use")) return lyrUse(cur, body);
        if (p.equals("settings")) return settings(body);
        if (p.equals("word/lookup")) return wordLookup(q.get("w"));
        if (p.equals("word/add")) return wordAdd(body);
        if (p.equals("stats")) { JSONObject r = null; try { r = hub("GET", "/api/music/stats", null); } catch (Exception ignored) {} if (r == null) throw new IOException("听歌统计在电脑上的群星里记；在设置里填群星地址就能看到"); return r; }
        if (p.equals("lyricsq")) return lyrics(new JSONObject().put("url", "q:" + q.get("title") + q.get("artist")).put("title", q.get("title")).put("artist", q.containsKey("artist") ? q.get("artist") : "").put("dur", parseInt(q.get("dur"))));
        if (p.equals("queue")) return queueView();
        if (p.equals("jump")) return startSong(body.optInt("i"));
        if (p.equals("qadd")) return qAdd(body.optJSONArray("items") == null ? new JSONArray() : body.optJSONArray("items"), body.optString("mode"));
        if (p.equals("qremove")) { int i = body.optInt("i"); if (i < 0 || i >= queue.size()) throw new IOException("没有这一首"); if (i == pos) throw new IOException("正在放的这首不能移走，先切到别的歌"); synchronized (queue) { queue.remove(i); } if (i < pos) pos--; saveQueue(); rearm(); return new JSONObject().put("ok", 1); }
        if (p.equals("qmove")) { int i = body.optInt("i"), j = body.optInt("j"), n = queue.size(); if (i < 0 || i >= n || j < 0 || j >= n) throw new IOException("位置不对"); synchronized (queue) { queue.add(j, queue.remove(i)); } if (pos == i) pos = j; else if (i < pos && j >= pos) pos--; else if (i > pos && j <= pos) pos++; saveQueue(); rearm(); return new JSONObject().put("ok", 1); }
        if (p.equals("qclear")) { synchronized (queue) { JSONObject c = cur != null && pos >= 0 && pos < queue.size() ? queue.get(pos) : null; queue.clear(); if (c != null) queue.add(c); pos = c != null ? 0 : -1; } saveQueue(); return new JSONObject().put("ok", 1); }
        if (p.equals("pl/list")) return plList();
        if (p.equals("pl/get")) return plGet(q.containsKey("name") ? q.get("name") : body.optString("name"), "1".equals(q.get("wait")));
        if (p.equals("pl/save")) return plSave(body.optString("name"), body.optJSONArray("paths") == null ? new JSONArray() : body.optJSONArray("paths"));
        if (p.equals("pl/add")) return plAdd(body.optString("name"), body.optJSONArray("items") == null ? new JSONArray() : body.optJSONArray("items"));
        if (p.equals("pl/delete")) return plDelete(body.optString("name"));
        if (p.equals("pl/rename")) return plRename(body.optString("from"), body.optString("to"));
        if (p.equals("pl/play")) { JSONObject g = plGet(body.optString("name")); JSONArray e = g.getJSONArray("entries"), items = new JSONArray(); int idx = 0, want = body.optInt("i", 0); for (int k = 0; k < e.length(); k++) { Object it = e.getJSONObject(k).opt("item"); if (it instanceof JSONObject) { if (k == want) idx = items.length(); items.put(it); } } return play(items, idx); }
        if (p.equals("reindex")) { pathIndex(true); return new JSONObject().put("paths", pidx == null ? 0 : pidx.size()); }
        if (p.equals("rediscover")) return discover();
        if (p.equals("sink")) { String x = Upnp.soap(rUrl("cm"), Upnp.CM, "GetProtocolInfo", new String[0][], 8000); return new JSONObject().put("sink", new JSONArray(java.util.Arrays.asList(Util.tag(x, "Sink").split(",")))); }
        if (p.equals("config")) return body.length() > 0 ? setCfg(body) : cfgJson();
        throw new IOException("没有这个接口: " + p);
    }

    /* ---------- 小工具 ---------- */
    void changed() { Listener l = listener; if (l != null) try { l.changed(); } catch (Throwable ignored) {} }
    static int parseInt(String s) { try { return (int) Double.parseDouble(s == null || s.isEmpty() ? "0" : s); } catch (Exception e) { return 0; } }
    static long parseLong(String s) { try { return Long.parseLong(s == null || s.isEmpty() ? "0" : s); } catch (Exception e) { return 0; } }
    static List<String> keys(JSONObject o) { List<String> l = new ArrayList<String>(); java.util.Iterator<String> it = o.keys(); while (it.hasNext()) l.add(it.next()); return l; }
    static byte[] readBytes(File f) throws IOException { java.io.FileInputStream in = new java.io.FileInputStream(f); try { return Util.readAll(in); } finally { in.close(); } }
    static String read(File f) throws IOException { return new String(readBytes(f), Util.UTF8); }
    static void write(File f, String s) { try { File t = new File(f.getPath() + ".tmp"); FileOutputStream o = new FileOutputStream(t); o.write(s.getBytes(Util.UTF8)); o.close(); t.renameTo(f); } catch (IOException ignored) {} }
}
