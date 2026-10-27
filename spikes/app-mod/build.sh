#!/bin/sh
# Builds build/appmod.apk with javac + D8 + aapt2 (same recipe as android/build.sh, no Gradle).
set -eu
cd "$(dirname "$0")"
. ../../tooling/android-env.sh
rm -rf build && mkdir -p build/gen build/classes build/dex
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias appmod -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AgentOS appmod debug"
aapt2 compile --dir res -o build/res.zip
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml --min-sdk-version 33 --target-sdk-version 33 \
  --java build/gen -o build/unsigned.apk build/res.zip
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR:$ANDROID_BUILD_TOOLS/core-lambda-stubs.jar" -d build/classes $(find src build/gen -name '*.java')
d8 --release --min-api 33 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android --ks-key-alias appmod --out build/appmod.apk build/aligned.apk
echo "built $(pwd)/build/appmod.apk"
