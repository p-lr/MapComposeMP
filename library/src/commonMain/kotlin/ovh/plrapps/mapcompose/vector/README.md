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
circle, raster, hillshade, heatmap -- follow maplibre-gl-js and are covered by pixel-level tests.
Symbols work but predate the expression engine. The remaining layer types are not implemented; each
painter's source says what it would need.

Remaining directions
- Symbols. Rework against the expression engine, and the layout properties listed as unread below.
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
- ✅ Raster — opacity, hue-rotate, saturation, contrast, brightness-min/max, resampling,
  overzoom
- ✅ Hillshade — Terrain-RGB decode (mapbox/terrarium/custom), exaggeration,
  illumination-direction, shadow/highlight/accent colours, overzoom, neighbour-backfilled borders
- ❌ FillExtrusion
- ✅ Heatmap — weight, intensity, radius, opacity, `heatmap-color` ramp, kernels gathered across
  neighbouring tiles
- ❌ Sky

Layer gating that applies to all of them: `visibility`, `minzoom`/`maxzoom`, `filter`, and
`*-sort-key` ordering within a layer.

Defaults for every implemented property come from `spec/style/StyleSpecDefaults.kt`, which
`StyleSpecDefaultsTest` checks against the vendored copy of upstream's `v8.json` property table.

Divergences forced by stroking on the CPU instead of tessellating on the GPU, each documented at its
painter: `line-round-limit` is inert, `line-blur` and `line-gradient` are approximated,
`circle-pitch-scale` / `circle-pitch-alignment` are inert (no camera pitch), and a
`viewport`-anchored `*-translate` is not counter-rotated. Raster adds its own, from drawing into a
tile bitmap rather than sampling a texture: `raster-fade-duration` is inert (no frame loop to
cross-fade over), the image is resampled twice, and a source's `bounds` is not honoured. Hillshade
shares the last two and adds `hillshade-illumination-anchor`, which is inert for the same reason a
`viewport`-anchored `*-translate` is -- the bearing is unknown when a tile is rasterized -- so the
light is always map-anchored; it also lights each DEM sample and interpolates the resulting colours,
where upstream interpolates the slope and lights each screen pixel. Heatmap shares that extra
resample too, and adds two of its own: only the 8 immediate neighbours are gathered, so a
`heatmap-radius` past roughly one tile still clips; and `heatmap-opacity` is folded into the colour
ramp when the tile is rasterized, so it cannot animate -- the same root cause as
`raster-fade-duration` being inert.

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
#### Hillshade
A CPU port of upstream's two GPU passes: `hillshade_prepare.fragment.glsl`'s Sobel operator over the
decoded DEM, then `hillshade.fragment.glsl`'s lighting, both in
`renderer/utils/HillshadeShading.kt`. `data/DemData.kt` is the port of `data/dem_data.ts`, including
the 1 px border ring -- seeded by clamping and then backfilled from the 8 neighbouring tiles, which
is what keeps the slope continuous across a tile boundary. Upstream's `19.2562` hardcodes a 512 px
DEM tile; the ground resolution is derived from the tile's own size here instead, so a 256 px DEM is
right too.

#### Heatmap
Upstream has *two* paths in `src/webgl/draw/draw_heatmap.ts`. The flat one accumulates the whole
viewport into a single quarter-resolution framebuffer with additive blending and
`StencilMode.disabled` -- "Allow kernels to be drawn across boundaries, so that large kernels are not
clipped to tiles" -- then maps it through a 256x1 `heatmap-color` ramp texture; the terrain one does
the same per tile and accepts the seams. This port keeps the flat path's *output* inside the tile
pipeline instead of adding an overlay: `TileRenderer` gathers the points of the tile **and its 8
neighbours** (`NeighbourTile`, fetched by `VectorRasterizer.neighbourVectorTiles`, reusing the same
`neighbourRefs` the hillshade border backfill uses) and hands them all to `HeatmapLayerPainter`. The
kernel is finite, so that reproduces what the shared framebuffer would hold -- no seam. Points are
de-duplicated with upstream's own rule from `CircleBucket.addFeature`, "Do not include points that
are outside the tile boundaries", so one duplicated into a neighbour's MVT buffer is never counted
twice.

`renderer/utils/HeatmapKernel.kt` holds the pure maths (`kernelValue`, `kernelExtentInRadii`, and the
two texture reads `bilinearSample` / `sampleColorRamp`), so `commonTest` covers it and only the
painter needs `skiaTest` -- the same split as `HillshadeShading.kt`. The density field is a quarter
of the tile bitmap in each axis, as upstream's is of the screen, and density is interpolated *before*
the ramp lookup, as the fragment shader does. `heatmap-color` is the one spec default that is an
expression rather than a scalar: `StyleSpecDefaults.HEATMAP_COLOR` keeps it as JSON text and the
painter compiles it through the ordinary property serializer. It is read at a *density* rather than
at a zoom or a feature, hence `processAsHeatmapColor`.

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




