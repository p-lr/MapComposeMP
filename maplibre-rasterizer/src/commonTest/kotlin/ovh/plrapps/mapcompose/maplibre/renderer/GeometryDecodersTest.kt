import kotlin.test.Test
import kotlin.test.assertEquals
import ovh.plrapps.mapcompose.maplibre.renderer.GeometryDecoders.Companion.tileCoordToCanvas
import ovh.plrapps.mapcompose.maplibre.renderer.GeometryDecoders.Companion.decodeZigZag

class GeometryDecodersTest {
    private fun encodeZigZag(n: Int): Int = (n shl 1) xor (n shr 31)

    @Test
    fun testDecodeZigZag() {
        val values = listOf(0, 1, -1, 2, -2, 123456, -123456, Int.MAX_VALUE / 2, -(Int.MAX_VALUE / 2) - 1)
        for (n in values) {
            val encoded = encodeZigZag(n)
            assertEquals(n, decodeZigZag(encoded), "decodeZigZag(encodeZigZag($n))")
        }
    }

    @Test
    fun testTileCoordToCanvas() {
        // canvasSize = 256, extent = 4096, scale = 0.0625
        assertEquals(Pair(0f, 0f), tileCoordToCanvas(0, 0, 256, 4096))
        assertEquals(Pair(62.5f, 125f), tileCoordToCanvas(1000, 2000, 256, 4096))
        assertEquals(Pair(-6.25f, -6.25f), tileCoordToCanvas(-100, -100, 256, 4096))
        assertEquals(Pair(0f, -16f), tileCoordToCanvas(0, -16, 16, 16))
        assertEquals(Pair(250.625f, -1f), tileCoordToCanvas(4010, -16, 256, 4096))
    }
} 