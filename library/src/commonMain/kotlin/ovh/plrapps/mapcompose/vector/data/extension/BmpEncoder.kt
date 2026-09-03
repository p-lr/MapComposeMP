package ovh.plrapps.mapcompose.vector.data.extension

/**
 * An uncompressed 32-bit BMP encoder, used to hand a rasterized tile to MapCompose's core.
 *
 * A vector tile crosses the `TileStreamProvider` boundary as *bytes*, so it has to be encoded and
 * decoded again even though both ends hold an `ImageBitmap`. PNG made that round trip a deflate
 * followed by an inflate over a full RGBA tile -- at 512 px tiles with `superSamplingFactor = 2`,
 * a 1024x1024 image, which is an order of magnitude more work than the copy it stands in for.
 * BMP has no compression, so the encode is a header plus one pixel copy and the decode is a read.
 *
 * Skia has no BMP *encoder* (`Image.encodeToData` honours only JPEG, PNG and WEBP), hence this file.
 * Both decoders on the other side read BMP natively: Android's `BitmapFactory` lists it among the
 * supported formats, and Skia has `SkBmpCodec`.
 *
 * The file written here is a [BITMAPFILEHEADER] followed by a [BITMAPV4HEADER]: 32 bits per pixel,
 * `BI_BITFIELDS` with an explicit alpha mask (the older `BITMAPINFOHEADER` has no way to declare
 * one), and a **negative height**, which is BMP's way of saying the rows are stored top-down like
 * every other image format. Rows are 32 bpp, so BMP's 4-byte row alignment is automatic and there
 * is no padding to write.
 *
 * **The pixel data is straight alpha**, as [ovh.plrapps.mapcompose.vector.data.imageBitmapFromArgb]'s
 * input is. A tile `ImageBitmap` is `PREMUL`, so a caller must ask for unpremultiplied pixels rather
 * than handing over the bitmap's own bytes -- labelling premultiplied values as straight is what
 * saturates every translucent pixel, a label's halo and the heatmap ramp's cold end among them.
 * `BmpRoundTripTest` is what holds that down.
 */

/** [BITMAPFILEHEADER] (14 bytes) + [BITMAPV4HEADER] (108 bytes). */
internal const val BMP_HEADER_SIZE: Int = 122

private const val BI_BITFIELDS = 3

/** The header for a top-down, 32-bit, `BI_BITFIELDS` BMP of [width] x [height] BGRA pixels. */
internal fun bmpHeader(width: Int, height: Int): ByteArray {
    require(width > 0 && height > 0) { "bmp size must be positive, was ${width}x$height" }

    val pixelBytes = width * height * 4
    val header = ByteArray(BMP_HEADER_SIZE)

    /* BITMAPFILEHEADER */
    header[0] = 'B'.code.toByte()
    header[1] = 'M'.code.toByte()
    header.putInt(2, BMP_HEADER_SIZE + pixelBytes)  // bfSize
    // bfReserved1, bfReserved2 stay 0.
    header.putInt(10, BMP_HEADER_SIZE)              // bfOffBits

    /* BITMAPV4HEADER */
    header.putInt(14, 108)                          // bV4Size
    header.putInt(18, width)                        // bV4Width
    header.putInt(22, -height)                      // bV4Height, negative: rows are top-down
    header.putShort(26, 1)                          // bV4Planes
    header.putShort(28, 32)                         // bV4BitCount
    header.putInt(30, BI_BITFIELDS)                 // bV4V4Compression
    header.putInt(34, pixelBytes)                   // bV4SizeImage
    // bV4XPelsPerMeter, bV4YPelsPerMeter, bV4ClrUsed, bV4ClrImportant stay 0.
    header.putInt(54, 0x00FF0000)                   // bV4RedMask
    header.putInt(58, 0x0000FF00)                   // bV4GreenMask
    header.putInt(62, 0x000000FF)                   // bV4BlueMask
    header.putInt(66, -0x1000000)                   // bV4AlphaMask, 0xFF000000
    // bV4CSType (LCS_CALIBRATED_RGB), bV4Endpoints and the three gamma fields stay 0.

    return header
}

/**
 * A BMP of [width] x [height] built from straight-alpha ARGB pixels, row-major and top-down --
 * the layout `android.graphics.Bitmap.getPixels` produces.
 */
internal fun encodeBmp32(argb: IntArray, width: Int, height: Int): ByteArray {
    require(argb.size >= width * height) {
        "expected ${width * height} pixels for a ${width}x$height bmp, got ${argb.size}"
    }

    val out = bmpHeader(width, height).copyOf(BMP_HEADER_SIZE + width * height * 4)

    var j = BMP_HEADER_SIZE
    for (i in 0 until width * height) {
        val px = argb[i]
        out[j++] = (px and 0xFF).toByte()           // B
        out[j++] = ((px ushr 8) and 0xFF).toByte()  // G
        out[j++] = ((px ushr 16) and 0xFF).toByte() // R
        out[j++] = ((px ushr 24) and 0xFF).toByte() // A
    }

    return out
}

/**
 * A BMP of [width] x [height] built from straight-alpha bytes that are already in BMP's own channel
 * order -- what a Skia `readPixels` into a `BGRA_8888` / `UNPREMUL` [org.jetbrains.skia.ImageInfo]
 * returns, so no per-pixel work is needed at all.
 */
internal fun encodeBmp32FromBgra(bgra: ByteArray, width: Int, height: Int): ByteArray {
    val pixelBytes = width * height * 4
    require(bgra.size >= pixelBytes) {
        "expected $pixelBytes bytes for a ${width}x$height bmp, got ${bgra.size}"
    }

    val out = bmpHeader(width, height).copyOf(BMP_HEADER_SIZE + pixelBytes)
    bgra.copyInto(out, destinationOffset = BMP_HEADER_SIZE, startIndex = 0, endIndex = pixelBytes)
    return out
}

/** BMP is little-endian throughout. */
private fun ByteArray.putInt(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun ByteArray.putShort(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}
