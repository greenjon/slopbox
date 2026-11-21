#!/bin/sh
# Rockbox's codepage tables (iso.cp, 932.cp, 936.cp, 949.cp, 950.cp), made by
# Rockbox's own tools/codepages, for the engine's tag reading (Default Codepage).
# Usage: make_codepages.sh <output dir>
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
rb="$here/third_party/rockbox/tools"
out="$1"
mkdir -p "$out"
tmp=$(mktemp -d)
cc -O2 -o "$tmp/codepages" "$rb/codepages.c" "$rb/codepage_tables.c"
(cd "$out" && "$tmp/codepages")
rm -rf "$tmp"
