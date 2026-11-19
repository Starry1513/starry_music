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
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
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
        setContentView(web);
        startForegroundService(new Intent(this, MusicService.class));
        waitAndLoad(0);
    }
    void waitAndLoad(final int n) {
        if (MusicService.port > 0 && MusicService.token != null) { web.loadUrl("http://127.0.0.1:" + MusicService.port + "/?app=1&k=" + MusicService.token); return; }
        if (n > 100) { web.loadData("<p style='color:#fff;background:#05040d'>本地服务没起来</p>", "text/html; charset=utf-8", null); return; }
        h.postDelayed(new Runnable() { public void run() { waitAndLoad(n + 1); } }, 100);
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
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
