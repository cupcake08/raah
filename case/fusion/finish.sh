#!/bin/bash
# Put the transparent stills from raah_render.py on the studio backdrop (backdrop.sh).
#   finish.sh <png dir> [out dir, default case/renders/hq]
# Writes <name>.jpg for sharing and keeps the cut-out as <name>_cutout.png.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$1"
out="${2:-$here/../renders/hq}"
mkdir -p "$out"
for f in "$src"/*.png; do
  name=$(basename "$f" .png)
  "$here/backdrop.sh" "$f" "$out/$name.jpg"
  cp "$f" "$out/${name}_cutout.png"
done
ls -la "$out"
