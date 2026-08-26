## Specifications
- style-spec https://maplibre.org/maplibre-style-spec/
  - [Expressions](https://maplibre.org/maplibre-style-spec/expressions/)
  - [Layers](https://maplibre.org/maplibre-style-spec/layers/)
- tilejson-spec https://github.com/mapbox/tilejson-spec/blob/master/2.2.0/README.md
- vector-tile-spec https://github.com/mapbox/vector-tile-spec/tree/master

## Reference implementations :
- Maplibre-gl-js https://github.com/maplibre/maplibre-gl-js/tree/main/src/render
- Maplibre-native https://github.com/maplibre/maplibre-native/blob/main/src/mbgl/renderer/layers/render_symbol_layer.cpp

## Status
Parsers, decoders and the expression engine are done. The 2D painters -- background, fill, line,
circle -- follow maplibre-gl-js and are covered by pixel-level tests. Symbols work but predate the
expression engine. The remaining layer types are not implemented; each painter's source says what it
would need.

Remaining directions
- Symbols. Rework against the expression engine, and the layout properties listed as unread below.
- Raster and hillshade. Both are blocked on source types: `Source.type` is ignored today, so every
  source is fetched and pbf-decoded as MVT.
- Heatmap. Needs a viewport-wide accumulation overlay, like the one symbols already use; a per-tile
  heatmap would seam at every tile edge.
- fill-extrusion and sky. 3D; blocked on camera pitch.

TL;DR
### ✅ Decoders

- ✅ PBF decoder ->
  - ✅ Geometry decoder
- ✅ Style decoder
- ✅ interpolation for:
  - ✅ Color
  - ✅ Number
  - ✅ String
- ✅ Expressions (may require further analysis)
- ✅ Filters (MVP)

### 🚧 Layers

- ✅ Background — colour, opacity, pattern
- ✅ Fill — colour, opacity, antialias/outline, translate, pattern, holes, multipolygons
- ✅ Line — colour, opacity, width, gap-width casing, offset, blur, dasharray, cap/join, translate,
  pattern, gradient
- ✅ Circle — radius, colour, opacity, blur, stroke width/colour/opacity, translate
- 🛠️ Symbols
- 🛠️ Sprites (part of Symbols)
- ❌ Raster
- ❌ FillExtrusion
- ❌ Heatmap
- ❌ Hillshade
- ❌ Sky

Layer gating that applies to all of them: `visibility`, `minzoom`/`maxzoom`, `filter`, and
`*-sort-key` ordering within a layer.

Defaults for every implemented property come from `spec/style/StyleSpecDefaults.kt`, which
`StyleSpecDefaultsTest` checks against the vendored copy of upstream's `v8.json` property table.

Divergences forced by stroking on the CPU instead of tessellating on the GPU, each documented at its
painter: `line-round-limit` is inert, `line-blur` and `line-gradient` are approximated,
`circle-pitch-scale` / `circle-pitch-alignment` are inert (no camera pitch), and a
`viewport`-anchored `*-translate` is not counter-rotated.

### What is implemented and close to MapLibre

#### MVT (Vector Tile) Geometry Decoding
ZigZag decoding, correct coordinate handling, support for POINT, LINESTRING, POLYGON.
Tests for the decoder and reversibility.

#### Symbol Painter
Rendering text on lines and polygons.
Text centering on lines/polygons, angle calculation.
Correct text rotation (so it’s never upside down).
Support for text-halo (outline), color, size, opacity.
Support for text-offset, text-anchor.
Correct work with text templates (substituteTemplate).
Basic collision system implemented: labels do not overlap (within a tile).
Support for allowOverlap, ignorePlacement, priorities.
Visual debugging (borders, color).
Collision reset.
Currently, collision reset is implemented at the tile level, but MapLibre has nuances with global placement (especially when rendering multiple tiles in one frame).
No spatial index (R-tree), but for small tiles this is not critical. No Fonts(WIP). 
#### Fill, Line, Circle & Background
Ported against `maplibre-gl-js/src/render/draw_*.ts` and the matching shaders. Polygon rings are
grouped into polygons and holes by `classifyRings`, the port of upstream's `classify_rings.ts`, and
holes are cut by the non-zero fill rule rather than by identifying them explicitly.

Each painter has a pixel-level test suite that renders into an off-screen `ImageBitmap` and asserts
`toPixelMap()` colours -- plain `kotlin.test`, no `runComposeUiTest`. They live in `skiaTest`
(desktop, iOS, wasm) because `ImageBitmap` cannot be allocated in Android unit tests.
#### Styles
Using expressions (process()) to obtain styles.
LineLabelPlacement
Placing labels along a line, taking symbol-spacing into account.
Tests for even and correct placement.
Tests
Unit test coverage for decoder, collision, and line placement.

What is partially or simplistically implemented
Not implemented (or not fully implemented)
What is not yet implemented (or only partially implemented):

Icon placement and icon support
In MapLibre, labels can be not only text but also icons (icon-image, icon-size, etc.).

Layout Expressions
In MapLibre, layout expressions can be very complex (e.g., depending on zoom, feature-state, data-driven styling).

Collision Groups
MapLibre allows specifying collision groups (collision-group) so that labels from different groups do not interfere with each other.

Rotated collision box
In MapLibre, rotated collision boxes are used for text along lines, so collisions are calculated based on the actual position of the text, not just AABB.

Complex scenarios with multilingual labels, fallback, bidirectional text
MapLibre supports advanced language scenarios.

Dynamic loading and removal of labels when zoom/viewport changes
In MapLibre, placement can be global for the entire frame, not just per tile.

Everything related to basic rendering, collisions, and text placement is implemented close to MapLibre.
To be improved: rotated collision box, spatial index, icon support, complex expressions, collision groups.




