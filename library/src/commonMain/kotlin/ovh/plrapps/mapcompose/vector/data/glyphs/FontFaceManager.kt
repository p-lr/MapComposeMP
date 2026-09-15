package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.vector.spec.style.FontFaceDeclaration
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics

/**
 * The font files a style declares in its root
 * [`font-faces`](https://maplibre.org/maplibre-style-spec/root/#font-faces), and which of them to
 * draw a given codepoint with. A port of maplibre-gl-js `src/render/font_face_manager.ts`.
 *
 * Nothing is downloaded up front: a file waits until a codepoint it covers is actually drawn, so a
 * style may declare more fonts than any one map ever reaches for. A file that fails to load is
 * remembered as failed and skipped from then on -- the specification asks for an unsupported font to
 * be ignored, and its codepoints fall through to the next file and then to the `glyphs` URL. That is
 * the same rule [GlyphManager] applies to a range the server does not serve: one request, not one
 * per tile.
 *
 * Upstream registers each file with the browser under a generated CSS family name, so that a style
 * cannot restyle the surrounding page and a codepoint is pinned to one file rather than to whatever
 * font matching picks. Here each file becomes a [FontFamily] holding exactly one font, which pins it
 * the same way -- the resolver has nothing else to choose from.
 */
class FontFaceManager(
    declarations: List<FontFaceDeclaration>,
    private val loadResource: suspend (String) -> RawSource?,
    /**
     * Turns a downloaded file into the family to draw with. Injected for the reason upstream injects
     * `CreateRasterizer`: the real one needs a platform font stack -- Android's builds the
     * `Typeface` eagerly and so fails outright on androidHostTest -- and a test of *which* file is
     * chosen wants none of that.
     */
    private val buildFamily: (String, ByteArray) -> FontFamily? = ::fontFamilyFromBytes,
) {
    /** The declared files by the `text-font` name they were declared under, in style order. */
    private val faces: Map<String, List<DeclaredFontFace>> = declarations
        .mapNotNull { declaration -> declare(declaration) }
        .groupBy { it.fontName }

    private val mutex = Mutex()

    /** Whether the style declared any font file at all. */
    val hasFontFaces: Boolean get() = faces.isNotEmpty()

    /**
     * The font file to draw [codePoint] with: each name of the `text-font` stack in turn, and within
     * a name each declared file, until one covers it and loads.
     *
     * Returns `null` to leave the codepoint to the `glyphs` URL.
     */
    suspend fun familyFor(fontStack: List<String>, codePoint: Int): LoadedFontFace? {
        if (faces.isEmpty()) return null
        for (fontName in fontStack) {
            for (face in faces[fontName.trim()].orEmpty()) {
                if (!face.ranges.covers(codePoint)) continue
                val family = load(face) ?: continue
                return LoadedFontFace(identity = face.identity, family = family)
            }
        }
        return null
    }

    /**
     * Downloads a declared file and builds the family to draw with, once.
     *
     * The download itself happens outside the lock -- it is a network round trip, and every label of
     * every tile passes through here -- so two graphemes wanting the same file at the same moment
     * may both fetch it. Upstream avoids that by caching the in-flight promise; the cost here is one
     * extra request against holding the lock across the network, and the second result simply
     * replaces the first.
     */
    private suspend fun load(face: DeclaredFontFace): FontFamily? {
        mutex.withLock { loaded[face.identity] }?.let { return it.family }

        val bytes = runCatching {
            withContext(IODispatcher) { loadResource(face.url)?.buffered()?.readByteArray() }
        }.getOrNull()
        val family = bytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { buildFamily(face.identity, it) }

        mutex.withLock { loaded[face.identity] = Attempt(family) }
        return family
    }

    /** What loading a file produced, `family == null` meaning it failed and is not to be retried. */
    private class Attempt(val family: FontFamily?)

    private val loaded = mutableMapOf<String, Attempt>()

    /**
     * Turns one declaration into a face to draw with, or `null` if there is nothing usable in it.
     *
     * A declaration naming no `unicode-range` covers every codepoint. One naming ranges that are all
     * malformed is dropped, as upstream's `_declareFontFace` drops it: a file declared for a range
     * nobody can read is not a file declared for everything.
     */
    private fun declare(declaration: FontFaceDeclaration): DeclaredFontFace? {
        val ranges = if (declaration.unicodeRange.isEmpty()) {
            listOf(UnicodeRange.DEFAULT)
        } else {
            declaration.unicodeRange.mapNotNull { entry ->
                UnicodeRange.parse(entry) ?: run {
                    StyleDiagnostics.report(
                        location = "font-faces",
                        message = "Ignoring the unicode range \"$entry\" of the font face at " +
                            "${declaration.url}: it is not a valid range.",
                    )
                    null
                }
            }
        }
        if (ranges.isEmpty()) return null
        return DeclaredFontFace(
            fontName = declaration.fontName,
            url = declaration.url,
            ranges = ranges,
            identity = "${declaration.fontName}|${declaration.url}",
        )
    }

    private class DeclaredFontFace(
        val fontName: String,
        val url: String,
        val ranges: List<UnicodeRange>,
        /** Stable across the style's lifetime, and what a drawn grapheme is cached under. */
        val identity: String,
    )
}

/** A declared font file that loaded, ready to draw with. */
class LoadedFontFace(val identity: String, val family: FontFamily)
