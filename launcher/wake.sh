#!/bin/sh
# Wake word helper (launcher/README.md "Wake word"). Phone steps hold the shared lock (scripts/withphone).
#   launcher/wake.sh fetch              Porcupine 4.0.2 + Vosk 0.3.75 + JNA (Maven Central) and the Vosk model -> ~/.cache/agentos-wake
#   launcher/wake.sh key                paste the Picovoice AccessKey (silent prompt, or stdin) -> phone, root-only + app-private
#   launcher/wake.sh train "hey agent"  have Picovoice train a keyword from text (uses the key) -> files/wake.ppn
#   launcher/wake.sh vosk [off]         key-free fallback "Hey Agent": push the model + set the flag (off removes both)
#   launcher/wake.sh status             engine files, service, last log lines, CPU of the home process
#   launcher/wake.sh cpu [60]           CPU % of the home process and its wake thread over N seconds (read-only)
set -eu
cd "$(dirname "$0")"
S=${ANDROID_SERIAL:-ZY22J3TK8S}; P=local.agentos.home; D=/data/data/$P/files
C=${AGENTOS_WAKE_CACHE:-$HOME/.cache/agentos-wake}; M=https://repo1.maven.org/maven2
sh_() { adb -s "$S" shell "su -c '$1'"; }
own() { echo "chown -R \$(stat -c %u /data/data/$P):\$(stat -c %g /data/data/$P) $1 && restorecon -RD $1"; }
restart() { adb -s "$S" shell am force-stop $P; adb -s "$S" shell am start -n $P/.Home >/dev/null; }
askkey() { printf 'Picovoice AccessKey: ' >&2; stty -echo 2>/dev/null || true; read -r K; stty echo 2>/dev/null || true; echo >&2; [ -n "$K" ] || { echo "no key" >&2; exit 1; }; }
case "${1:-status}" in
  fetch)
    mkdir -p "$C/lib/arm64-v8a" "$C/jar" "$C/assets" && cd "$C"
    get() { [ -s "$2" ] || curl -sfL --max-time 120 -o "$2" "$M/$1"; }
    get ai/picovoice/porcupine-android/4.0.2/porcupine-android-4.0.2.aar pv.aar
    get com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar vosk.aar
    get net/java/dev/jna/jna/5.19.1/jna-5.19.1.aar jna.aar
    for a in pv vosk jna; do rm -rf x && mkdir x && (cd x && unzip -q ../$a.aar) && cp x/classes.jar jar/$a.jar && cp x/jni/arm64-v8a/*.so lib/arm64-v8a/
      if [ $a = pv ]; then cp x/res/raw/*.ppn x/res/raw/porcupine_params.pv assets/; fi; done; rm -rf x
    [ -d vosk-model-small-en-us-0.15 ] || { curl -sfL --max-time 600 -o vm.zip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip && unzip -q vm.zip && rm vm.zip; }
    echo "cached in $C";;
  key)   askkey; printf %s "$K" | ../scripts/withphone ./wake.sh _key; echo "key installed: Command center › Hey Agent";;
  _key)  sh_ "umask 077; mkdir -p /data/adb/agentos && cat > /data/adb/agentos/porcupine.key && cp /data/adb/agentos/porcupine.key $D/porcupine.key && $(own $D/porcupine.key)"; restart;;
  train)
    askkey; T=$(mktemp)
    curl -sf --max-time 120 -X POST https://rest.picovoice.ai/en/api/ppn -H "x-api-key: $K" -H 'Content-Type: application/json' \
      -d "{\"platform\":\"android\",\"phrase\":\"${2:-hey agent}\"}" -o "$T" || { rm -f "$T"; echo "training failed: use the Picovoice Console (README)" >&2; exit 1; }
    ../scripts/withphone ./wake.sh _ppn "$T" "${2:-hey agent}"; rm -f "$T"; echo "trained \"${2:-hey agent}\"";;
  _ppn)  adb -s "$S" push "$2" /data/local/tmp/wake.ppn >/dev/null; sh_ "mv /data/local/tmp/wake.ppn $D/wake.ppn && echo \"$3\" > $D/wake.phrase && $(own "$D/wake.ppn $D/wake.phrase")"; restart;;
  vosk)
    if [ "${2:-}" = off ]; then ../scripts/withphone sh -c "adb -s $S shell \"su -c 'rm -rf $D/wake.vosk $D/vosk'\""; echo "fallback off"; exit; fi
    [ -d "$C/vosk-model-small-en-us-0.15" ] || ./wake.sh fetch
    ../scripts/withphone ./wake.sh _vosk; echo "fallback on: Command center › Hey Agent";;
  _vosk) adb -s "$S" push "$C/vosk-model-small-en-us-0.15" /data/local/tmp/vosk >/dev/null
         sh_ "rm -rf $D/vosk && mv /data/local/tmp/vosk $D/vosk && touch $D/wake.vosk && $(own "$D/vosk $D/wake.vosk")"; restart;;
  status)
    sh_ "ls $D | grep -E \"porcupine.key|wake|vosk\"; dumpsys activity services $P/.Wake | grep -E \"isForeground|startRequested\"; logcat -d -s AgentOSWake | tail -5; top -b -n 1 -p \$(pidof $P) | tail -2";;
  cpu)  # CPU of the home process and its "wake" thread over N s (default 60), in % of one core (100 ticks/s)
    adb -s "$S" shell "su -c 'sh -s ${2:-60}'" <<'EOF'
p=$(pidof local.agentos.home); W=; for t in /proc/$p/task/*; do [ "$(cat $t/comm)" = wake ] && W=$t; done
j() { [ -n "$1" ] && awk '{print $14+$15}' $1/stat || echo 0; }
a=$(j /proc/$p); b=$(j "$W"); sleep $1; c=$(j /proc/$p); d=$(j "$W")
awk -v x=$((c-a)) -v y=$((d-b)) -v n=$1 'BEGIN{printf "home process %.1f%%, wake thread %.1f%% of one core over %d s\n", x/n, y/n, n}'
EOF
    ;;
  *) sed -n 2,8p wake.sh; exit 1;;
esac
