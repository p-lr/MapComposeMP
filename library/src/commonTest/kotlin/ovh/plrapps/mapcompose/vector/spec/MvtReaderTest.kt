package ovh.plrapps.mapcompose.vector.spec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MvtReaderTest {

    private val sample = Tile(
        layers = listOf(
            Tile.Layer(
                version = 2,
                name = "roads",
                features = listOf(
                    Tile.Feature(
                        id = 42,
                        tags = intArrayOf(0, 0, 1, 1),
                        type = Tile.GeomType.LINESTRING,
                        geometry = intArrayOf(9, 50, 34, 18, 20, 0, 0, 20),
                    ),
                    Tile.Feature(type = Tile.GeomType.POINT, geometry = intArrayOf(9, 8192, 8191)),
                ),
                keys = listOf("class", "lanes"),
                values = listOf(
                    Tile.Value(stringValue = "motorway"),
                    Tile.Value(intValue = 3),
                    Tile.Value(floatValue = 1.5f),
                    Tile.Value(doubleValue = -2.25),
                    Tile.Value(uintValue = 1L shl 40),
                    Tile.Value(sintValue = -7),
                    Tile.Value(boolValue = true),
                ),
                extent = 8192,
            ),
            Tile.Layer(version = 1, name = "empty"),
        )
    )

    @Test
    fun `a tile reads back exactly what was written`() {
        val decoded = MvtReader.decode(sample.encodeToByteArray())

        assertEquals(2, decoded.layers.size)
        val roads = decoded.layers[0]
        assertEquals(2, roads.version)
        assertEquals("roads", roads.name)
        assertEquals(8192, roads.extent)
        assertEquals(listOf("class", "lanes"), roads.keys)
        assertEquals(sample.layers[0].values, roads.values)

        val line = roads.features[0]
        assertEquals(42L, line.id)
        assertEquals(Tile.GeomType.LINESTRING, line.type)
        assertContentEquals(intArrayOf(0, 0, 1, 1), line.tags)
        assertContentEquals(intArrayOf(9, 50, 34, 18, 20, 0, 0, 20), line.geometry)

        val point = roads.features[1]
        assertNull(point.id)
        assertContentEquals(intArrayOf(9, 8192, 8191), point.geometry)
        assertEquals(0, point.tags.size)
    }

    @Test
    fun `absent optional fields keep their absence`() {
        val decoded = MvtReader.decode(sample.encodeToByteArray())
        val empty = decoded.layers[1]
        assertEquals("empty", empty.name)
        assertNull(empty.extent, "an absent extent is the caller's 4096, not a value read")
        assertTrue(empty.features.isEmpty())
    }

    @Test
    fun `a geometry integer past 2 to the 31 survives`() {
        // uint32 command integers above Int.MAX_VALUE are not used by real encoders, but must not
        // corrupt their neighbours.
        val tile = Tile(listOf(Tile.Layer(2, "l", listOf(Tile.Feature(geometry = intArrayOf(-1, 5))))))
        assertContentEquals(intArrayOf(-1, 5), MvtReader.decode(tile.encodeToByteArray()).layers[0].features[0].geometry)
    }

    @Test
    fun `unpacked repeated fields are accepted`() {
        val feature = ProtoOut().apply {
            varintField(3, 1)
            varintField(4, 9)
            varintField(4, 4)
            varintField(4, 6)
            varintField(2, 0)
            varintField(2, 1)
        }.bytes()
        val layer = ProtoOut().apply {
            stringField(1, "points")
            message(2, feature)
        }.bytes()
        val tile = ProtoOut().apply { message(3, layer) }.bytes()

        val decoded = MvtReader.decode(tile).layers.single().features.single()
        assertContentEquals(intArrayOf(9, 4, 6), decoded.geometry)
        assertContentEquals(intArrayOf(0, 1), decoded.tags)
    }

    @Test
    fun `unknown fields and extensions are skipped`() {
        val layer = ProtoOut().apply {
            stringField(1, "l")
            varintField(99, 12345)
            bytesField(100, byteArrayOf(1, 2, 3))
            tag(101, 5); fixed32(7)
            tag(102, 1); fixed64(8)
            varintField(5, 512)
        }.bytes()
        val tile = ProtoOut().apply {
            varintField(20, 1)
            message(3, layer)
        }.bytes()

        val decoded = MvtReader.decode(tile).layers.single()
        assertEquals("l", decoded.name)
        assertEquals(512, decoded.extent)
    }

    @Test
    fun `bytes that are not a protobuf are refused`() {
        assertFailsWith<IllegalArgumentException> {
            MvtReader.decode("<html><body>404</body></html>".encodeToByteArray() + byteArrayOf(-1, -1, -1))
        }
        val truncated = sample.encodeToByteArray().let { it.copyOf(it.size - 5) }
        assertFailsWith<IllegalArgumentException> { MvtReader.decode(truncated) }
    }

    @Test
    fun `an empty payload is an empty tile`() {
        assertTrue(MvtReader.decode(ByteArray(0)).layers.isEmpty())
    }
}
