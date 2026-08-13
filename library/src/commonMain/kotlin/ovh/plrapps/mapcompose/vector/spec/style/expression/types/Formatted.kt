package ovh.plrapps.mapcompose.vector.spec.style.expression.types

import androidx.compose.ui.graphics.Color

/** Ported from `maplibre-style-spec/src/expression/types/formatted.ts`. */
enum class VerticalAlign(val value: String) {
    BOTTOM("bottom"), CENTER("center"), TOP("top");

    companion object {
        fun fromStringOrNull(value: String?): VerticalAlign? = entries.firstOrNull { it.value == value }
    }
}

data class FormattedSection(
    val text: String,
    val image: ResolvedImage? = null,
    val scale: Double? = null,
    val fontStack: String? = null,
    val textColor: Color? = null,
    val verticalAlign: VerticalAlign? = null,
)

data class Formatted(val sections: List<FormattedSection>) {

    fun isEmpty(): Boolean {
        if (sections.isEmpty()) return true
        return sections.none { it.text.isNotEmpty() || (it.image != null && it.image.name.isNotEmpty()) }
    }

    /** Concatenates the section texts, which is what the symbol renderer consumes today. */
    override fun toString(): String = sections.joinToString(separator = "") { it.text }

    companion object {
        fun fromString(unformatted: String): Formatted =
            Formatted(listOf(FormattedSection(text = unformatted)))

        fun factory(text: Any?): Formatted = when (text) {
            is Formatted -> text
            else -> fromString(text?.toString() ?: "")
        }
    }
}
