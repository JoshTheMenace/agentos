#!/bin/sh
# Contact sheet of a recording: sheet.sh <name> [fps] [cols]  ->  <name>-frames.png (frame-by-frame review in one image)
set -eu; cd "$(dirname "$0")"; n=$1; fps=${2:-8}; cols=${3:-6}
d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$n.mp4"); rows=$(python3 -c "import math;print(math.ceil($d*$fps/$cols))")
ffmpeg -v error -y -i "$n.mp4" -vf "fps=$fps,scale=180:-1,tile=${cols}x${rows}:padding=2:color=0x202020" -frames:v 1 -update 1 "$n-frames.png"
echo "$n-frames.png ($d s, $fps fps, ${cols}x$rows)"
