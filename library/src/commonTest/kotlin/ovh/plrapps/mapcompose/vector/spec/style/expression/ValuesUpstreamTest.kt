package ovh.plrapps.mapcompose.vector.spec.style.expression

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Collator
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Transcribed from `maplibre-style-spec/src/expression/values.test.ts`.
 *
 * The `Padding`, `NumberArray`, `ColorArray`, `ProjectionDefinition` and
 * `VariableAnchorOffsetCollection` cases are dropped — those types are not modelled here. The
 * primitive and container cases, which upstream does not test but this port relies on throughout,
 * are added.
 */
class ValuesUpstreamTest {

    @Test
    fun `typeOf`() {
        assertEquals(ColorType, typeOf(Color.Red))
        assertEquals(CollatorType, typeOf(Collator(false, false, null)))
        assertEquals(FormattedType, typeOf(Formatted.factory("a")))
        assertEquals(ResolvedImageType, typeOf(ResolvedImage.fromString("img")))
    }

    @Test
    fun `typeOf primitives and containers`() {
        assertEquals(NullType, typeOf(null))
        assertEquals(StringType, typeOf("a"))
        assertEquals(BooleanType, typeOf(true))
        assertEquals(NumberType, typeOf(1.0))
        assertEquals(ObjectType, typeOf(mapOf("a" to 1.0)))

        // A homogeneous array takes its element type and length; a mixed one widens to `value`.
        assertEquals(array(NumberType, 3), typeOf(listOf(1.0, 2.0, 3.0)))
        assertEquals(array(StringType, 2), typeOf(listOf("a", "b")))
        assertEquals(array(ValueType, 2), typeOf(listOf(1.0, "b")))
        assertEquals(array(ValueType, 0), typeOf(emptyList<Any?>()))
    }
}
