#!/bin/sh
# Builds build/agentos.apk with javac + D8 + aapt2 (no Gradle). SDK comes from tooling/android-env.sh.
set -eu
cd "$(dirname "$0")"
. ../tooling/android-env.sh
rm -rf build assets/runtime-mods && mkdir -p build/gen/local/agentos build/classes build/dex build/ort build/apkroot/lib assets
if [ -f runtime-mods/build.sh ]; then
  sh runtime-mods/build.sh "$PWD/assets/runtime-mods" "$PWD/build/gen/local/agentos"
else
  echo 'Runtime mod pack not checked out; building the manager without built-ins.'
  echo 'package local.agentos; final class BuiltinMods { static final RuntimeMods.Kind[] VALUES = {}; }' > build/gen/local/agentos/BuiltinMods.java
fi
# AGENTOS_VAULT_TEST=1 ./build.sh = TEST BUILD: the vault fills WITHOUT a fingerprint (own key + file). Never ship one.
[ "${AGENTOS_VAULT_TEST:-}" = 1 ] && VT=true || VT=false
echo "package local.agentos; final class BuildFlags { static final boolean VAULT_TEST = $VT; }" > build/gen/local/agentos/BuildFlags.java
# ONNX Runtime for Prompt Guard (research/04 3a): pinned AAR from Maven Central. We take only classes.jar and the arm64
# .so files; the AAR's manifest (a telemetry ContentProvider + INTERNET) is never merged.
ORT=third_party/onnxruntime-android-1.31.1.aar
[ -f $ORT ] || { mkdir -p third_party; curl -fsSL -o $ORT https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.1/onnxruntime-android-1.31.1.aar; }
echo "d4d50660300ed2c4f38ffdfd5ab5bfa3a22f995f282dda24f42ea3ea55467b49  $ORT" | shasum -a 256 -c - >/dev/null
unzip -q $ORT classes.jar 'jni/arm64-v8a/*' -d build/ort && mv build/ort/jni/arm64-v8a build/apkroot/lib/
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias agentos -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AgentOS debug"
aapt2 compile --dir res -o build/res.zip
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets --min-sdk-version 33 --target-sdk-version 33 \
  --java build/gen -o build/unsigned.apk build/res.zip
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR:$ANDROID_BUILD_TOOLS/core-lambda-stubs.jar" -cp build/ort/classes.jar -d build/classes $(find src build/gen -name '*.java')
d8 --release --min-api 33 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class') build/ort/classes.jar
(cd build/dex && zip -q ../unsigned.apk classes.dex)
(cd build/apkroot && zip -q -0 -r ../unsigned.apk lib) # native libs stored uncompressed; zipalign -p page-aligns them
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android --ks-key-alias agentos --out build/agentos.apk build/aligned.apk
echo "built $(pwd)/build/agentos.apk"
