#!/bin/bash
# 酸败自助补丁工具 —— 沙箱纯命令行构建（含 DiagLog/DiagApp + 启动里程碑）
set -e
cd "$(dirname "$0")"

AJAR=/opt/android-sdk/platforms/android-34/android.jar
BT=/opt/android-sdk/build-tools/34.0.0
APKSIG=$(find /root/.gradle -name 'apksig-*.jar' | head -1)
# 直装版：无 root/Shizuku 依赖，仅保留签名用的 apksig。
LIBS="$APKSIG"

WORK=build_diag
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/assets"

echo "== 1/4 主源码(纯直装：无 Shizuku/AIDL stub) =="
javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath "$AJAR" \
  -cp "$LIBS" -d "$WORK/classes" $(find src -name '*.java')

echo "== 2/4 d8 合并 dex =="
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$AJAR" --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class') \
  "$APKSIG"

echo "== 3/4 资源 + 签名物料 =="
cp keys/sign_key.der keys/sign_cert.der "$WORK/assets/"
# 数据通道(bridge.dex)已移除：它会破坏游戏manifest导致直装包闪退

echo "== 4/4 aapt 打包 + 签名 + 校验 =="
"$BT/aapt" package -f -M AndroidManifest.xml -S res -A "$WORK/assets" -I "$AJAR" \
  --min-sdk-version 26 --target-sdk-version 33 -F "$WORK/base.apk"
( cd "$WORK/dex" && "$BT/aapt" add ../base.apk classes.dex )
"$BT/zipalign" -f 4 "$WORK/base.apk" "$WORK/aligned.apk"

echo "== 签名 + 校验 =="
"$BT/apksigner" sign --ks keys/lvshu.p12 --ks-pass pass:lvshu300 \
  --key-pass pass:lvshu300 --ks-key-alias lvshu --min-sdk-version 26 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "酸败自助补丁工具.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify --print-certs "酸败自助补丁工具.apk" | grep -E "SHA-256 digest|Verifies"
ls -l "酸败自助补丁工具.apk"
