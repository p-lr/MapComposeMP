package ovh.plrapps.mapcompose.maplibre.spec.style.symbol

import kotlinx.serialization.Serializable

@Serializable(with = TextAnchorSerializer::class)
enum class TextAnchor(val value: String) {
    Center("center"),
    Left("left"),
    Right("right"),
    Top("top"),
    Bottom("bottom"),
    TopLeft("top-left"),
    TopRight("top-right"),
    BottomLeft("bottom-left"),
    BottomRight("bottom-right");

    companion object {
        fun fromString(value: String?): TextAnchor {
            return TextAnchor.entries.find { it.value.equals(value, ignoreCase = true) } ?: Center
        }
    }
} 