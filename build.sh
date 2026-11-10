#!/bin/zsh
# Builds moto-cloner-allowlist.apk without Gradle, using the Android SDK's build-tools.
set -euo pipefail
cd "${0:A:h}"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/37.0.0"
JAR="$SDK/platforms/android-36/android.jar"
OUT=build

rm -rf -- "${OUT:?}"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res" libs

# 1. libxposed API (compile-only; the framework provides it at runtime). Pinned by SHA-256.
XAPI_VER=102.0.0
XAPI_SHA256=423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0
XAPI_AAR="libs/libxposed-api-$XAPI_VER.aar"
if [[ ! -f "$XAPI_AAR" ]]; then
  curl -sSfL -o "$XAPI_AAR.tmp" \
    "https://repo1.maven.org/maven2/io/github/libxposed/api/$XAPI_VER/api-$XAPI_VER.aar"
  mv "$XAPI_AAR.tmp" "$XAPI_AAR"
fi
echo "$XAPI_SHA256  $XAPI_AAR" | shasum -a 256 -c --quiet
unzip -q -o -d "$OUT" "$XAPI_AAR" classes.jar
XAPI_JAR="$OUT/classes.jar"

# 2. Compile the module against android.jar + libxposed API
javac --release 17 -cp "$JAR:$XAPI_JAR" -d "$OUT/classes" $(find src -name '*.java')

# 3. Dex only the module's own classes
"$BT/d8" --release --min-api 33 --lib "$JAR" --classpath "$XAPI_JAR" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# 4. Resources + manifest, then classes.dex and the libxposed metadata (META-INF/xposed/*)
"$BT/aapt2" compile --dir res -o "$OUT/res/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  -o "$OUT/unsigned.apk" "$OUT/res/res.zip"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
(cd resources && zip -q -r ../"$OUT"/unsigned.apk META-INF)

# 5. Align + sign (keystore is created once and reused so updates install in place)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ ! -f signing.keystore ]]; then
  keytool -genkeypair -keystore signing.keystore -storepass cloneallowlist -keypass cloneallowlist \
    -alias module -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Moto Cloner Allowlist" >/dev/null
fi
"$BT/apksigner" sign --ks signing.keystore --ks-pass pass:cloneallowlist --ks-key-alias module \
  --out moto-cloner-allowlist.apk "$OUT/aligned.apk"
"$BT/apksigner" verify moto-cloner-allowlist.apk
echo "Built: $(pwd)/moto-cloner-allowlist.apk"
