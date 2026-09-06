package ovh.plrapps.mapcompose.vector.data

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.readString
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.vector.renderer.utils.sdfPixel
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An [ImageBitmap] from raw pixels.
 *
 * [argb] is **straight** (non-premultiplied) ARGB, one packed int per pixel -- the convention
 * `Color.toArgb()` produces and Android's `Bitmap.setPixels` consumes, which is what every caller
 * here hands over. Converting that to whatever the backend stores is the actual's job: Skia's
 * raster surfaces are premultiplied, Android's `Bitmap` premultiplies on the way in.
 */
internal expect fun imageBitmapFromArgb(
    argb: IntArray,
    width: Int,
    height: Int
): ImageBitmap

internal expect fun byteArrayToImageBitmap(bytes: ByteArray): ImageBitmap

/**
 * One sprite sheet: its index JSON and the image the entries are cut out of.
 *
 * A style may declare several ([ovh.plrapps.mapcompose.vector.spec.style.sprites]), each with its
 * own id, so a sheet is a value rather than something [SpriteManager] is.
 */
class SpriteSheet(
    val index: Map<String, Sprite>,
    val image: ImageBitmap,
)

/**
 * The sprites a style can draw, across every sheet it declares.
 *
 * A style's `sprite` may be a single URL or a list of `{id, url}` pairs; in the list form an entry
 * is addressed as `"<id>:<name>"`, which is the namespacing upstream applies when it merges the
 * sheets into one atlas. Only the first sheet used to be loaded, so a style using the list form
 * silently lost every icon but one sheet's.
 */
class SpriteManager(private val sheets: List<SpriteSheet>) {

    constructor(spriteIndex: Map<String, Sprite>, spriteImage: ImageBitmap) :
        this(listOf(SpriteSheet(spriteIndex, spriteImage)))

    /**
     * Every entry, resolved to the sheet image it belongs to.
     *
     * Later sheets do not overwrite earlier ones: the style lists them in priority order, and an id
     * that two sheets both define should resolve the way the style wrote it.
     */
    private val entries: Map<String, Pair<Sprite, ImageBitmap>> = buildMap {
        for (sheet in sheets) {
            for ((id, sprite) in sheet.index) {
                if (!containsKey(id)) put(id, sprite to sheet.image)
            }
        }
    }

    /**
     * Every sprite id the style holds.
     *
     * This is what `availableImages` means to the expression engine: an `["image", a, b]` marks a
     * [ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage] available only when
     * its name is in this list, which is how the fallback chain picks the first id that actually
     * exists -- upstream reads the same set off its sprite atlas.
     */
    val availableImages: List<String> = entries.keys.toList()

    fun getSpriteInfo(spriteId: String): Sprite? = entries[spriteId]?.first

    /**
     * Cut-out sprites, keyed by id plus the tint and SDF shading applied to them.
     *
     * Copy-on-write, and deliberately not an `LruCache`: [getSprite] is reached from every tile
     * worker at once (through the non-suspend `PatternBrushCache.get`, so there is no `Mutex` to
     * take), and `LruCache.get` structurally mutates its `LinkedHashMap` to record recency -- an
     * unguarded read is therefore a write, and the pool can lose entries or spin. The key space is
     * the style's sprite ids times the tint and SDF combinations they are drawn with, so it is
     * bounded by the style; the cap is a backstop and clears rather than evicting by recency.
     */
    private var spriteCache: Map<String, ImageBitmap> = emptyMap()

    /**
     * Cuts one sprite out of its sheet, tinted or SDF-shaded as the layer asks.
     *
     * [tintColor] applies to a plain image; an SDF entry ignores it and is recoloured by [sdf]
     * instead. An SDF entry with no [sdf] is shaded with the spec defaults rather than refused --
     * it used to throw, so an SDF icon in a layer that set no `icon-color` crashed the painter.
     *
     * @return the entry's metadata and its cut-out image, or `null` if no sheet holds the id.
     */
    fun getSprite(spriteId: String, tintColor: Color? = null, sdf: SDF? = null): Pair<Sprite, ImageBitmap>? {
        val (spriteInfo, sheetImage) = entries[spriteId] ?: return null

        val cacheKey = "$spriteId-${tintColor?.toArgb() ?: "none"}-${sdf?.hashCode() ?: "none"}"
        spriteCache[cacheKey]?.let {
            return spriteInfo to it
        }

        var sprite = cropImageBitmap(
            source = sheetImage,
            x = spriteInfo.x,
            y = spriteInfo.y,
            width = spriteInfo.width,
            height = spriteInfo.height,
            tintColor = if (!spriteInfo.sdf && tintColor != null) tintColor else null
        )

        if (spriteInfo.sdf) {
            sprite = renderSdf(
                src = resizeImageBitmapWithAspectRatio(
                    src = sprite,
                    targetMaxSize = SDF_RENDER_SIZE
                ),
                sdf = sdf ?: SDF(),
            )
        }

        val snapshot = spriteCache
        spriteCache = if (snapshot.size >= SPRITE_CACHE_MAX_SIZE) {
            mapOf(cacheKey to sprite)
        } else {
            snapshot + (cacheKey to sprite)
        }
        return spriteInfo to sprite
    }

    fun getAvailableSprites(): List<String> = availableImages

    companion object {
        /** Backstop on [spriteCache]; the live set is bounded by the style, not by this. */
        private const val SPRITE_CACHE_MAX_SIZE = 512

        /**
         * The size an SDF entry is magnified to before it is shaded.
         *
         * The distance field is linear, so bilinear magnification is lossless in a way the shaded
         * result is not -- shading first and scaling after would visibly stair-step the halo.
         */
        const val SDF_RENDER_SIZE = 128

        fun resizeImageBitmapWithAspectRatio(
            src: ImageBitmap,
            targetMaxSize: Int,
            filterQuality: FilterQuality = FilterQuality.High
        ): ImageBitmap {
            val srcWidth = src.width
            val srcHeight = src.height

            val scale = targetMaxSize.toFloat() / max(srcWidth, srcHeight)
            val dstWidth = (srcWidth * scale).roundToInt()
            val dstHeight = (srcHeight * scale).roundToInt()

            val resizedBitmap = ImageBitmap(dstWidth, dstHeight)

            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(resizedBitmap),
                size = Size(dstWidth.toFloat(), dstHeight.toFloat())
            ) {
                drawImage(
                    image = src,
                    dstSize = IntSize(dstWidth, dstHeight),
                    filterQuality = filterQuality
                )
            }
            return resizedBitmap
        }

        /**
         * Recolours an SDF entry into a fill plus a halo.
         *
         * The per-pixel maths is [sdfPixel], a port of `symbol_sdf.fragment.glsl`; this is only the
         * loop over the bitmap. The halo used to be a fixed-width ring drawn *inside* the shape's
         * edge and added to the fill, which brightened the boundary instead of surrounding it.
         */
        fun renderSdf(src: ImageBitmap, sdf: SDF): ImageBitmap {
            val width = src.width
            val height = src.height
            val srcPixels = src.toPixelMap()
            val outPixels = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    outPixels[y * width + x] = sdfPixel(
                        distance = srcPixels[x, y].alpha,
                        fillColor = sdf.fillColor,
                        haloColor = sdf.haloColor,
                        haloWidth = sdf.haloWidth,
                        haloBlur = sdf.haloBlur,
                        fontScale = sdf.fontScale,
                    ).toArgb()
                }
            }

            return imageBitmapFromArgb(outPixels, width, height)
        }

        fun cropImageBitmap(
            source: ImageBitmap,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            tintColor: Color? = null,
        ): ImageBitmap {
            val result = ImageBitmap(width, height)
            val canvas = Canvas(result)
            canvas.drawImageRect(
                image = source,
                srcOffset = IntOffset(x, y),
                srcSize = IntSize(width, height),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(width, height),
                paint = Paint().apply {
                    isAntiAlias = false
                }
            )

            if (tintColor != null) {
                canvas.drawRect(
                    Rect(0f, 0f, width.toFloat(), height.toFloat()),
                    Paint().apply {
                        color = tintColor
                        blendMode = BlendMode.SrcIn
                    }
                )
            }
            return result
        }

        /**
         * Loads one sprite sheet.
         *
         * @param spriteUrl the sheet's URL without an extension; `.json` and `.png` are appended,
         * prefixed with `@2x` when [pixelRatio] asks for the hidpi variant.
         * @param id the sheet's id from a list-form `sprite`, used to namespace its entries as
         * `"<id>:<name>"`. Empty for the single-URL form, whose entries keep their bare names.
         */
        @OptIn(ExperimentalResourceApi::class)
        suspend fun loadSheet(
            spriteUrl: String,
            pixelRatio: Int = 1,
            id: String = "",
            loadResource: suspend (String) -> RawSource?,
        ): Result<SpriteSheet> {
            val suffix = if (pixelRatio > 1) "@2x" else ""
            val jsonUrl = "$spriteUrl$suffix.json"
            val imageUrl = "$spriteUrl$suffix.png"

            return try {
                val spriteJson = withContext(IODispatcher) {
                    loadResource(jsonUrl)?.buffered()?.readString() ?: throw Exception("Sprite JSON not found")
                }
                val decoded = json.decodeFromString<Map<String, Sprite>>(spriteJson)
                val spriteIndex = if (id.isEmpty()) decoded else decoded.mapKeys { "$id:${it.key}" }

                val spriteImageBytes = withContext(IODispatcher) {
                    loadResource(imageUrl)?.buffered()?.readByteArray() ?: throw Exception("Sprite image not found")
                }
                val spriteImage = byteArrayToImageBitmap(spriteImageBytes)

                Result.success(SpriteSheet(spriteIndex, spriteImage))
            } catch (e: Exception) {
                println("Failed to load sprite: ${e.message}")
                Result.failure(e)
            }
        }

        /** Loads a single sheet as a whole [SpriteManager] -- the single-URL `sprite` form. */
        suspend fun load(
            spriteUrl: String,
            pixelRatio: Int = 1,
            loadResource: suspend (String) -> RawSource?,
        ): Result<SpriteManager> =
            loadSheet(spriteUrl, pixelRatio, id = "", loadResource = loadResource)
                .map { SpriteManager(listOf(it)) }
    }
}

/**
 * How an SDF sprite is recoloured: `icon-color` plus the three `icon-halo-*` properties, and the
 * scale the icon will be drawn at.
 *
 * The defaults are the style spec's, so an SDF entry in a layer that sets none of them renders as
 * MapLibre would render it -- a black shape with no halo.
 *
 * [fontScale] is the ratio between the size the icon is drawn at and its size on the sheet. The
 * halo is specified in layout pixels but applied to a distance field measured in sheet pixels, so
 * it is what converts between the two; see
 * [ovh.plrapps.mapcompose.vector.renderer.utils.sdfHaloBuffer].
 */
data class SDF(
    val fillColor: Color = StyleSpecDefaults.ICON_COLOR,
    val haloColor: Color = StyleSpecDefaults.ICON_HALO_COLOR,
    val haloWidth: Float = StyleSpecDefaults.ICON_HALO_WIDTH.toFloat(),
    val haloBlur: Float = StyleSpecDefaults.ICON_HALO_BLUR.toFloat(),
    val fontScale: Float = 1f,
)