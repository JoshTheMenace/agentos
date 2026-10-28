#!/bin/sh
# Build only. Run through scripts/withphone; no production manifest or debug flag is changed.
set -eu
cd "$(dirname "$0")/.."
. ../tooling/android-env.sh
D=/tmp/agentos-mod-tests
mkdir -p "$D/classes" "$D/dex"
cat > "$D/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.agentos.modtests">
<application android:label="AgentOS mod tests" android:theme="@android:style/Theme.Material.NoActionBar">
<activity android:name="local.agentos.ModFixture" android:exported="true"/>
</application>
<instrumentation android:name="local.agentos.ModTests" android:targetPackage="local.agentos"/>
</manifest>
XML
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR:$ANDROID_BUILD_TOOLS/core-lambda-stubs.jar" -cp build/classes -d "$D/classes" mod-tests/ModTests.java mod-tests/ModFixture.java
d8 --min-api 33 --lib "$ANDROID_JAR" --classpath build/classes --output "$D/dex" $(find "$D/classes" -name '*.class')
aapt2 link -I "$ANDROID_JAR" --manifest "$D/AndroidManifest.xml" --min-sdk-version 33 --target-sdk-version 33 -o "$D/unsigned.apk"
(cd "$D/dex" && zip -q "$D/unsigned.apk" classes.dex)
zipalign -f 4 "$D/unsigned.apk" "$D/aligned.apk"
apksigner sign --ks debug.keystore --ks-pass pass:android --ks-key-alias agentos --out "$D/tests.apk" "$D/aligned.apk"
