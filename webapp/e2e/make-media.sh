#!/bin/bash
# The small test files the end to end tests play (4 s, 320 x 180), one per way a file plays:
# native, repacked, sound converted, and one that needs the app. Run again after changing a line.
set -e
cd "$(dirname "$0")/media"
SRC="-f lavfi -i testsrc2=size=320x180:rate=25:duration=4 -f lavfi -i sine=frequency=440:duration=4:sample_rate=48000"
H264="-c:v libx264 -preset veryfast -b:v 200k -g 25 -pix_fmt yuv420p"
q="-hide_banner -loglevel error -y"
out="-fflags +bitexact -map_metadata -1"
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a aac -b:a 64k -movflags +faststart h264-aac.mp4
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a aac -b:a 64k tail-index.mp4
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a aac -b:a 64k h264-aac.mkv
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a aac -b:a 64k h264-aac.ts
ffmpeg $q $SRC -map 0 -map 1 $out $H264 -c:a ac3 -b:a 96k h264-ac3.mkv
ffmpeg $q $SRC -map 0 -map 1 $out -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 200k -g 25 -c:a libopus -b:a 48k vp9-opus.webm
ffmpeg $q $SRC -map 0 -map 1 $out -c:v mpeg4 -b:v 200k -g 25 -c:a ac3 -b:a 96k mpeg4.avi
ffmpeg $q -f lavfi -i testsrc2=size=160x240:rate=1 -frames:v 1 -q:v 6 $out thumb.jpg
