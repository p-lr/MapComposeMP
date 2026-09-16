package ovh.plrapps.mapcompose.vector.renderer.utils

import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import ovh.plrapps.mapcompose.vector.spec.style.ICON_TEXT_FIT_BOTH
import ovh.plrapps.mapcompose.vector.spec.style.ICON_TEXT_FIT_HEIGHT
import ovh.plrapps.mapcompose.vector.spec.style.ICON_TEXT_FIT_NONE
import ovh.plrapps.mapcompose.vector.spec.style.ICON_TEXT_FIT_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Nine-patch stripe geometry and `icon-text-fit` sizing. */
class StretchableIconTest {

    private fun sprite(
        width: Int = 20,
        height: Int = 20,
        pixelRatio: Float = 1f,
        content: List<Double>? = null,
        stretchX: List<List<Double>>? = null,
        stretchY: List<List<Double>>? = null,
        textFitWidth: String? = null,
        textFitHeight: String? = null,
    ) = Sprite(
        width = width, height = height, x = 0, y = 0, pixelRatio = pixelRatio,
        stretchX = stretchX, stretchY = stretchY, content = content,
        textFitWidth = textFitWidth, textFitHeight = textFitHeight,
    )

    @Test
    fun `an icon with no stretchable range scales uniformly`() {
        val stripes = stretchStripes(stretches = null, sourceLength = 10f, targetLength = 30f)
        assertEquals(listOf(IconStripe(0f, 10f, 0f, 30f)), stripes)
    }

    @Test
    fun `fixed parts keep their size and the stretch absorbs the rest`() {
        // A 10 px icon stretchable only between 4 and 6, drawn at 20 px: the two 4 px borders stay,
        // the 2 px middle becomes 12.
        val stripes = stretchStripes(listOf(listOf(4.0, 6.0)), sourceLength = 10f, targetLength = 20f)
        assertEquals(3, stripes.size)
        assertEquals(IconStripe(0f, 4f, 0f, 4f), stripes[0])
        assertEquals(IconStripe(4f, 6f, 4f, 16f), stripes[1])
        assertEquals(IconStripe(6f, 10f, 16f, 20f), stripes[2])
    }

    @Test
    fun `several stretchable ranges share the difference in proportion`() {
        val stripes = stretchStripes(
            listOf(listOf(2.0, 4.0), listOf(6.0, 10.0)),
            sourceLength = 12f,
            targetLength = 24f,
        )
        val stretched = stripes.filter { it.srcLength != it.dstLength }
        assertEquals(2, stretched.size)
        // 12 px to give away, split 2:4 between a 2 px and a 4 px range.
        assertEquals(6f, stretched[0].dstLength)
        assertEquals(12f, stretched[1].dstLength)
    }

    @Test
    fun `the stripes tile the whole axis without a gap`() {
        val stripes = stretchStripes(listOf(listOf(3.0, 5.0)), sourceLength = 16f, targetLength = 40f)
        assertEquals(0f, stripes.first().dstFrom)
        assertEquals(40f, stripes.last().dstTo)
        for ((a, b) in stripes.zipWithNext()) {
            assertEquals(a.srcTo, b.srcFrom)
            assertEquals(a.dstTo, b.dstFrom)
        }
    }

    @Test
    fun `a target smaller than the fixed parts falls back to a uniform scale`() {
        val stripes = stretchStripes(listOf(listOf(4.0, 6.0)), sourceLength = 10f, targetLength = 5f)
        assertTrue(stripes.all { it.dstLength >= 0f }, "no stripe may invert: $stripes")
        assertEquals(5f, stripes.last().dstTo)
    }

    @Test
    fun `a degenerate range is ignored`() {
        val stripes = stretchStripes(listOf(listOf(4.0, 4.0)), sourceLength = 10f, targetLength = 20f)
        assertEquals(listOf(IconStripe(0f, 10f, 0f, 20f)), stripes)
    }

    @Test
    fun `icon-text-fit none leaves the icon alone`() {
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_NONE, sprite = sprite(), iconWidth = 20f, iconHeight = 20f,
            textWidth = 100f, textHeight = 40f, padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(20f, size.width)
        assertEquals(20f, size.height)
    }

    @Test
    fun `icon-text-fit width grows only the width`() {
        /* Upstream's `fitIconToText` stretches the icon to the label's own extent plus the padding;
         * the sprite's frame is absorbed by the nine-patch stretch rather than added on top, which
         * is what `icon-text-fit-padding` is authored to leave room for. */
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_WIDTH,
            sprite = sprite(content = listOf(5.0, 5.0, 15.0, 15.0)),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 40f, textHeight = 8f,
            padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(40f, size.width)
        assertEquals(20f, size.height)
    }

    @Test
    fun `icon-text-fit height grows only the height`() {
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_HEIGHT,
            sprite = sprite(content = listOf(5.0, 5.0, 15.0, 15.0)),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 40f, textHeight = 30f,
            padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(20f, size.width)
        assertEquals(30f, size.height)
    }

    @Test
    fun `icon-text-fit padding is added around the label`() {
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_BOTH,
            sprite = sprite(content = listOf(5.0, 5.0, 15.0, 15.0)),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 40f, textHeight = 30f,
            padding = listOf(1.0, 2.0, 3.0, 4.0),
        )
        assertEquals(40f + 4f + 2f, size.width)
        assertEquals(30f + 1f + 3f, size.height)
    }

    @Test
    fun `icon-text-fit shrinks a large icon onto a short label`() {
        /* `fitIconToText` has no `max` in it, so a big shield around a two-character label comes
         * down to the label. This used to return the icon's own size, which is what
         * `assertEquals(20f, …)` pinned here before. */
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_BOTH,
            sprite = sprite(content = listOf(5.0, 5.0, 15.0, 15.0)),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 2f, textHeight = 2f,
            padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(2f, size.width)
        assertEquals(2f, size.height)
    }

    // region textFitWidth / textFitHeight
    //
    // `applyTextFit` (`src/symbol/shaping.ts`), reached only for an entry that declares either --
    // upstream's `if (image.textFitWidth || image.textFitHeight)` in `quads.ts`. Both fields were
    // parsed here and never read.

    @Test
    fun `the default stretchOrShrink imposes no aspect ratio`() {
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_BOTH,
            sprite = sprite(content = listOf(0.0, 0.0, 20.0, 10.0)),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 40f, textHeight = 40f,
            padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(40f, size.width)
        assertEquals(40f, size.height)
    }

    @Test
    fun `a proportional height widens the icon to the content's aspect ratio`() {
        // A content box twice as wide as it is tall, and a label that is square: `proportional`
        // height with `stretchOnly` width takes the width up to twice the height.
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_BOTH,
            sprite = sprite(
                content = listOf(0.0, 0.0, 20.0, 10.0),
                textFitWidth = TEXT_FIT_STRETCH_ONLY,
                textFitHeight = TEXT_FIT_PROPORTIONAL,
            ),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 40f, textHeight = 40f,
            padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(80f, size.width)
        assertEquals(40f, size.height)
    }

    @Test
    fun `a stretchOnly width is left alone once it already exceeds the ratio`() {
        // Wider than the content's aspect ratio asks for: `stretchOnly` may not bring it back down.
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_BOTH,
            sprite = sprite(
                content = listOf(0.0, 0.0, 20.0, 10.0),
                textFitWidth = TEXT_FIT_STRETCH_ONLY,
                textFitHeight = TEXT_FIT_PROPORTIONAL,
            ),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 120f, textHeight = 40f,
            padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(120f, size.width)
    }

    @Test
    fun `a proportional width heightens the icon to the content's aspect ratio`() {
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_BOTH,
            sprite = sprite(
                content = listOf(0.0, 0.0, 20.0, 10.0),
                textFitWidth = TEXT_FIT_PROPORTIONAL,
                textFitHeight = TEXT_FIT_STRETCH_ONLY,
            ),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 80f, textHeight = 10f,
            padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(80f, size.width)
        assertEquals(40f, size.height)
    }

    @Test
    fun `a hidpi sheet's ratio is the same ratio`() {
        // The same content box on an @2x sheet: the pixelRatio cancels in the aspect ratio.
        val size = iconTextFitSize(
            fit = ICON_TEXT_FIT_BOTH,
            sprite = sprite(
                width = 40, height = 40, pixelRatio = 2f,
                content = listOf(0.0, 0.0, 40.0, 20.0),
                textFitWidth = TEXT_FIT_STRETCH_ONLY,
                textFitHeight = TEXT_FIT_PROPORTIONAL,
            ),
            iconWidth = 20f, iconHeight = 20f,
            textWidth = 40f, textHeight = 40f,
            padding = listOf(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(80f, size.width)
    }

    // endregion
}
