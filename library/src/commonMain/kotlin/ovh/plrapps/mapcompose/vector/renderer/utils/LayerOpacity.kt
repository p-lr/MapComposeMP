package ovh.plrapps.mapcompose.vector.renderer.utils

import ovh.plrapps.mapcompose.vector.spec.style.FillLayer
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.LineLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat

/**
 * `fill-layer-opacity` / `line-layer-opacity`: the opacity the *whole layer* is composited at,
 * once, after every one of its features has been drawn.
 *
 * It is not `fill-opacity` scaled. Per-feature opacity -- and the alpha of `fill-color` itself --
 * accumulates where two features of the layer overlap, and upstream keeps that: it draws the layer
 * into its own framebuffer, accumulation and all, then blits that framebuffer once at this alpha
 * (`src/webgl/draw/draw_layer_opacity.ts`), so overlapping features read as a single surface.
 * [ovh.plrapps.mapcompose.vector.renderer.TileRenderer] reproduces the pass with a `saveLayer`.
 *
 * The spec types both as `data-constant` with the parameters `["zoom", "global-state"]` -- no
 * `feature` -- hence the `null` feature here, and clamps them to `0..1`, hence the [coerceIn]. The
 * layer types that have no such property, which is all of them but `fill` and `line`, are opaque.
 */
internal fun layerOpacityOf(styleLayer: Layer, actualZoom: Double): Float = when (styleLayer) {
    is FillLayer -> styleLayer.paint.fillLayerOpacity.processAsFloat(feature = null, zoom = actualZoom)
        ?: StyleSpecDefaults.FILL_LAYER_OPACITY.toFloat()

    is LineLayer -> styleLayer.paint.lineLayerOpacity.processAsFloat(feature = null, zoom = actualZoom)
        ?: StyleSpecDefaults.LINE_LAYER_OPACITY.toFloat()

    else -> 1f
}.coerceIn(0f, 1f)
