#!/usr/bin/env bash
#
# Fetches the upstream sources the symbol layout/placement split is a port of.
#
# `symbol/SymbolBucketBuilder.kt` and `symbol/SymbolLayerLayout.kt` port `symbol_layout.ts` and
# `SymbolBucket.populate`; `symbol/Placement.kt` and `symbol/OpacityState.kt` port `placement.ts`;
# `symbol/CollisionDetector.kt` ports `collision_index.ts`; `symbol/CrossTileSymbolIndex.kt` ports
# `cross_tile_symbol_index.ts`; `symbol/SymbolSize.kt` ports `symbol_size.ts`; and
# `symbol/SymbolProjection.kt` ports `path_interpolator.ts` plus the label-path half of
# `projection.ts`. Each names its origin in its KDoc; this drops upstream's copies somewhere they can
# be read side by side, and prints the constants the port carries over so a changed value stands out.
#
# Two of these are here because they were missed the first time and cost a bug. When a new placement
# may *start* is not in `placement.ts` at all -- it is `Style._updatePlacement` in `style.ts`, which
# refuses one while the last is `stillRecent`. And the fade is finished off in
# `symbol_icon.vertex.glsl`, by adding `u_fade_change` to the committed opacity, not by re-running
# placement. `pauseable_placement.ts` is the one piece deliberately not ported: it spreads a
# placement over frames on a 2 ms budget, where this port runs it on a background dispatcher.
#
# There is no local checkout to diff against -- every script here clones and deletes -- so pass an
# out-dir to keep the sources around.
#
# Usage:  library/tools/fetch-symbol-placement.sh [<git-ref>] [<out-dir>]
#
set -euo pipefail

REF="${1:-main}"
OUT="${2:-}"
REPO="https://github.com/maplibre/maplibre-gl-js.git"

PATHS=(
    "src/style/style.ts"
    "src/style/pauseable_placement.ts"
    "src/shaders/glsl/symbol_icon.vertex.glsl"
    "src/symbol/placement.ts"
    "src/symbol/opacity_state.ts"
    "src/symbol/collision_index.ts"
    "src/symbol/cross_tile_symbol_index.ts"
    "src/symbol/symbol_size.ts"
    "src/symbol/symbol_layout.ts"
    "src/symbol/projection.ts"
    "src/symbol/path_interpolator.ts"
    "src/symbol/get_anchors.ts"
    "src/data/bucket/symbol_bucket.ts"
)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SYMBOL="$SCRIPT_DIR/../src/commonMain/kotlin/ovh/plrapps/mapcompose/vector/symbol"
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
echo "When a new placement may start (Style._updatePlacement):"
grep -nE "placementSettled|stillRecent|setStale|_placementInputsChanged" "$WORK/gl/src/style/style.ts" | head -20 || true
echo
echo "How the fade is finished at draw time (u_fade_change):"
grep -nE "u_fade_change|fade_opacity" "$WORK/gl/src/shaders/glsl/symbol_icon.vertex.glsl" || true
echo
echo "Upstream's collision and fade constants:"
grep -nE 'viewportPadding|circleDist|radius \* 2\.5|radius \* 0\.25' \
    "$WORK/gl/src/symbol/collision_index.ts" || true
grep -nE 'roundingFactor|KDBUSH_THRESHHOLD' "$WORK/gl/src/symbol/cross_tile_symbol_index.ts" || true
grep -rnE 'fadeDuration: *[0-9]+|fadeDuration = *[0-9]+' "$WORK/gl/src/ui/map.ts" || true
grep -nE 'SIZE_PACK_FACTOR|MAX_GLYPH_ICON_SIZE|MAX_PACKED_SIZE' "$WORK/gl/src/symbol/symbol_size.ts" || true
grep -nE 'tilePixelRatio = ' "$WORK/gl/src/symbol/symbol_layout.ts" || true
echo
echo "The same constants in this port:"
grep -nE 'VIEWPORT_PADDING|SYMBOL_FADE_DURATION_MS|CIRCLE_DISTANCE_FACTOR|END_PADDING_FACTOR|ROUNDING_FACTOR|LAYOUT_TILE_SIZE: ' \
    "$SYMBOL"/CollisionDetector.kt "$SYMBOL"/Placement.kt "$SYMBOL"/SymbolProjection.kt \
    "$SYMBOL"/CrossTileSymbolIndex.kt "$SYMBOL"/SymbolBucket.kt || true
echo
echo "And this port's scheduling, which is Style._updatePlacement:"
grep -nE 'stillRecent|setStale|isStale|recencyRemainingMs' "$SYMBOL"/Placement.kt || true
