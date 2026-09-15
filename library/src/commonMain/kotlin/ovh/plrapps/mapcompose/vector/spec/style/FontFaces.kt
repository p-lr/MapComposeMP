package ovh.plrapps.mapcompose.vector.spec.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics

/**
 * One font file a style declares in its root `font-faces`, as the spec writes it.
 *
 * Upstream's `MLFontFace`. A declaration may also be a bare URL string, which is the same as an
 * entry naming no `unicode-range`: it covers every codepoint.
 */
@Serializable
data class FontFace(
    val url: String? = null,
    @SerialName("unicode-range") val unicodeRange: List<String>? = null,
)

/**
 * One declared file, kept with the `text-font` name it was declared under and in the order the
 * style listed it. Upstream's `DeclaredFontFace`, minus the generated CSS family name -- a font
 * here is handed to Compose as a [androidx.compose.ui.text.font.FontFamily] of its own rather than
 * registered on a shared document.
 */
class FontFaceDeclaration(
    val fontName: String,
    val url: String,
    val unicodeRange: List<String>,
)

/**
 * The style's `font-faces`, flattened in declaration order.
 *
 * The property is modelled as raw JSON for the reason `sprite` is: a value may be a URL string, one
 * object, or a list of either, and kotlinx-serialization has no union. Nothing here throws -- an
 * entry with no URL is reported through [StyleDiagnostics] and skipped, as upstream warns and skips
 * it, because a decode that throws leaves `getMapLibreConfiguration` returning a failure and blanks
 * the whole map.
 */
val MapLibreStyle.fontFaces: List<FontFaceDeclaration>
    get() {
        val declared = fontFacesJson ?: return emptyList()
        val out = mutableListOf<FontFaceDeclaration>()
        for ((fontName, element) in declared) {
            val entries = when {
                element is JsonArray -> element
                element is JsonNull -> continue
                else -> listOf(element)
            }
            for (entry in entries) {
                val face = when (entry) {
                    /* `isString` and not `contentOrNull`: a JSON number reads back as the text "42",
                     * where upstream's `typeof declaration === 'string'` rejects it. */
                    is JsonPrimitive -> entry.takeIf { it.isString }?.content?.let { FontFace(url = it) }
                    is JsonObject -> runCatching {
                        json.decodeFromJsonElement(FontFace.serializer(), entry)
                    }.getOrNull()

                    else -> null
                }
                val url = face?.url
                if (url.isNullOrEmpty()) {
                    StyleDiagnostics.report(
                        location = "font-faces",
                        message = "Ignoring the font face declared for \"$fontName\": it has no URL.",
                    )
                    continue
                }
                out += FontFaceDeclaration(
                    fontName = fontName,
                    url = url,
                    unicodeRange = face.unicodeRange.orEmpty(),
                )
            }
        }
        return out
    }

/** Whether the style declares any font file at all. */
val MapLibreStyle.hasFontFaces: Boolean get() = !fontFacesJson.isNullOrEmpty()
