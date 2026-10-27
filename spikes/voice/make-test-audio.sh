#!/bin/sh
# Builds out/test.wav for Chrome's fake mic: 1 s silence, question 1, 20 s silence, question 2, 30 s silence.
set -e; cd "$(dirname "$0")"; mkdir -p out
say -o out/q1.aiff "Hi there. What is the capital of France? Answer in one word."
say -o out/q2.aiff "What is my phone battery level right now?"
f="-f lavfi -t"; s="anullsrc=r=48000:cl=mono"
ffmpeg -loglevel error -y $f 1 -i $s -i out/q1.aiff $f 20 -i $s -i out/q2.aiff $f 30 -i $s \
  -filter_complex "[0][1][2][3][4]concat=n=5:v=0:a=1,aresample=48000,aformat=sample_fmts=s16:channel_layouts=mono" out/test.wav
echo out/test.wav
