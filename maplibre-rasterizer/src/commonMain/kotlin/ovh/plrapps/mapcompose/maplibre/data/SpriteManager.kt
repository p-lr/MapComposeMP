package ovh.plrapps.mapcompose.maplibre.data

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.Image.Companion.makeFromEncoded
import org.jetbrains.skia.ImageInfo
import ovh.plrapps.mapcompose.maplibre.spec.sprites.Sprite
import kotlin.math.max
import kotlin.math.roundToInt

class SpriteManager(
    private val spriteIndex: Map<String, Sprite>,
    private val spriteImage: ImageBitmap
) {
    fun getSpriteInfo(spriteId: String): Sprite? {
        val spriteInfo = spriteIndex[spriteId] ?: run {
            return null
        }
        return spriteInfo
    }

    /**
     * Gets a sprite by its id
     * @param spriteId Sprite id from JSON file
     * @return Pair of sprite object with metadata and sprite cutout image
     */
    fun getSprite(spriteId: String, tintColor: Color? = null, sdf: SDF? = null): Pair<Sprite, ImageBitmap>? {
        val spriteInfo = spriteIndex[spriteId] ?: return null

        var sprite = cropImageBitmap(
            source = spriteImage,
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
                    targetMaxSize = 128 // scale sdf for improve result
                ),
                sdf = sdf ?: error("sdf not provided")
            )
        }

        return spriteInfo to sprite
    }

    fun getAvailableSprites(): List<String> = spriteIndex.keys.toList()

    companion object {
        var softness = 0.005f

        fun intArrayToImageByteArray(ints: IntArray): ByteArray {
            val bytes = ByteArray(ints.size * 4)
            for (i in ints.indices) {
                val v = ints[i]
                val j = i * 4
                bytes[j] = (v shr 0 and 0xFF).toByte()  // B
                bytes[j + 1] = (v shr 8 and 0xFF).toByte()  // G
                bytes[j + 2] = (v shr 16 and 0xFF).toByte()  // R
                bytes[j + 3] = (v shr 24 and 0xFF).toByte()  // A
            }
            return bytes
        }

        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

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
                size = androidx.compose.ui.geometry.Size(dstWidth.toFloat(), dstHeight.toFloat())
            ) {
                drawImage(
                    image = src,
                    dstSize = IntSize(dstWidth, dstHeight),
                    filterQuality = filterQuality
                )
            }
            return resizedBitmap
        }

        fun renderSdf(
            src: ImageBitmap,
            sdf: SDF
        ): ImageBitmap {
            val width = src.width
            val height = src.height
            val strokeColor = sdf.haloColor
            val strokeWidth = sdf.haloWidth
            val fillColor = sdf.fillColor

            val srcPixels = src.toPixelMap()


            val fillEdge = sdf.threshold
            val strokeEdge = fillEdge + strokeWidth
            val outPixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val sdfVal = srcPixels[x, y].alpha

                    val fillAlpha = smoothstep(fillEdge - softness, fillEdge + softness, sdfVal)
                    val strokeAlpha = smoothstep(strokeEdge - softness, strokeEdge + softness, sdfVal)

                    val strokeOnly = (fillAlpha - strokeAlpha).coerceIn(0f, 1f)
                    val fillOnly = fillAlpha.coerceIn(0f, 1f)

                    val r = fillColor.red * fillOnly + strokeColor.red * strokeOnly
                    val g = fillColor.green * fillOnly + strokeColor.green * strokeOnly
                    val b = fillColor.blue * fillOnly + strokeColor.blue * strokeOnly
                    val a = (fillOnly + strokeOnly).coerceIn(0f, 1f)

                    outPixels[idx] = Color(r, g, b, a).toArgb()
                }
            }


            val byteArray = intArrayToImageByteArray(outPixels)
            val imageInfo = ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL)
            val skiaImage = Image.makeRaster(imageInfo, byteArray, width * 4)
            return skiaImage.toComposeImageBitmap()
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
         * Loads sprites from the specified URL
         * @param spriteUrl Sprite URL (without extension)
         * @param pixelRatio Pixel density (1 or 2 for @2x)
         * @return Result of loading sprites
         */
        @OptIn(ExperimentalResourceApi::class)
        suspend fun load(spriteUrl: String, pixelRatio: Int = 1): Result<SpriteManager> {
            val suffix = if (pixelRatio > 1) "@2x" else ""
            val jsonUrl = Url("$spriteUrl$suffix.json")
            val imageUrl = Url("$spriteUrl$suffix.png")

            return try {
                val spriteJson = httpClient.get(jsonUrl).bodyAsText()
                val spriteIndex = json.decodeFromString<Map<String, Sprite>>(spriteJson)

                val spriteImageBytes = httpClient.get(imageUrl).bodyAsBytes()
                val spriteImage = makeFromEncoded(spriteImageBytes).toComposeImageBitmap()

                Result.success(SpriteManager(spriteIndex, spriteImage))
            } catch (e: Exception) {
                println("Failed to load sprite: ${e.message}")
                Result.failure(e)
            }

        }
    }
}

data class SDF(
    val haloColor: Color = Color.Unspecified,
    val haloWidth: Float = 0.02f,
    val threshold: Float = 0.729f,
    val fillColor: Color
)