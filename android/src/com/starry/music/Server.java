package com.starry.music;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 只给本机 WebView 用的小 HTTP 服务（127.0.0.1），接口和电脑版一样：/api/music/... */
public class Server {
    final Core core; final Context ctx; final String token;
    ServerSocket ss; public int port;
    final ExecutorService pool = Executors.newCachedThreadPool();

    public Server(Context c, Core core, String token) { this.ctx = c; this.core = core; this.token = token; }

    public void start() throws IOException {
        for (int p = 8790; p < 8800; p++) {
            try { ss = new ServerSocket(); ss.setReuseAddress(true); ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p)); port = p; break; }
            catch (IOException e) { ss = null; }
        }
        if (ss == null) throw new IOException("本地端口都被占了");
        pool.execute(new Runnable() { public void run() {
            while (!ss.isClosed()) {
                try { final Socket s = ss.accept(); pool.execute(new Runnable() { public void run() { handle(s); } }); }
                catch (IOException e) { if (ss.isClosed()) break; }
            }
        } });
    }
    public void stop() { try { ss.close(); } catch (Exception ignored) {} pool.shutdownNow(); }

    static class Req { String method, path; Map<String, String> q = new HashMap<String, String>(), h = new HashMap<String, String>(); InputStream body; long len; }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) >= 0) { if (c == '\n') break; if (c != '\r') b.write(c); if (b.size() > 16384) throw new IOException("请求行太长"); }
        if (c < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), Util.UTF8);
    }
    static Map<String, String> query(String qs) {
        Map<String, String> m = new HashMap<String, String>();
        if (qs == null) return m;
        for (String kv : qs.split("&")) { if (kv.isEmpty()) continue; int i = kv.indexOf('='); try { m.put(URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8"), i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8")); } catch (Exception ignored) {} }
        return m;
    }
    static void head(OutputStream o, int code, String type, long len, String extra) throws IOException {
        String msg = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : code == 400 ? "Bad Request" : "Error";
        StringBuilder sb = new StringBuilder("HTTP/1.1 " + code + " " + msg + "\r\nContent-Type: " + type + "\r\nCache-Control: no-cache\r\nConnection: close\r\n");
        if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
        if (extra != null) sb.append(extra);
        sb.append("\r\n"); o.write(sb.toString().getBytes(Util.UTF8));
    }
    static void send(OutputStream o, int code, String type, byte[] b) throws IOException { head(o, code, type, b.length, null); o.write(b); o.flush(); }
    static void json(OutputStream o, int code, JSONObject j) throws IOException { send(o, code, "application/json; charset=utf-8", j.toString().getBytes(Util.UTF8)); }
    static JSONObject err(String m) { try { return new JSONObject().put("ok", false).put("error", m); } catch (Exception e) { return new JSONObject(); } }

    void handle(Socket s) {
        try {
            s.setSoTimeout(120000);
            InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
            String rl = line(in); if (rl == null) return;
            String[] p = rl.split(" "); if (p.length < 2) return;
            Req r = new Req(); r.method = p[0]; int qi = p[1].indexOf('?');
            r.path = qi < 0 ? p[1] : p[1].substring(0, qi); r.q = query(qi < 0 ? null : p[1].substring(qi + 1));
            String l; while ((l = line(in)) != null && !l.isEmpty()) { int c = l.indexOf(':'); if (c > 0) r.h.put(l.substring(0, c).trim().toLowerCase(), l.substring(c + 1).trim()); }
            r.len = r.h.containsKey("content-length") ? Long.parseLong(r.h.get("content-length")) : 0; r.body = in;
            route(r, out);
        } catch (Throwable ignored) {
        } finally { try { s.close(); } catch (IOException ignored) {} }
    }

    boolean authed(Req r) {
        String t = r.h.get("x-starry-token"); if (t == null) t = r.q.get("k");
        if (t == null) { String ck = r.h.get("cookie"); if (ck != null) for (String c : ck.split(";\\s*")) if (c.startsWith("sm_k=")) t = c.substring(5); }
        return token.equals(t);
    }

    void route(Req r, OutputStream o) throws Exception {
        if (!authed(r)) { send(o, 401, "text/plain; charset=utf-8", "需要口令".getBytes(Util.UTF8)); return; }
        if (r.path.equals("/") || r.path.equals("/music")) {
            byte[] b = Util.readAll(ctx.getAssets().open("music.html"));
            head(o, 200, "text/html; charset=utf-8", b.length, "Set-Cookie: sm_k=" + token + "; Path=/; HttpOnly; SameSite=Strict\r\n"); o.write(b); o.flush(); return;
        }
        if (r.path.equals("/opencc.js")) { send(o, 200, "text/javascript; charset=utf-8", Util.readAll(ctx.getAssets().open("opencc.js"))); return; }
        if (!r.path.startsWith("/api/music")) { send(o, 404, "text/plain", new byte[0]); return; }
        String api = r.path.replaceFirst("^/api/music/?", "");
        if (api.equals("art")) {
            String u = r.q.get("u");
            if (u == null || core.ip == null || !u.startsWith("http://" + core.ip + ":")) { send(o, 400, "text/plain", new byte[0]); return; }
            try { Util.Resp x = Util.http("GET", u, null, null, 8000); send(o, 200, x.type == null ? "image/jpeg" : x.type, x.body); } catch (Exception e) { send(o, 502, "text/plain", new byte[0]); }
            return;
        }
        try {
            if (api.equals("ftp/upload") && r.method.equals("POST")) { json(o, 200, core.upload(r.q.get("dir"), r.q.containsKey("name") ? r.q.get("name") : "", r.q.get("id"), r.body, r.len).put("ok", true)); return; }
            if (api.equals("ftp/download")) {
                final OutputStream fo = o; final boolean[] started = {false};
                String pp = Core.cleanPath(r.q.get("path"));
                final String nm = java.net.URLEncoder.encode(pp.substring(pp.lastIndexOf('/') + 1), "UTF-8").replace("+", "%20");
                try {
                    core.download(pp, r.q.get("id"), o, new Runnable() { public void run() { try { head(fo, 200, "application/octet-stream", -1, "Content-Disposition: attachment; filename*=UTF-8''" + nm + "\r\n"); started[0] = true; } catch (IOException ignored) {} } });
                } catch (Exception e) { if (!started[0]) json(o, 500, err(String.valueOf(e.getMessage()))); }
                o.flush(); return;
            }
            if (api.equals("ftp/save")) { json(o, 200, core.saveToPhone(r.q.get("path"), r.q.get("id")).put("ok", true)); return; }
            if (api.equals("ftp/ls")) { json(o, 200, core.ftpLs(r.q.get("dir")).put("ok", true)); return; }
            if (api.equals("ftp/mkdir")) { final String d = Core.cleanPath(r.q.get("dir")); core.withFtp(new Core.FtpWork<Void>() { public Void run(Ftp c) { c.mkdirs(d); return null; } }); json(o, 200, new JSONObject().put("ok", true)); return; }
            if (api.equals("ftp/jobs")) { json(o, 200, core.jobsView().put("ok", true)); return; }
            JSONObject body = new JSONObject();
            if (r.len > 0) { byte[] b = new byte[(int) Math.min(r.len, 4_000_000)]; int got = 0; while (got < b.length) { int n = r.body.read(b, got, b.length - got); if (n <= 0) break; got += n; } String t = new String(b, 0, got, Util.UTF8).trim(); if (!t.isEmpty()) body = new JSONObject(t); }
            if (api.equals("ftp/batch")) { json(o, 200, core.batch(body.getJSONArray("ops")).put("ok", true)); return; }
            json(o, 200, core.api(api, r.q, body).put("ok", true));
        } catch (Exception e) { json(o, 400, err(String.valueOf(e.getMessage()))); }
    }
}
