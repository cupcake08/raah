#!/bin/bash
# Stitch the frames rendered by raah_anim.py into MP4 and GIF, on a light background.
#   stitch.sh <frames dir> [out dir, default case/renders]
set -euo pipefail
frames="$1"
out="${2:-$(cd "$(dirname "$0")/../renders" && pwd)}"
bg="color=c=0xEDECE7:s=1280x720:r=30"
for kind in assembly turntable; do
  ffmpeg -loglevel error -y -f lavfi -i "$bg" -framerate 30 -i "$frames/${kind}_%04d.png" \
    -filter_complex "[0][1]overlay=shortest=1,format=yuv420p" \
    -c:v libx264 -crf 18 -preset slow -movflags +faststart "$out/raah_$kind.mp4"
  ffmpeg -loglevel error -y -f lavfi -i "$bg" -framerate 30 -i "$frames/${kind}_%04d.png" \
    -filter_complex "[0][1]overlay=shortest=1,fps=15,scale=640:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
    "$out/raah_$kind.gif"
done
ls -la "$out"/raah_*.mp4 "$out"/raah_*.gif
