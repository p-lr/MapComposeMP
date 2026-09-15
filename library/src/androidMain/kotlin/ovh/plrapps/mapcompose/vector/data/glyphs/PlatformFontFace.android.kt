package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * A temporary file, because Android is the one target with no bytes-to-`Font` factory.
 *
 * `Typeface.Builder(ByteBuffer)` is API 29 against this library's `minSdk 24`, and Compose's Android
 * overloads take a resource id, an asset path, a `File` or -- from API 26 -- a file descriptor. The
 * `File` one has no API gate, and an Android application's `java.io.tmpdir` is its own cache
 * directory, so the file lands inside the app sandbox and is deleted on exit.
 *
 * A failure returns `null` rather than throwing: the codepoints that file covers then fall through
 * to the `glyphs` URL, exactly as they would on a target that could not read the font at all.
 */
internal actual fun fontFamilyFromBytes(identity: String, bytes: ByteArray): FontFamily? =
    runCatching {
        val file = File.createTempFile("mapcompose-font-", ".ttf")
        file.deleteOnExit()
        file.writeBytes(bytes)
        FontFamily(Font(file = file))
    }.getOrNull()
