package com.starry.music;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 小工具：XML 取标签、转义、时间格式、HTTP 请求 */
public final class Util {
    private Util() {}
    public static final Charset UTF8 = Charset.forName("UTF-8");

    public static String unesc(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    /** 取第一个 <t>…</t>（忽略命名空间前缀），返回反转义后的内容 */
    public static String tag(String x, String t) {
        if (x == null) return "";
        Matcher m = Pattern.compile("<(?:\\w+:)?" + t + "(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?" + t + ">").matcher(x);
        return m.find() ? unesc(m.group(1)) : "";
    }
    public static String attr(String attrs, String k) {
        Matcher m = Pattern.compile("(?:^|\\s)" + k + "=\"([^\"]*)\"").matcher(attrs == null ? "" : attrs);
        return m.find() ? unesc(m.group(1)) : "";
    }
    public static double hms(String s) {
        if (s == null || s.isEmpty() || s.startsWith("NOT")) return 0;
        String[] p = s.split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Double.parseDouble(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60 + Double.parseDouble(p[1]);
            return Double.parseDouble(s);
        } catch (Exception e) { return 0; }
    }
    public static String toHms(double t) {
        long s = Math.max(0, (long) Math.floor(t));
        return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
    public static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    public static class Resp { public int code; public byte[] body; public String type; }
    public static Resp http(String method, String url, Map<String, String> headers, byte[] body, int timeoutMs) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
        c.setRequestMethod(method); c.setUseCaches(false);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) { c.setDoOutput(true); c.setFixedLengthStreamingMode(body.length); OutputStream o = c.getOutputStream(); o.write(body); o.close(); }
        Resp r = new Resp();
        try {
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? new byte[0] : readAll(in);
            r.type = c.getContentType();
        } finally { c.disconnect(); }
        return r;
    }
}
