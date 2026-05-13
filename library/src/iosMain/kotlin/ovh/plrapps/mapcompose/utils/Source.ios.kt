@file:OptIn(ExperimentalForeignApi::class)

package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.io.Source
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGDataProviderCreateSequential
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGDataProviderSequentialCallbacks
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Host
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithDataProvider
import platform.posix.memcpy

private class SourceContext(
    val source: Source,
    val tempBuf: ByteArray = ByteArray(8192)
)

// Must be top-level vals — staticCFunction lambdas cannot capture variables.

private val getBytesCallback = staticCFunction { info: COpaquePointer?,
                                                 buffer: COpaquePointer?,
                                                 count: ULong ->
    if (info == null || buffer == null) return@staticCFunction 0uL
    val ctx = info.asStableRef<SourceContext>().get()
    val destBase = buffer.reinterpret<ByteVar>()
    var totalRead = 0L
    var remaining = count.toLong()
    while (remaining > 0L && !ctx.source.exhausted()) {
        val chunk = minOf(remaining, ctx.tempBuf.size.toLong()).toInt()
        val n = ctx.source.readAtMostTo(ctx.tempBuf, 0, chunk)
        if (n <= 0) break
        ctx.tempBuf.usePinned { pinned ->
            memcpy(destBase + totalRead.toInt(), pinned.addressOf(0), n.toULong())
        }
        totalRead += n
        remaining -= n
    }
    totalRead.toULong()
}

// off_t on iOS = Long
private val skipForwardCallback = staticCFunction { info: COpaquePointer?, count: Long ->
    if (info == null) return@staticCFunction 0L
    val ctx = info.asStableRef<SourceContext>().get()
    try { ctx.source.skip(count); count } catch (_: Exception) { 0L }
}

private val releaseInfoCallback = staticCFunction { info: COpaquePointer? ->
    info?.asStableRef<SourceContext>()?.dispose()
    Unit
}

/**
 * 1. Bitmap.allocPixels(...) allocates Skia's buffer
 * 2. peekPixels().addr + interpretCPointer<ByteVar>() exposes it as a raw pointer
 * 3. CoreGraphics draws directly into it.
 * One pixel buffer, no copy.
 */
private fun Source.decodeToImageBitmap(): ImageBitmap? {
    val stableRef = StableRef.create(SourceContext(this))

    // CGDataProviderCreateSequential copies the struct immediately, so memScoped is safe.
    val provider = memScoped {
        val cb = alloc<CGDataProviderSequentialCallbacks>().also {
            it.version = 0u
            it.getBytes = getBytesCallback.reinterpret()
            it.skipForward = skipForwardCallback.reinterpret()
            it.rewind = null  // Source is forward-only; JPEG/PNG decode without rewinding
            it.releaseInfo = releaseInfoCallback.reinterpret()
        }
        CGDataProviderCreateSequential(stableRef.asCPointer(), cb.ptr)
    } ?: run { stableRef.dispose(); return null }
    // StableRef ownership transferred to the provider; releaseInfoCallback will dispose it.

    val imageSource = CGImageSourceCreateWithDataProvider(provider, null)
    CGDataProviderRelease(provider)
    imageSource ?: return null

    val cgImage = CGImageSourceCreateImageAtIndex(imageSource, 0uL, null)
    CFRelease(imageSource)
    cgImage ?: return null

    return cgImage.toImageBitmap().also { CGImageRelease(cgImage) }
}

private fun CGImageRef.toImageBitmap(): ImageBitmap? {
    val w = CGImageGetWidth(this).toInt()
    val h = CGImageGetHeight(this).toInt()
    if (w <= 0 || h <= 0) return null

    // Allocate Skia's bitmap first so CoreGraphics draws directly into Skia's memory — no copy.
    val bitmap = Bitmap()
    if (!bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))) return null

    val pixmap = bitmap.peekPixels() ?: return null
    val pixelsPtr = interpretCPointer<ByteVar>(pixmap.addr) ?: return null

    // BGRA / premultiplied — matches ImageInfo.makeN32Premul on iOS (little-endian ARM).
    val bitmapInfo = kCGBitmapByteOrder32Host or CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value
    val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null

    val ok = run {
        val ctx = CGBitmapContextCreate(
            data = pixelsPtr,
            width = w.toULong(),
            height = h.toULong(),
            bitsPerComponent = 8uL,
            bytesPerRow = pixmap.rowBytes.toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        ) ?: return@run false
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), this)
        CGContextRelease(ctx)
        true
    }
    CGColorSpaceRelease(colorSpace)

    return if (ok) bitmap.asComposeImageBitmap() else null
}

actual fun Source.decodeFirstLayer(
    hasLayers: Boolean,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap? = runCatching { decodeToImageBitmap() }.getOrNull()

actual fun Source.decodeOverlay(
    previousLayer: ImageBitmap?,
    tileSize: Int,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap? = runCatching { decodeToImageBitmap() }.getOrNull()

actual fun processFinalImage(
    currentImage: ImageBitmap,
    previousLayer: ImageBitmap?
): ImageBitmap = currentImage
