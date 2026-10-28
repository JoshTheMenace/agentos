#!/bin/sh
# SDK-free APK build (aapt2 -> javac -> D8 -> zipalign -> apksigner), copied from systemui-lab/app/build.sh.
# Usage: demo-apps/build.sh pigeon|beacon   -> demo-apps/<app>/build/<app>.apk
set -eu
cd "$(dirname "$0")"
APP=${1:?app name}
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
[ -f debug.keystore ] || "$JDK/keytool" -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias demo -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AgentOS demo apps"
cd "$APP"; rm -rf build; mkdir -p build/gen build/classes build/dex
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 33 \
  --java build/gen -o build/unsigned.apk build/res.zip
[ "$APP" = pigeon ] && SHARED=../../android/src/local/agentos/deep || SHARED=
"$JDK/javac" --release 8 -Xlint:-options -cp "$JAR" -d build/classes $(find src build/gen ${SHARED:-} -name '*.java')
$D8 --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk
$APKSIGNER sign --ks ../debug.keystore --ks-pass pass:android --ks-key-alias demo --out "build/$APP.apk" build/aligned.apk
echo "built $PWD/build/$APP.apk"
