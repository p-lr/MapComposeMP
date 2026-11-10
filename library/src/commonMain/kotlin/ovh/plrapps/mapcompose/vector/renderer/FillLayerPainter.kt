package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.renderer.utils.withTranslate
import ovh.plrapps.mapcompose.vector.spec.FeatureClasses
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.FillLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.followsFeatureClass
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsBoolean
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDoubleList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import kotlin.math.abs

/**
 * Draws `fill` layers.
 *
 * Follows maplibre-gl-js `src/render/draw_fill.ts`: the fill itself, then -- when `fill-antialias`
 * is on, which is the spec default -- a one-pixel outline whose alpha is
 * `outline_color * (alpha * opacity)`, as in `shaders/fill_outline.fragment.glsl`.
 *
 * `fill-antialias` is honoured on the fill itself: the path is drawn through `drawIntoCanvas` with
 * an explicit `Paint`, because `DrawScope.drawPath` builds its own paint and is always antialiased.
 * Turning it off gives the hard, aliased edges upstream's un-antialiased triangles have.
 *
 * **Divergence:** upstream falls the outline colour back to `fill-color` when `fill-outline-color`
 * is unset, because that pass is how it antialiases GPU-rasterized triangles. Skia's coverage
 * antialiasing already does that here, so a same-coloured hairline on top would only fatten every
 * polygon and bleed half a pixel into its neighbour -- it is skipped, and the outline is drawn only
 * when the style actually asks for a distinct `fill-outline-color`.
 */
class FillLayerPainter(
    private val spriteManager: SpriteManager? = null,
    private val patternBrushes: PatternBrushCache = PatternBrushCache(),
) : BaseLayerPainter<FillLayer>() {
    /* One paint for every plain fill and one for every outline of the layer, rather than a native
     * paint and its cleaner per feature. A painter draws on one thread at a time. */
    private val solidFill = Paint().apply { style = PaintingStyle.Fill }
    private val outline = Paint().apply {
        isAntiAlias = true
        style = PaintingStyle.Stroke
    }

    /** Everything the style says about one feature's fill, evaluated; see [resolve]. */
    private class ResolvedFill(
        val color: Color,
        val opacity: Float,
        val antialias: Boolean,
        val translate: List<Double>,
        /** `|dx| + |dy|` of [translate], in style pixels: how far it can move the fill. */
        val translateReach: Float,
        val translateAnchor: String,
        val patternName: String?,
        val outlineColor: Color?,
    )

    /* The per-class results, valid for exactly the inputs below; a painter serves one style layer
     * over one tile layer of one render, so in practice this is filled once and read from then on. */
    private var classResults: Array<ResolvedFill?>? = null
    private var classResultsFor: FeatureClasses? = null
    private var classResultsStyle: FillLayer? = null
    private var classResultsZoom = Double.NaN

    /**
     * The fill properties of one feature -- one evaluation per feature **class** where the layer
     * allows it, since features that agree on type and tags agree on every one of them. See
     * [FeatureClasses].
     */
    private fun resolve(
        style: FillLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        classes: FeatureClasses?,
        featureIndex: Int,
        views: FeatureViews?,
    ): ResolvedFill {
        if (classes == null || featureIndex < 0 || !followsFeatureClass(style)) {
            return evaluate(style, featureProperties ?: views?.at(featureIndex), actualZoom)
        }
        var results = classResults
        if (results == null || classResultsFor !== classes || classResultsStyle !== style ||
            classResultsZoom != actualZoom
        ) {
            results = arrayOfNulls(classes.count)
            classResults = results
            classResultsFor = classes
            classResultsStyle = style
            classResultsZoom = actualZoom
        }
        val classId = classes.classOf[featureIndex]
        return results[classId]
            ?: evaluate(style, featureProperties ?: views?.at(featureIndex), actualZoom)
                .also { results[classId] = it }
    }

    private var followsClassStyle: FillLayer? = null
    private var followsClass = false

    /** Whether every property [evaluate] reads has one value per feature class. */
    private fun followsFeatureClass(style: FillLayer): Boolean {
        if (followsClassStyle !== style) {
            val paint = style.paint
            followsClass = paint.fillColor.followsFeatureClass() && paint.fillOpacity.followsFeatureClass() &&
                paint.fillAntialias.followsFeatureClass() && paint.fillTranslate.followsFeatureClass() &&
                paint.fillTranslateAnchor.followsFeatureClass() && paint.fillPattern.followsFeatureClass() &&
                paint.fillOutlineColor.followsFeatureClass()
            followsClassStyle = style
        }
        return followsClass
    }

    private fun evaluate(style: FillLayer, featureProperties: EvalFeature?, actualZoom: Double): ResolvedFill {
        val paint = style.paint
        val translate = paint.fillTranslate.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.FILL_TRANSLATE
        return ResolvedFill(
            color = paint.fillColor?.processAsColor(featureProperties, actualZoom)
                ?: StyleSpecDefaults.FILL_COLOR,
            opacity = paint.fillOpacity.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.FILL_OPACITY.toFloat(),
            antialias = paint.fillAntialias.processAsBoolean(featureProperties, actualZoom)
                ?: StyleSpecDefaults.FILL_ANTIALIAS,
            translate = translate,
            translateReach = translate.sumOf { abs(it) }.toFloat(),
            translateAnchor = paint.fillTranslateAnchor?.processAsString(featureProperties, actualZoom)
                ?: StyleSpecDefaults.FILL_TRANSLATE_ANCHOR,
            patternName = paint.fillPattern?.processAsString(featureProperties, actualZoom),
            outlineColor = paint.fillOutlineColor?.processAsColor(featureProperties, actualZoom),
        )
    }

    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: FillLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        if (feature.type != Tile.GeomType.POLYGON) return
        val path = createPath(feature, canvasSize, extent) ?: return
        paintPath(canvas, path, style, featureProperties, actualZoom)
    }

    /**
     * Fills one feature already decoded into [path] -- by `TileGeometry`, which decodes a tile layer
     * once for every style layer that draws it. [cull], when given, skips a feature that cannot
     * reach the part of the canvas being drawn.
     */
    fun paintPath(
        canvas: DrawScope,
        path: Path,
        style: FillLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        cull: FeatureCull? = null,
    ) = paintPath(
        canvas, path, style, featureProperties, actualZoom, cull,
        classes = null, featureIndex = -1, views = null,
    )

    /**
     * [paintPath] for a feature of a tile layer whose [classes] are known: [featureIndex] is its
     * position in that layer, and the style is then evaluated once per class -- see [resolve].
     * [views] stands in for a null [featureProperties], and is only asked when the style really has
     * to be evaluated against this feature.
     */
    internal fun paintPath(
        canvas: DrawScope,
        path: Path,
        style: FillLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        cull: FeatureCull?,
        classes: FeatureClasses?,
        featureIndex: Int,
        views: FeatureViews?,
    ) {
        val resolved = resolve(style, featureProperties, actualZoom, classes, featureIndex, views)
        val translate = resolved.translate
        if (cull != null) {
            // The outline is a one-density-pixel stroke, and antialiasing reaches a pixel further.
            val reach = resolved.translateReach * canvas.density + canvas.density + 1f
            if (!cull.reaches(reach)) return
        }

        val fillColor = resolved.color
        val fillOpacity = resolved.opacity
        val antialias = resolved.antialias
        val translateAnchor = resolved.translateAnchor

        // A pattern that the sprite sheet cannot supply falls back to fill-color: dropping the
        // feature would punch a hole through every layer drawn underneath it.
        val patternBrush = patternBrushes.get(spriteManager, resolved.patternName)

        val outlineColor = resolved.outlineColor

        canvas.withTranslate(translate, translateAnchor) {
            drawIntoCanvas { canvasHandle ->
                val fill: Paint
                if (patternBrush != null) {
                    // A pattern leaves a shader in its paint, so it gets one of its own.
                    fill = Paint().apply {
                        isAntiAlias = antialias
                        this.style = PaintingStyle.Fill
                    }
                    patternBrush.applyTo(size, fill, fillOpacity)
                } else {
                    fill = solidFill
                    fill.isAntiAlias = antialias
                    fill.color = fillColor.withOpacity(fillOpacity)
                }
                canvasHandle.drawPath(path, fill)

                if (antialias && outlineColor != null) {
                    outline.strokeWidth = density
                    outline.color = outlineColor.withOpacity(fillOpacity)
                    canvasHandle.drawPath(path, outline)
                }
            }
        }
    }
}

/**
 * Multiplies a colour's own alpha by a `*-opacity` value, the way the shaders do -- rather than
 * replacing it, which would make a translucent colour opaque at full opacity.
 */
internal fun Color.withOpacity(opacity: Float): Color =
    if (opacity >= 1f) this else copy(alpha = alpha * opacity.coerceAtLeast(0f))
