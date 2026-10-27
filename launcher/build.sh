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
"$AAPT2" link -I "$JAR" --manifest AndroidManifest.xml -A build/assets --min-sdk-version 33 --target-sdk-version 33 -o build/unsigned.apk
"$JDK/javac" --release 8 -Xlint:-options -cp "$JAR" -d build/classes $(find src -name '*.java')
$D8 --release --min-api 33 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk
$APKSIGNER sign --ks $KS --ks-pass pass:android --ks-key-alias demo --out build/home.apk build/aligned.apk
echo "built $PWD/build/home.apk"
