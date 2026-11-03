#!/usr/bin/env bash
# Regenerates the LVGL bitmap fonts in ui/fonts from the Barlow family (SIL OFL 1.1).
# Needs node/npx and network access. Run from anywhere.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../ui/fonts"
TTF_DIR="${TTF_DIR:-$(mktemp -d)}"
GF=https://raw.githubusercontent.com/google/fonts/main/ofl
CONV="npx --yes lv_font_conv@1.5.3"

mkdir -p "$OUT"
for f in barlowsemicondensed/BarlowSemiCondensed-Bold.ttf \
         barlowsemicondensed/BarlowSemiCondensed-SemiBold.ttf \
         barlowsemicondensed/BarlowSemiCondensed-Medium.ttf \
         barlow/Barlow-SemiBold.ttf; do
  [ -f "$TTF_DIR/$(basename "$f")" ] || curl -fsSL "$GF/$f" -o "$TTF_DIR/$(basename "$f")"
done

ASCII=0x20-0x7E
LATIN1=0xA0-0xFF
PUNCT=0x2013,0x2014,0x2018,0x2019,0x201C,0x201D,0x2022,0x2026
DIGITS="0123456789.,:-"

gen() { # name ttf size range-args...
  local name=$1 ttf=$2 size=$3; shift 3
  $CONV --no-compress --bpp 4 --format lvgl --size "$size" \
        --font "$TTF_DIR/$ttf" "$@" --lv-font-name "$name" -o "$OUT/$name.c"
  echo "  $name"
}

echo "Generating fonts into $OUT"
gen font_num_96   BarlowSemiCondensed-Bold.ttf      96 --symbols "$DIGITS"
gen font_num_64   BarlowSemiCondensed-Bold.ttf      64 --symbols "$DIGITS"
gen font_num_44   BarlowSemiCondensed-Bold.ttf      44 --symbols "$DIGITS"
gen font_bold_28  BarlowSemiCondensed-Bold.ttf      28 -r $ASCII -r $LATIN1 -r $PUNCT
gen font_semi_26  BarlowSemiCondensed-SemiBold.ttf  26 -r $ASCII -r $LATIN1 -r $PUNCT
gen font_semi_22  BarlowSemiCondensed-SemiBold.ttf  22 -r $ASCII -r $LATIN1 -r $PUNCT
gen font_med_18   BarlowSemiCondensed-Medium.ttf    18 -r $ASCII -r $LATIN1 -r $PUNCT
gen font_label_12 Barlow-SemiBold.ttf               12 -r $ASCII -r 0xB0,0xB7
