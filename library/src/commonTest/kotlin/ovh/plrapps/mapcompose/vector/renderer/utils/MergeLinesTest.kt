package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeLinesTest {

    private fun feature(
        text: String?,
        vararg points: Pair<Float, Float>,
    ) = MergeableFeature(
        text = text,
        lines = mutableListOf(points.toMutableList()),
        value = text ?: "",
    )

    private fun geometryOf(feature: MergeableFeature<String>) = feature.lines!![0].toList()

    @Test
    fun `two same-text lines sharing an endpoint become one`() {
        val merged = mergeLines(
            listOf(
                feature("Main St", 0f to 0f, 10f to 0f),
                feature("Main St", 10f to 0f, 20f to 0f),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(listOf(0f to 0f, 10f to 0f, 20f to 0f), geometryOf(merged[0]))
    }

    @Test
    fun `a line adjacent to the start of an existing one is merged onto its front`() {
        val merged = mergeLines(
            listOf(
                feature("Main St", 10f to 0f, 20f to 0f),
                feature("Main St", 0f to 0f, 10f to 0f),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(listOf(0f to 0f, 10f to 0f, 20f to 0f), geometryOf(merged[0]))
    }

    @Test
    fun `a line adjacent to both ends merges all three`() {
        val merged = mergeLines(
            listOf(
                feature("Main St", 0f to 0f, 10f to 0f),
                feature("Main St", 20f to 0f, 30f to 0f),
                feature("Main St", 10f to 0f, 20f to 0f),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(
            listOf(0f to 0f, 10f to 0f, 20f to 0f, 30f to 0f),
            geometryOf(merged[0]),
        )
    }

    @Test
    fun `different text is never merged`() {
        val merged = mergeLines(
            listOf(
                feature("Main St", 0f to 0f, 10f to 0f),
                feature("Side St", 10f to 0f, 20f to 0f),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `lines that do not touch are not merged`() {
        val merged = mergeLines(
            listOf(
                feature("Main St", 0f to 0f, 10f to 0f),
                feature("Main St", 50f to 0f, 60f to 0f),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `an unlabelled feature passes through untouched`() {
        val merged = mergeLines(
            listOf(
                feature(null, 0f to 0f, 10f to 0f),
                feature(null, 10f to 0f, 20f to 0f),
            )
        )
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.lines != null })
    }

    @Test
    fun `a merged-away feature is dropped from the result`() {
        val input = listOf(
            feature("Main St", 0f to 0f, 10f to 0f),
            feature("Main St", 20f to 0f, 30f to 0f),
            feature("Main St", 10f to 0f, 20f to 0f),
        )
        mergeLines(input)
        // The three-way merge voids the entry it folded into its neighbour.
        assertEquals(1, input.count { it.lines == null })
        assertNull(input[1].lines)
    }
}
