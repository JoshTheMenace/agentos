#!/bin/sh
# Record one transition: rec.sh <name> <seconds> '<shell commands run on the Mac while recording>'
# Saves <name>.mp4 here (adb screenrecord, pulled, deleted from the phone). Run inside scripts/withphone.
set -u; cd "$(dirname "$0")"; S=${ANDROID_SERIAL:-ZY22J3TK8S}; n=$1; secs=$2; act=$3
adb -s "$S" shell "screenrecord --time-limit $secs --bit-rate 6M /sdcard/pn3-$n.mp4" & R=$!
sleep 1.2; sh -c "$act"; wait $R
adb -s "$S" pull "/sdcard/pn3-$n.mp4" "$n.mp4" >/dev/null && adb -s "$S" shell rm -f "/sdcard/pn3-$n.mp4"
echo "$n.mp4 $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$n.mp4") s"
