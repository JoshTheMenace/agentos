#!/bin/sh
# SDK-free APK build (aapt2 -> javac -> D8 -> zipalign -> apksigner), same recipe as demo-apps/build.sh.
# Bundles ../web as assets/web. Output: launcher/build/home.apk
set -eu
cd "$(dirname "$0")"
JDK=${JDK:-/opt/homebrew/opt/openjdk/bin}
T=${AGENTOS_TOOLING:-/Users/joshthemenace/.t3/scratch/2026-10-21-i-have-an-android-phone-178d4212/discovery/tooling}
if [ -d "$T" ]; then  # pinned Google tools, no SDK install
  BT=$T/uilab-dl/bt/android-13; JAR=$T/uilab-dl/pf/android-13/android.jar
  AAPT2=$BT/aapt2; ZIPALIGN=$BT/zipalign; APKSIGNER="$JDK/java --enable-native-access=ALL-UNNAMED -jar $BT/lib/apksigner.jar"
  D8="$JDK/java -cp $T/r8-8.3.37.jar com.android.tools.r8.D8"
else                  # fallback: SDK from tooling/android-env.sh
  . ../tooling/android-env.sh
  JAR=$ANDROID_JAR; AAPT2=aapt2; ZIPALIGN=zipalign; APKSIGNER=apksigner; D8=d8
fi
KS=../demo-apps/debug.keystore
[ -f $KS ] || "$JDK/keytool" -genkeypair -keystore $KS -storepass android -keypass android -alias demo -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AgentOS demo apps"
rm -rf build; mkdir -p build/classes build/dex build/assets
cp -R ../web build/assets/web
# Wake word engines (Wake.java): Porcupine + Vosk jars, arm64 .so files and Porcupine's model/keywords from ~/.cache/agentos-wake
# (wake.sh fetch, tried once here). Missing = the launcher still builds; the toggle then reads "Needs a Picovoice key".
W=${AGENTOS_WAKE_CACHE:-$HOME/.cache/agentos-wake}; [ -d "$W/jar" ] || ./wake.sh fetch >/dev/null 2>&1 || true
CP=$JAR; SRC=$(find src -name '*.java')
if [ -f "$W/jar/pv.jar" ] && [ -f "$W/jar/vosk.jar" ] && [ -f "$W/jar/jna.jar" ]; then
  CP=$JAR:$W/jar/pv.jar:$W/jar/vosk.jar:$W/jar/jna.jar; SRC="$SRC $(find src-wake -name '*.java')"
  mkdir -p build/assets/wake build/lib && cp "$W"/assets/* build/assets/wake/ && cp -R "$W/lib/arm64-v8a" build/lib/
fi
"$AAPT2" link -I "$JAR" --manifest AndroidManifest.xml -A build/assets --min-sdk-version 33 --target-sdk-version 33 -o build/unsigned.apk
"$JDK/javac" --release 8 -Xlint:-options -cp "$CP" -d build/classes $SRC
JARS=; [ "$CP" = "$JAR" ] || JARS="$W/jar/pv.jar $W/jar/vosk.jar $W/jar/jna.jar"
$D8 --release --min-api 33 --lib "$JAR" --output build/dex $(find build/classes -name '*.class') $JARS
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
[ ! -d build/lib ] || (cd build && zip -qr unsigned.apk lib)
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk
$APKSIGNER sign --ks $KS --ks-pass pass:android --ks-key-alias demo --out build/home.apk build/aligned.apk
echo "built $PWD/build/home.apk"
