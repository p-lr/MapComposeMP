package ovh.plrapps.mapcompose.maplibre.spec.style

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutTypeTest {
    @Test
    fun `parse should handle different cases`() {
        assertEquals(LayoutType.FILL, LayoutType.parse("fill"))
        assertEquals(LayoutType.FILL, LayoutType.parse("FILL"))
        assertEquals(LayoutType.FILL, LayoutType.parse("Fill"))
        assertEquals(LayoutType.FILL, LayoutType.parse("fIlL"))
    }

    @Test
    fun `parse should handle hyphenated types`() {
        assertEquals(LayoutType.FILL_EXTRUSION, LayoutType.parse("fill-extrusion"))
        assertEquals(LayoutType.FILL_EXTRUSION, LayoutType.parse("FILL-EXTRUSION"))
        assertEquals(LayoutType.FILL_EXTRUSION, LayoutType.parse("Fill-Extrusion"))
    }

    @Test
    fun `parse should return UNDEFINED for invalid input`() {
        assertEquals(LayoutType.UNDEFINED, LayoutType.parse("invalid"))
        assertEquals(LayoutType.UNDEFINED, LayoutType.parse(""))
        assertEquals(LayoutType.UNDEFINED, LayoutType.parse(" "))
    }
} 