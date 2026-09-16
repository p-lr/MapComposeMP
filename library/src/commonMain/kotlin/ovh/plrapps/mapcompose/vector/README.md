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
icons, and SDF glyphs from a `glyphs` server — or from the font files a style declares in its root
`font-faces`, which are downloaded and rasterized locally, one grapheme cluster at a time.

A style's root `state` block is honoured: its defaults are flattened as upstream's
`getGlobalStateDefaults` does and baked into every expression and filter the style compiles, so
`["global-state", k]` reads what the style declared — including in `layout.visibility`, which is an
expression here as it is upstream. There is no runtime setter; see [Divergences](#divergences).

```
VectorTileStreamProvider (interface)
  → VectorLayer            wires the MapState viewport to the rasterizer, on two cadences
  → VectorRasterizer       fetches and decodes sources, drives one TileRenderer per tile
        → TileRenderer     per-layer gating, then dispatch to a painter
              → Background / Fill / Line / Circle / Raster / Hillshade / ColorRelief / Heatmap painters
        │
        │  symbols, in two passes with different lifetimes (see Symbol layout and placement)
        ├─ SymbolBucketBuilder   layout: one SymbolBucket per canonical tile per style layer
        │     → SymbolLayerLayout → TextLabelBuilder → GlyphLayout + GlyphRasterizer
        ├─ CrossTileSymbolIndex  one identity per label, across tiles and zooms
        └─ Placement             placement: project, collide, fade — held, at most one per 300 ms
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
| MLT decode (`vector` source with `encoding: "mlt"`) | ❌ — recognised, source refused at load |
| MVT geometry command stream (`renderer/GeometryDecoders.kt`) | ✅ |
| Style JSON, incl. legacy v7 functions and filters | ✅ |
| Expressions (577-case upstream conformance suite) | ✅ |
| Glyph range protobuf (`data/glyphs/GlyphPbf.kt`) | ✅ |
| GeoJSON, projected and tiled in memory (`data/geojson/`) | ✅ |

## Layers

Universal gating, in `renderer/BaseRenderer.kt` and `renderer/TileRenderer.kt`: `visibility`,
`minzoom`/`maxzoom`, the layer `filter`, and `*-sort-key` ordering within a layer. Filters are
evaluated at the tile's *integer* zoom, as upstream's buckets are. **Paint properties are evaluated
at that same integer zoom**, where upstream evaluates them at the fractional map zoom — see
[Divergences](#divergences).

**The geometry expressions are wired into filters *and* paint/layout properties.** `within` and
`distance` need two things no other expression does: the feature's decoded geometry, and the
canonical `(z, x, y)` of the tile it came from, which is what projects tile-local coordinates back
to lng/lat. Upstream threads the tile id beside the feature everywhere — into
`_featureFilter.filter(...)` and on into `populatePaintArrays(…, {canonical})`
(`data/bucket/circle_bucket.ts`). Here it rides on the `EvalFeature` instead, which the renderer
already builds once per (feature, tile) and hands to every painter, every `processAs*` helper and
the symbol layout pass — equivalent, since both are per-tile, and it means no property-evaluation
call site has to carry it. For an overzoomed source the id is the **ancestor** actually fetched,
upstream's `OverscaledTileID.canonical`; for a gathered neighbouring tile it is that neighbour's own,
with `x` wrapped into `0..2^z-1`.

Geometry is decoded lazily rather than behind upstream's `FeatureFilter.needGeometry` flag:
`BaseRenderer.buildEvalFeature` always attaches the provider and `EvalFeature.geometry` is
`by lazy`, so the work still happens only when an expression reads it — one step later than
upstream's gate, which is what lets a `within` in a *paint* property see anything. The decode
rescales from the MVT layer's own `extent` to the style spec's `EXTENT` of 8192, as
`src/data/load_geometry.ts` does, and keeps upstream's clamp to a signed 15-bit range.

The property columns below are the style spec's own, from the table
`library/tools/fetch-style-spec-defaults.sh` vendors into
`commonTest/composeResources/files/style-spec-defaults.json`. **Inert** means the property is
modelled and parsed but cannot change what is drawn; the reason is in [Divergences](#divergences).

### background — `renderer/BackgroundLayerPainter.kt`

`background-color`, `background-opacity`, `background-pattern`. All supported.

### fill — `renderer/FillLayerPainter.kt`

`fill-color`, `fill-opacity`, `fill-layer-opacity`, `fill-antialias`, `fill-outline-color`,
`fill-pattern`, `fill-translate`, `fill-translate-anchor`, `fill-sort-key`. All supported. Rings are
grouped into polygons and holes by `classifyRings`, the port of upstream's `classify_rings.ts`, and
holes are cut with the non-zero fill rule rather than identified explicitly.

`fill-layer-opacity` is **not** `fill-opacity` under another name, and neither is
`line-layer-opacity`. Per-feature opacity — and the alpha of `fill-color` itself — accumulates where
two features of the layer overlap; layer opacity is applied once to the finished layer, so the
overlap reads as a single surface. Upstream draws the layer into its own framebuffer and blits it at
that alpha (`src/webgl/draw/draw_layer_opacity.ts`); `TileRenderer` wraps the layer's feature loop in
a `Canvas.saveLayer`, which is the same offscreen-then-composite. Setting both keeps the
accumulation and then composites it, as the spec says. See `renderer/utils/LayerOpacity.kt`; the
default of `1` allocates no offscreen. Only `fill` and `line` have such a property.

### line — `renderer/LineLayerPainter.kt`

`line-color`, `-opacity`, `-layer-opacity`, `-width`, `-gap-width`, `-offset`, `-blur`,
`-dasharray`, `-pattern`, `-gradient`, `-translate`, `-translate-anchor`, `line-cap`, `line-join`,
`line-miter-limit`, `line-round-limit`, `line-sort-key`. All supported.

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

A circle is drawn at *every vertex* of the feature, whatever its geometry type — straight from
upstream's `CircleBucket.addFeature`.

**The radial profile is `circle.fragment.glsl`, sampled into a gradient.**
`renderer/utils/CircleShading.kt` holds the shader's two `smoothstep`s. Both are governed by one band
width `B = max(1 / R, circle-blur)`, where `R = circle-radius + circle-stroke-width`: `opacity_t`
fades the whole disc's alpha over the last `B` of `R`, and `color_t` cross-fades the fill colour into
the stroke colour over the `B` *inside* the fill radius. Two things follow that were wrong before.
`circle-blur` feathers the **stroke** as well as the fill, over the combined radius, rather than
fading the fill alone and leaving the ring sharp. And `circle-radius: 0` with a positive
`circle-stroke-width` is a solid disc of the stroke colour, not nothing — the geometry reaches `R`
whatever the radius, and `radius / R` of 0 puts `color_t` at 1 everywhere.

Reaching a fragment shader from Compose means a radial gradient, so `circleGradientStops` samples the
profile: the breakpoints of the two ramps, then a fixed count of samples per interval between them
(a count, not a spacing, because a band is a fraction of `R` and the error is then scale-invariant).
The mix runs **premultiplied**, as the shader's does — upstream's `Color` stores `r`, `g`, `b`
already multiplied by alpha — and is unpremultiplied back to a Compose `Color` afterwards, which is
what keeps the outermost stop carrying the disc's hue: Skia interpolates gradient stops
unpremultiplied, and a transparent *black* stop would drag every coloured circle's edge dark. Two
residual divergences: the profile is interpolated linearly between samples rather than evaluated per
fragment (under 0.005 of alpha), and Skia's own coverage antialiasing still applies at `R`, on top of
the feathered edge, so the outermost pixel is marginally softer than upstream's. A circle that is
neither blurred nor stroked skips the gradient — its profile is flat but for the one-pixel
faux-antialiasing, which is what Skia's coverage antialiasing already is — and is drawn as a plain
solid disc, which is what a dense point layer costs.

**A disc straddling a tile boundary is drawn by both tiles.** Upstream drops every vertex outside
the tile because it draws circles into one viewport-wide framebuffer with `StencilMode.disabled`
(`draw_circle.ts`), so the tile that owns a point spills the whole disc across the boundary. A tile
is rasterized into its own bitmap here and that bitmap is the clip, so the half that falls outside
has to be drawn by the *neighbour*, which means the neighbour has to carry the point. The
neighbouring tiles are therefore **gathered** for a circle layer, the way they already were for the
heatmap (`NeighbourTile`, `VectorRasterizer.neighbourVectorTiles` /
`neighbourGeoJsonTiles`), and each contributes the vertices it owns, offset by a tile.

`renderer/utils/TileBounds.kt`'s `CircleVertexGate` is what keeps that from drawing a vertex twice —
an MVT buffer duplicates a point near an edge into both tiles, and drawing it from both doubles a
translucent circle's alpha along the seam. A vertex the tile owns (`isInsideTile`, half-open) is
always drawn; one it does not is drawn from the buffered copy only when the neighbour that owns it
was **not** gathered, which is the fallback for a neighbour whose fetch failed. Before the gathering
the buffered copy was the only repair, and it is bounded by the source's buffer: at the usual 64
units of a 4096 extent a disc reaching further than `tileBitmapSize / 64` pixels was drawn by nobody.

Gathering is gated on a circle layer being visible and in zoom range, since it costs 8 fetches per
source — a style with no circle layer at that zoom pays nothing, and a source a heatmap reads too
gathers once. What remains bounded is a disc reaching further than one whole tile, the same limit
`heatmap-radius` has. What still uses the strict rule is anything that *counts* points rather than
drawing them — the heatmap's accumulation and a symbol's anchor.

### symbol — `symbol/SymbolLayerLayout.kt`, `renderer/TextLabelBuilder.kt`

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

**A label that follows a line is drawn glyph by glyph**, along the projected line, as upstream's
`placeGlyphsAlongLine` (`symbol/projection.ts`) does — the collision pass already walked that curve
(`Placement.circleChain`), so a label on a bend used to be tested against a chain of circles and then
drawn as a straight bar poking out of it. The walk happens **at draw time**
(`ui/symbols/CurvedLabel.kt`, `SymbolComposer`), for the same reason the size and the fade change do:
a placement is held for at least a fade duration, so a bend baked at commit would step once per cycle
through a gesture instead of following the road. What is projected each frame is the label's own
stretch of line, cut by the layout pass (`SymbolInstance.Text.globalLine`): a merged road arrives as
one linestring of hundreds of vertices, and each of its labels would otherwise re-project every one
of them on every frame. `text-keep-upright` is decided there too, by
comparing the first and last glyph in *screen* space (upstream's `requiresOrientationChange`), which
is what makes it right on a rotated map; layout's own `makeTextUpright` decides in the bucket's space
and stays only for the straight paths. `text-max-angle` is unchanged — a layout-time gate — but it
finally shows, since an accepted span is now drawn along its bend. `text-max-width` is forced to `0`
for line placement, as `symbol_layout.ts` does, so a line label is never wrapped.

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

`icon-color` and `icon-halo-color` / `-width` / `-blur` apply to **SDF entries only**, as they do
upstream: the ordinary-icon program is `fragColor = texture(u_texture, v_tex) * alpha`
(`symbol_icon.fragment.glsl`) and carries no colour uniform, so a plain image keeps its own texels
whatever the layer sets. Only `symbol_sdf.fragment.glsl` reads `fill_color`. Not a divergence --
this port used to tint a plain entry with `icon-color` through a `SrcIn` fill, which rendered every
multicolour PNG icon in such a layer as a monochrome silhouette.

**Inert:** `icon-pitch-alignment`, `text-pitch-alignment`, and the `viewport` value of
`icon-translate-anchor` / `text-translate-anchor`.

`text-field` is typed `formatted` and `icon-image` `resolvedImage`, so `["format", …]` and
`["image", …]` compile and evaluate; a `["format", …]` section's `font-scale`, `text-font`,
`text-color`, `vertical-align` and inline `image` are all honoured per section. The legacy `{token}`
syntax is still expanded, `{name}` preferring the configured language's `name:xx`.

An image section is upstream's `TaggedString.addImageSection`: it contributes one private-use
character to the shaped text — which is what lets a `text-field` made of an image alone render at
all, where the concatenated section text is empty — its `font-scale` is ignored, since a sprite
carries its own size, and an image the sheet does not have drops the section entirely, its advance
included (`if (!imagePosition) continue`). A section shorter than its line is offset by
`vertical-align`, `bottom` by default, and a line grown by a tall image or by a `font-scale` above
one takes upstream's second `align()` branch, where the pen is the line's own top rather than
`SHAPING_DEFAULT_OFFSET` plus half a line — all of it in `data/glyphs/GlyphLayout.kt`. Shading
follows `symbol_text_and_icon.fragment.glsl`, which is the program upstream picks for a label
carrying images: a plain image is blitted with only `text-opacity` applied, an SDF entry falls into
the same branch the glyphs do and is recoloured by the **text**'s `text-color` and `text-halo-*`.

**Inline images diverge in three ways.** Only the glyph path draws them: the Compose `TextMeasurer`
fallback a style with no `glyphs` URL falls back to measures one run in one style, so it drops a
section's font, scale, colour and image alike, and a field made only of an image is not drawn by it
at all. An inline image belongs to the label's own box and takes no part in `icon-text-fit`. And
`mergeLines` and `anchorIsTooClose` still key on section *text*, so two labels differing only by an
inline image are one key for merging and for repeat suppression.

**`text-writing-mode` is a preference order, resolved at placement.** A label a style lists
`vertical` for and whose text can be stacked is shaped **twice** — upstream's
`shapedTextOrientations`, whose horizontal half is always built — and `Placement` walks the style's
own list, keeping the first setting whose collision box fits (`placeTextForPlacementModes`,
`src/symbol/placement.ts`). So `["horizontal", "vertical"]` reads horizontally wherever there is
room and stacks only where there is not, `["vertical", "horizontal"]` asks for the reverse, and a
list naming one mode alone offers no fallback. The chosen setting reaches the draw pass as the
accepted instance rather than as a flag, which is the same instance-swap a `text-variable-anchor`
label already goes through. Membership used to decide it at shaping time instead: either list
stacked every eligible label, whether or not a horizontal one would have fitted, and there was no
second shaping to fall back to when the first collided.

**Vertical setting diverges in three ways, all for want of glyph rotation.** A label is eligible only
when **every** codepoint is one this port can stack, where upstream's `allowsVerticalWritingMode`
asks whether *any* has upright vertical orientation and rotates the rest ninety degrees; a mixed
Latin/CJK label is therefore always horizontal here. Only a **point** label gets a second setting,
upstream's `addVerticalShapingForPointLabelIfNeeded` — its other vertical branch, a line label under
`textAlongLine && keepUpright`, would have to rotate each glyph along the path and verticalize its
punctuation (`verticalizePunctuation`, and the rotated quad in `quads.ts`), so a line label always
shapes horizontally. And `icon-text-fit` offers no vertical setting either: the icon was stretched
around the horizontal box, and upstream builds a second, vertical icon quad for that case. The
Compose fallback cannot stack a run at all, so a style with no `glyphs` URL is horizontal throughout.

Where a symbol ends up on screen, and whether it is drawn at all, is not this file's business — see
[Symbol layout and placement](#symbol-layout-and-placement).

### raster — `renderer/RasterLayerPainter.kt`

`raster-opacity`, `-hue-rotate`, `-saturation`, `-contrast`, `-brightness-min`, `-brightness-max`,
`-resampling`. Supported; the colour adjustments collapse into one `ColorMatrix`
(`renderer/utils/RasterColorMatrix.kt`), which returns `null` when everything is at its default so
the common case draws with no colour filter at all. **Inert:** `raster-fade-duration`.

### hillshade — `renderer/HillshadeLayerPainter.kt`

`hillshade-exaggeration`, `-method`, `-illumination-direction`, `-illumination-altitude`,
`-shadow-color`, `-highlight-color`, `-accent-color`, and `resampling` — which, as on `color-relief`,
really is spelled without the layer-type prefix. Supported.
**Inert:** `hillshade-illumination-anchor`.

A CPU port of upstream's two GPU passes: `hillshade_prepare.fragment.glsl`'s Sobel operator over the
decoded DEM and then `hillshade.fragment.glsl`'s lighting, both in `renderer/utils/HillshadeShading.kt`.
`data/DemData.kt` ports `data/dem_data.ts`, including the 1 px border ring — seeded by clamping, then
backfilled from the 8 neighbouring tiles, which is what keeps the slope continuous across a tile
boundary. The ground resolution is derived from the DEM tile's own size, so a 256 px DEM is right as
well as a 512 px one — which is also what upstream's `28.2562` does, since maplibre-gl-js#5768
replaced the `19.2562` that hardcoded 512.

**All five `hillshade-method` algorithms are implemented**, each a branch of that same fragment
shader: `standard` (MapLibre's legacy one, and the only one reading `hillshade-accent-color`),
`basic`, `combined` and `igor` (ports of the matching `gdaldem` algorithms, the last of which ignores
the light's altitude), and `multidirectional`, which averages one `basic` pass per light. Only
`standard` reshapes the slope by `hillshade-exaggeration`; the other four scale the derivative by
`exaggeration * 2` directly. Flat ground is short-circuited in the painter, but *not* to
transparent — `basic` and `multidirectional` light a flat surface by the cosine of the light's
altitude, so the painter computes the flat colour once per tile rather than assuming it away.

**A style may declare several light sources.** `-illumination-direction` and
`-illumination-altitude` are `numberArray` in the spec, `-shadow-color` and `-highlight-color` are
`colorArray`, and the source count is the longest of the four — every shorter list is padded with
its own last element, as `HillshadeStyleLayer.getIlluminationProperties` does. A bare number or
colour is a one-element array, so nothing a pre-multidirectional style wrote changes meaning. These
are the only two properties in the whole spec with those types, and they are the reason
`expression/types/NumberArray.kt` and `ColorArray.kt` exist.

### color-relief — `renderer/ColorReliefLayerPainter.kt`

`color-relief-color`, `-opacity`, and `resampling` — which really is spelled without the layer-type
prefix, in the spec and in upstream's `layer.paint.get('resampling')`. Supported.

The second layer type that reads a `raster-dem` source. Upstream uploads the DEM as one texture and
the ramp as two more, and `color_relief.fragment.glsl` unpacks an elevation, binary-searches the
elevation stops and lets GL's `LINEAR` filter blend the two neighbouring colour texels; the DEM is
already unpacked into metres at decode here (`data/DemData.kt`), so `renderer/utils/ColorReliefRamp.kt`
is that search and blend as pure functions and the painter is one CPU loop over the DEM's samples.

**Only a top-level `interpolate` over `["elevation"]` produces a ramp.** That is upstream's rule and
not this port's: `ColorReliefStyleLayer._createColorRamp` reads the stop *labels* off the interpolate
and evaluates the expression at each of them, so a `step`, a `case` or a plain colour string leaves
the ramp empty, upstream pads it to one transparent stop, and the layer draws nothing. Reading the
labels rather than the expression also means the blend between two stops is **linear** whatever the
interpolation type said — an `["exponential", 2]` ramp is flattened between its stops, because the
GPU only ever sees two colours and a texture filter.

Divergences, beyond the ones raster and hillshade already have (resampled twice, `bounds` ignored):
the elevation stops are exact metres, where upstream round-trips each through `packDEMData` and
quantizes it to the DEM encoding's step; there is no `MAX_TEXTURE_SIZE` cap on the number of stops,
so a very long ramp renders in full rather than decimated; and `resampling` filters the resulting
*colours* rather than the elevations, the same divergence hillshade has and with the same scope.

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
| `vector` | TileJSON `tiles`, `minzoom`, `maxzoom`, `scheme` (`tms` mirrors the row), `promoteId`, overzoom | `encoding: "mlt"` — refused, see below |
| `raster` | the above, but overzoomed by stretching | `bounds`, `tileSize` |
| `raster-dem` | the above, plus `encoding` (`mapbox` / `terrarium` / `custom`, per `DemUnpack`) | `bounds` |
| `geojson` | inline `data` or a URL, `minzoom`, `maxzoom`, `promoteId`, `filter`, `generateId`, `buffer`, `tolerance`, overzoom | `cluster*`, `lineMetrics` |
| `image`, `video` | — | recognised, never fetched |

A source's own options win over the TileJSON it references, which is upstream's
`extend(tileJSON, options)` in `src/source/load_tilejson.ts` -- `tiles`, `minzoom`, `maxzoom`,
`scheme`, `attribution`, `bounds`, `tileSize` and `encoding`, each applied only where the style
actually wrote it. So a source pointing at a document and overriding `"scheme": "tms"` addresses
rows its own way, and a `raster-dem` source that writes no `encoding` takes the document's. The four
`custom` DEM factors are not in upstream's pick list and stay the source's own. `tileSize` is
carried and still not honoured.

#### `encoding: "mlt"` is refused, not decoded

A `vector` source may declare its wire format. Upstream picks its decoder off it in
`src/source/vector_tile_worker_source.ts`:

```ts
const vectorTile = params.encoding !== 'mlt'
    ? new VectorTile(new PbfReader(rawData))
    : new MLTVectorTile(rawData);
```

MapLibre Tiles is not decoded here. `data/VectorEncoding.kt` reads the property -- off the *merged*
TileJSON, as `DemUnpack` reads its own, because upstream's `params.encoding` also comes out of
`extend(tileJSON, options)` -- and `getMapLibreConfiguration` refuses such a source: it records a
`StyleDiagnostic` under `sources.<name>` and does not register it, so it is never fetched and never
reaches `decodePBFFromByteArray`. Layers reading it draw nothing.

Refusing is the point. pbandk does not reliably throw on foreign bytes: it collects them into
`Tile.unknownFields`, so an MLT tile handed to the protobuf decoder comes back as an empty or
garbage `Tile` and not even the `catch` in `decodePBFFromByteArray` fires. The map was simply blank
with nothing said, which is the same failure `SourceType.UNKNOWN` was introduced to avoid.

Porting a decoder is deliberately out of scope. Upstream delegates to the npm package
`@maplibre/mlt`; the implementations that exist are TypeScript, Java, Rust and C++, none of them
reachable from `commonMain` across Android, iOS, desktop and wasm. The format is column-oriented
with FSST string dictionaries, FastPFOR/varint integer encodings, morton-ordered geometry and its
own protobuf tileset-metadata schema, and it is still marked experimental and evolving.
`VectorEncoding` is the seam a decoder would plug into.

#### `promoteId`

A `vector` or `geojson` source may name the feature property that stands in for the feature's id.
Upstream reads it once per feature, in `src/data/feature_index.ts`:

```ts
getId(feature: VectorTileFeatureLike, sourceLayerId: string): string | number {
    let id: string | number = feature.id;
    if (this.promoteId) {
        const propName = typeof this.promoteId === 'string' ?
            this.promoteId : this.promoteId[sourceLayerId];
        id = feature.properties[propName] as string | number;
        if (typeof id === 'boolean') id = Number(id);
        …
    }
    return id;
}
```

`renderer/BaseRenderer.buildEvalFeature` is this port's seam for it, and
`promoteIdPropertyFor` is the `typeof … === 'string'` branch. Both shapes the spec allows are read:
a bare property name, and the object naming one property per source layer -- which a `geojson`
source keys under `_geojsonTileLayer`, upstream's name for its single synthetic layer and
`GeoJsonTiler.LAYER_NAME` here. The promoted value *replaces* the id rather than falling back to it,
so a property the feature does not carry leaves `["id"]` null, as upstream leaves it `undefined`;
the one coercion is upstream's boolean one, every other type arriving already normalized by
`extractFeatureProperties`. Upstream's `cluster_id` arm has no analogue -- geojson clustering is not
supported.

Modelling it as anything but raw JSON is what the object form used to break. `Source.promoteId` was
a `String?`, and kotlinx-serialization has no union, so `{"roads": "ref"}` -- what any multi-layer
vector source writes -- threw out of the decode, which `getMapLibreConfiguration` turns into a
`Result.failure` that blanks the whole map. It is a `JsonElement` normalized by
`Source.promoteIdSpec`, the pattern `sprite` and `font-faces` already use, and a shape the spec does
not allow is now a `StyleDiagnostic` under `sources.<name>` with that source simply keeping its
protobuf ids.

**A layer filter reads the raw id, not the promoted one, and that is upstream's behaviour.**
`src/source/worker_tile.ts` computes the promoted id and hands it to `bucket.populate`, but every
bucket's `populate` filters against `toEvaluationFeature(feature, needGeometry)`
(`src/data/evaluation_feature.ts`), which keeps `feature.id`; only the `BucketFeature` that reaches
paint and layout carries `getId`'s. So `["id"]` in a `filter` sees the protobuf id and `["id"]` in
`fill-color` sees the promoted one. `EvalFeature.filterFeature` is that second object -- non-null
only for a promoting source, so nothing else allocates for it -- and `shouldRenderFeature` filters
against `filterFeature ?: this`. `renderer/PromoteIdWiringTest.kt` pins both halves.

**A GeoJSON feature's own id may be a string, and the synthetic tile smuggles it.** RFC 7946 types
an id as a string or a number and MapLibre keeps either, but `Tile.Feature.id` is a protobuf
`uint64` and `spec/vector_tile.kt` is pbandk-generated. `GeoJsonTiler` therefore writes the id into
the layer's own tag table under `SYNTHETIC_ID_KEY` -- a NUL-prefixed key no document writes -- and
`buildEvalFeature` lifts it back out and removes it before the properties reach `["get"]` or
`["properties"]`. It rides inside the `Tile`, so it survives the overzoom crop, the neighbour gather
and both tile caches with no plumbing of its own. An integral id is additionally written to
`Tile.Feature.id`, so the tile stays readable as an ordinary MVT one. `GeoJson.readId` reads the
quoting rather than guessing: it used to fall back to `content.toLongOrNull()` and turn the string
`"42"` into the number 42.

### Tile URL templates

`MapLibreTileSource.getTileUrl` substitutes every token upstream's `CanonicalTileID.url`
(`src/tile/tile_id.ts`) does, in upstream's order:

| Token | Expands to |
|---|---|
| `{z}` `{x}` | the tile's zoom and column |
| `{y}` | the row, mirrored when the source's `scheme` is `tms` |
| `{prefix}` | `(x % 16)` and `(y % 16)` as two lowercase hex digits |
| `{ratio}` | `@2x` above a pixel ratio of 1, the empty string otherwise |
| `{quadkey}` | the Bing-style quadkey, one base-4 digit per zoom level |
| `{bbox-epsg-3857}` | the tile's bounding box in EPSG:3857 metres, `minX,minY,maxX,maxY` |

The last four used to be left literal, so a WMS, quadkey, sharded or retina template requested a URL
with the braces still in it -- a 404 for every tile of that source rather than a wrong-looking one.
`{quadkey}` and `{bbox-epsg-3857}` are built by `TileUrlTemplate.kt`, a port of the helpers upstream
inlines from the archived `@mapbox/whoots-js`; both take the plain xyz row, because upstream calls
them with `this.y` *before* the `scheme` substitution, and the bounding box does its own,
unconditional flip -- a different thing from TileJSON's `scheme`, and independent of it. Its four
components are written by `plainDecimalString` rather than by `Double.toString`: mercator metres live
around `1e7`, where Kotlin reaches for exponent notation on every target and JavaScript's `String`
does not, and a WMS server is handed the text verbatim.

Which template a tile takes is `(x + y) % tiles.size`, as upstream does, and deliberately not
`random()` -- a random shard means the same tile is requested from a different host on every retry,
so nothing downstream of the fetch can recognise it.

`{ratio}` follows the map's own density: the source holds a `() -> Float` rather than a value,
because a source is built while the style loads and a tile URL only ever by a fetch that happens
after `MapUI` has composed and the density is known. Suspending on the density at load time instead
is the deadlock `addVectorLayer` already avoids, since it awaits `makeTileStreamProvider()` before
returning the layer id. **Sprite sheets are the half this does not cover**: `SpriteManager.loadSheet`
is called eagerly while the style loads, so it needs the ratio then, and a style's `@2x` sheet is
still not requested unless a caller passes `getMapLibreConfiguration`'s own `pixelRatio`. Making it
follow the density means loading sheets lazily, or resolving the density before the configuration is
built.

`{s}` is not supported, and is not a divergence: it is a Leaflet token, and upstream does not
substitute it either.

### Overzooming

Above a source's `maxzoom` there are no tiles to fetch, and MapLibre does not drop the layer: it
clamps the requested zoom to `maxzoom` for the *canonical* tile coordinates and remembers the
requested one separately (`covering_tiles.ts`, `OverscaledTileID`). `MapLibreTileSource.resolve`
returns exactly that as a `TileRef` — the ancestor's `z/x/y`, plus which of its `span x span`
sub-squares the requested tile is.

What happens to the ancestor depends on the source, and here it follows upstream's
`reparseOverscaled` flag:

- an **image** source (`raster`, `raster-dem`) is *stretched*: the ancestor's sub-square is
  magnified over the whole tile (`RasterLayerPainter`, `HillshadeLayerPainter`,
  `ColorReliefLayerPainter`). The sub-square is a **fractional** window of the ancestor's samples,
  resolved by `renderer/utils/OverzoomSampling.kt` — upstream draws the source tile's own quad and
  samples the texture at fractional coordinates, so it never faces the question, and dividing the
  ancestor's dimension by the span instead made a 256-sample source *disappear* nine levels above
  its `maxzoom`, the quotient having reached zero. The window carries the half sample that bilinear
  filtering needs on each side, so two sub-squares of one ancestor magnify continuously rather than
  each clamping the filter at its own edge; it stops at the tile's own samples, because a DEM's
  border ring is not shadeable (`sobelDeriv` would read one sample further still);
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
the line-anchor walk and `anchorIsTooClose` all see the geometry `symbol_layout.ts` would see. Its
bucket's layout space is `LAYOUT_TILE_SIZE * span` wide for the same reason the geometry is: the
ancestor covers that many map tiles.

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

Four of its options are resolved at load, in the order upstream resolves them.

**`filter`** is an ordinary boolean expression, compiled by the same `FeatureFilterSerializer` a
layer's is — so legacy v7 syntax is converted and a filter that fails to compile is a diagnostic
rather than a throw. `GeoJson.applySourceFilter` is upstream's `_filterGeoJSON`
(`src/source/geojson_worker_source.ts`) and runs on the JSON document, before it is parsed, because
that is where upstream runs it: the worker filters `data.features` and hands `geojson-vt` what
survived. It is evaluated at **zoom 0** with no tile, as upstream's
`compiled.value.evaluate({zoom: 0}, feature)` is, so `within` answers `false` and `distance` `NaN`
— there is no canonical tile id at load time and upstream passes none either. One divergence:
`EvalFeature.type` is the feature's real geometry type, where upstream hands `evaluate` the raw
GeoJSON `Feature`, whose `.type` is the literal string `"Feature"`, so `["geometry-type"]` and
`$type` match nothing at all in an upstream source filter.

**`generateId`** replaces every feature's id with its index in the *filtered* document, which is
`geojson-vt`'s `convert.js` (`let id = geojson.id; … else if (options.generateId) id = index || 0`)
— it does not fill in for a missing id, and a bare `Feature` gets 0. Filtering the document rather
than the parsed feature list is what makes that index right: a feature the filter dropped must not
consume an id. `promoteId` still wins, and for free, since it is applied later in
`BaseRenderer.buildEvalFeature` and replaces the id there — `convert.js`'s own precedence.

**`buffer`** and **`tolerance`** are authored in style pixels and used in tile units;
`GeoJsonTiler.pixelsToTileUnits` is upstream's `GeoJSONSource._pixelsToTileUnits`,
`pixelValue * (EXTENT / 512)`. So the spec's defaults of 128 px and 0.375 px are 1024 and 3 tile
units at extent 4096. The buffer used to be a flat 64 units — a sixteenth of upstream's, 8 px on a
512 px tile — and this port needs it *more* than upstream does, not less: a tile is rasterized into
its own bitmap and that bitmap is the clip, where upstream draws the whole viewport into one
framebuffer and lets the owning tile spill a shape across the boundary. `buffer` is clamped to the
spec's `0..512`. The source's `maxzoom` is not simplified at all, which is `splitTile`'s
`z === options.maxZoom ? 0 : …`.

`cluster*` and `lineMetrics` are still unsupported, but they are now *reported* through
`StyleDiagnostics` rather than silently dropped — `json` has `ignoreUnknownKeys = true`, so an
unmodelled option is not an error, it is silence.

## Symbol layout and placement

Symbols run in **two passes with different lifetimes**, as they do upstream. Everything that depends
only on the data runs once and is cached; everything that depends on where the map currently is runs
again on every viewport update.

```
layout    SymbolBucketBuilder → SymbolLayerLayout        once per (canonical tile, style layer, integer zoom)
          symbol_layout.ts, SymbolBucket.populate        cached in VectorRasterizer, survives pan/rotate/zoom-within-a-level
              ↓ SymbolBucket: anchors, shaped labels, icon quads, collision boxes, SizeData

identity  CrossTileSymbolIndex                            one crossTileID per conceptual label
          cross_tile_symbol_index.ts                      matched by text + rounded anchor, across tiles and zooms
              ↓ SymbolInstance.crossTileID

placement Placement → CollisionDetector                   at most once per fade duration (300 ms)
          style.ts's _updatePlacement, placement.ts       considered per viewport update, usually deferred
              ↓ PlacementResult: what to draw, and each symbol's fade state

draw      SymbolComposer                                  every frame; re-projects, re-scales, fades
```

**Layout is pinned to a constant tile size.** `LAYOUT_TILE_SIZE` is upstream's nominal 512, times the
display density, and it is what makes the split real. Layout used to be handed the size a tile
occupies *on screen*, which changes with every fractional zoom, so nothing it produced could outlive
one viewport update: a pan that fetched no tiles still re-shaped every label, re-walked every line
and rebuilt every collision box. Every layout-space length — a label's own width, `symbol-spacing`,
`text-padding`, `icon-padding`, the arc-length walk in `LineLabelPlacement`, `symbol-avoid-edges` —
is measured against that constant instead, which is upstream's `tilePixelRatio = EXTENT / tileSize`.

**Size expressions bridge the two passes** (`symbol/SymbolSize.kt`, upstream's `symbol_size.ts`). A
label is shaped and rasterized once, at the bucket's own zoom, but drawn at whatever fractional zoom
the map is at. So the bucket carries `SizeData` plus, for a size that varies per feature, that
feature's size at the two zooms bracketing it; the placement pass evaluates those at the zoom being
drawn and the ratio to the size the label was rasterized at is the scale it is drawn and collided
at — upstream's `textScale` / `iconScale`. Upstream brackets `[z, z + 1)` and rasterizes at `z + 1`
so every later scale is a minification; MapCompose's `VisibleTilesResolver` rounds the level *up*, so
the range here is `(z - 1, z]` and `SymbolSizes` passes `z - 1` to keep that same guarantee.

**An overzoomed source keeps its identities too.** Past a source's `maxzoom` — 14 in
`test_style_bright.json`, 15 in `test_style_street_v2.json`, so most of the interesting zoom range —
a bucket's canonical tile stops changing and only its `span` grows. Its layout space is
`LAYOUT_TILE_SIZE * density * span` wide, so the *same* label sits at twice the `tileAnchor` one zoom
level up; `TileLayerIndex` divides both factors out before comparing. And because the canonical tile
is the same, two display zooms land on one index slot, so a replaced entry's ids have to be released
at the zoom *it* claimed them under rather than the new bucket's. Get either wrong and every label
past `maxzoom` is a new symbol at every zoom step, which is a fade out and in on each one.

**Cross-tile identity is what makes the rest work.** `CrossTileSymbolIndex` gives the same label in a
parent and a child tile one `crossTileID`, matched on the text and a rounded anchor. Three things
key on it: the placement pass skips an id it has already placed this cycle, which de-duplicates a
road labelled once in every tile it crosses; a fade survives the tile swap on a zoom instead of
restarting; and a `text-variable-anchor` choice is remembered, so a label does not flick between
anchors as the map moves. It replaced two stand-ins of this port's own — a rule that dropped a repeat
of the same text within 250 viewport pixels, and a coordinate-quantized cache of the chosen anchor.

**Fading** (`symbol/OpacityState.kt`) is upstream's `OpacityState` / `JointOpacityState`, over
upstream's default 300 ms. `Placement.commit` diffs this cycle's decisions against the previous
cycle's and starts the fades; a symbol that has lost its ground keeps being drawn at falling opacity
until it is hidden, which is what makes it fade *out* rather than vanish. The fade is *finished* at
draw time, by adding `PlacementResult.fadeChangeAt` to the committed opacity -- upstream's
`u_fade_change` uniform and the `fade_opacity[0] + fade_change` line in `symbol_icon.vertex.glsl`.

**A placement is held, not recomputed.** This is `Style._updatePlacement` (`src/style/style.ts`),
and it is the half of the design that keeps labels steady:

```js
const placementSettled = this.pauseablePlacement?.isDone() && !this.placement.stillRecent(now(), transform.zoom);
if (forceFullPlacement || !this.pauseablePlacement || (placementSettled && (placementInputsChanged || this.placement.stale))) { … }
```

A new placement starts only once the last is no longer `stillRecent` -- at most once per fade
duration -- and until then the committed decisions stand while the draw pass keeps re-projecting and
re-scaling them. `VectorRasterizer.place` is that decision; `Placement.stillRecent` / `setStale` /
`recencyRemainingMs` are its state. Running a placement on every viewport update instead, which this
port briefly did, makes every near-threshold label re-decide sixty times a second, and a pinch
shimmers.

`zoomAdjustment` shortens the window when the map has settled *below* the placement's zoom, with
upstream's reason: "when zooming out quickly, labels can overlap each other ... discovering the
collisions more quickly and fading them more quickly reduces the unwanted effect". It applies "only
after the map has stopped zooming", which is what `zoomAtLastRecencyCheck` decides.

**A departing tile's symbols are held through their fade.** A pan or a zoom swaps the bucket set
wholesale, and a symbol on a tile that just left is no longer among the candidates the fading pass
walks — so it disappeared in a single frame however opaque it still was, which read as labels
blinking out just after a gesture. Upstream never sees this because the *tile* is held:
`Tile.holdingForSymbolFade` keeps it in the render set so its bucket is still walked. There is no
tile lifecycle to hook here, so `Placement.result` carries those symbols over from the previous
result instead, for as long as `commit` keeps their opacity alive — bounded, because every commit
advances the fade. A held symbol is drawn but not re-collided, which upstream's held tile is; its
symbols share a `crossTileID` with whatever replaced them and are skipped as duplicates anyway.

`result` prefers the previous result's own symbols over the bucket's candidates for anything that is
no longer placed, because what it holds is *what was actually drawn*. A `SpriteWithText` at a
`text-variable-anchor` is accepted as two entries under one `crossTileID` — the label and the icon it
names — so a carry-over that de-duplicates by id dropped one of them, and the icon vanished in a
single frame while its label faded out over 300 ms. And the candidate for such a symbol is the
bucket's *un-split* instance, whose label sits at the plain `text-offset` rather than at the anchor
the symbol settled on, so redrawing it from there made the label jump for the length of its fade.
`PlacementCarryOverTest` pins both.

**A line's anchors nest across an overzoomed source's zoom steps.** `getAnchors` multiplies the
first-anchor offset by `bucket.overscaling` before taking it modulo the spacing, and this port did
not. Past a source's `maxzoom` the same canonical tile is laid out again at every display zoom, over
a line that is `overscaling` times longer in layout units at an unchanged `symbol-spacing`, so the
offset decides everything: `(S / 2) % (S / ov)` puts the coarser level's anchors inside the finer
level's, while `S / (2 * ov)` -- what this had -- makes the two sets share **no** point. Every line
label was therefore a new symbol at every zoom step, fading in over its own dying copy and landing
somewhere else along the road. Measured against swisstopo's style through a 14 -> 17 pinch, the port
went from 143 to 225 matched identities on `transportation_label` and from 0 to 13 on
`waterway_line_label`, with a fifth to a third fewer identities minted per step.
`LineLabelPlacementTest` pins the subset property directly.

**A departing tile's *identities* are held too, and for longer.** The fade above only works while a
symbol keeps its `crossTileID`: `Placement.commit` keys every opacity on it, so a symbol whose id
changed is new by definition and restarts at opacity 0, while its old id is still in the previous
cycle's opacities and is faded out on top of it. The same label is drawn twice for a whole fade
duration, one copy rising and one falling, which composites to a visible dip — a flicker.
`CrossTileSymbolIndex.removeStaleBuckets` retires a tile's ids the moment its bucket is missing from
the list it is handed, which is upstream's behaviour and right for upstream, because there the list
*is* the renderable tile set. Here the list `place` is called with can be transiently short for
reasons that have nothing to do with what is on screen: a layout run still in flight, a tile whose
fetch has not landed, the one-tile ring jittering as the map pans. So `VectorRasterizer`'s
`heldForSymbolFade` keeps a departed bucket in the list it hands the index for
`SYMBOL_BUCKET_HOLD_MS` — one placement cycle, so the absence can be told from a short list, plus one
fade — and the index itself is left exactly as upstream wrote it. Held buckets go to the identity
pass alone, never to `PlacementOrder`.

**Neither pass is cancelled in flight.** `VectorRasterizer.place` and `layoutBuckets` are both
`withContext` blocks, so cancelling one discards its result while every side effect it already
applied stands. Both are fed by a `Channel(CONFLATED)` and consumed by one uncancelled coroutine
(`VectorLayer.startSymbolsProcessing`): the newest request still wins, but the pass already running
finishes. Layout is the one that made this visible. It awaits every missing tile's fetch, so it lasts
as long as the network does, while its `LayoutKey` changes at every tile-matrix shift and every
integer zoom step — under `collectLatest` the run in flight was killed several times a second through
a gesture and never published at all, and the fetches it had already completed were discarded with it
(`fetchTile` rethrows `CancellationException` before caching the bytes). Placement went on running
against a bucket set from before the gesture, and what finally landed swapped the whole set in one
step.

**An incomplete layout pass is retried, and publishes what it had.** A tile whose fetch failed
contributes no bucket, because an instance-less placeholder would install an empty `TileLayerIndex`
over a live tile — but leaving a *hole* is a fade: every label on that tile is unplaced from the next
cycle on, and the pass only re-runs when the tile set or the integer zoom changes, so it stays gone
until the map moves and then fades back in. `layoutBuckets` therefore reports
`LayoutOutcome.unresolved`; `VectorLayer` re-runs the same key up to `LAYOUT_RETRY_LIMIT` times with
a doubling delay, the pass republishes the previous pass's bucket for any slot it could not rebuild,
and a pass that resolved nothing at all is not published — "every fetch failed" is not the statement
"there are no symbols here".

**The fade clock belongs to the placement, not to the screen.** `SymbolComposer` draws
`opacity + fadeChange`, and the two halves are only consistent when the elapsed time is measured from
the placement the opacity was committed by. The clock used to be one `elapsedMillis` state that a
`LaunchedEffect` zeroed when its body ran — and an effect body runs when its coroutine is dispatched,
which can be after the frame that first draws the new placement, while a cancelled effect's
`withFrameMillis` callback for the current frame can still write the old value into it first. Either
way that frame pairs a freshly committed opacity with the *previous* cycle's fade change, which is
close to a whole fade duration because a new placement starts at most once per fade. The arithmetic
of `alphaAt` then picks out exactly one class of symbol: an already-visible one is `1 + change`,
clamped back to 1 and unaffected; a departing one is `1 - change` and blinks out for a frame; a
**newly placed** one is `0 + change` and flashes in at nearly full opacity, is drawn at 0 on the next
frame, and only then fades in over its 300 ms. That is "labels flicker when they appear, ones already
on screen do not". `remember(placement)` gives each cycle its own `FadeClock`, which is created
during composition and so is at zero before anything can draw, and a lingering effect writes to the
clock nobody reads any more. `FadeClockTest` pins the arithmetic that made it visible.

**What a held placement does *not* freeze** is anything that has to keep following the map. Position
is re-projected every frame, and so is size: `PlacedSymbol.textScaleAt` / `iconScaleAt` evaluate the
size expression at the frame's zoom, exactly as upstream recomputes its `u_size` / `u_size_t`
uniforms from `painter.transform.zoom` (`symbol/projection.ts`) while collision keeps the
placement's own zoom (`symbol/placement.ts`). Freezing the scale at commit would make labels *step*
in size every 300 ms through a zoom.

**Cadences.** Layout runs only when the tile set or the integer zoom changes, behind a 250 ms
throttle. Placement is *considered* on every viewport update, behind a 16 ms one, and actually runs
at most once per fade duration; a cycle deferred by `stillRecent` is retried when the window lapses,
which is what upstream gets for free by re-entering `_updatePlacement` on every render frame.

## Sprites and glyphs

**Sprites** (`data/SpriteManager.kt`, `spec/sprites/Sprite.kt`). Every sheet a style declares is
loaded, not just the first; the list form's `id` namespaces its entries as `"<id>:<name>"`, as
upstream's merged atlas does -- except for the id `default`, whose entries keep their bare names
(`_getSpriteImageId`, `src/render/image_manager.ts`). The single-URL form *is* that id upstream
(`coerceSpriteToArray`, `src/util/style.ts`), so the two `sprite` forms are one code path; keying the
exception on an *empty* id instead, as this did, broke every bare `icon-image` of a style written as
`[{"id": "default", "url": ...}]` -- which is what a style becomes the moment a second sheet is added
beside its first. An entry's `pixelRatio` is divided out wherever a size in layout pixels
is needed — icon size, pattern tile — so an `@2x` sheet draws at the same size as a plain one.
`stretchX` / `stretchY` / `content` drive the nine-patch path in
`renderer/utils/StretchableIcon.kt`, which is what `icon-text-fit` stretches an icon with. SDF
entries are recoloured by `renderer/utils/SdfShading.kt`, a port of `symbol_sdf.fragment.glsl`:
`icon-color` fills, `icon-halo-color` / `-width` / `-blur` surround, and the fill is composited over
the halo rather than added to it. An entry the sheet index does *not* flag `sdf` is cut out and
drawn as it is -- never tinted, since those four properties reach no ordinary-icon shader upstream.

**Glyphs** (`data/glyphs/`). `GlyphManager` resolves the style's `glyphs` template, fetches one
`{fontstack}/{range}.pbf` per 256-codepoint range a label touches, and caches the decoded ranges;
a range that fails is cached empty, so a font the server lacks costs one request rather than one per
tile. Which ranges a label touches is collected the way upstream's `SymbolBucket.populate` collects
its glyph dependencies: per font stack, over **every** `["format", ...]` section, unioning the text of
all the sections that share a stack before the fetch. Asking for the first such section's text alone
left a later section's script unrequested — a glyph the server does have, never asked for, which is
indistinguishable on screen from one it lacks, since `GlyphLayout` drops an unresolved codepoint
without even an advance. `GlyphLayout` is the port of `symbol/shaping.ts` — advances,
`text-letter-spacing`, `text-line-height`, `text-transform`, `text-justify`, the vertical setting
`text-writing-mode` selects — which orientation a label is shaped in is the *caller's* choice, since
the property is a preference order the placement pass resolves — and upstream's balanced line
breaking for `text-max-width`. `GlyphRasterizer` composites the shaped glyphs' distance
fields through the same `sdfPixel` the icons go through, which is what makes `text-halo-width` a real
dilated outline. It also rasterizes each glyph on its own (`renderGlyphs`, upstream's quads) for a
label that follows a line, because that one is drawn glyph by glyph; blitting sub-rectangles of the
composite instead cannot work, since a glyph's distance field reaches `GLYPH_BORDER` samples past its
ink and so overlaps its neighbours'.

There is **no maximum label length** — upstream's `shapeText` rejects a label only when nothing was
positioned, and so does this. What is bounded is the composite itself: upstream rasterizes into a
shared glyph atlas and a long label costs it quads, while `GlyphRasterizer.render` allocates one
bitmap covering the whole label, so it refuses one past `MAX_LABEL_BITMAP_DIMENSION` (8192 px) or
`MAX_LABEL_BITMAP_PIXELS` (4 M). Both are far above anything a style draws, and a label past either
is not dropped — `TextLabelBuilder` falls through to the Compose measure, which caps its own lines.
A character count used to stand in for this, and rejected any `text-field` past 256 code units
before either path could draw it.

**Font faces** (`data/glyphs/FontFaceManager.kt`, `LocalGlyphRasterizer.kt`, `TinySdf.kt`,
`UnicodeRange.kt`). A style's root `font-faces` names a font *file* per `text-font` name, optionally
per `unicode-range`, and this port honours it as upstream's `font_face_manager.ts` +
`glyph_manager.ts` do: the file is downloaded the first time a codepoint it covers is drawn (never
before, and once only — a failed download is remembered as failed), built into a `FontFamily` of its
own through the `fontFamilyFromBytes` `expect`, and the text is drawn with it. Resolution order is
upstream's: a declared file wins over the `glyphs` server for every codepoint it covers, and a style
with font files and no server renders real labels rather than falling back to Compose.

What is drawn is a **grapheme cluster**, not a codepoint, which is the point of the property: a
Devanagari or Khmer syllable is several codepoints that come apart when drawn one at a time, and
handing the whole cluster to the platform's text engine is what keeps them together. Segmentation is
`graphemeClusters`, an `expect` over `java.text.BreakIterator`, `NSString`'s composed character
sequences and `Intl.Segmenter` — the same shape as `compareLocalized` — and it is skipped entirely
for a style declaring no font file, so nothing about the common path changed. `GlyphLayout.shape`
takes an optional cluster lookup and makes a drawn cluster **one** item, which is what keeps line
breaking from splitting a syllable; the item keeps its first codepoint, since that is what the break
and whitespace classes are read from.

The rasterization is upstream's `_drawGlyph` with Compose's text stack in place of a canvas: the
grapheme is laid out at `24 × 2` pixels against a `Density(1f)`, drawn white into an `ImageBitmap`,
and its alpha run through `TinySdf` — a port of `mapbox/tiny-sdf`, Felzenszwalb's distance transform
at upstream's `radius = 8`, `cutoff = 0.25`. Metrics are divided back by that scale and carry
upstream's two calibration constants (`leftAdjustment = 0.5`, `topAdjustment = 27.5`), which is what
lines a locally drawn glyph up with a server-generated one. Two things differ from upstream and are
deliberate: the distance field is **halved** back to glyph units rather than kept at double
resolution, because `Glyph` promises `bitmapWidth == width + 2 * GLYPH_BORDER` and every reader
assumes it, where upstream carries an `isDoubleResolution` flag to the atlas; and an advance is
measured **between two sentinel letters**, because Compose trims a line's trailing whitespace and a
space would otherwise measure zero.

A style with no `glyphs` URL and no font file falls back to Compose's own text stack
(`LabelArt.Measured`): labels still render, with the platform's default font, `text-font` ignored and
the halo approximated by a blur. `library/tools/fetch-glyphs-proto.sh` re-fetches upstream's `glyphs.proto` for diffing against
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
`ExpressionOrValue.Invalid`, evaluates to null, and lets the painter's `?: default` apply. A
*constant* of the wrong shape — `"line-cap": ["bla"]`, which reaches the constant path precisely
because `bla` is not a known operator — is reported the same way rather than thrown, since a decode
that throws leaves `getMapLibreConfiguration` returning `Result.failure` and blanks the whole map.

**Global state.** `["global-state", k]` reads the map baked into its `StyleExpression` at compile
time, which is upstream's `createExpression(value, key, spec, globalState)`. Compilation happens
inside the style's `Json` decode here, and a kotlinx serializer is handed no per-decode context, so
`data/DecodeStyle.kt` parses the style to a tree first, lifts `state` off the root — JSON promises
nothing about key order, and `state` may follow `layers` — and leaves the flattened defaults in
`StyleGlobalState` for the two serializers to pick up. That object is the same shape as
`StyleDiagnostics`, which exists because the same seam gives a serializer no way to report a
non-fatal parse error either.

**Evaluation holds no state, which is a divergence.** Upstream's `StyleExpression` reuses one
mutable `EvaluationContext` across evaluations (`_evaluator`) to avoid per-feature allocation; that
is safe in maplibre-gl-js because JS has one thread. Here a parsed style is one object graph, so
there is one `StyleExpression` per style property shared by every tile worker
(`core/TileCollector.kt`) and by the symbol layout pass. `EvaluationContext` is therefore immutable
and built per evaluation. A shared one is a data race with a specific and misleading symptom: a
worker overwrites `feature` mid-tree-walk, the walk reads another feature's properties, the type
error is caught by `StyleExpression.evaluate`, and the property falls back to its spec default —
`line-color`'s default is black, so swisstopo's white roads rendered black; and a raced *filter*
simply drops `road_fill`, leaving the dark casing. It showed under overzoom because all `span²`
sibling map tiles walk the same ancestor's whole feature list at once. `StyleExpression.warningHistory`
and the runtime `to-color` cache are copy-on-write for the same reason, as is
`SpriteManager`'s sprite cache — an `LruCache.get` reinserts the entry, so an unguarded read is a
write.

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
- **`fill-layer-opacity` / `line-layer-opacity`** composite the layer once **per tile bitmap**,
  where upstream composites once per viewport framebuffer. The result is the same: a tile is clipped
  to its own bitmap and tiles are disjoint on screen, so no two features of the layer overlap across
  a seam, and `over(dst, alpha * layer)` is what upstream's blit does either way. What is inert is
  the property's `transition`, as it is for every transitionable property here.

**Forced by having no camera pitch or bearing at rasterization time:**

- `circle-pitch-scale`, `circle-pitch-alignment`, `icon-pitch-alignment`, `text-pitch-alignment` are
  inert — there is no pitch.
- A `viewport`-anchored `*-translate` is not counter-rotated (`renderer/utils/PaintUtils.kt`). Tiles
  are rasterized once and then rotated with the map, so the bearing is unknown at draw time; a
  `viewport` anchor applies the same unrotated offset as `map` and differs only on a rotated map.
- `hillshade-illumination-anchor` is inert for the same reason, so the light is always map-anchored.
  Note this is the spec *default*, so a style that says nothing gets map-anchored light. It applies
  to every source of a `multidirectional` layer alike.

**Forced by locale data — `Intl` has no single Kotlin Multiplatform equivalent:**

- **`["number-format", …]` is platform-backed**, not hand-rolled: `formatNumberPlatform`
  (`spec/style/expression/definitions/PlatformNumberFormat.kt`) is `Intl.NumberFormat` on wasm,
  `NSNumberFormatter` on iOS and `java.text.NumberFormat` on Android and desktop, so grouping,
  decimal separators, currency symbols and a currency's own digit count are real CLDR data. Three
  things still differ. **Unit names come from a shared English table**
  (`definitions/NumberFormatUnits.kt`), CLDR's `short` forms — no JVM API exposes the localized
  ones, and four targets agreeing on `m` beats three localizing it while desktop appends `meter`.
  **With no `locale` the host's default is used**, which is what upstream does too, so output
  differs between platforms exactly as it differs between browsers; it is why
  `number-format/default` is the one fixture of its group still listed in
  `ExpressionConformanceTest.KNOWN_DIVERGENCES`. And **an option `Intl` would reject is kept
  rather than thrown** — an unknown unit or currency code, a minimum above the maximum — where
  upstream's `RangeError` costs the property its whole value.
- **`["collator", …]` is platform-backed too**, and for the same reason: `compareLocalized` /
  `resolveLocalePlatform` (`spec/style/expression/types/PlatformCollator.kt`) are `Intl.Collator` on
  wasm, `NSString.compare(_:options:range:locale:)` on iOS and `java.text.Collator` on Android and
  desktop, so letter ordering is real CLDR data — Swedish "ä" sorts after "z" on every target, where
  the hand-rolled fold table this replaced put it next to "a" in every locale, and
  `["resolved-locale", …]` performs ECMA-402 lookup matching instead of echoing the tag back. Three
  things still differ. **Upstream's `usage: 'search'` collation is wasm-only** — it is what makes
  German "ü" == "ue" and German "ä" a primary-distinct letter, and neither `java.text` nor Foundation
  can ask for it; those are the three `collator` fixtures still listed in
  `ExpressionConformanceTest.KNOWN_DIVERGENCES`, of which wasm now satisfies the one that does not
  also turn on an unresolvable `dk` locale — the other two collate that input by the host default,
  exactly as `number-format/default` formats by it. **`java.text` has
  no case level**, so `case` sensitivity is a `PRIMARY` comparison with a lowercase-before-uppercase
  tie-break rather than ICU's `caseLevel`. And **Foundation has no strength setting**, so `base`
  sensitivity folds diacritics away rather than demoting them to a secondary difference: on iOS a
  locale where an accented letter is a *letter* reads it as its base there, so Swedish "ä" == "a"
  while "ä" > "z" still holds. **With no `locale` the host's default is used**, as it is for
  `number-format` and as upstream does.

**Forced by drawing into a tile bitmap rather than sampling a texture:**

- **Paint properties are evaluated at the tile's integer zoom**, where upstream evaluates them at
  the fractional map zoom, per frame, from uniforms (`renderer/BaseRenderer.kt`,
  `core/VectorRasterizer.kt`'s `actualZoom`). A tile reaches the vector package through a
  `TileStreamProvider`, whose only zoom is the tile's, and it is rasterized once and cached by
  `(row, col, z)`. Two consequences show: an `["interpolate", …, ["zoom"], …]` on `line-width` or
  `circle-radius` steps at integer zooms instead of moving continuously, and such a width scales
  with the tile between pyramid levels rather than staying put in screen pixels. Fixing it means
  re-rasterizing the viewport as the map zooms — and doing it for *some* tiles would seam wherever
  a line crosses a tile edge, because two neighbours rendered at different zooms disagree about
  every width. Two knock-on effects: `patternZoomScale` (`renderer/utils/PaintUtils.kt`) is
  therefore always `1`, and `HillshadeLayerPainter`'s `slopeDivisor` gets the integer zoom.
- **Symbols are one viewport overlay above all tile content**, so a non-symbol style layer declared
  *after* a symbol layer cannot cover its labels. `TileRenderer` skips symbol layers and
  `SymbolComposer` draws every symbol of every symbol layer above the whole tile canvas, because
  collision has to run across the viewport rather than per tile. MapCompose's core composites every
  layer into **one** bitmap per tile (`core/TileCollector.kt`), so interleaving would mean a second
  raster pass per band of style layers. Measured against the vendored
  styles, one layer in one style is affected (`test_style_swisstopo.json`'s `hazard` fill).
  Ordering *among* symbols is upstream's, and upstream has **two** orders that are not each other
  reversed (`SymbolOrdering`, `PlacementOrder`, `SymbolDrawOrder`): a later style layer is placed
  first and drawn last, but a lower `symbol-sort-key` is placed first *and* drawn first, i.e.
  underneath, and `symbol-z-order: viewport-y` orders by the anchor projected into the viewport,
  descending for placement and ascending for drawing. `source` suppresses only the y ordering, not
  the sort key; `auto` orders by y only where the layer allows overlap.
- `raster-fade-duration` is inert. A tile is rasterized once and handed to the tile pipeline as
  bytes; there is no frame loop and no per-tile load timeline to cross-fade against.
- Raster, hillshade, color-relief and heatmap output is resampled twice — once into the tile bitmap, again when
  that bitmap is drawn. That is the price of keeping those layers in style order among the vector
  layers rather than making them overlays.
- A source's `bounds` is not honoured.
- `hillshade`'s and `color-relief`'s output is computed per DEM sample and the resulting *colours*
  are then filtered onto the tile, where upstream filters the DEM texture and shades per screen
  pixel. It shows only where one DEM sample covers several screen pixels, and it is what
  `color-relief`'s `resampling` ends up selecting between.
- `heatmap-opacity` is folded into the colour ramp at rasterization, so it cannot animate — the same
  root cause as `raster-fade-duration`.
- Only the 8 immediate neighbours are gathered for a heatmap, so a `heatmap-radius` past roughly one
  tile still clips.
- A `*-pattern` is anchored to the world and scaled by density and by `2^(tileZoom - actualZoom)`
  (`renderer/utils/PaintUtils.kt`), which is the shader's `u_pixel_coord_*` and `u_scale` baked into
  the repeated bitmap because a Compose `ShaderBrush` has no local matrix.

**Symbols** — the placement architecture, not the properties. See
[Symbol layout and placement](#symbol-layout-and-placement) for what the two passes are:

- **A label is rasterized once per bucket and scaled at draw.** Layout rasterizes it at the largest
  size the style asks for while that bucket is on screen, and the placement pass scales it down by
  `textScale` for the zoom actually being drawn, so a label is resampled between integer zooms.
  Upstream shades glyphs in the fragment shader at the final screen resolution, which needs a GPU
  path this port does not have; the alternative is re-shaping every label on every viewport update,
  which is what the split exists to stop.
- **Layout properties are evaluated at the bucket's integer zoom**, not the fractional map zoom, as
  upstream's worker does. `text-size` and `icon-size` are the exception and reach the fractional zoom
  through `SizeData`. Paint properties are evaluated at the bucket's zoom too, because a label's
  colour and halo are baked into its raster rather than passed as vertex attributes.
- **Line label spacing is baked at the bucket's zoom.** `symbol-spacing` and the anchor walk are
  measured against `LAYOUT_TILE_SIZE`, so they no longer drift as the map zooms within a level.
  Upstream bakes them into its bucket for the same reason, and carries the same factor-of-two wobble
  across a level boundary.
- **`PauseablePlacement` is not ported.** Upstream spreads one placement over frames on a 2 ms
  budget and renders the partial result, because it has one thread. `VectorRasterizer.place` runs
  the whole pass on `Dispatchers.Default` instead -- `MapState.scope` is `Dispatchers.Main`, and a
  collision pass over every symbol on screen has no business there -- so there is nothing to
  interleave and no partially-placed frame. `forceFullPlacement`, which exists to keep incremental
  placement from starving, goes with it.
- One tree stands in for upstream's two `GridIndex`es: `*-ignore-placement` is the *insert* side
  only, as it is upstream (`collision_index.ts`'s `grid` vs `ignoredGrid`) -- such a symbol blocks
  nobody but is still tested against everybody, which is `*-allow-overlap`'s job and not this
  property's. The second grid is not needed because its only upstream reader is
  `queryRenderedSymbols`, which this port does not have.
- **The index holds oriented boxes where upstream's holds axis-aligned ones**, and that is
  deliberate: upstream replaces a rotated box by its envelope back in `collision_feature.ts`
  ("Collision features require an 'on-axis' geometry, so take the envelope of the rotated
  geometry"), and again in `_projectCollisionBox` via `getAABB(points)`. Keeping the real
  orientation means two crossing road labels pack as tightly as their bodies allow rather than as
  their envelopes do, so this port suppresses strictly less than MapLibre, never more. The R-tree's
  AABB query is the broad phase; `OBB.intersects` (separating axis) is the exact test.
- **A line label is a chain of circles** (`LabelPlacement.circles`), upstream's
  `placeCollisionCircles` against `placeCollisionBox`, because the straight envelope of a label
  following a curve claims far more ground than the label covers. The walk is by half the label's
  **drawn** width, converted into layout units by the bucket's own projection factor
  (`2^(bucketZoom - displayZoom)`, in (0.5, 1]); walking half its *layout* width, as this used to,
  made the chain that factor too short, so two labels the eye sees overlapping were both placed.
  `symbol/SymbolProjection.kt` walks
  the label's own stretch of line, projects it, and spaces circles along it at upstream's
  `radius * 2.5`; `symbol/CollisionGeometry.kt` ports `_circlesCollide` and `_circleAndRectCollide`
  from `grid_index.ts` and adds the circle-against-oriented-box test upstream has no need of, and
  `insert` adds one R-tree entry per circle as `insertCollisionCircles` adds one grid circle per
  circle. Upstream's `placeFirstAndLastGlyph` walks by the first and last glyph's offsets, where this
  walks by half the label's width -- the same span without the per-glyph bookkeeping a GPU quad
  buffer needs.
- **The index is bounded by the padded viewport**, as upstream's grid is: `CollisionDetector` takes
  the viewport size and refuses -- neither places nor indexes -- a symbol whose box falls entirely
  outside the viewport grown by `VIEWPORT_PADDING`, upstream's `viewportPadding = 100`. A symbol
  inside that margin still competes, which is what keeps labels near the edge from reshuffling as
  the map pans. The refusal comes before the overlap mode is consulted, so `*-allow-overlap` does
  not exempt a symbol from it, exactly as upstream folds `!isInsideGrid` into `unplaceable`.
  Upstream's companion `isOffscreen` is not ported: its only consumer is the placement fade's
  `skipFade`, which this port derives from the projected anchor instead.
- Not ported, and so behaving as upstream does at its defaults: collision *groups*
  (`CollisionGroups`, `crossSourceCollisions`) -- upstream defaults to one group for all sources,
  which is what this port always does, but there is no way to ask for per-source groups. Everything
  gated on a camera is absent for want of one: `perspectiveRatioCutoff`, the globe occlusion tests,
  and the pitched-label projection in `_projectCollisionBox`.
- Two parts of `cross_tile_symbol_index.ts` are deliberately not ported: the `KDBush` index it builds
  once a key carries more than 128 symbols, which its own comments say agrees with the linear path it
  replaces, and `handleWrapJump`, which re-keys the indexes when a longitude wraps -- MapCompose's
  infinite scroll wraps the map's *x* rather than a longitude, and a tile reference here carries no
  wrap to rewrite.
- Line labels are also de-duplicated *within* a tile by upstream's own `anchorIsTooClose` -- a repeat
  of the same text within half a `symbol-spacing` of an anchor already taken is dropped before it
  reaches collision.
- Line anchors are upstream's: `symbol/LineLabelPlacement.kt` transcribes
  `symbol/get_anchors.ts` (the spacing enlargement for a long label, the first-anchor offset, the
  in-tile test, the "does the whole label fit on the line" test and the single retry at the middle
  of a line that is not continued), and `SymbolBucketBuilder` runs `renderer/utils/MergeLines.kt` --
  upstream's `merge_lines.ts` -- over a `symbol-placement: line` layer's features first, so a road
  arriving as one MVT feature per block is labelled as one long line. `renderer/utils/ClipLine.kt`
  clips those lines to the tile beforehand, as `symbol_layout.ts` does; `line-center` deliberately
  does not clip.
- `symbol-avoid-edges` is honoured, dropping a label whose padded box crosses the tile edge.
  Upstream declares the property and never reads it, relying on its cross-tile index instead.
- **A line label whose glyphs do not fit on its path is drawn straight**, at the path's chord
  angle, where upstream's `placeGlyphsAlongLine` hides it (`notEnoughRoom`). Hiding it here would
  flicker: the placement pass has already decided the label fits and would keep re-placing it. A
  path that bends less than half a dp likewise takes the single-blit path — the chord, not the
  anchor segment's angle, so a label does not jump as the map zooms across that threshold.
- **The Compose fallback (`LabelArt.Measured`) is never bent.** A `TextLayoutResult` can only be
  drawn whole, so a per-glyph pass would repaint the entire paragraph once per glyph and would split
  on UTF-16 code units rather than clusters. A style with a `glyphs` URL — which is every style that
  renders text the way MapLibre does — takes the glyph path.
- **`text-rotate` does not reach a line label**, as it does not upstream: the glyph angles come from
  the line.
- Text is shaped in logical order: there is no bidirectional reordering (upstream delegates that to
  an optional `rtl-text-plugin`) and no shaping *across* cluster boundaries. Within a cluster a
  `font-faces` file is shaped by the platform, so Devanagari and Khmer conjuncts are right; Arabic
  contextual forms, which join across clusters, are not.
- A codepoint no `font-faces` file covers and the glyph server has no glyph for is dropped, where
  upstream falls back to a locally rendered `TinySDF` for CJK (`localIdeographFontFamily` is a map
  option rather than a style property, and is not ported).
- **A grapheme cluster no declared file covers is left to the server codepoint by codepoint**, where
  upstream draws nothing for it — only a file can draw a cluster whole. Decomposing does take a
  letter apart from its marks, but the alternative is that adding a `font-faces` block for one
  script silently blanks every accented word of another, which the server was serving perfectly well
  before.
- A `font-faces` file's weight and style are not sniffed out of its family name, as upstream does not
  either for such a file (`sniffFontStyles: false`): the file carries its own. Upstream's TinySDF
  `lang` is not passed, and a relative `url` is not resolved against the style URL — the same as
  `glyphs` and `sprite`.
- On Android a declared font file goes through a temporary file in the app's cache directory:
  `Typeface.Builder(ByteBuffer)` is API 29 against this library's `minSdk 24`, and Compose's Android
  overloads take no bytes. A file the platform cannot read leaves its codepoints to the server.

**Global state is bound at load, and there is no runtime setter.** Upstream hands
`createExpression` a live object and `setGlobalStateProperty` mutates it in place, re-evaluating what
depends on it; here the flattened `state` defaults are baked into each expression when the style is
decoded, so what a style declares is what it renders with for its lifetime. What a setter would need
is already in place on the reading side — `StylePropertyExpression.globalStateRefs` and
`FeatureFilter.getGlobalStateRefs()` say which properties to invalidate — but nothing on the writing
side: no cache key in `core/VectorRasterizer.kt` carries style state, so its seven `LruCache`s and
`VectorLayer.symbolBuckets` would all have to be dropped, and the map itself is read by the whole
tile-worker pool, which makes it copy-on-write or nothing.

**GeoJSON** — `data/geojson/GeoJsonTiler.kt`: `geojson-vt` builds a tile pyramid up front and splits
each parent into four children, reusing the parent's already-clipped geometry, and precomputes each
vertex's simplification distance once for all zooms. This cuts every requested tile straight from the
whole document and runs Douglas-Peucker per tile at that tile's own tolerance — the same shape of
result, recomputed. `cluster` and `lineMetrics` are not supported, and are reported as such.

**MapLibre Tiles (`encoding: "mlt"`) are not decoded.** Upstream selects between the MVT and MLT
decoders on `params.encoding` (`src/source/vector_tile_worker_source.ts`) and delegates the latter
to the npm package `@maplibre/mlt`; there is no Kotlin or Kotlin Multiplatform decoder to delegate
to, and the format is still experimental. A vector source declaring it is refused at load with a
`StyleDiagnostic` and draws nothing, rather than being fed to the protobuf decoder -- which does not
fail on foreign bytes, it collects them into `Tile.unknownFields`. See **Sources** above.

## Tile resolution

A tile is rasterized at
`vectorTileBitmapSize(mapState.tileSize, density, superSamplingFactor, magnifyingFactor)`
(`core/VectorLayer.kt`) and drawn into `mapState.tileSize * relativeScale` device pixels, with
`relativeScale ∈ (2^(mf-1), 2^mf]` because `VisibleTilesResolver.getLevel` rounds the level up after
subtracting the magnifying factor. Sizing against `max(density, 2^magnifyingFactor)` is what keeps
the bitmap ≥ the destination, so a tile is always minified and never stretched. `addVectorLayer`'s
`superSamplingFactor` renders larger still and filters back down in `VectorRasterizer.getTile`,
costing `factor²` fill.

Symbols are sized in none of those spaces. They are a viewport overlay, laid out against the constant
`LAYOUT_TILE_SIZE` and projected to the screen by the placement pass -- see
[Symbol layout and placement](#symbol-layout-and-placement). What `VectorLayer` derives from a tile's
on-screen size (`fullWidth * scale / 2^zoom`) is not a layout space any more but the map's
*fractional* zoom, which is the zoom `text-size` and `icon-size` are evaluated at.

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
  conformance suite, filters, geometry decoding, `HillshadeShading`, `ColorReliefRamp`,
  `HeatmapKernel`, `SdfShading`, `StretchableIcon`, `AnchorOffsets`, `GlyphPbf`, `GlyphLayout`,
  `GlyphManager`, `GeoJson`, `GeoJsonTiler`, the R-tree and the OBB. MVT fixtures are built by hand in
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
`SymbolSizeUpstreamTest` and `CrossTileSymbolIndexUpstreamTest` are the symbol pair;
`symbol_size.test.ts` transcribes directly, and `cross_tile_symbol_index.test.ts` needs only its
anchors converted from MVT `EXTENT` units into this port's layout pixels.

Upstream's `placement.test.ts` and `projection.test.ts` are *not* transcribed, for the same reason
the `*_bucket` tests are not: they drive `Placement` through a `Style`, a `Transform` and a GL
`SymbolBucket`, none of which has an analogue here. `PlacementTest`, `PlacementSchedulingTest`,
`OpacityStateTest`, `SymbolProjectionTest` and `SymbolBucketCacheTest` assert the same behaviour
directly — `PlacementSchedulingTest` being the one that pins the rule that keeps labels steady, that
a viewport which moved inside the window defers instead of replacing. Fixtures are in
`commonTest/.../symbol/SymbolFixtures.kt` — buckets, instances, viewports and a whole
`VectorRasterizer` built from plain numbers, with a `LabelArt` that has a box and no ink, so none of
them needs a graphics backend.
