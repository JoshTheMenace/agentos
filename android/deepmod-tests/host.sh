#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
. tooling/android-env.sh
D=/tmp/agentos-deepmod-host
mkdir -p "$D/classes"
# Test-only JSON implementation; Android supplies org.json on the device.
[ -f "$D/json.jar" ] || curl -fsSL https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$D/json.jar"
javac -cp "$D/json.jar:$ANDROID_JAR" -d "$D/classes" android/src/local/agentos/deep/Contract.java android/deepmod-tests/ContractTest.java
java -cp "$D/classes:$D/json.jar:$ANDROID_JAR" ContractTest
[ -f android/build/classes/local/agentos/Broker.class ] || { echo 'Build android/ first for the production policy checks.' >&2; exit 1; }
javac -cp "$D/json.jar:$ANDROID_JAR:android/build/classes" -d "$D/classes" android/deepmod-tests/PolicyTest.java
java -cp "$D/classes:$D/json.jar:android/build/classes:$ANDROID_JAR" local.agentos.PolicyTest
