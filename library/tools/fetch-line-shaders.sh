#!/usr/bin/env bash
#
# Fetches the upstream sources the line mesh is a port of, for diffing against a future bump.
#
# `renderer/utils/LineShading.kt` ports the two line shaders, `renderer/utils/LineTessellation.kt`
# ports `line_bucket.ts`, and `renderer/utils/LineDash.kt` ports `LineAtlas.getDashRanges`. Each
# quotes the part of upstream it follows in its KDoc; this drops upstream's copies somewhere they can
# be read side by side, and prints the constants the port carries over so a changed value stands out.
#
# Usage:  library/tools/fetch-line-shaders.sh [<git-ref>] [<out-dir>]
#
set -euo pipefail

REF="${1:-main}"
OUT="${2:-}"
REPO="https://github.com/maplibre/maplibre-gl-js.git"

PATHS=(
    "src/shaders/glsl/line.vertex.glsl"
    "src/shaders/glsl/line.fragment.glsl"
    "src/data/bucket/line_bucket.ts"
    "src/render/line_atlas.ts"
    "src/util/color_ramp.ts"
)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UTILS="$SCRIPT_DIR/../src/commonMain/kotlin/ovh/plrapps/mapcompose/vector/renderer/utils"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Cloning $REPO @ $REF"
git clone --quiet --depth 1 --branch "$REF" "$REPO" "$WORK/gl" 2>/dev/null \
    || git clone --quiet --depth 1 "$REPO" "$WORK/gl"
SHA="$(git -C "$WORK/gl" rev-parse HEAD)"

if [ -z "$OUT" ]; then
    OUT="$WORK/out"
fi
mkdir -p "$OUT"

for path in "${PATHS[@]}"; do
    if [ ! -f "$WORK/gl/$path" ]; then
        echo "error: $path not found at $SHA -- upstream moved it" >&2
        exit 1
    fi
    cp "$WORK/gl/$path" "$OUT/$(basename "$path")"
done

echo "Upstream sources @ $SHA copied to $OUT:"
ls -1 "$OUT"
echo
echo "Constants the port carries over:"
grep -nE 'COS_HALF_SHARP_CORNER|SHARP_CORNER_OFFSET|DEG_PER_TRIANGLE|EXTRUDE_SCALE' \
    "$WORK/gl/src/data/bucket/line_bucket.ts" || true
echo
grep -nE 'COS_HALF_SHARP_CORNER|SHARP_CORNER_OFFSET|DEG_PER_TRIANGLE|GRADIENT_RAMP_RESOLUTION' \
    "$UTILS/LineTessellation.kt" || true
echo
echo "The alpha ramp LineShading.kt reproduces:"
grep -nE 'ANTIALIASING|inset|outset|blur2|v_width2' "$WORK/gl/src/shaders/glsl/line.vertex.glsl" \
    "$WORK/gl/src/shaders/glsl/line.fragment.glsl" || true
