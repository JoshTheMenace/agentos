#!/bin/sh
# Builds watch/build/watch.apk with aapt2 + javac + D8 (no Gradle, no AndroidX), same recipe as android/build.sh.
# Plain Android APIs only, so it compiles against android-33 and runs on Wear OS 3+ (Watch4: Wear OS 5/6).
set -eu
cd "$(dirname "$0")"
. ../tooling/android-env.sh
rm -rf build && mkdir -p build/classes build/dex
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias watch -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AgentOS watch debug"
# --debug-mode: install.sh hands the token over with run-as (the watch has no root)
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml --min-sdk-version 30 --target-sdk-version 33 --debug-mode -o build/unsigned.apk
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR:$ANDROID_BUILD_TOOLS/core-lambda-stubs.jar" -d build/classes $(find src -name '*.java')
d8 --release --min-api 30 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android --ks-key-alias watch --out build/watch.apk build/aligned.apk
echo "built $(pwd)/build/watch.apk"
