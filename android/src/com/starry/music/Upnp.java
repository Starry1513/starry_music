package com.starry.music;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UPnP：发现播放器、发 SOAP（排队，一次一个） */
public class Upnp {
    public static final String AV = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String CD = "urn:schemas-upnp-org:service:ContentDirectory:1";

    public static class Dev { public String type, name, model, ip; public Map<String, String> svcs = new HashMap<String, String>(); }

    /** 搜 3.5 秒，返回所有 MediaRenderer / MediaServer 设备 */
    public static List<Dev> discover() {
        Set<String> locs = new LinkedHashSet<String>();
        MulticastSocket s = null;
        try {
            s = new MulticastSocket(); s.setSoTimeout(500); s.setTimeToLive(2);
            InetAddress grp = InetAddress.getByName("239.255.255.250");
            for (String st : new String[]{"urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:device:MediaServer:1", "ssdp:all"}) {
                byte[] m = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n").getBytes();
                s.send(new DatagramPacket(m, m.length, grp, 1900));
            }
            long end = System.currentTimeMillis() + 3500; byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                Matcher mm = Pattern.compile("(?im)^location:\\s*(.+)$").matcher(new String(p.getData(), 0, p.getLength()));
                if (mm.find()) locs.add(mm.group(1).trim());
            }
        } catch (IOException ignored) {
        } finally { if (s != null) s.close(); }
        List<Dev> devs = new ArrayList<Dev>();
        for (String loc : locs) {
            try {
                Util.Resp r = Util.http("GET", loc, null, null, 6000);
                String x = new String(r.body, Util.UTF8);
                String base = Util.tag(x, "URLBase"); if (base.isEmpty()) base = loc;
                Matcher dm = Pattern.compile("<device>([\\s\\S]*?)</device>").matcher(x);
                while (dm.find()) {
                    String d = dm.group(1), type = Util.tag(d, "deviceType");
                    if (!type.contains("MediaRenderer") && !type.contains("MediaServer")) continue;
                    Dev v = new Dev(); v.type = type.contains("Renderer") ? "r" : "s"; v.name = Util.tag(d, "friendlyName"); v.model = Util.tag(d, "modelName"); v.ip = new URL(loc).getHost();
                    Matcher sm = Pattern.compile("<service>([\\s\\S]*?)</service>").matcher(d);
                    while (sm.find()) {
                        String st = Util.tag(sm.group(1), "serviceType"), cu = Util.tag(sm.group(1), "controlURL");
                        String k = st.contains("AVTransport") ? "avt" : st.contains("RenderingControl") ? "rc" : st.contains("ConnectionManager") ? "cm" : st.contains("ContentDirectory") ? "cd" : null;
                        if (k != null && !cu.isEmpty() && !v.svcs.containsKey(k)) v.svcs.put(k, new URL(new URL(base), cu).toString());
                    }
                    devs.add(v);
                }
            } catch (Exception ignored) {}
        }
        return devs;
    }

    private static final Object Q = new Object();   // 播放器的 HTTP 服务很脆：所有 SOAP 一个一个发

    public static String soap(String url, String st, String action, String[][] args, int timeoutMs) throws IOException {
        StringBuilder inner = new StringBuilder();
        for (String[] a : args) inner.append('<').append(a[0]).append('>').append(Util.esc(a[1])).append("</").append(a[0]).append('>');
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + st + "\">" + inner + "</u:" + action + "></s:Body></s:Envelope>";
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "text/xml; charset=\"utf-8\""); h.put("SOAPACTION", "\"" + st + "#" + action + "\"");
        synchronized (Q) {
            Util.Resp r = Util.http("POST", url, h, body.getBytes(Util.UTF8), timeoutMs);
            String x = new String(r.body, Util.UTF8);
            if (r.code != 200) throw new IOException(action + " 失败 " + r.code + " " + Util.tag(x, "errorDescription"));
            return x;
        }
    }
}
