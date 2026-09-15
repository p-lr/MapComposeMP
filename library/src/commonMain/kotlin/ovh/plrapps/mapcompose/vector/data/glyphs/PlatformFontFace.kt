package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.text.font.FontFamily

/**
 * Builds a [FontFamily] from a downloaded font file, or `null` where the platform cannot.
 *
 * Upstream hands the bytes to the browser's CSS Font Loading API under a generated family name
 * (`FontFaceManager._loadFontFace`), so that a style cannot restyle the page and a codepoint is
 * pinned to one file rather than to whatever font matching picks. A family of exactly one
 * [androidx.compose.ui.text.font.Font] is the same guarantee here: the resolver has nothing else to
 * pick from.
 *
 * Compose exposes `Font(identity, data)` on skiko -- desktop, iOS and wasm -- but nothing taking
 * bytes on Android, so the two actuals do not collapse into one. Returning `null` is not an error:
 * the codepoints that file covers fall through to the next file and then to the `glyphs` URL, which
 * is what the specification asks for when a font is unsupported.
 *
 * [identity] distinguishes two files in Compose's own font cache and has to be stable for one file.
 */
internal expect fun fontFamilyFromBytes(identity: String, bytes: ByteArray): FontFamily?
