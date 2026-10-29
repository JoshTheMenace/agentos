# Recording helper for record.sh scripts. Source it after setting A (the adb command) and ST (a scratch dir):  . ../../kit/rec.sh
# rec N / stop N record raw/take-N.mp4; tap X Y and swipe X1 Y1 X2 Y2 [MS] drive the phone AND log the touch with its time since the
# recording started into take-N.taps (next to the script, kept in git), so render.mjs's camera can zoom toward each one.
# Times are "when adb returned, minus REC_LAG" (screenrecord's own start-up, default 0.3 s). Check the first take against the footage
# (the screen reacts within a frame or two of the tap) and fix any constant offset with "tapShift" in edit.json.
now() { perl -MTime::HiRes=time -e 'printf "%.3f", time'; }
rec() { $A shell "screenrecord --time-limit 180 --bit-rate 12000000 /sdcard/soc-$1.mp4" & echo $! > "$ST/rec.pid"; T0=$(now); TAPS=take-$1.taps; : > "$TAPS"; sleep 1.5; }
stop() { $A shell pkill -INT screenrecord || true; wait "$(cat "$ST/rec.pid")" 2>/dev/null || sleep 2; sleep 1
  $A pull "/sdcard/soc-$1.mp4" "raw/take-$1.mp4" >/dev/null && $A shell rm -f "/sdcard/soc-$1.mp4"; echo "raw/take-$1.mp4 + $TAPS ($(wc -l < "$TAPS" | tr -d ' ') touches)"; }
log() { printf '%s %s %s\n' "$1" "$(perl -e "printf '%.2f', $(now) - ${T0:-0} - ${REC_LAG:-0.3}")" "$2" >> "${TAPS:-/dev/null}"; }
tap() { $A shell input tap "$1" "$2"; log tap "$1 $2"; }
swipe() { $A shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"; log swipe "$1 $2 $3 $4 ${5:-300}"; }
