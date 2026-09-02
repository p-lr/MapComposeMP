package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Establishes what `Canvas.drawVertices` guarantees before the line mesh is built on top of it.
 *
 * The mesh port reproduces `line.fragment.glsl`'s alpha falloff with per-vertex colours, so it
 * depends on three things holding on every target: that vertex colours are interpolated across a
 * triangle at all, that they are used directly when the paint carries no shader -- Android's Compose
 * actual drops the `blendMode` argument (`AndroidCanvas.android.kt`, `TODO(njawad)`), so nothing may
 * depend on it -- and that `isAntiAlias = false` leaves no seam between two triangles sharing an
 * edge, which is why the feather is geometry here rather than a paint setting.
 *
 * `androidHostTest` cannot allocate an `ImageBitmap`, so this runs on desktop, iOS and wasm only;
 * Android draws these tiles through the same software Skia canvas.
 */
class DrawVerticesProbeTest {

    @Test
    fun `vertex colours are interpolated across a triangle`() {
        val bitmap = renderToBitmap(size = 64) {
            drawIntoCanvas { canvas ->
                // A quad covering the bitmap, red on the left edge and blue on the right.
                val positions = listOf(
                    Offset(0f, 0f), Offset(64f, 0f), Offset(0f, 64f), Offset(64f, 64f),
                )
                val colors = listOf(Color.Red, Color.Blue, Color.Red, Color.Blue)
                canvas.drawVertices(
                    Vertices(
                        vertexMode = VertexMode.Triangles,
                        positions = positions,
                        textureCoordinates = positions,
                        colors = colors,
                        indices = listOf(0, 1, 2, 1, 3, 2),
                    ),
                    BlendMode.Modulate,
                    Paint().apply { color = Color.White; isAntiAlias = false },
                )
            }
        }

        val left = bitmap.pixelAt(2, 32)
        val middle = bitmap.pixelAt(32, 32)
        val right = bitmap.pixelAt(61, 32)

        assertTrue(left.red > 0.9f && left.blue < 0.1f, "the left edge keeps its vertex colour: $left")
        assertTrue(right.blue > 0.9f && right.red < 0.1f, "the right edge keeps its vertex colour: $right")
        assertTrue(
            middle.red in 0.35f..0.65f && middle.blue in 0.35f..0.65f,
            "the middle is a blend of the two: $middle"
        )
    }

    @Test
    fun `vertex alpha is interpolated too`() {
        val bitmap = renderToBitmap(size = 64) {
            drawIntoCanvas { canvas ->
                val positions = listOf(
                    Offset(0f, 0f), Offset(64f, 0f), Offset(0f, 64f), Offset(64f, 64f),
                )
                val opaque = Color.Red
                val clear = Color.Red.copy(alpha = 0f)
                val colors = listOf(opaque, clear, opaque, clear)
                canvas.drawVertices(
                    Vertices(
                        vertexMode = VertexMode.Triangles,
                        positions = positions,
                        textureCoordinates = positions,
                        colors = colors,
                        indices = listOf(0, 1, 2, 1, 3, 2),
                    ),
                    BlendMode.Modulate,
                    Paint().apply { color = Color.White; isAntiAlias = false },
                )
            }
        }

        val left = bitmap.pixelAt(2, 32)
        val middle = bitmap.pixelAt(32, 32)
        val right = bitmap.pixelAt(61, 32)

        assertTrue(left.alpha > 0.9f, "the opaque edge stays opaque: $left")
        assertTrue(right.alpha < 0.1f, "the clear edge stays clear: $right")
        assertTrue(middle.alpha in 0.35f..0.65f, "the middle is half transparent: $middle")
    }

    @Test
    fun `two triangles sharing an edge leave no seam`() {
        // The mesh feathers its own edges, so it is drawn with antialiasing off; Skia antialiases
        // each triangle on its own, which would show as a hairline along every shared edge.
        val bitmap = renderToBitmap(size = 64) {
            drawIntoCanvas { canvas ->
                val positions = listOf(
                    Offset(0f, 0f), Offset(64f, 0f), Offset(0f, 64f), Offset(64f, 64f),
                )
                val colors = List(4) { Color.Red }
                canvas.drawVertices(
                    Vertices(
                        vertexMode = VertexMode.Triangles,
                        positions = positions,
                        textureCoordinates = positions,
                        colors = colors,
                        // Two triangles meeting along the (64,0)-(0,64) diagonal.
                        indices = listOf(0, 1, 2, 1, 3, 2),
                    ),
                    BlendMode.Modulate,
                    Paint().apply { color = Color.White; isAntiAlias = false },
                )
            }
        }

        for (i in 4 until 60) {
            val onDiagonal = bitmap.pixelAt(i, 63 - i)
            assertTrue(onDiagonal.alpha > 0.99f, "no seam along the shared edge at ($i, ${63 - i}): $onDiagonal")
        }
    }

    @Test
    fun `the paint colour modulates the vertex colours`() {
        // Found by this probe: with no shader in the paint, Skia multiplies the vertex colours by
        // the paint's own colour, which defaults to opaque black -- so the mesh comes out black
        // unless the paint is white. Android's actual drops the blendMode argument entirely and its
        // framework default is the same modulate, so a white paint is the one setting that reads the
        // same on every target. LineLayerPainter must never leave the paint colour at its default.
        val bitmap = renderToBitmap(size = 64) {
            drawIntoCanvas { canvas ->
                val positions = listOf(
                    Offset(0f, 0f), Offset(64f, 0f), Offset(0f, 64f), Offset(64f, 64f),
                )
                val colors = List(4) { Color.Red }
                canvas.drawVertices(
                    Vertices(
                        vertexMode = VertexMode.Triangles,
                        positions = positions,
                        textureCoordinates = positions,
                        colors = colors,
                        indices = listOf(0, 1, 2, 1, 3, 2),
                    ),
                    BlendMode.Modulate,
                    Paint().apply { isAntiAlias = false },
                )
            }
        }

        assertColorEquals(
            Color.Black,
            bitmap.pixelAt(32, 32),
            message = "a default black paint modulates every vertex colour to black",
        )
    }
}
