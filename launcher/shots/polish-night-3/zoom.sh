#!/bin/sh
# Zoomed contact sheet of part of the screen: zoom.sh <name> <out> <fps> <crop w:h:x:y> <scale-w> <cols> [start end]
set -eu; cd "$(dirname "$0")"; n=$1; o=$2; fps=$3; crop=$4; sw=$5; cols=$6; ss=${7:-0}; to=${8:-99}
d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$n.mp4"); end=$(python3 -c "print(min($d,$to))")
rows=$(python3 -c "import math;print(max(1,math.ceil(($end-$ss)*$fps/$cols)))")
ffmpeg -v error -y -ss "$ss" -to "$end" -i "$n.mp4" -vf "fps=$fps,crop=$crop,scale=$sw:-1,tile=${cols}x${rows}:padding=2:color=0x202020" -frames:v 1 -update 1 "$o.png"
echo "$o.png"
