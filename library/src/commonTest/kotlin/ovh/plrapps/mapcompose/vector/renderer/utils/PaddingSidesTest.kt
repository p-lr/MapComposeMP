package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The CSS 1/2/3/4-value expansion a `padding`-typed property goes through.
 *
 * Port of `Padding.parse` (`maplibre-style-spec/src/expression/types/padding.ts`). `icon-padding` is
 * the one property in the spec with that type, and it was modelled as a plain `Double` here, so an
 * asymmetric value failed the constant decode and fell back to the default.
 */
class PaddingSidesTest {

    @Test
    fun `one value is every side`() {
        assertEquals(PaddingSides(3f, 3f, 3f, 3f), paddingSides(listOf(3.0), default = 2.0))
    }

    @Test
    fun `two values are vertical then horizontal`() {
        assertEquals(PaddingSides(1f, 2f, 1f, 2f), paddingSides(listOf(1.0, 2.0), default = 2.0))
    }

    @Test
    fun `three values name the two verticals and share the horizontal`() {
        assertEquals(
            PaddingSides(1f, 2f, 3f, 2f),
            paddingSides(listOf(1.0, 2.0, 3.0), default = 2.0),
        )
    }

    @Test
    fun `four values are top right bottom left`() {
        assertEquals(
            PaddingSides(1f, 2f, 3f, 4f),
            paddingSides(listOf(1.0, 2.0, 3.0, 4.0), default = 2.0),
        )
    }

    @Test
    fun `an absent value falls back to the spec default`() {
        assertEquals(PaddingSides(2f, 2f, 2f, 2f), paddingSides(null, default = 2.0))
        assertEquals(PaddingSides(2f, 2f, 2f, 2f), paddingSides(emptyList(), default = 2.0))
    }

    @Test
    fun `the scale is applied to every side`() {
        assertEquals(
            PaddingSides(2f, 4f, 6f, 8f),
            paddingSides(listOf(1.0, 2.0, 3.0, 4.0), default = 2.0, scale = 2f),
        )
    }

    @Test
    fun `an asymmetric padding grows the box and moves its centre`() {
        val padding = paddingSides(listOf(0.0, 20.0, 0.0, 0.0), default = 2.0)
        assertEquals(20f, padding.width)
        assertEquals(0f, padding.height)
        assertEquals(10f, padding.centerShiftX, "the box grows to the right alone")
        assertEquals(0f, padding.centerShiftY)
    }
}
