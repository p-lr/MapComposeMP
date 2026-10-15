package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A [TileGeometry.Fills] path is drawn by several tile workers at once -- every sub-square of an
 * overzoomed ancestor shares it through `VectorRasterizer.geometryCache`.
 *
 * Compose's skiko `Path` builds its native path lazily, on the first read, and a draw is a read: two
 * threads drawing a fresh path both swapped in a snapshot and both closed the `PathBuilder`, so one of
 * them threw `Object already closed: class org.jetbrains.skia.PathBuilder` out of
 * `FillLayerPainter.paintPath`. That exception left the provider, cancelled core's `collectTiles`,
 * and no tile was drawn again. Here every thread is released onto each path at the same instant.
 *
 * Desktop only: `wasmJs` has no threads, so the race cannot be provoked there.
 */
class TileGeometryThreadSafetyTest {

    @Test
    fun aSharedFillPathCanBeDrawnFromSeveralThreadsAtOnce() {
        val layer = Mvt.layer(features = List(FEATURES) { index -> Mvt.polygonFeature(disc(index)) })
        val fills = runBlocking { TileGeometry(geometrySize = CANVAS_SIZE).fills(layer, EXTENT) }
        val paths = fills.paths.map { requireNotNull(it) }

        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val bitmaps = List(THREADS) { ImageBitmap(CANVAS_SIZE, CANVAS_SIZE) }
        val barrier = CyclicBarrier(THREADS)
        val threads = bitmaps.map { bitmap ->
            Thread {
                val canvas = Canvas(bitmap)
                val paint = Paint().apply { color = Color.Red }
                for (path in paths) {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        canvas.drawPath(path, paint)
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }

        assertTrue(failures.isEmpty(), "drawing a shared path failed: ${failures.firstOrNull()}")
        for (bitmap in bitmaps) {
            val pixels = bitmap.toPixelMap()
            assertEquals(Color.Red, pixels[CANVAS_SIZE / 2, CANVAS_SIZE / 2])
            assertEquals(Color.Transparent, pixels[0, 0])
        }
    }

    /** A many-sided polygon round the tile centre, so building its native path takes a while. */
    private fun disc(index: Int): List<Pair<Int, Int>> {
        val radius = 500 + index
        return List(VERTICES) { i ->
            val angle = 2 * PI * i / VERTICES
            (EXTENT / 2 + radius * cos(angle)).roundToInt() to (EXTENT / 2 + radius * sin(angle)).roundToInt()
        }
    }

    private companion object {
        const val FEATURES = 300
        const val VERTICES = 256
        const val THREADS = 8
        const val CANVAS_SIZE = 64
        const val EXTENT = 4096
    }
}
