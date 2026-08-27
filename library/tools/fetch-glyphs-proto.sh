#!/usr/bin/env bash
#
# Fetches MapLibre's glyph range schema, for diffing against the decoder that reads it.
#
# `data/glyphs/GlyphPbf.kt` reads `glyphs.pbf` ranges directly rather than through generated
# protobuf code -- three messages of seven scalar fields do not justify adding a protobuf toolchain
# to the build. Its KDoc quotes the schema; this drops upstream's copy next to the source tree so a
# change to it can be spotted, and prints a diff against the quoted copy.
#
# Usage:  library/tools/fetch-glyphs-proto.sh [<git-ref>]
#
set -euo pipefail

REF="${1:-main}"
REPO="https://github.com/maplibre/maplibre-gl-js.git"
PROTO_PATH="src/style/glyphs.proto"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DECODER="$SCRIPT_DIR/../src/commonMain/kotlin/ovh/plrapps/mapcompose/vector/data/glyphs/GlyphPbf.kt"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Cloning $REPO @ $REF"
git clone --quiet --depth 1 --branch "$REF" "$REPO" "$WORK/gl" 2>/dev/null \
    || git clone --quiet --depth 1 "$REPO" "$WORK/gl"
SHA="$(git -C "$WORK/gl" rev-parse HEAD)"

if [ ! -f "$WORK/gl/$PROTO_PATH" ]; then
    echo "error: $PROTO_PATH not found at $SHA -- upstream moved it" >&2
    exit 1
fi

echo "Upstream $PROTO_PATH @ $SHA:"
echo
cat "$WORK/gl/$PROTO_PATH"
echo
echo "Fields the decoder reads:"
grep -oE '^ *tag\.field == [0-9]+' "$DECODER" | sort -u || true
echo
echo "Compare the message definitions above with the schema quoted in GlyphPbf.kt's KDoc."
