package ovh.plrapps.mapcompose.vector.spec.style.props

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `text-field` and `icon-image` carry a spec type that is neither a scalar nor a colour, and the
 * expected type is what decides whether an expression compiles at all.
 *
 * Both properties used to be modelled as `string`. `ParsingContext` then compared the parsed
 * expression's `FormattedType` / `ResolvedImageType` against `StringType`, found neither an
 * assertion nor a coercion applied, and discarded the property -- so every `["format", ...]` label
 * in a style silently disappeared. These tests pin the typing down.
 */
class FormattedAndImagePropertyTest {

    private val bare = Json { ignoreUnknownKeys = true }

    private fun layout(json: String): SymbolLayout =
        bare.decodeFromString(SymbolLayout.serializer(), json)

    private fun feature(vararg properties: Pair<String, Any?>) =
        EvalFeature(type = "Point", properties = properties.toMap())

    @Test
    fun aConstantTextFieldIsOneUnformattedSection() {
        val field = layout("""{"text-field":"Peak"}""").textField
        assertTrue(field is ExpressionOrValue.Value)
        val formatted = assertNotNull(field.processAsFormatted())
        assertEquals(1, formatted.sections.size)
        assertEquals("Peak", formatted.toString())
    }

    @Test
    fun aFormatExpressionCompilesAndKeepsItsSections() {
        val field = layout("""{"text-field":["format","a",{},"b",{"font-scale":1.5}]}""").textField
        assertTrue(
            field is ExpressionOrValue.Expression,
            "a format expression must compile, but was $field",
        )
        val formatted = assertNotNull(field.processAsFormatted())
        assertEquals(listOf("a", "b"), formatted.sections.map { it.text })
        assertEquals(1.5, formatted.sections[1].scale)
    }

    @Test
    fun aFormatExpressionCarriesItsSectionColor() {
        val field = layout(
            """{"text-field":["format","x",{"text-color":"#ff0000"}]}"""
        ).textField
        val formatted = assertNotNull(field.processAsFormatted())
        assertEquals(Color.Red, formatted.sections.single().textColor)
    }

    @Test
    fun aPlainStringExpressionIsCoercedToFormatted() {
        val field = layout("""{"text-field":["get","name"]}""").textField
        assertTrue(field is ExpressionOrValue.Expression)
        assertEquals("Zermatt", field.processAsFormatted(feature("name" to "Zermatt")).toString())
    }

    @Test
    fun aConstantIconImageResolvesToItsName() {
        val image = layout("""{"icon-image":"marker"}""").iconImage
        assertTrue(image is ExpressionOrValue.Value)
        assertEquals("marker", image.processAsImageName())
    }

    @Test
    fun anImageExpressionCompiles() {
        val image = layout("""{"icon-image":["image","marker"]}""").iconImage
        assertTrue(
            image is ExpressionOrValue.Expression,
            "an image expression must compile, but was $image",
        )
        assertEquals("marker", image.processAsImageName(availableImages = listOf("marker")))
    }

    @Test
    fun availableImagesDecidesWhetherAnImageIsAvailable() {
        val image = layout("""{"icon-image":["image","marker"]}""").iconImage
        assertEquals(
            ResolvedImage(name = "marker", available = true),
            image.processAsImage(availableImages = listOf("marker", "dot")),
        )
        assertEquals(
            ResolvedImage(name = "marker", available = false),
            image.processAsImage(availableImages = listOf("dot")),
        )
    }

    @Test
    fun anImageFallsBackToTheFirstAvailableName() {
        val image = layout("""{"icon-image":["coalesce",["image","missing"],["image","dot"]]}""").iconImage
        assertEquals("dot", image.processAsImageName(availableImages = listOf("dot")))
    }

    @Test
    fun aConcatExpressionStillProducesText() {
        val field = layout("""{"text-field":["concat",["get","name"]," ",["get","ele"]]}""").textField
        assertEquals(
            "Matterhorn 4478",
            field.processAsFormatted(feature("name" to "Matterhorn", "ele" to "4478")).toString(),
        )
    }

    @Test
    fun anUncompilableTextFieldYieldsNull() {
        // A known operator with the wrong arity -- an *unknown* head is not treated as an
        // expression at all, and decodes as a malformed constant.
        val field = layout("""{"text-field":["get"]}""").textField
        assertTrue(field is ExpressionOrValue.Invalid, "expected Invalid but was $field")
        assertNull(field.processAsFormatted())
    }
}
