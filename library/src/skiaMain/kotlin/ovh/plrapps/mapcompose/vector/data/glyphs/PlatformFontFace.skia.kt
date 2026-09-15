package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

/**
 * Compose's own bytes-to-`Font` factory (`ui-text`, `skikoMain/PlatformFont.skiko.kt`), which is
 * skiko's `Typeface.makeFromData` underneath -- so desktop, iOS and wasm all take this one.
 *
 * Weight and style are left at their defaults deliberately: the file carries its own, and upstream
 * likewise passes `sniffFontStyles: false` for a `font-faces` file where it sniffs them out of the
 * family name for an ordinary font stack (`glyph_manager._createTinySDF`).
 */
internal actual fun fontFamilyFromBytes(identity: String, bytes: ByteArray): FontFamily? =
    runCatching { FontFamily(Font(identity = identity, data = bytes)) }.getOrNull()
