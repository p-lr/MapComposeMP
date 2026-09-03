package ovh.plrapps.mapcompose.vector.data.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The header layout and channel order of [encodeBmp32].
 *
 * Pure byte arithmetic, so it lives in `commonTest` and runs on every target. Whether a decoder on
 * the other side actually reads what is written here is `BmpRoundTripTest`'s job, in `skiaTest`.
 */
class BmpEncoderTest {

    private fun ByteArray.int(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.short(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `header starts with the BM magic`() {
        val header = bmpHeader(4, 4)
        assertEquals('B'.code.toByte(), header[0])
        assertEquals('M'.code.toByte(), header[1])
    }

    @Test
    fun `file header declares the total size and the pixel offset`() {
        val header = bmpHeader(3, 5)
        assertEquals(BMP_HEADER_SIZE + 3 * 5 * 4, header.int(2), "bfSize")
        assertEquals(0, header.short(6), "bfReserved1")
        assertEquals(0, header.short(8), "bfReserved2")
        assertEquals(BMP_HEADER_SIZE, header.int(10), "bfOffBits")
    }

    @Test
    fun `info header is a V4 header of 32bpp bitfields`() {
        val header = bmpHeader(7, 2)
        assertEquals(108, header.int(14), "bV4Size")
        assertEquals(7, header.int(18), "bV4Width")
        assertEquals(1, header.short(26), "bV4Planes")
        assertEquals(32, header.short(28), "bV4BitCount")
        assertEquals(3, header.int(30), "bV4V4Compression should be BI_BITFIELDS")
        assertEquals(7 * 2 * 4, header.int(34), "bV4SizeImage")
    }

    @Test
    fun `height is negative so that rows are stored top-down`() {
        assertEquals(-9, bmpHeader(4, 9).int(22))
    }

    @Test
    fun `channel masks describe a BGRA byte order with alpha`() {
        val header = bmpHeader(2, 2)
        assertEquals(0x00FF0000, header.int(54), "red mask")
        assertEquals(0x0000FF00, header.int(58), "green mask")
        assertEquals(0x000000FF, header.int(62), "blue mask")
        assertEquals(-0x1000000, header.int(66), "alpha mask")
    }

    @Test
    fun `a pixel is written as B G R A`() {
        val bytes = encodeBmp32(intArrayOf(0x7F112233), width = 1, height = 1)
        assertEquals(0x33.toByte(), bytes[BMP_HEADER_SIZE], "blue")
        assertEquals(0x22.toByte(), bytes[BMP_HEADER_SIZE + 1], "green")
        assertEquals(0x11.toByte(), bytes[BMP_HEADER_SIZE + 2], "red")
        assertEquals(0x7F.toByte(), bytes[BMP_HEADER_SIZE + 3], "alpha")
    }

    @Test
    fun `pixels are written row-major`() {
        // A 2x2 whose four pixels are distinguishable by their blue channel alone.
        val bytes = encodeBmp32(
            intArrayOf(0xFF0000_00.toInt(), 0xFF000001.toInt(), 0xFF000002.toInt(), 0xFF000003.toInt()),
            width = 2,
            height = 2,
        )
        for (i in 0 until 4) {
            assertEquals(i.toByte(), bytes[BMP_HEADER_SIZE + i * 4], "pixel $i")
        }
    }

    @Test
    fun `the encoded size is the header plus four bytes per pixel`() {
        assertEquals(BMP_HEADER_SIZE + 4, encodeBmp32(IntArray(1), 1, 1).size)
        assertEquals(BMP_HEADER_SIZE + 3 * 5 * 4, encodeBmp32(IntArray(15), 3, 5).size)
        assertEquals(BMP_HEADER_SIZE + 5 * 3 * 4, encodeBmp32(IntArray(15), 5, 3).size)
    }

    @Test
    fun `bgra bytes are copied through unchanged`() {
        val bgra = ByteArray(2 * 2 * 4) { it.toByte() }
        val bytes = encodeBmp32FromBgra(bgra, width = 2, height = 2)

        assertEquals(BMP_HEADER_SIZE + bgra.size, bytes.size)
        for (i in bgra.indices) {
            assertEquals(bgra[i], bytes[BMP_HEADER_SIZE + i], "byte $i")
        }
    }

    @Test
    fun `a size that does not match the pixels is rejected`() {
        assertFailsWith<IllegalArgumentException> { encodeBmp32(IntArray(3), 2, 2) }
        assertFailsWith<IllegalArgumentException> { encodeBmp32FromBgra(ByteArray(15), 2, 2) }
        assertFailsWith<IllegalArgumentException> { bmpHeader(0, 4) }
    }
}
