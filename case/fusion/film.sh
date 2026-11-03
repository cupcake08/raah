#!/bin/bash
# Cut the ray-traced renders into a 720p showcase film and a looping turntable.
#   film.sh <stills dir> <turntable frames dir> [out dir, default case/renders]
# stills dir: hero, face, three_quarter, exploded, inside and back as transparent PNGs (raah_render.py start).
# frames dir: turn_0000.png ... (raah_render.py turntable).
# Each still gets a slow push-in; shots cross-fade. Writes raah_showcase.mp4, raah_turntable_hq.mp4 and .gif.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
stills="$1"; frames="$2"
out="${3:-$here/../renders}"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
W=1280; H=720; FPS=24; XF=0.6

"$here/backdrop.sh" "$frames/turn_%04d.png" "$out/raah_turntable_hq.mp4" ${W}x${H}
ffmpeg -loglevel error -y -i "$out/raah_turntable_hq.mp4" -filter_complex \
  "fps=12,scale=640:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
  "$out/raah_turntable_hq.gif"

# shot list: name and seconds on screen ("turn" is the turntable clip, as long as its frames run)
turn=$(echo "scale=3; $(ls "$frames"/turn_*.png | wc -l) / $FPS" | bc)
shots=(hero:3.5 face:3 three_quarter:3 turn:$turn exploded:3.5 inside:3 back:3.5)
inputs=(); i=0
for s in "${shots[@]}"; do
  name=${s%:*}; sec=${s#*:}
  if [ "$name" = turn ]; then
    inputs+=(-i "$out/raah_turntable_hq.mp4")
  else
    # portrait and square shots get a margin so the push-in doesn't crowd the frame edge
    ffmpeg -loglevel error -y -i "$stills/$name.png" -vf \
      "pad='if(gte(ih,iw),iw*1.2,iw)':'if(gte(ih,iw),ih*1.2,ih)':(ow-iw)/2:(oh-ih)/2:color=0x00000000" "$tmp/${name}_in.png"
    "$here/backdrop.sh" "$tmp/${name}_in.png" "$tmp/$name.png" $(( W*2 ))x$(( H*2 ))
    n=$(echo "$sec*$FPS" | bc | cut -d. -f1)
    ffmpeg -loglevel error -y -loop 1 -i "$tmp/$name.png" -vf \
      "zoompan=z='1+0.06*on/$n':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=$n:s=${W}x${H}:fps=$FPS,format=yuv420p" \
      -frames:v "$n" -c:v libx264 -crf 12 -preset fast "$tmp/$i.mp4"
    inputs+=(-i "$tmp/$i.mp4")
  fi
  i=$(( i + 1 ))
done

# chain the cross-fades: each one starts XF seconds before the running total ends
graph=""; t=0
for k in $(seq 0 $(( ${#shots[@]} - 1 ))); do
  graph+="[$k:v]fps=$FPS,settb=AVTB,format=yuv420p[s$k];"
done
prev="[s0]"
for k in $(seq 1 $(( ${#shots[@]} - 1 ))); do
  t=$(echo "$t + ${shots[$((k-1))]#*:} - $XF" | bc)
  graph+="$prev[s$k]xfade=transition=fade:duration=$XF:offset=$t[v$k];"
  prev="[v$k]"
done
end=$(echo "$t + ${shots[${#shots[@]}-1]#*:} - 0.6" | bc)
ffmpeg -loglevel error -y "${inputs[@]}" -filter_complex \
  "${graph}${prev}fade=t=in:d=0.5:color=0xF6F5F1,fade=t=out:st=$end:d=0.6:color=0xF6F5F1,format=yuv420p[out]" \
  -map "[out]" -c:v libx264 -crf 17 -preset slow -movflags +faststart "$out/raah_showcase.mp4"
ls -la "$out"/raah_showcase.mp4 "$out"/raah_turntable_hq.*
