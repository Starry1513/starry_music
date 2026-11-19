# Starry Music (群星音乐)

[中文](README.md) | **English**

Control a DLNA music player at home from your phone, tablet or computer. Browse and play the player's own library, see synced lyrics on your phone, manage playlists, and move files to and from the player. Tested mainly on the **A&K ACRO CA1000T**; most other DLNA / UPnP players work too (see the compatibility table below).

The player always does the playback itself, and your phone is only a remote. Audio never passes through the phone, so locking it or walking away does not interrupt playback.

Two versions:

| Version | Runs on | How to open |
|---|---|---|
| Desktop | Windows / macOS / Linux with Node.js 18+, no other dependencies | `node server.js`, then open `http://<computer-IP>:8790/?k=<token>` in a browser |
| Phone / tablet | Android 10+ app (build it from `android/`, see below) | Install and open; no computer needed |

Both versions share the same UI and API (`ui/music.html` + `/api/music/*`). The desktop version can also run inside the "Constellation" (群星) hub (`integrations/constellation.js`).

The interface is in Simplified Chinese.

## Features

- **Library**: browse by the player's own categories: folders, artists, albums, all songs, playlists.
- **Playback control**:
  - Play, pause, seek, previous / next.
  - Volume and mute. The phone's volume keys control the player's volume.
  - Loudness compensation.
  - Play order: in order, repeat all, repeat one, shuffle.
  - Sleep timer that fades out over the last 20 seconds, or "stop after this song".
- **Queue**: play next, add to end, reorder, remove. The queue survives restarts.
- **Lyrics**:
  - While a song plays, synced lyrics are searched on LRCLIB and NetEase Cloud Music at the same time. The best match by title, artist and duration is picked and converted to Simplified Chinese (Japanese is left as is).
  - Foreign-language songs show a Chinese translation when one exists.
  - If the match is wrong, tap "换歌词" (change lyrics) to search and pick another version. Your choice is remembered. You can also hide lyrics for a song.
  - Lyric text is never stored locally; only "which version this song uses" is saved.
- **Tap English words** (can be turned off in settings):
  - Tap an English word in the lyrics to see its meaning.
  - "+ 生词本" adds it to Constellation's word review, together with words synced from Eudic. Without Constellation, words are kept on the device.
- **Playlists**:
  - Stored on the player as `/Playlists/*.m3u`, so they also show up on the player itself. A copy is kept in the app, and either side can edit.
  - Opening a playlist shows the local copy first, then quietly checks it against the player.
- **Smart playlists**: "最近常听" (played most recently) and "很久没听" (not played for a long time) are rebuilt daily from your listening history and written to the player.
- **Listening stats**:
  - What you listened to each day and for how long, including songs started on the player itself.
  - Shown on Constellation's overview page and in settings.
- **Tidy up (fill in song info)**:
  - Pick songs (missing info / just imported / a folder; multi-select) → look them up on NetEase Cloud Music and Apple Music → each song shows before and after: title, artist, album, track number, year, cover.
  - Confident matches are pre-ticked; unsure ones are left alone. You can "换一个" (pick another version) or tap a field to edit it. Changes are written to the files after you confirm.
  - Writing runs on the computer (needs Python, `tools/tagfix.py`). Audio data is never touched, and the original tags are backed up so they can be restored.
  - Artist names are unified (Leslie Cheung → 张国荣, G.E.M. → 邓紫棋, …). Spellings you choose are remembered and applied automatically next time.
  - After you upload songs, it offers to fill in their info right away.
- **Missing album tracks**: compare each album in your library against the NetEase / Apple Music track list and see which songs you don't have.
- **File transfer**: copy files to and from the player, with live speed for both legs. The Android app talks to the player directly, without a computer; you can also "Share → 传到播放器" (send to player) from other apps.
- **Android extras**: playback controls in the notification and on the lock screen, headset buttons, volume keys control the player's volume.

## Compatibility

| Feature | Player must provide | CA1000T (tested) | Typical DLNA player |
|---|---|---|---|
| Find the player | SSDP (UPnP multicast) | ✓ | ✓ |
| Play / pause / seek | AVTransport | ✓ | ✓ |
| Volume / mute | RenderingControl | ✓ | ✓ |
| Loudness | RenderingControl `SetLoudness` | ✓ | Some |
| Browse the library | Built-in MediaServer (ContentDirectory) | ✓ (AK Connect) | Depends on model; remote control only without it |
| Gapless next track | `SetNextAVTransportURI` that actually advances | ✗: accepted but ignored. The app pushes the next song right at the end; gap about 1 s | Depends on model |
| Write playlists back | Writable storage (FTP here) | ✓ (AK File Drop) | Usually no |
| File transfer | FTP | ✓ (AK File Drop, port 1212) | Usually no |
| EQ, gain and other device settings | Private protocol | ✗ (no public interface) | ✗ |

Known CA1000T issues:

- Pushing DSD (dsf/dff) for playback once froze it, so DSD is not pushed by default. Turn on `allowDsd` in the config if you want it.
- FTP drops when the screen turns off; keep the screen on while transferring.
- Its FTP deletes a folder together with everything inside it. The app checks first and refuses to delete a folder that is not empty.
- When updating the library (media scan), "清除歌单" (clear playlists) is ticked by default. Untick it before scanning, or your playlists will be wiped.

## Configuration

Desktop: copy `config.example.json` to `config.json` and edit. Everything is optional: the player is found automatically and FTP uses admin / admin.

```json
{
  "server": { "host": "0.0.0.0", "port": 8790, "token": "" },
  "player": {
    "name": "",
    "ip": "",
    "allowDsd": false,
    "internalPath": "/storage/emulated/0",
    "sdPath": "/storage/sdcard1",
    "ftp": { "port": 1212, "user": "admin", "pass": "admin" },
    "lyrics": true,
    "playlistAlbums": [],
    "python": ""
  },
  "hub": { "url": "", "token": "" }
}
```

| Key | Meaning |
|---|---|
| `token` | Access token. Generated automatically if empty and saved to `data/token.txt`. Not needed from the same computer |
| `name` / `ip` | Pick one player when there are several at home |
| `sdPath` | Path of the memory card on the player, usually `/storage/XXXX-XXXX`. Needed to match playlist paths |
| `playlistAlbums` | Playlist names that download apps wrote into the album field (e.g. NetEase playlist names). Tidy up treats them as "no album" |
| `python` | Python used to write tags (default `python3`, `py` on Windows). Needs `pip install -r tools/requirements.txt` |
| `hub` | Constellation hub address and token. When set, words are added to Constellation's word review |

Android app: in settings (gear icon, top right) enter the player IP (leave empty to find it automatically), the target folder for "Share" uploads, and optionally the Constellation address and token.

Settings also has three switches: tap English words, show lyric translations, write smart playlists to the player.

## File transfer

There is only one route: **device ⇄ player**, through the player's built-in **AK File Drop** (FTP). The desktop version goes browser → program on the computer → player; the Android app goes app → player directly. Device-to-device transfer is out of scope (use KDE Connect or similar).

- File Drop is plain FTP. Use it only on your home network and never forward its port to the internet.
- A download waits for any upload of the same file to finish, so you never get half a file.
- Retries once after a dropped connection.
- Checks that a folder is empty before deleting it (see the CA1000T issues above).

Measured on 2026-11-19 over home Wi-Fi with the CA1000T screen on; md5 matched before and after:

| Direction | File | Average | Peak |
|---|---|---|---|
| Computer → CA1000T | 100 MB | 1.8 MB/s | 2.0 MB/s |
| CA1000T → computer | 100 MB | 1.6 MB/s | 2.3 MB/s |
| Phone app → CA1000T | 40 MB | 1.6 MB/s | 3.4 MB/s |
| CA1000T → phone app | 40 MB | 1.4 MB/s | 2.5 MB/s |
| Tablet app → CA1000T | 40 MB | 1.8 MB/s | 1.9 MB/s |
| CA1000T → tablet app | 40 MB | 2.1 MB/s | 2.4 MB/s |

The bottleneck is the player's FTP service, about 2 MB/s. A 30 MB FLAC takes about 15 seconds.

This is also why writing song info is slow: the player has no tag-editing interface, and FTP cannot rewrite part of a file. Each song is downloaded in full, edited, then uploaded in full, so about 30 seconds per song.

## Building the Android app

No Gradle. You need JDK 17, `android-34.jar`, `dalvik-exchange` (dx), `aapt`, `zipalign` and `apksigner`:

```bash
cd android
AJ=/path/to/android-34.jar KS=/path/to/your.keystore ./build.sh
```

Because the build uses dx, the Java code uses anonymous classes only, no lambdas.

## Layout

```
server.js                 Desktop server
core/music.js             Core: discovery, playback, queue, library, playlists, FTP transfer
core/extras.js            Multi-source lyrics, word lookup, listening stats, smart playlists, settings
core/meta.js              Tidy up: song info lookup, before/after, writing, missing album tracks
core/artist_alias.json    Artist name variants → one spelling
core/ftp.js               Small FTP client
core/opencc_t2cn.js       Traditional → Simplified Chinese (opencc-js)
ui/music.html             UI (shared by desktop and Android)
integrations/             Wrapper for the Constellation hub
android/                  Android app (Java, no Gradle)
tools/tagfix.py           Writes tags (title, artist, album, cover…); original tags backed up for restore
```

## Privacy

- All data stays on your own devices: queue, playlist copies, listening stats, lyric choices.
- Network lookups: lyrics (LRCLIB, NetEase Cloud Music), song info and covers (NetEase Cloud Music, Apple Music), and word meanings (Youdao). Only titles, artists, durations, album names or the word itself are sent.
