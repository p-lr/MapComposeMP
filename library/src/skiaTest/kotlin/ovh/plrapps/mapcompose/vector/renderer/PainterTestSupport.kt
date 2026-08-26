package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Support for the painter tests: a headless draw scope and pixel assertions.
 *
 * This lives in `skiaTest` (desktop, iOS and wasm) rather than `commonTest` because `ImageBitmap`
 * needs a real graphics backend: on androidHostTest every allocation fails with
 * `Method createBitmap in android.graphics.Bitmap not mocked`, which would need Robolectric.
 *
 * Deliberately plain `kotlin.test` -- no `runComposeUiTest`, which is what makes the style-parsing
 * tests fail on androidHostTest and time out on wasm.
 */

/**
 * Renders [block] into an off-screen bitmap and returns it.
 *
 * Mirrors how `VectorRasterizer.renderTile` drives a `CanvasDrawScope`, so a painter under test sees
 * the same kind of draw scope it does in production. Density is 1 by default so that a pixel in a
 * style property is a pixel in the assertions.
 */
internal fun renderToBitmap(
    size: Int = 64,
    density: Float = 1f,
    background: Color = Color.Transparent,
    block: suspend DrawScope.() -> Unit,
): ImageBitmap {
    val bitmap = ImageBitmap(size, size)
    CanvasDrawScope().draw(
        density = Density(density),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(size.toFloat(), size.toFloat()),
    ) {
        if (background != Color.Transparent) drawRect(color = background)
        runSynchronously { block() }
    }
    return bitmap
}

/**
 * Runs a suspending block that is not expected to actually suspend, and rethrows what it throws.
 *
 * `BaseLayerPainter.paint` is `suspend` only so that the fill and line painters can take a mutex
 * around their shared geometry cache; an uncontended mutex takes its fast path and never suspends.
 * `draw` is not a suspending call, so the block cannot simply be awaited -- and `runBlocking` does
 * not exist on wasm or JS, where these tests also run.
 */
internal fun runSynchronously(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    val result = outcome ?: error("the painter suspended; it is only expected to on cache contention")
    result.getOrThrow()
}

/** The colour at one pixel of a rendered bitmap. */
internal fun ImageBitmap.pixelAt(x: Int, y: Int): Color = toPixelMap()[x, y]

/**
 * Asserts two colours match within [tolerance] per channel.
 *
 * Compose stores 8-bit channels, and anti-aliasing means an assertion has to be taken away from a
 * shape's edge or given room; exact equality would make these tests brittle for no gain.
 */
internal fun assertColorEquals(expected: Color, actual: Color, tolerance: Float = 0.02f, message: String = "") {
    val deltas = listOf(
        expected.red - actual.red,
        expected.green - actual.green,
        expected.blue - actual.blue,
        expected.alpha - actual.alpha,
    )
    if (deltas.any { kotlin.math.abs(it) > tolerance }) {
        kotlin.test.fail("${if (message.isEmpty()) "" else "$message: "}expected $expected but was $actual")
    }
}

/** Counts pixels that are not fully transparent. */
internal fun ImageBitmap.opaquePixelCount(): Int {
    val pixels = toPixelMap()
    var count = 0
    for (y in 0 until pixels.height) {
        for (x in 0 until pixels.width) {
            if (pixels[x, y].alpha > 0.01f) count++
        }
    }
    return count
}

/**
 * A [SpriteManager] holding a single solid-colour sprite, for the `*-pattern` properties.
 *
 * Real sprite sheets come from the style's `sprite` URL; a one-sprite sheet built here keeps the
 * pattern tests independent of any bundled PNG.
 */
internal fun spriteSheet(name: String, color: Color, size: Int = 8): SpriteManager {
    val sheet = renderToBitmap(size = size) { drawRect(color = color) }
    return SpriteManager(
        spriteIndex = mapOf(name to Sprite(width = size, height = size, x = 0, y = 0)),
        spriteImage = sheet,
    )
}
