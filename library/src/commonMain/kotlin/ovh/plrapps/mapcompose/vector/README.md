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
The overall project structure and, most importantly, the parsers and decoders have been implemented.
For rendering, only the basic painters have been implemented (background, lines, polygons, labels).

Three main directions
- Expression parser and processing. Needs to be covered with tests, and the existing tests require reorganization.
- Rendering.Implement the remaining painters and fix bugs in the existing ones.
- Tests. Not enough tests, more needed

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

- 🛠 Background
- 🛠️ Lines
- 🛠️ Polygons
- 🛠️ Symbols
- ❌ Sprites (part of Symbols)
- ❌ Raster
- ❌ Circle
- ❌ FillExtrusion
- ❌ Heatmap
- ❌ Hillshade
- ❌ Sky

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
#### Line, Background & Polygons
WIP
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




