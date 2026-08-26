#!/usr/bin/env python3
"""Reduce MapLibre's v8.json to the per-property {type, default, units} table.

Invoked by fetch-style-spec-defaults.sh. Kept as its own file so the extraction can also be run
against a local checkout of the spec without going over the network:

    python3 library/tools/extract-style-spec-defaults.py \
        <path-to-v8.json> <out.json> <provenance>
"""
import json
import sys


def main() -> None:
    src, out, provenance = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(src) as f:
        spec = json.load(f)

    blocks = {}
    for name, block in spec.items():
        if not (name.startswith("paint_") or name.startswith("layout_")):
            continue
        blocks[name] = {
            prop: {
                "type": value.get("type"),
                "default": value.get("default"),
                "units": value.get("units"),
            }
            for prop, value in sorted(block.items())
        }

    payload = {"upstream": provenance, "properties": dict(sorted(blocks.items()))}
    with open(out, "w") as f:
        json.dump(payload, f, separators=(",", ":"), sort_keys=False)

    total = sum(len(b) for b in blocks.values())
    print(f"Wrote {total} properties across {len(blocks)} blocks from {provenance}")


if __name__ == "__main__":
    main()
