package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ownership rule that decides which circle vertices a tile draws once its neighbours are
 * gathered: one owner per vertex, with the MVT buffer's copy kept as the fallback for a neighbour
 * that is missing.
 */
class CircleVertexGateTest {

    private val size = 64
    private val reach = 8.0

    @Test
    fun `a vertex the tile owns is always drawn`() {
        val gate = CircleVertexGate(coveredDirections = CircleVertexGate.ALL_DIRECTIONS)

        assertTrue(gate.accepts(x = 0.0, y = 0.0, canvasSize = size, reach = reach))
        assertTrue(gate.accepts(x = 63.9, y = 63.9, canvasSize = size, reach = reach))
    }

    @Test
    fun `a buffered copy is dropped when the neighbour that owns it was gathered`() {
        val gate = CircleVertexGate(coveredDirections = setOf(-1 to 0))

        assertFalse(gate.accepts(x = -1.0, y = 32.0, canvasSize = size, reach = reach))
    }

    @Test
    fun `a buffered copy is kept when its own tile was not gathered`() {
        val gate = CircleVertexGate(coveredDirections = setOf(-1 to 0))

        // The eastern neighbour is missing, so this tile is the only one that can draw the disc.
        assertTrue(gate.accepts(x = 64.0, y = 32.0, canvasSize = size, reach = reach))
        // ... but only while the disc still reaches in.
        assertFalse(gate.accepts(x = 73.0, y = 32.0, canvasSize = size, reach = reach))
    }

    @Test
    fun `a corner vertex is owned by the diagonal neighbour`() {
        assertEquals(-1 to -1, tileDirectionOf(-1.0, -1.0, size))
        assertEquals(1 to 1, tileDirectionOf(64.0, 64.0, size))
        assertEquals(0 to 0, tileDirectionOf(10.0, 10.0, size))

        val gate = CircleVertexGate(coveredDirections = setOf(-1 to 0, 0 to -1))

        // The corner tile itself was not gathered, so the buffered copy still draws.
        assertTrue(gate.accepts(x = -1.0, y = -1.0, canvasSize = size, reach = reach))
    }

    @Test
    fun `a gathered neighbour contributes the vertices it owns offset by a tile`() {
        val gate = CircleVertexGate.forNeighbour(dx = -1, dy = 0, canvasSize = size)

        // One pixel inside the western neighbour's eastern edge: its disc reaches into this tile.
        assertTrue(gate.accepts(x = 63.0, y = 32.0, canvasSize = size, reach = reach))
        // Ten pixels in, against a reach of eight: out of reach.
        assertFalse(gate.accepts(x = 54.0, y = 32.0, canvasSize = size, reach = reach))
        // A vertex the neighbour merely carries in its own buffer belongs to this tile, which draws
        // it from its own copy -- drawing it twice is what doubles a translucent circle's alpha.
        assertFalse(gate.accepts(x = 65.0, y = 32.0, canvasSize = size, reach = reach))
    }
}
