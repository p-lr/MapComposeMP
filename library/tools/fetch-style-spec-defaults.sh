#!/usr/bin/env bash
#
# Vendors the MapLibre style spec's property table into a test resource.
#
# `StyleSpecDefaults` in the main source set hardcodes the spec defaults that the layer painters
# fall back to. This bundles upstream's `src/reference/v8.json` down to just what that object
# claims -- per property: type, default and units -- so `StyleSpecDefaultsTest` can assert the two
# agree and a spec bump shows up as a test failure rather than a silent rendering difference.
#
# Usage:  library/tools/fetch-style-spec-defaults.sh [<git-ref>]
#
set -euo pipefail

REF="${1:-d881eef3be6a3602ff3434e1d9a20067b3fe1a31}"
REPO="https://github.com/maplibre/maplibre-style-spec.git"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/../src/commonTest/composeResources/files"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Cloning $REPO @ $REF"
git clone --quiet --depth 1 "$REPO" "$WORK/mls"
git -C "$WORK/mls" fetch --quiet --depth 1 origin "$REF" 2>/dev/null || true
git -C "$WORK/mls" checkout --quiet "$REF" 2>/dev/null || echo "  (using default branch; $REF not fetchable as a ref)"

SHA="$(git -C "$WORK/mls" rev-parse HEAD)"

python3 "$SCRIPT_DIR/extract-style-spec-defaults.py" \
    "$WORK/mls/src/reference/v8.json" "$OUT_DIR/style-spec-defaults.json" "$SHA"

cp "$WORK/mls/LICENSE.txt" "$OUT_DIR/style-spec-defaults-LICENSE.txt"
echo "Copied upstream LICENSE (BSD-3-Clause) alongside the defaults"
