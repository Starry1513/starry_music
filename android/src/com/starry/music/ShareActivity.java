package com.starry.music;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** 在别的 App 里“分享 → 群星音乐”：把文件交给服务，传到播放器 */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent(); Intent i = new Intent(this, MusicService.class).setAction("share");
        java.util.ArrayList<Uri> list = new java.util.ArrayList<Uri>();
        if (Intent.ACTION_SEND.equals(in.getAction())) { Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) list.add(u); }
        else if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) { java.util.ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) list.addAll(l); }
        if (list.isEmpty()) { Toast.makeText(this, "没有可传的文件", Toast.LENGTH_SHORT).show(); finish(); return; }
        android.content.ClipData cd = android.content.ClipData.newRawUri("files", list.get(0));
        for (int k = 1; k < list.size(); k++) cd.addItem(new android.content.ClipData.Item(list.get(k)));
        i.setClipData(cd); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startForegroundService(i);
        Toast.makeText(this, "开始传到播放器：" + list.size() + " 个文件", Toast.LENGTH_SHORT).show();
        finish();
    }
}
