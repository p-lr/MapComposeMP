# `vector` — a MapLibre style renderer for MapCompose

A from-scratch port of [maplibre-gl-js](https://github.com/maplibre/maplibre-gl-js)'s style engine
and renderer onto Compose Multiplatform's CPU canvas. A MapLibre style URL goes in; ordinary
MapCompose tiles come out.

## Specifications

- style-spec https://maplibre.org/maplibre-style-spec/
  - [Expressions](https://maplibre.org/maplibre-style-spec/expressions/)
  - [Layers](https://maplibre.org/maplibre-style-spec/layers/)
  - [Sprite](https://maplibre.org/maplibre-style-spec/sprite/)
- tilejson-spec https://github.com/mapbox/tilejson-spec/blob/master/2.2.0/README.md
- vector-tile-spec https://github.com/mapbox/vector-tile-spec/tree/master

## Reference implementations

- maplibre-gl-js https://github.com/maplibre/maplibre-gl-js/tree/main/src/render
- maplibre-native https://github.com/maplibre/maplibre-native/blob/main/src/mbgl/renderer/layers/render_symbol_layer.cpp

## Status

Every layer type the style spec defines is implemented except the two that need a camera the
renderer does not have: `fill-extrusion` and `sky`. Every other property of every other layer type
is read; the handful that are read and then ignored are listed under
[Divergences](#divergences), each with the reason and the file that documents it.

Sources: `vector`, `raster`, `raster-dem` and `geojson`. Sprites, including SDF and stretchable
icons, and SDF glyphs from a `glyphs` server.

```
VectorTileStreamProvider (interface)
  → VectorLayer            wires the MapState viewport to the rasterizer
  → VectorRasterizer       fetches and decodes sources, drives one TileRenderer per tile
        → TileRenderer     per-layer gating, then dispatch to a painter
              → Background / Fill / Line / Circle / Raster / Hillshade / Heatmap painters
        → SymbolsProducer  symbols, produced separately so collision runs across the viewport
              → SymbolLayerPainter → TextLabelBuilder → GlyphLayout + GlyphRasterizer
        → CollisionDetector  R-tree over oriented bounding boxes
```

## Decoders

| Piece | State |
|---|---|
| MVT protobuf decode (`spec/vector_tile.kt`, pbandk) | ✅ |
| MVT geometry command stream (`renderer/GeometryDecoders.kt`) | ✅ |
| Style JSON, incl. legacy v7 functions and filters | ✅ |
| Expressions (577-case upstream conformance suite) | ✅ |
| Glyph range protobuf (`data/glyphs/GlyphPbf.kt`) | ✅ |
| GeoJSON, projected and tiled in memory (`data/geojson/`) | ✅ |

## Layers

Universal gating, in `renderer/BaseRenderer.kt` and `renderer/TileRenderer.kt`: `visibility`,
`minzoom`/`maxzoom`, the layer `filter`, and `*-sort-key` ordering within a layer. Filters are
evaluated at the tile's *integer* zoom and paint properties at the fractional map zoom, as upstream
does — do not "fix" that.

The property columns below are the style spec's own, from the table
`library/tools/fetch-style-spec-defaults.sh` vendors into
`commonTest/composeResources/files/style-spec-defaults.json`. **Inert** means the property is
modelled and parsed but cannot change what is drawn; the reason is in [Divergences](#divergences).

### background — `renderer/BackgroundLayerPainter.kt`

`background-color`, `background-opacity`, `background-pattern`. All supported.

### fill — `renderer/FillLayerPainter.kt`

`fill-color`, `fill-opacity`, `fill-antialias`, `fill-outline-color`, `fill-pattern`,
`fill-translate`, `fill-translate-anchor`, `fill-sort-key`. All supported. Rings are grouped into
polygons and holes by `classifyRings`, the port of upstream's `classify_rings.ts`, and holes are cut
with the non-zero fill rule rather than identified explicitly.

### line — `renderer/LineLayerPainter.kt`

`line-color`, `-opacity`, `-width`, `-gap-width`, `-offset`, `-blur`, `-dasharray`, `-pattern`,
`-gradient`, `-translate`, `-translate-anchor`, `line-cap`, `line-join`, `line-miter-limit`,
`line-round-limit`, `line-sort-key`. All supported.

A feature is tessellated into the triangle ribbon upstream's `line_bucket.ts` builds
(`renderer/utils/LineTessellation.kt`) and drawn with `Canvas.drawVertices`, each vertex carrying the
alpha `line.fragment.glsl` would have computed there (`renderer/utils/LineShading.kt`). That alpha is
piecewise linear across the ribbon, so vertices at its slope changes reproduce the shader rather than
approximate it. A dashed line is cut into its painted runs before tessellation
(`renderer/utils/LineDash.kt`).

### circle — `renderer/CircleLayerPainter.kt`

`circle-radius`, `-color`, `-opacity`, `-blur`, `-stroke-width`, `-stroke-color`,
`-stroke-opacity`, `-translate`, `-translate-anchor`, `circle-sort-key`. Supported.
**Inert:** `circle-pitch-scale`, `circle-pitch-alignment`.

A circle is drawn at *every vertex* of the feature, whatever its geometry type, and vertices outside
the tile are dropped — both straight from upstream's `CircleBucket.addFeature`.

### symbol — `renderer/SymbolLayerPainter.kt`, `renderer/TextLabelBuilder.kt`

A glyph's `top` is negative-upward from its pen, so `inkTop = pen - top` and the pen is the font's
ascent line rather than its baseline. `GlyphLayout` starts a line at
`lineHeight / 2 + SHAPING_DEFAULT_OFFSET` (upstream's `-17`), which centres the ink in the box the
painter anchors — the box convention this port uses in place of upstream's anchor-relative one.

Layout: `icon-image`, `-size`, `-anchor`, `-offset`, `-rotate`, `-padding`, `-keep-upright`,
`-allow-overlap`, `-overlap`, `-ignore-placement`, `-optional`, `-rotation-alignment`,
`-text-fit`, `-text-fit-padding`; `text-field`, `-font`, `-size`, `-max-width`, `-line-height`,
`-letter-spacing`, `-justify`, `-transform`, `-writing-mode`, `-anchor`, `-variable-anchor`,
`-variable-anchor-offset`, `-radial-offset`, `-offset`, `-rotate`, `-padding`, `-max-angle`,
`-keep-upright`, `-allow-overlap`, `-overlap`, `-ignore-placement`, `-optional`,
`-rotation-alignment`; `symbol-placement` (`point`, `line`, `line-center`), `-spacing`,
`-avoid-edges`, `-sort-key`, `-z-order`. All supported.

Paint: `icon-color`, `-opacity`, `-halo-color`, `-halo-width`, `-halo-blur`, `-translate`;
`text-color`, `-opacity`, `-halo-color`, `-halo-width`, `-halo-blur`, `-translate`. All supported.

**Inert:** `icon-pitch-alignment`, `text-pitch-alignment`, and the `viewport` value of
`icon-translate-anchor` / `text-translate-anchor`.

`text-field` is typed `formatted` and `icon-image` `resolvedImage`, so `["format", …]` and
`["image", …]` compile and evaluate; a `["format", …]` section's `font-scale`, `text-font` and
`text-color` are honoured per section. The legacy `{token}` syntax is still expanded, `{name}`
preferring the configured language's `name:xx`.

### raster — `renderer/RasterLayerPainter.kt`

`raster-opacity`, `-hue-rotate`, `-saturation`, `-contrast`, `-brightness-min`, `-brightness-max`,
`-resampling`. Supported; the colour adjustments collapse into one `ColorMatrix`
(`renderer/utils/RasterColorMatrix.kt`), which returns `null` when everything is at its default so
the common case draws with no colour filter at all. **Inert:** `raster-fade-duration`.

### hillshade — `renderer/HillshadeLayerPainter.kt`

`hillshade-exaggeration`, `-illumination-direction`, `-shadow-color`, `-highlight-color`,
`-accent-color`. Supported. **Inert:** `hillshade-illumination-anchor`.

A CPU port of upstream's two GPU passes: `hillshade_prepare.fragment.glsl`'s Sobel operator over the
decoded DEM and then `hillshade.fragment.glsl`'s lighting, both in `renderer/utils/HillshadeShading.kt`.
`data/DemData.kt` ports `data/dem_data.ts`, including the 1 px border ring — seeded by clamping, then
backfilled from the 8 neighbouring tiles, which is what keeps the slope continuous across a tile
boundary. Upstream's `19.2562` hardcodes a 512 px DEM tile; the ground resolution is derived from the
tile's own size here, so a 256 px DEM is right too.

### heatmap — `renderer/HeatmapLayerPainter.kt`

`heatmap-weight`, `-intensity`, `-radius`, `-opacity`, `-color`. Supported.

Upstream has *two* paths in `src/webgl/draw/draw_heatmap.ts`. The flat one accumulates the whole
viewport into a single quarter-resolution framebuffer with additive blending and
`StencilMode.disabled` — *"Allow kernels to be drawn across boundaries, so that large kernels are not
clipped to tiles"* — then maps it through a 256×1 `heatmap-color` ramp; the terrain one does the same
per tile and accepts the seams. This port keeps the flat path's *output* inside the tile pipeline:
`TileRenderer` gathers the points of the tile **and its 8 neighbours** (`NeighbourTile`, fetched by
`VectorRasterizer.neighbourVectorTiles`) and hands them all to the painter. The kernel is finite, so
that reproduces what the shared framebuffer would hold. Points are de-duplicated with upstream's own
rule from `CircleBucket.addFeature` (`renderer/utils/TileBounds.kt`), so a point the MVT buffer
duplicated into a neighbour is never counted twice.

### fill-extrusion, sky — not implemented

Both are 3D and need a pitched camera, which MapCompose does not have.
`renderer/FillExtrusionPainter.kt` and `renderer/SkyLayerPainter.kt` are stubs that `TileRenderer`
skips before the per-feature loop; each says what it would need.

## Sources

`Source.type` is read into `SourceType` and carried on `MapLibreTileSource`, which is what lets
`VectorRasterizer` decode a `vector` source's tiles as MVT and a `raster` or `raster-dem` source's as
an image. Feeding an image to the protobuf decoder produces only noise, hence the
`vectorSourceNames` / `rasterSourceNames` / `demSourceNames` split, and hence `vectorSourceNames`
being a whitelist rather than "everything that is not raster".

| Type | Honoured | Not honoured |
|---|---|---|
| `vector` | TileJSON `tiles`, `minzoom`, `maxzoom`, `scheme` (`tms` mirrors the row) | overzoom above `maxzoom` — see below |
| `raster` | the above, plus overzoom to a magnified ancestor | `bounds`, `tileSize` |
| `raster-dem` | the above, plus `encoding` (`mapbox` / `terrarium` / `custom`, per `DemUnpack`) | `bounds` |
| `geojson` | inline `data` or a URL, `minzoom`, `maxzoom` | `cluster*`, `lineMetrics`, `promoteId`, `tolerance` |
| `image`, `video` | — | recognised, never fetched |

**Overzooming is applied to image sources only.** Cropping a magnified ancestor image is enough,
whereas reusing an ancestor *vector* tile would mean rescaling and translating every feature's
tile-local geometry, which the painters do not do; a vector source keeps asking for the tile it was
asked for and renders nothing when the server has none.

`heatmapSourceNames` is a fourth, overlapping set: the vector sources a `heatmap` layer reads, whose
neighbouring tiles are fetched too. It is gated on a heatmap layer actually drawing at the current
zoom, because those 8 extra fetches per source would otherwise be paid on every tile.

A `geojson` source has no server: the document is projected once when the style loads and
`GeoJsonTiler` cuts each requested tile out of it — clip to the tile with a buffer, simplify with
Douglas-Peucker, emit an MVT-shaped `Tile`. From `TileRenderer`'s point of view it is an ordinary
vector tile from then on. The spec forbids a `source-layer` on a layer reading a geojson source, so
`BaseRenderer.tileLayerFor` takes the tile's only layer when a style layer names none.

## Sprites and glyphs

**Sprites** (`data/SpriteManager.kt`, `spec/sprites/Sprite.kt`). Every sheet a style declares is
loaded, not just the first; the list form's `id` namespaces its entries as `"<id>:<name>"`, as
upstream's merged atlas does. An entry's `pixelRatio` is divided out wherever a size in layout pixels
is needed — icon size, pattern tile — so an `@2x` sheet draws at the same size as a plain one.
`stretchX` / `stretchY` / `content` drive the nine-patch path in
`renderer/utils/StretchableIcon.kt`, which is what `icon-text-fit` stretches an icon with. SDF
entries are recoloured by `renderer/utils/SdfShading.kt`, a port of `symbol_sdf.fragment.glsl`:
`icon-color` fills, `icon-halo-color` / `-width` / `-blur` surround, and the fill is composited over
the halo rather than added to it.

**Glyphs** (`data/glyphs/`). `GlyphManager` resolves the style's `glyphs` template, fetches one
`{fontstack}/{range}.pbf` per 256-codepoint range a label touches, and caches the decoded ranges;
a range that fails is cached empty, so a font the server lacks costs one request rather than one per
tile. `GlyphLayout` is the port of `symbol/shaping.ts` — advances, `text-letter-spacing`,
`text-line-height`, `text-transform`, `text-justify`, vertical `text-writing-mode`, and upstream's
balanced line breaking for `text-max-width`. `GlyphRasterizer` composites the shaped glyphs' distance
fields through the same `sdfPixel` the icons go through, which is what makes `text-halo-width` a real
dilated outline.

A style with no `glyphs` URL falls back to Compose's own text stack (`LabelArt.Measured`): labels
still render, with the platform's default font, `text-font` ignored and the halo approximated by a
blur. `library/tools/fetch-glyphs-proto.sh` re-fetches upstream's `glyphs.proto` for diffing against
the schema quoted in `GlyphPbf.kt`; the decoder is hand-rolled rather than generated because three
messages of seven scalar fields do not justify adding a protobuf toolchain to the build.

## Property defaults

Every fallback lives in `spec/style/StyleSpecDefaults.kt`, never inline at a painter's `?:`.
`library/tools/fetch-style-spec-defaults.sh` vendors upstream's `v8.json` property table into
`commonTest/composeResources/files/style-spec-defaults.json` (the extraction itself is
`tools/extract-style-spec-defaults.py`, runnable against a local checkout), and `StyleSpecDefaultsTest`
asserts the two agree — a spec bump fails a test instead of silently changing what a style looks
like. Properties whose spec default is `undefined` are handled per-painter and listed in that test's
`EXPECTED_UNDEFINED`.

Parsing never throws: errors accumulate in `ParsingContext.errors` and surface as
`MapLibreConfiguration.diagnostics`, and a property that fails to compile becomes
`ExpressionOrValue.Invalid`, evaluates to null, and lets the painter's `?: default` apply.

**Tile scale.** A style is authored in CSS pixels, which is what a `Dp` is here. Tile content is
drawn into `mapState.tileSize * relativeScale` *device* pixels, so `addVectorLayer` sets the map's
magnifying factor from the screen density (`magnifyingFactorForDensity`) to put one style pixel on
one dp — matching the labels, which are sized in dp, and the `Dp` widths `PathApi` takes. Pass
`adaptTileScaleToDensity = false` to keep a factor of your own.

## Divergences

Every one of these is documented at the file that causes it; this is the index.

**Forced by tessellating on the CPU rather than on the GPU** — `renderer/LineLayerPainter.kt`:

- A **round cap or round join** is real fan geometry. Upstream marks those vertices so its `dist`
  becomes a Euclidean norm the fragment shader evaluates per pixel, which vertex interpolation cannot
  reproduce; `roundStepCount` picks an angular step that keeps the chord within a quarter pixel.
- **`line-pattern`** keeps the pre-mesh `Stroke` path, because a pattern lives in a `ShaderBrush` and
  a shader in the paint is what would make `drawVertices` behave differently on Android, whose
  Compose actual drops the blend mode. A patterned line is therefore wallpapered in canvas space
  rather than mapped along the line, and gets none of what the mesh does better.
- **A dashed line's ends** are the layer's `line-cap`, where upstream's dash texture is antialiased
  along the line too. Sub-pixel.
- **`fill-antialias`** draws an outline only when the style sets a distinct `fill-outline-color`
  (`renderer/FillLayerPainter.kt`). Upstream falls that colour back to `fill-color` because the pass
  is how it antialiases GPU triangles; Skia's coverage antialiasing already does that here, so the
  same-coloured hairline would only fatten every polygon and bleed into its neighbour. The property
  itself *is* honoured on the fill, which is drawn through an explicit `Paint`.

**Forced by having no camera pitch or bearing at rasterization time:**

- `circle-pitch-scale`, `circle-pitch-alignment`, `icon-pitch-alignment`, `text-pitch-alignment` are
  inert — there is no pitch.
- A `viewport`-anchored `*-translate` is not counter-rotated (`renderer/utils/PaintUtils.kt`). Tiles
  are rasterized once and then rotated with the map, so the bearing is unknown at draw time; a
  `viewport` anchor applies the same unrotated offset as `map` and differs only on a rotated map.
- `hillshade-illumination-anchor` is inert for the same reason, so the light is always map-anchored.
  Note this is the spec *default*, so a style that says nothing gets map-anchored light.

**Forced by drawing into a tile bitmap rather than sampling a texture:**

- `raster-fade-duration` is inert. A tile is rasterized once and handed to the tile pipeline as
  bytes; there is no frame loop and no per-tile load timeline to cross-fade against.
- Raster, hillshade and heatmap output is resampled twice — once into the tile bitmap, again when
  that bitmap is drawn. That is the price of keeping those layers in style order among the vector
  layers rather than making them overlays.
- A source's `bounds` is not honoured.
- `heatmap-opacity` is folded into the colour ramp at rasterization, so it cannot animate — the same
  root cause as `raster-fade-duration`.
- Only the 8 immediate neighbours are gathered for a heatmap, so a `heatmap-radius` past roughly one
  tile still clips.
- A `*-pattern` is anchored to the world and scaled by density and by `2^(tileZoom - actualZoom)`
  (`renderer/utils/PaintUtils.kt`), which is the shader's `u_pixel_coord_*` and `u_scale` baked into
  the repeated bitmap because a Compose `ShaderBrush` has no local matrix.

**Symbols** — the placement architecture, not the properties:

- Placement and collision run per *viewport*, in `VectorRasterizer.clearCollision`, over an R-tree of
  oriented bounding boxes. Upstream runs a global placement per frame with cross-frame fading; there
  is no fade here, and a label's chosen `text-variable-anchor` is kept stable across frames by a
  coordinate-quantized cache rather than by upstream's placement history. One tree stands in for
  upstream's two `GridIndex`es: `*-ignore-placement` is the *insert* side only, as it is upstream
  (`collision_index.ts`'s `grid` vs `ignoredGrid`) -- such a symbol blocks nobody but is still
  tested against everybody, which is `*-allow-overlap`'s job and not this property's.
- Line labels are de-duplicated *within* a tile by upstream's own `anchorIsTooClose` -- a repeat of
  the same text within half a `symbol-spacing` of an anchor already taken is dropped before it
  reaches collision -- and *across* tiles by suppressing a repeat within
  `MIN_LINE_LABEL_REPEAT_DIST` viewport pixels, which stands in for the un-ported
  `cross_tile_symbol_index.ts`.
- Line anchors are upstream's: `renderer/collision/LineLabelPlacement.kt` transcribes
  `symbol/get_anchors.ts` (the spacing enlargement for a long label, the first-anchor offset, the
  in-tile test, the "does the whole label fit on the line" test and the single retry at the middle
  of a line that is not continued), and `SymbolsProducer` runs `renderer/utils/MergeLines.kt` --
  upstream's `merge_lines.ts` -- over a `symbol-placement: line` layer's features first, so a road
  arriving as one MVT feature per block is labelled as one long line. `renderer/utils/ClipLine.kt`
  clips those lines to the tile beforehand, as `symbol_layout.ts` does; `line-center` deliberately
  does not clip.
- `symbol-avoid-edges` is honoured, dropping a label whose padded box crosses the tile edge.
  Upstream declares the property and never reads it, relying on its cross-tile index instead.
- Text is shaped in logical order: there is no bidirectional reordering (upstream delegates that to
  an optional `rtl-text-plugin`) and no Arabic contextual shaping.
- A codepoint the glyph server has no glyph for is dropped, where upstream falls back to a locally
  rendered `TinySDF` for CJK.
- Labels are rasterized at their layout size and composited, so a label is resampled if the map
  scales it; upstream shades glyphs in the fragment shader at the final screen resolution.

**GeoJSON** — `data/geojson/GeoJsonTiler.kt`: `geojson-vt` builds a tile pyramid up front and splits
each parent into four children, reusing the parent's already-clipped geometry, and precomputes each
vertex's simplification distance once for all zooms. This cuts every requested tile straight from the
whole document and runs Douglas-Peucker per tile at that tile's own tolerance — the same shape of
result, recomputed. `cluster` and `lineMetrics` are not supported.

## Tile resolution

A tile is rasterized at `vectorTileBitmapSize(mapState.tileSize, density, superSamplingFactor)`
(`core/VectorLayer.kt`) and drawn into `mapState.tileSize * relativeScale` device pixels, with
`relativeScale ∈ (0.5, 1.0]` because `VisibleTilesResolver.getLevel` rounds the level up. Deriving the
bitmap size from `mapState.tileSize` is what keeps it ≥ the destination. `addVectorLayer`'s
`superSamplingFactor` renders larger still and filters back down in `VectorRasterizer.getTile`,
costing `factor²` fill. `VectorLayer` passes the size a tile occupies **on screen**
(`fullWidth * scale / 2^zoom`) to `produceSymbols`, not `mapState.tileSize` and not the bitmap size:
symbols are a viewport overlay, so they are laid out in on-screen pixels. That is also the space
every length the symbol painters measure against a tile's geometry lives in -- a label's own width,
`symbol-spacing`, `text-padding`, all of them style pixels times `density.density` -- so laying out
against the unscaled tile size made the geometry `relativeScale` times too small and
`symbol-spacing` that many times too coarse.

## Testing

Two source sets, split by what needs a graphics backend:

- **`commonTest`** — pure maths and decoding: the expression engine and its 577-case upstream
  conformance suite, filters, geometry decoding, `HillshadeShading`, `HeatmapKernel`, `SdfShading`,
  `StretchableIcon`, `AnchorOffsets`, `GlyphPbf`, `GlyphLayout`, `GlyphManager`, `GeoJson`,
  `GeoJsonTiler`, the R-tree and the OBB. MVT fixtures are built by hand in
  `commonTest/.../renderer/MvtFixtures.kt` and glyph ranges in
  `commonTest/.../data/glyphs/GlyphFixtures.kt`, so no binary fixture is needed anywhere.
- **`skiaTest`** (desktop, iOS, wasm) — anything that allocates an `ImageBitmap`: every layer
  painter, `TileRenderer`, `SpriteManager`, `GlyphRasterizer`, `PatternBrushCache`. They render into
  an off-screen bitmap and assert `toPixelMap()` colours. Plain `kotlin.test` — never
  `runComposeUiTest`, which fails on androidHostTest and times out on wasm. Support is in
  `skiaTest/.../renderer/PainterTestSupport.kt`.

`*UpstreamTest.kt` files are transcriptions of MapLibre's own unit tests, each naming its upstream
path and keeping upstream's wording, so a failure can be traced back to a `describe`/`test` block by
search. Do not rewrite their assertions to match this port — if one fails, the port is wrong.
