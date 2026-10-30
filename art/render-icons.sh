#!/usr/bin/env bash
# Renders art/icon.svg into the adaptive launcher foreground at every density.
# Needs rsvg-convert (librsvg). Run from the repo root: ./art/render-icons.sh
set -euo pipefail

res=app/src/main/res
tmp=$(mktemp --suffix=.svg)
trap 'rm -f "$tmp"' EXIT

# The foreground layer is 108dp; keep the art inside the ~66dp the launcher mask shows.
{
	echo '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
	echo '<g transform="translate(19 19) scale(0.1367)">'
	sed -e '1d' -e '$d' art/icon.svg
	echo '</g></svg>'
} > "$tmp"

for pair in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
	dpi=${pair%%:*}
	px=${pair##*:}
	mkdir -p "$res/mipmap-$dpi"
	rsvg-convert -w "$px" -h "$px" "$tmp" -o "$res/mipmap-$dpi/ic_launcher_foreground.png"
done
