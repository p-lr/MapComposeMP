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
        → CollisionDetector  R-tree over oriented boxes / circle chains, viewport-bounded
  → ImageBitmap.toBytes()  uncompressed BMP, handed to MapCompose's ordinary tile pipeline
```

The last step is not decoration. `addVectorLayer` builds a plain `TileStreamProvider`, so a
rasterized tile has to cross a **byte** boundary and be decoded again by `TileCollector` even though
both ends hold an `ImageBitmap`. See [Tile encoding](#tile-encoding).

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

**An icon and its label share the feature's anchor**, as they do in upstream's `symbol_layout.ts`:
the icon is placed by `icon-anchor` / `icon-offset`, the label by `text-anchor` plus `text-offset` /
`text-radial-offset` / `text-variable-anchor-offset`, and the icon's size never enters the label's
offset. A style's own `text-offset` is authored to clear the icon it is drawn with, so an implicit
gap on top of it — which this port used to add, half the icon's height plus 2 dp — hangs every label
about a text line too low. `spriteWithTextBounds` is the union of the two boxes, and is what
`SymbolComposer` positions the pair by and what `Symbol.SpriteWithText.align` is derived from.

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
| `vector` | TileJSON `tiles`, `minzoom`, `maxzoom`, `scheme` (`tms` mirrors the row), overzoom | — |
| `raster` | the above, but overzoomed by stretching | `bounds`, `tileSize` |
| `raster-dem` | the above, plus `encoding` (`mapbox` / `terrarium` / `custom`, per `DemUnpack`) | `bounds` |
| `geojson` | inline `data` or a URL, `minzoom`, `maxzoom`, overzoom | `cluster*`, `lineMetrics`, `promoteId`, `tolerance` |
| `image`, `video` | — | recognised, never fetched |

### Overzooming

Above a source's `maxzoom` there are no tiles to fetch, and MapLibre does not drop the layer: it
clamps the requested zoom to `maxzoom` for the *canonical* tile coordinates and remembers the
requested one separately (`covering_tiles.ts`, `OverscaledTileID`). `MapLibreTileSource.resolve`
returns exactly that as a `TileRef` — the ancestor's `z/x/y`, plus which of its `span x span`
sub-squares the requested tile is.

What happens to the ancestor depends on the source, and here it follows upstream's
`reparseOverscaled` flag:

- an **image** source (`raster`, `raster-dem`) is *stretched*: the ancestor is cropped to the
  sub-square and drawn over the whole tile (`RasterTileImage.of`, `HillshadeLayerPainter`);
- a **vector** or **geojson** source is *re-rendered at the display zoom*: `TileRenderer` decodes the
  ancestor's geometry at `canvasSize * span` and **translates** the destination by
  `-(subX, subY) * canvasSize`. Translating rather than scaling is the point — the geometry grows
  with the span while `line-width`, `circle-radius`, `text-size` and a pattern's period stay in
  screen pixels, which is what upstream's re-parse achieves by rebuilding the bucket. It also makes
  `isInsideTile` the *canonical* tile's bounds, so `CircleBucket.addFeature`'s rule reads as upstream
  writes it: a vertex in a neighbouring sub-square is drawn and merely clipped.

Filters and paint properties see the **requested** zoom, not the ancestor's — that is
`reparseOverscaled: true`, which posts `zoom: tileID.overscaledZ` to upstream's worker. A symbol
layer is laid out once per *canonical* tile, as one `SymbolBucket` is, so `mergeLines`, `clipLine`,
the line-anchor walk and `anchorIsTooClose` all see the geometry `symbol_layout.ts` would see.

The pyramid has to be deep enough to ask: MapCompose's `levelCount` is the map's maximum tile `z`,
and it should be chosen for the deepest zoom an app wants to offer rather than for a source's
`maxzoom`.

Known cost: a sub-square tessellates its ancestor's whole geometry. The path cache is keyed by the
ancestor and the span, so a feature's `Path` is built once and shared by all `span²` sub-squares, but
each of them still draws the full-size path under the bitmap's clip.

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

**Forced by having no reachable GPU context** — the whole raster path:

- A tile is **rasterized on the CPU into an `ImageBitmap`**, never drawn as geometry into the live
  canvas and never sampled as a texture. This is structural, not an oversight. A skiko
  `Surface.makeRenderTarget` needs a `DirectContext`; Compose Multiplatform creates one per window
  on its own render thread and exposes no accessor, and tiles are rasterized on
  `IODispatcher.limitedParallelism` workers — background threads with no bound GL or Metal context.
  On Android a `RenderNode` + `HardwareRenderer` + `ImageReader` pass would run on the GPU but has to
  read the buffer straight back to the CPU, since the pipeline boundary is bytes; and the heatmap,
  hillshade and glyph painters are pixel loops that need CPU pixels either way.
- Drawing the geometry live each frame instead would not be a shortcut round it: `TileCanvas` costs
  one `drawImageRect` per tile per frame today, where a live path would re-run `LineTessellation`,
  the heatmap and hillshade loops and the SDF compositing on every frame. It buys crispness at
  fractional zoom at the cost of frame rate.
- The consequences that show are listed in their own painters — `raster-fade-duration` and
  `heatmap-opacity` cannot animate, a raster source is resampled twice, and everything under the
  bearing group below.

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
  tested against everybody, which is `*-allow-overlap`'s job and not this property's. The second
  grid is not needed because its only upstream reader is `queryRenderedSymbols`, which is not ported.
- **The index holds oriented boxes where upstream's holds axis-aligned ones**, and that is
  deliberate: upstream replaces a rotated box by its envelope back in `collision_feature.ts`
  ("Collision features require an 'on-axis' geometry, so take the envelope of the rotated
  geometry"), and again in `_projectCollisionBox` via `getAABB(points)`. Keeping the real
  orientation means two crossing road labels pack as tightly as their bodies allow rather than as
  their envelopes do, so this port suppresses strictly less than MapLibre, never more. The R-tree's
  AABB query is the broad phase; `OBB.intersects` (separating axis) is the exact test.
- **A symbol may also be a chain of circles** (`LabelPlacement.circles`), upstream's
  `placeCollisionCircles` against `placeCollisionBox`, because the straight envelope of a label
  following a curve claims far more ground than the label covers. The detector handles a chain
  wherever one arrives -- `renderer/collision/CollisionGeometry.kt` ports `_circlesCollide` and
  `_circleAndRectCollide` from `grid_index.ts` and adds the circle-against-oriented-box test
  upstream has no need of, and `insert` adds one R-tree entry per circle as `insertCollisionCircles`
  adds one grid circle per circle. **Nothing generates a chain yet**: building it needs the label's
  projected path, which belongs to the symbol placement pass, so a line label is still one straight
  box today.
- **The index is bounded by the padded viewport**, as upstream's grid is: `CollisionDetector` takes
  the viewport size and refuses -- neither places nor indexes -- a symbol whose box falls entirely
  outside the viewport grown by `VIEWPORT_PADDING`, upstream's `viewportPadding = 100`. A symbol
  inside that margin still competes, which is what keeps labels near the edge from reshuffling as
  the map pans. The refusal comes before the overlap mode is consulted, so `*-allow-overlap` does
  not exempt a symbol from it, exactly as upstream folds `!isInsideGrid` into `unplaceable`.
  Upstream's companion `isOffscreen` is not ported: its only consumer is the placement fade's
  `skipFade`, and placement here is binary.
- Not ported, and so behaving as upstream does at its defaults: collision *groups*
  (`CollisionGroups`, `crossSourceCollisions`) -- upstream defaults to one group for all sources,
  which is what this port always does, but there is no way to ask for per-source groups. Everything
  gated on a camera is absent for want of one: `perspectiveRatioCutoff`, the globe occlusion tests,
  and the pitched-label projection in `_projectCollisionBox`.
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

A tile is rasterized at
`vectorTileBitmapSize(mapState.tileSize, density, superSamplingFactor, magnifyingFactor)`
(`core/VectorLayer.kt`) and drawn into `mapState.tileSize * relativeScale` device pixels, with
`relativeScale ∈ (2^(mf-1), 2^mf]` because `VisibleTilesResolver.getLevel` rounds the level up after
subtracting the magnifying factor. Sizing against `max(density, 2^magnifyingFactor)` is what keeps
the bitmap ≥ the destination, so a tile is always minified and never stretched. `addVectorLayer`'s
`superSamplingFactor` renders larger still and filters back down in `VectorRasterizer.getTile`,
costing `factor²` fill. `VectorLayer` passes the size a tile occupies **on screen**
(`fullWidth * scale / 2^zoom`) to `produceSymbols`, not `mapState.tileSize` and not the bitmap size:
symbols are a viewport overlay, so they are laid out in on-screen pixels. That is also the space
every length the symbol painters measure against a tile's geometry lives in -- a label's own width,
`symbol-spacing`, `text-padding`, all of them style pixels times `density.density` -- so laying out
against the unscaled tile size made the geometry `relativeScale` times too small and
`symbol-spacing` that many times too coarse.

## Tile encoding

`addVectorLayer` returns an ordinary `TileStreamProvider`, so a rasterized tile leaves this package
as **bytes** and MapCompose's `TileCollector` decodes it back into an `ImageBitmap`. Nothing in the
vector package controls that boundary; the only thing it controls is the format.

That format is an **uncompressed 32-bit BMP** (`data/extension/BmpEncoder.kt`), not PNG. Deflating a
tile and inflating it again is by far the most expensive thing that happens to it: on a synthetic
map-like tile, a 1024×1024 PNG round trip measures ~600 ms against ~13 ms for BMP, almost all of it
in the encoder. BMP costs bytes instead — 4 MB for that tile against ~1.2 MB — but they live only
between the encode and the decode. Both decoders on the other side read BMP natively: Android's
`BitmapFactory` lists it among the supported formats, and Skia has `SkBmpCodec`.

Two things about the encoder are load-bearing:

- **The pixel data is straight alpha.** A tile `ImageBitmap` is `PREMUL`, so both actuals ask for
  unpremultiplied pixels explicitly — a Skia `readPixels` into `BGRA_8888` / `UNPREMUL`,
  `Bitmap.getPixels` on Android. Writing the bitmap's own bytes out as if they were straight alpha
  passes every opaque assertion and saturates every translucent one, which is the same trap
  `imageBitmapFromArgb` records.
- **Rows are top-down**, via BMP's negative-height convention, and the alpha channel needs a
  `BITMAPV4HEADER` with `BI_BITFIELDS` — the older `BITMAPINFOHEADER` has no way to declare an alpha
  mask at all.

Skia has no BMP *encoder* (`Image.encodeToData` honours only JPEG, PNG and WEBP), which is why this
one is hand-rolled. It is a 122-byte header plus one pixel copy.

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

`BmpRoundTripTest` (in `skiaTest`) is what decides the tile encoding, and it is deliberately
written against **core's own `decodeFirstLayer`** rather than a re-implementation of it: nothing in
this package decodes what `toBytes()` writes. Because it runs on all three skia targets it is also
the answer to "does skiko's wasm build ship a BMP codec" — if it ever fails there alone, the actual
moves out of `skiaMain` into `desktopMain` + `iosMain` and wasm goes back to PNG. Android has no
unit-test route at all (androidHostTest cannot allocate an `ImageBitmap`), so its decode is checked
by running the demo APK.

`*UpstreamTest.kt` files are transcriptions of MapLibre's own unit tests, each naming its upstream
path and keeping upstream's wording, so a failure can be traced back to a `describe`/`test` block by
search. Do not rewrite their assertions to match this port — if one fails, the port is wrong.
