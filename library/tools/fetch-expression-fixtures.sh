#!/usr/bin/env bash
#
# Vendors MapLibre's expression conformance suite into a single bundled test resource.
#
# The upstream suite is 577 tiny `test.json` files. Compose resources are read one path at a time,
# so they are flattened into one JSON object keyed "<operator>/<case>" -- one resource read instead
# of 577, and no generated path list to keep in sync.
#
# Usage:  library/tools/fetch-expression-fixtures.sh [<git-ref>]
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

python3 - "$WORK/mls" "$OUT_DIR" "$SHA" <<'PY'
import json, os, sys

root, out_dir, sha = sys.argv[1], sys.argv[2], sys.argv[3]
tests_dir = os.path.join(root, "test/integration/expression/tests")

bundle = {}
for dirpath, _, filenames in os.walk(tests_dir):
    if "test.json" not in filenames:
        continue
    key = os.path.relpath(dirpath, tests_dir)
    with open(os.path.join(dirpath, "test.json")) as f:
        bundle[key] = json.load(f)

os.makedirs(out_dir, exist_ok=True)
payload = {"upstreamCommit": sha, "tests": dict(sorted(bundle.items()))}
with open(os.path.join(out_dir, "expression-tests.json"), "w") as f:
    json.dump(payload, f, separators=(",", ":"), sort_keys=False)

print(f"Wrote {len(bundle)} fixtures from {sha}")
PY

cp "$WORK/mls/LICENSE.txt" "$OUT_DIR/expression-tests-LICENSE.txt"
echo "Copied upstream LICENSE (BSD-3-Clause) alongside the fixtures"
