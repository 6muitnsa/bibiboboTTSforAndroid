#!/usr/bin/env bash
# 无 Gradle 构建：aapt -> javac -> d8 -> apksigner
# 每次构建自动递增 versionCode / versionName（见 version.properties），
# 保证新包能覆盖旧包、旧包装不回新包。
set -euo pipefail
cd "$(dirname "$0")"

SDK_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
R8_JAR=/tmp/r8.jar
OUT=bibibobo-tts.apk
VERSION_FILE=version.properties
# 签名：本地默认用仓库外的 keystore.jks；正式发布请用固定密钥并放 CI secret，
# 不要把 keystore 提交进仓库（泄露后别人可以冒名发更新）。
KS_FILE=${KS_FILE:-keystore.jks}
KS_PASS=${KS_PASS:-bibibobo}
KS_ALIAS=${KS_ALIAS:-bibibobo}

if [ ! -f "$VERSION_FILE" ]; then
    printf 'CODE=1\nNAME=1.0\n' > "$VERSION_FILE"
fi
. "./$VERSION_FILE"

rm -rf build
mkdir -p build/classes build/dex

javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$SDK_JAR" -d build/classes $(find src -name "*.java")

java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api 26 --lib "$SDK_JAR" --output build/dex \
    $(find build/classes -name "*.class")

aapt package -f -M AndroidManifest.xml -S res -I "$SDK_JAR" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "$CODE" --version-name "$NAME" \
    -F build/app.unsigned.apk
(cd build/dex && aapt add ../app.unsigned.apk classes.dex >/dev/null)

zipalign -f 4 build/app.unsigned.apk build/app.aligned.apk

if [ ! -f "$KS_FILE" ]; then
    keytool -genkeypair -keystore "$KS_FILE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=bibibobo, OU=tts, O=bibibobo, L=local, ST=local, C=CN" >/dev/null 2>&1
fi

apksigner sign --ks "$KS_FILE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --min-sdk-version 26 --out "$OUT" build/app.aligned.apk

apksigner verify --print-certs "$OUT" | head -4
ls -l "$OUT"
echo "built: $NAME ($CODE)"

# 下一次构建用递增后的版本号
NEXT_CODE=$((CODE + 1))
NEXT_NAME="${NAME%.*}.$(( ${NAME##*.} + 1 ))"
printf "CODE=%s\nNAME=%s\n" "$NEXT_CODE" "$NEXT_NAME" > "$VERSION_FILE"
echo "next build will be: $NEXT_NAME ($NEXT_CODE)"
