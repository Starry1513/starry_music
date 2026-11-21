package com.starry.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** 常驻服务：跑本地接口服务 + 核心；通知栏/锁屏播放控制；手机音量键调播放器音量；接收“分享”传到播放器 */
public class MusicService extends Service {
    public static volatile MusicService I;
    public static volatile int port; public static volatile String token;
    Core core; Server server; MediaSession ms; VolumeProvider vp;
    WifiManager.MulticastLock mlock;
    final Handler ui = new Handler(Looper.getMainLooper());
    long lastNotif; String lastKey = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("music", "播放控制", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("xfer", "传文件", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("ask", "新下载的歌：问要不要传", NotificationManager.IMPORTANCE_HIGH));
        Notification n = base("群星音乐", "正在连接播放器…").build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); else startForeground(1, n);
        try { mlock = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createMulticastLock("starry-music"); mlock.setReferenceCounted(false); mlock.acquire(); } catch (Exception ignored) {}
        String t = getSharedPreferences("cfg", 0).getString("token", "");
        if (t.isEmpty()) { byte[] b = new byte[12]; new SecureRandom().nextBytes(b); StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); t = sb.toString(); getSharedPreferences("cfg", 0).edit().putString("token", t).apply(); }
        token = t;
        core = new Core(this);
        core.inbox.notify = new Inbox.Notify() { public void done(final int ok, final int tagged, final int err, final String target) { ui.post(new Runnable() { public void run() {
            String msg = ok + " 首新歌已传到播放器的 " + target + (tagged > 0 ? "，其中 " + tagged + " 首补好了歌名、歌手、专辑和封面" : "") + (err > 0 ? "；" + err + " 首失败，打开 App 看原因" : "") + "。播放器更新曲库后就能看到";
            getSystemService(NotificationManager.class).notify(3, new Notification.Builder(MusicService.this, "xfer").setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("导入文件夹：传完了").setContentText(msg).setStyle(new Notification.BigTextStyle().bigText(msg))
                .setContentIntent(PendingIntent.getActivity(MusicService.this, 0, new Intent(MusicService.this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build());
        } }); } };
        // 网易云 / 百度网盘下好了新歌：弹一条通知问要不要传到播放器（「导入新歌」里选“先问我”时）
        core.inbox.ask = new Inbox.Ask() {
            public void ask(final int n, final String names) { ui.post(new Runnable() { public void run() {
                Intent open = new Intent(MusicService.this, MainActivity.class).putExtra("page", "inbox").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                Notification.Builder b = new Notification.Builder(MusicService.this, "ask").setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("下载好了 " + n + " 首新歌，传到播放器吗？").setContentText(names).setStyle(new Notification.BigTextStyle().bigText(names + "\n传之前会先补好歌名、歌手、专辑和封面；m4a 会先转成 flac。"))
                    .setContentIntent(PendingIntent.getActivity(MusicService.this, 4, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).setAutoCancel(true)
                    .addAction(new Notification.Action.Builder(null, "传到播放器", PendingIntent.getService(MusicService.this, 41, new Intent(MusicService.this, MusicService.class).setAction("askYes"), PendingIntent.FLAG_IMMUTABLE)).build())
                    .addAction(new Notification.Action.Builder(null, "先不传", PendingIntent.getService(MusicService.this, 42, new Intent(MusicService.this, MusicService.class).setAction("askNo"), PendingIntent.FLAG_IMMUTABLE)).build());
                getSystemService(NotificationManager.class).notify(4, b.build());
            } }); }
            public void clear() { getSystemService(NotificationManager.class).cancel(4); }
        };
        core.listener = new Core.Listener() { public void changed() { ui.post(new Runnable() { public void run() { refresh(false); } }); } };
        server = new Server(this, core, token);
        new Thread(new Runnable() { public void run() { try { server.start(); port = server.port; } catch (Exception e) { port = -1; } } }).start();
        setupSession();
        I = this;
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        if (a != null) {
            if (a.equals("prev")) call("prev", null);
            else if (a.equals("next")) call("next", null);
            else if (a.equals("toggle")) call("PLAYING".equals(core.state) ? "pause" : "resume", null);
            else if (a.equals("share")) share(i);
            else if (a.equals("quit")) { stopSelf(); }
            else if (a.equals("askYes")) { try { core.inbox.approve(null); } catch (Exception ignored) {} getSystemService(NotificationManager.class).cancel(4); }
            else if (a.equals("askNo")) { try { core.inbox.decline(null); } catch (Exception ignored) {} getSystemService(NotificationManager.class).cancel(4); }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        I = null; try { server.stop(); } catch (Exception ignored) {} try { core.shutdown(); } catch (Exception ignored) {}
        try { ms.release(); } catch (Exception ignored) {} try { if (mlock != null) mlock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    void call(final String api, final JSONObject body) {
        core.bg.execute(new Runnable() { public void run() {
            try { core.api(api, new java.util.HashMap<String, String>(), body == null ? new JSONObject() : body); }
            catch (final Exception e) { ui.post(new Runnable() { public void run() { Toast.makeText(MusicService.this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show(); } }); }
        } });
    }

    /* ---------- 锁屏 / 通知栏 / 耳机按键 / 音量键 ---------- */
    void setupSession() {
        ms = new MediaSession(this, "starry-music");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { call("resume", null); }
            @Override public void onPause() { call("pause", null); }
            @Override public void onSkipToNext() { call("next", null); }
            @Override public void onSkipToPrevious() { call("prev", null); }
            @Override public void onSeekTo(long ms0) { try { call("seek", new JSONObject().put("t", ms0 / 1000.0)); } catch (Exception ignored) {} }
        });
        vp = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, 30) {
            @Override public void onSetVolumeTo(int v) { setCurrentVolume(v); try { call("volume", new JSONObject().put("v", v)); } catch (Exception ignored) {} }
            @Override public void onAdjustVolume(int d) { int v = Math.max(0, Math.min(100, getCurrentVolume() + d * 2)); setCurrentVolume(v); try { call("volume", new JSONObject().put("v", v)); } catch (Exception ignored) {} }
        };
        ms.setPlaybackToRemote(vp);
        ms.setSessionActivity(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
    }
    PendingIntent act(String a, int rq) { return PendingIntent.getService(this, rq, new Intent(this, MusicService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT); }
    Notification.Builder base(String title, String text) {
        return new Notification.Builder(this, "music").setSmallIcon(android.R.drawable.star_on).setContentTitle(title).setContentText(text).setOngoing(true).setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
    }
    void refresh(boolean force) {
        long now = System.currentTimeMillis();
        JSONObject cur = core.cur; boolean playing = "PLAYING".equals(core.state) || "TRANSITIONING".equals(core.state);
        String title = cur == null ? (core.online ? (core.playerName.isEmpty() ? "群星音乐" : core.playerName) : "群星音乐") : cur.optString("title");
        String text = cur == null ? (core.online ? "已连上，去 App 里点歌" : "没连上播放器") : cur.optString("artist").replace("<unknown>", "");
        String key = title + "|" + text + "|" + playing + "|" + core.vol;
        if (!force && key.equals(lastKey) && now - lastNotif < 15000) return;
        lastKey = key; lastNotif = now;
        if (core.vol != null) vp.setCurrentVolume(core.vol);
        ms.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, text)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, cur == null ? "" : cur.optString("album")).putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (core.dur * 1000)).build());
        ms.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, (long) (core.rel * 1000), playing ? 1f : 0f).build());
        ms.setActive(cur != null);
        Notification.Builder b = base(title, text)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一首", act("prev", 1)).build())
            .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "暂停" : "播放", act("toggle", 2)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "下一首", act("next", 3)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(ms.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class).notify(1, b.build());
    }

    /* ---------- 别的 App“分享”过来的文件 → 传到播放器 ---------- */
    void share(Intent i) {
        final List<Uri> uris = new ArrayList<Uri>();
        ClipData cd = i.getClipData();
        if (cd != null) for (int k = 0; k < cd.getItemCount(); k++) if (cd.getItemAt(k).getUri() != null) uris.add(cd.getItemAt(k).getUri());
        if (uris.isEmpty() && i.getData() != null) uris.add(i.getData());
        String d0 = getSharedPreferences("cfg", 0).getString("uploadDir", ""); final String dir = d0.isEmpty() ? "/Music/待分类" : d0;
        core.bg.execute(new Runnable() { public void run() {
            int ok = 0; String last = "";
            for (int k = 0; k < uris.size(); k++) {
                Uri u = uris.get(k); String name = "文件" + System.currentTimeMillis(); long size = -1;
                Cursor c = null;
                try { c = getContentResolver().query(u, null, null, null, null); if (c != null && c.moveToFirst()) { int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE); if (ni >= 0) name = c.getString(ni); if (si >= 0 && !c.isNull(si)) size = c.getLong(si); } } catch (Exception ignored) {} finally { if (c != null) c.close(); }
                final String id = "share" + System.currentTimeMillis() + k; final String nm = name; final long sz = size; final int idx = k + 1, total = uris.size();
                final Runnable[] prog = new Runnable[1];
                prog[0] = new Runnable() { public void run() {
                    Core.Job j; synchronized (core.jobs) { j = core.jobs.get(id); }
                    if (j == null || !"run".equals(j.state)) return;
                    int pct = sz > 0 ? (int) (100 * j.done / sz) : 0;
                    getSystemService(NotificationManager.class).notify(2, new Notification.Builder(MusicService.this, "xfer").setSmallIcon(android.R.drawable.stat_sys_upload)
                        .setContentTitle("传到播放器（" + idx + "/" + total + "）：" + nm).setContentText(String.format("%.1f MB/s · %d%%", j.speed / 1e6, pct)).setProgress(100, pct, sz <= 0).setOngoing(true).build());
                    ui.postDelayed(prog[0], 700);
                } };
                ui.postDelayed(prog[0], 300);
                try { InputStream in = getContentResolver().openInputStream(u); try { core.upload(dir, nm, id, in, sz); } finally { in.close(); } ok++; last = nm; }
                catch (final Exception e) { ui.post(new Runnable() { public void run() { Toast.makeText(MusicService.this, "传 " + nm + " 失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); } }); }
            }
            final String msg = ok + " 个文件已传到播放器的 " + dir + (ok == 1 ? "（" + last + "）" : "") + "。记得在播放器上更新一下曲库";
            ui.post(new Runnable() { public void run() {
                getSystemService(NotificationManager.class).notify(2, new Notification.Builder(MusicService.this, "xfer").setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("传完了").setContentText(msg).setStyle(new Notification.BigTextStyle().bigText(msg)).build());
                Toast.makeText(MusicService.this, msg, Toast.LENGTH_LONG).show();
            } });
        } });
    }
}
