#!/bin/bash
# Put transparent renders on the soft studio backdrop with a drop shadow.
#   backdrop.sh <in.png> <out.png|.jpg> [WxH canvas, default the render's own size]
#   backdrop.sh <frames/turn_%04d.png> <out.mp4> [WxH]      a frame sequence, 24 fps, lightly denoised
# A render smaller than the canvas, or of another shape, is fitted and centred. The backdrop
# is one still frame held under the whole sequence, so its grain does not crawl.
set -euo pipefail
in="$1"; out="$2"
case "$in" in *%*) first=$(printf "$in" 0); seq=1 ;; *) first="$in"; seq=0 ;; esac
if [ -n "${3:-}" ]; then
  w=${3%x*}; h=${3#*x}
else
  read -r w h < <(ffprobe -v error -show_entries stream=width,height -of csv=p=0 "$first" | tr , ' ')
fi
sig=$(( w / 90 )); off=$(( h / 45 ))
bg="gradients=s=${w}x${h}:c0=0xF6F5F1:c1=0xD7D4CC:x0=$(( w / 2 )):y0=$(( h * 42 / 100 )):x1=0:y1=0:type=radial:speed=0:r=24:d=1"
if [ $seq = 1 ]; then
  src=(-framerate 24 -i "$in"); fin="hqdn3d=1.5:1.5:3:3,format=yuv420p"
  enc=(-c:v libx264 -crf 16 -preset slow -movflags +faststart)
else
  src=(-i "$in")
  case "$out" in *.jpg) fin=yuvj420p; enc=(-frames:v 1 -q:v 1) ;; *) fin=rgb24; enc=(-frames:v 1) ;; esac
  fin="format=$fin"
fi
ffmpeg -loglevel error -y "${src[@]}" -f lavfi -i "$bg" -filter_complex "
  [0:v]format=rgba,scale=w=$w:h=$h:force_original_aspect_ratio=decrease:flags=lanczos,
       pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=0x00000000,split[fg][s];
  [s]lutrgb=r=88:g=82:b=74:a='val*0.30',gblur=sigma=$sig[sh];
  [1:v]noise=alls=3:allf=u,format=rgba,loop=loop=-1:size=1[b];
  [b][sh]overlay=0:$off:shortest=1[b2];
  [b2][fg]overlay=0:0,$fin" "${enc[@]}" "$out"
