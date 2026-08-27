package ovh.plrapps.mapcompose.vector.spec.sprites

import kotlinx.serialization.Serializable

/**
 * One entry of a sprite sheet's index JSON.
 *
 * Field names are the sheet format's own camelCase, not the style spec's kebab-case -- see
 * https://maplibre.org/maplibre-style-spec/sprite/.
 *
 * [x], [y], [width] and [height] are in *sheet* pixels. A sheet built for a hidpi screen packs
 * [pixelRatio] sheet pixels into one layout pixel, so anything the style measures in pixels --
 * `icon-size`, a `*-pattern`'s tile -- must go through [layoutWidth] / [layoutHeight] instead. That
 * division used to be missing, which drew every icon of an `@2x` sheet at twice its intended size.
 */
@Serializable
data class Sprite(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,

    /**
     * Sheet pixels per layout pixel: `1` for a plain sheet, `2` for an `@2x` one.
     *
     * A number rather than an integer because real sheets write it as `1.0`, which is what the
     * spec's `number` type allows.
     */
    val pixelRatio: Float = 1f,

    /**
     * Horizontally stretchable ranges, each `[from, to]` in sheet pixels.
     *
     * Present only on a stretchable ("nine-patch") icon. An icon with no range stretches uniformly,
     * as upstream's `ImagePosition` does when `stretchX` is absent.
     */
    val stretchX: List<List<Double>>? = null,

    /** Vertically stretchable ranges; see [stretchX]. */
    val stretchY: List<List<Double>>? = null,

    /** `[left, top, right, bottom]` in sheet pixels: the box `icon-text-fit` fits the label into. */
    val content: List<Double>? = null,

    /** `stretchOrShrink` / `stretchOnly` / `proportional`: how `icon-text-fit` may resize the icon. */
    val textFitWidth: String? = null,
    val textFitHeight: String? = null,

    /** Whether the entry is a signed-distance field to be recoloured, rather than a plain image. */
    val sdf: Boolean = false,
) {
    /** The icon's width in layout pixels -- what `icon-size` scales. */
    val layoutWidth: Float get() = width.toFloat() / pixelRatio

    /** The icon's height in layout pixels -- what `icon-size` scales. */
    val layoutHeight: Float get() = height.toFloat() / pixelRatio
}
