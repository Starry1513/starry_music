package com.starry.music;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.Uri;

/** 界面：WebView 打开本地服务的同一个星空页面（和电脑版共用 ui/music.html） */
public class MainActivity extends Activity {
    WebView web; ValueCallback<Uri[]> fileCb; final Handler h = new Handler();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#05040d")); getWindow().setNavigationBarColor(Color.parseColor("#05040d"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        // 通知（播放控制、传输进度）+ 读手机里的音乐（导入文件夹：网易云下载的歌）
        java.util.List<String> need = new java.util.ArrayList<String>();
        if (android.os.Build.VERSION.SDK_INT >= 33) { if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) need.add("android.permission.POST_NOTIFICATIONS"); if (checkSelfPermission("android.permission.READ_MEDIA_AUDIO") != 0) need.add("android.permission.READ_MEDIA_AUDIO"); }
        else if (checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") != 0) need.add("android.permission.READ_EXTERNAL_STORAGE");
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
        web = new WebView(this); web.setBackgroundColor(Color.parseColor("#05040d"));
        WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                fileCb = cb; Intent i = p.createIntent(); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, 9); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        // 给页面用的一点点本机能力：选一个授权文件夹（读网盘下载的压缩包要用）
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void pickTree(final String hint) { h.post(new Runnable() { public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try { i.putExtra("android.provider.extra.INITIAL_URI", android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + (hint == null || hint.isEmpty() ? "BaiduNetdisk" : hint))); } catch (Exception ignored) {}
                try { startActivityForResult(i, 10); } catch (Exception e) { web.evaluateJavascript("window.onTreePicked&&onTreePicked(false)", null); }
            } }); }
        }, "StarryApp");
        setContentView(web);
        startForegroundService(new Intent(this, MusicService.class));
        waitAndLoad(0);
    }
    void waitAndLoad(final int n) {
        if (MusicService.port > 0 && MusicService.token != null) { String pg = getIntent() != null ? getIntent().getStringExtra("page") : null; web.loadUrl("http://127.0.0.1:" + MusicService.port + "/?app=1&k=" + MusicService.token + (pg != null ? "&p=" + pg : "")); return; }
        if (n > 100) { web.loadData("<p style='color:#fff;background:#05040d'>本地服务没起来</p>", "text/html; charset=utf-8", null); return; }
        h.postDelayed(new Runnable() { public void run() { waitAndLoad(n + 1); } }, 100);
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); String pg = i.getStringExtra("page"); if (pg != null && web != null) web.evaluateJavascript("window.nav&&nav('" + pg.replaceAll("[^a-z]", "") + "')", null); }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 10) {
            boolean ok = false;
            if (res == RESULT_OK && data != null && data.getData() != null) try {
                getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getSharedPreferences("cfg", 0).edit().putString("inTree", data.getData().toString()).commit(); ok = true;
            } catch (Exception ignored) {}
            web.evaluateJavascript("window.onTreePicked&&onTreePicked(" + ok + ")", null); return;
        }
        if (req == 9 && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) { r = new Uri[data.getClipData().getItemCount()]; for (int i = 0; i < r.length; i++) r[i] = data.getClipData().getItemAt(i).getUri(); }
                else if (data.getData() != null) r = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(r); fileCb = null;
        }
    }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else moveTaskToBack(true); }
}
