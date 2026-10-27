#!/bin/sh
# Builds build/voicespike.apk (javac + D8 + aapt2, same recipe as android/build.sh). No resources.
set -eu
cd "$(dirname "$0")"
. ../../../tooling/android-env.sh
rm -rf build && mkdir -p build/classes build/dex
keytool -genkeypair -keystore build/debug.keystore -storepass android -keypass android -alias v -keyalg RSA \
  -keysize 2048 -validity 10000 -dname "CN=AgentOS voice spike" 2>/dev/null
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml --min-sdk-version 33 --target-sdk-version 33 -o build/unsigned.apk
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d build/classes $(find src -name '*.java')
d8 --release --min-api 33 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks build/debug.keystore --ks-pass pass:android --ks-key-alias v --out build/voicespike.apk build/aligned.apk
echo "built $(pwd)/build/voicespike.apk"
