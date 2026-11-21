#!/bin/bash
# 群星音乐 · 安卓版构建（无 Gradle）：javac → dx → aapt → zipalign → apksigner。dx 不支持 lambda，只写匿名类
# 需要：aapt apksigner dalvik-exchange zipalign（apt 装）、android-34.jar（AJ）、签名 keystore（KS，密码 KSP，默认 android）
set -e
cd "$(dirname "$0")"
AJ=${AJ:-libs/android-34.jar}
# 解 7z 用的两个库（Apache Commons Compress 1.18 + XZ for Java 1.8；都是 Java 7 字节码，dx 能吃）
for u in org/apache/commons/commons-compress/1.18/commons-compress-1.18.jar org/tukaani/xz/1.8/xz-1.8.jar; do f=libs/$(basename $u); [ -f "$f" ] || curl -sfL -o "$f" https://repo1.maven.org/maven2/$u || { echo "下载 $f 失败"; exit 1; }; done
LIBS=libs/commons-compress-1.18.jar:libs/xz-1.8.jar
KS=${KS:-debug.keystore}
rm -rf build && mkdir -p build/classes build/apk assets
cp ../ui/music.html assets/music.html
cp ../core/opencc_t2cn.js assets/opencc.js
cp ../core/artist_alias.json assets/artist_alias.json
TS=assets_extra/t2s.txt; [ -f "$TS" ] && cp "$TS" assets/t2s.txt
javac -nowarn -encoding UTF-8 --release 8 -cp "$AJ:$LIBS" -d build/classes $(find src -name '*.java') > build/javac.log 2>&1 || { grep -v "JAVA_TOOL_OPTIONS\|^Note" build/javac.log; echo "编译失败，没打包"; exit 1; }
[ -f build/classes/com/starry/music/MusicService.class ] || { echo "javac 失败"; exit 1; }
(cd build/classes && jar cf ../app.jar com)
dalvik-exchange --dex --min-sdk-version=26 --output=build/apk/classes.dex build/app.jar libs/commons-compress-1.18.jar libs/xz-1.8.jar 2>&1 | grep -v JAVA_TOOL || true
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F build/base.apk
(cd build/apk && zip -qr ../base.apk classes.dex)
zipalign -f -p 4 build/base.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:"${KSP:-android}" --key-pass pass:"${KSP:-android}" --out StarryMusic.apk build/aligned.apk 2>&1 | grep -v JAVA_TOOL || true
rm -f StarryMusic.apk.idsig
ls -la StarryMusic.apk
