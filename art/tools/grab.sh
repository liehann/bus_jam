#!/bin/bash
# grab.sh <dest>: wait (up to 30 s) for a Gemini download in ~/Downloads newer than the marker, move it to <dest>
# and refresh the marker. Only files newer than the marker move, so older downloads stay where they are.
# Run `grab.sh --reset` once before the first download of a session to set the marker to now.
M="${TMPDIR:-/tmp}/critter_grab_marker"
if [ "$1" = "--reset" ] || [ ! -e "$M" ]; then touch "$M"; [ "$1" = "--reset" ] && exit 0; fi
for i in $(seq 1 30); do
  f=$(find ~/Downloads -maxdepth 1 -name 'Gemini_Generated_Image_*' -newer "$M" | head -1)
  if [ -n "$f" ] && [ ! -e "$f.crdownload" ]; then sleep 1; mv "$f" "$1"; touch "$M"; echo "moved $f -> $1"; exit 0; fi
  sleep 1
done
echo "no new download"; exit 1
