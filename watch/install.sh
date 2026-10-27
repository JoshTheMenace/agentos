#!/bin/sh
# Installs build/watch.apk and hands it agentd's token (stdin only, never argv).
# Usage: watch/install.sh <watch-ip:port>   the real watch over Wi-Fi adb (see README); links watch -> Mac -> phone agentd
#        watch/install.sh phone             on the Moto itself (square screen, for testing the link without a watch)
#        watch/install.sh relink <watch>    redo the adb tunnel only (after the watch or phone reconnects)
# Holds the shared device lock while it touches the phone.
set -eu
cd "$(dirname "$0")"
. ../tooling/android-env.sh
S=${ANDROID_SERIAL:-ZY22J3TK8S}; P=local.agentos.watch; TOK=/data/data/com.termux/files/home/.agentos/agentd.token
until mkdir /tmp/agentos-device.lock 2>/dev/null; do echo "device busy (lock held), retrying..."; sleep 5; done
trap 'rmdir /tmp/agentos-device.lock' EXIT
# The tunnel: watch 127.0.0.1:8787 -> (adb reverse) Mac :18787 -> (adb forward) phone 127.0.0.1:8787, where agentd listens.
link() { adb -s "$S" forward tcp:18787 tcp:8787 >/dev/null; adb -s "$1" reverse tcp:8787 tcp:18787 >/dev/null; echo "linked $1 -> phone agentd"; }
case "${1:?usage: install.sh <watch-ip:port> | phone | relink <watch>}" in
  relink) link "${2:?watch serial}"; exit;;
  phone)
    adb -s "$S" install -r build/watch.apk
    uid=$(adb -s "$S" shell pm list packages -U $P | grep "^package:$P " | sed "s/.*uid://" | tr -d '\r'); D=/data/data/$P/files
    adb -s "$S" shell "su -c 'mkdir -p $D && cp $TOK $D/agentd.token && chown -R $uid:$uid $D && chmod 600 $D/agentd.token && restorecon -RD /data/data/$P'"
    adb -s "$S" shell pm grant $P android.permission.POST_NOTIFICATIONS
    adb -s "$S" shell am start -n $P/.Face;;
  *)
    W=$1
    adb -s "$W" install -r build/watch.apk
    adb -s "$S" shell "su -c 'cat $TOK'" | tr -d '\r' | adb -s "$W" shell "run-as $P sh -c 'mkdir -p files && cat > files/agentd.token && chmod 600 files/agentd.token'"
    adb -s "$W" shell pm grant $P android.permission.POST_NOTIFICATIONS || true
    link "$W"
    adb -s "$W" shell am start -n $P/.Face;;
esac
