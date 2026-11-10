#!/bin/zsh
# Builds clone-allowlist.apk without Gradle, using the Android SDK's build-tools.
set -euo pipefail
cd "${0:A:h}"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/37.0.0"
JAR="$SDK/platforms/android-36/android.jar"
OUT=build

rm -rf -- "${OUT:?}"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$OUT/res"

# 1. Compile Xposed API stubs (compile-time only, never packaged)
javac --release 17 -cp "$JAR" -d "$OUT/stubs" $(find stubs -name '*.java')

# 2. Compile the module against android.jar + stubs
javac --release 17 -cp "$JAR:$OUT/stubs" -d "$OUT/classes" $(find src -name '*.java')

# 3. Dex only the module's own classes
"$BT/d8" --release --min-api 33 --lib "$JAR" --classpath "$OUT/stubs" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# 4. Resources + manifest + assets
"$BT/aapt2" compile --dir res -o "$OUT/res/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets \
  -o "$OUT/unsigned.apk" "$OUT/res/res.zip"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

# 5. Align + sign (keystore is created once and reused so updates install in place)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ ! -f signing.keystore ]]; then
  keytool -genkeypair -keystore signing.keystore -storepass cloneallowlist -keypass cloneallowlist \
    -alias module -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Clone Allowlist" >/dev/null
fi
"$BT/apksigner" sign --ks signing.keystore --ks-pass pass:cloneallowlist --ks-key-alias module \
  --out clone-allowlist.apk "$OUT/aligned.apk"
"$BT/apksigner" verify clone-allowlist.apk
echo "Built: $(pwd)/clone-allowlist.apk"
