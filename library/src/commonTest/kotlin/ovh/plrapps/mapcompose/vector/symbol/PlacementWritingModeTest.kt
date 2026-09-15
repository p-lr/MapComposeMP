package ovh.plrapps.mapcompose.vector.symbol

import ovh.plrapps.mapcompose.vector.core.ViewportInfo
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.spec.style.WRITING_MODE_HORIZONTAL
import ovh.plrapps.mapcompose.vector.spec.style.WRITING_MODE_VERTICAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `text-writing-mode` as a preference *order*, resolved by the placement pass.
 *
 * Upstream's `placeTextForPlacementModes` (`src/symbol/placement.ts`) walks `bucket.writingModes`
 * and breaks on the first orientation whose box fits. This port used to decide the orientation at
 * shaping time by membership, so a style asking for `["horizontal", "vertical"]` got a stacked
 * label wherever the text was eligible, whether or not a horizontal one would have fitted, and
 * there was no second shaping to fall back to when the first collided.
 */
class PlacementWritingModeTest {

    private val index = CrossTileSymbolIndex()

    /** One placement cycle over one bucket, as `VectorRasterizer.place` runs it. */
    private fun place(
        instances: List<SymbolInstance>,
        viewportInfo: ViewportInfo = SymbolFixtures.viewport(),
    ): Placement {
        val buckets = listOf(SymbolFixtures.bucket(instances))
        index.addLayer(SymbolFixtures.LAYER, buckets, density = 1f)
        val placement = Placement(viewportInfo, zoom = 6.0, collisionDetectionEnabled = true)
        placement.placeBuckets(PlacementOrder(buckets, viewportInfo), previous = null)
        placement.commit(previous = null, now = 0L)
        return placement
    }

    /** The box each placed label was actually drawn with -- 40x12 horizontal, 12x40 stacked. */
    private fun placedBoxes(placement: Placement): List<Pair<Float, Float>> =
        placement.result().symbols
            .map { it.instance }
            .filterIsInstance<SymbolInstance.Text>()
            .map { it.value.width to it.value.height }

    /** A label eligible for both settings, 40x12 flat and 12x40 stacked. */
    private fun bothWays(key: String, x: Double, writingModes: List<String>) =
        SymbolFixtures.textInstance(
            key = key,
            global = Point(x, 0.5),
            width = 40f,
            height = 12f,
            verticalSize = 12f to 40f,
            writingModes = writingModes,
        )

    /**
     * A 12x12 box 24 px to the right of the centre: it covers ground the flat label wants and none
     * the stacked one does. The viewport's world is 1000 px wide, so a normalized delta of `d`
     * projects to `d * 1000` screen pixels.
     */
    private fun blocker() = SymbolFixtures.textInstance(
        key = "blocker",
        global = Point(0.5 + 24.0 / 1000.0, 0.5),
        width = 12f,
        height = 12f,
    )

    @Test
    fun `horizontal first keeps the flat setting where it fits`() {
        val placement = place(
            listOf(bothWays("label", 0.5, listOf(WRITING_MODE_HORIZONTAL, WRITING_MODE_VERTICAL)))
        )
        assertEquals(listOf(40f to 12f), placedBoxes(placement))
    }

    @Test
    fun `horizontal first falls back to the stacked setting when the flat one collides`() {
        /* Source order within a layer is placement order, so the blocker takes its ground first. */
        val placement = place(
            listOf(
                blocker(),
                bothWays("label", 0.5, listOf(WRITING_MODE_HORIZONTAL, WRITING_MODE_VERTICAL)),
            )
        )
        val boxes = placedBoxes(placement)
        assertTrue(boxes.contains(12f to 12f), "the blocker is placed")
        assertTrue(boxes.contains(12f to 40f), "the label fell back to its stacked setting")
    }

    @Test
    fun `vertical first keeps the stacked setting where it fits`() {
        val placement = place(
            listOf(bothWays("label", 0.5, listOf(WRITING_MODE_VERTICAL, WRITING_MODE_HORIZONTAL)))
        )
        assertEquals(listOf(12f to 40f), placedBoxes(placement))
    }

    @Test
    fun `a list naming one mode alone offers no fallback`() {
        val placement = place(
            listOf(blocker(), bothWays("label", 0.5, listOf(WRITING_MODE_HORIZONTAL)))
        )
        assertEquals(listOf(12f to 12f), placedBoxes(placement), "only the blocker survives")
    }

    @Test
    fun `a label with no stacked setting is placed flat whatever the list says`() {
        val instance = SymbolFixtures.textInstance(
            key = "latin",
            width = 40f,
            height = 12f,
            writingModes = listOf(WRITING_MODE_VERTICAL, WRITING_MODE_HORIZONTAL),
        )
        assertEquals(listOf(40f to 12f), placedBoxes(place(listOf(instance))))
    }
}
