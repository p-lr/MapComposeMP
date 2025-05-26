package ovh.plrapps.mapcompose.maplibre.utils.rtree

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtreeTest {
    @Test
    fun `test basic insertion and search`() {
        val rtree = Rtree<String>()
        val aabb1 = AABB(0f, 0f, 10f, 10f)
        val aabb2 = AABB(5f, 5f, 15f, 15f)

        rtree.insert(aabb1, "item1")
        rtree.insert(aabb2, "item2")

        val searchBounds = AABB(4f, 4f, 16f, 16f)
        val results = rtree.search(searchBounds)

        assertEquals(2, results.size)
        assertTrue(results.contains("item1"))
        assertTrue(results.contains("item2"))
    }

    @Test
    fun `test node splitting`() {
        val rtree = Rtree<String>(maxEntries = 4, minEntries = 2)

        rtree.insert(AABB(0f, 0f, 10f, 10f), "item1")
        rtree.insert(AABB(20f, 20f, 30f, 30f), "item2")
        rtree.insert(AABB(40f, 40f, 50f, 50f), "item3")
        rtree.insert(AABB(60f, 60f, 70f, 70f), "item4")
        rtree.insert(AABB(80f, 80f, 90f, 90f), "item5")

        val allResults = rtree.search(AABB(0f, 0f, 100f, 100f))
        println("[test node splitting] allResults: $allResults, size: ${rtree.size()}")
        assertEquals(5, allResults.size)

        val area1Results = rtree.search(AABB(0f, 0f, 15f, 15f))
        println("[test node splitting] area1Results: $area1Results")
        assertEquals(1, area1Results.size)
        assertTrue(area1Results.contains("item1"))

        val area2Results = rtree.search(AABB(15f, 15f, 35f, 35f))
        println("[test node splitting] area2Results: $area2Results")
        assertEquals(1, area2Results.size)
        assertTrue(area2Results.contains("item2"))
    }

    @Test
    fun `test AABB operations`() {
        val aabb1 = AABB(0f, 0f, 10f, 10f)
        val aabb2 = AABB(5f, 5f, 15f, 15f)

        assertTrue(aabb1.intersects(aabb2))
        assertTrue(aabb2.intersects(aabb1))

        val union = aabb1.union(aabb2)
        assertEquals(0f, union.minX)
        assertEquals(0f, union.minY)
        assertEquals(15f, union.maxX)
        assertEquals(15f, union.maxY)
    }

    @Test
    fun `test size tracking`() {
        val rtree = Rtree<String>()

        assertEquals(0, rtree.size())

        rtree.insert(AABB(0f, 0f, 10f, 10f), "item1")
        assertEquals(1, rtree.size())

        rtree.insert(AABB(5f, 5f, 15f, 15f), "item2")
        assertEquals(2, rtree.size())
    }

    @Test
    fun `test empty search results`() {
        val rtree = Rtree<String>()
        rtree.insert(AABB(0f, 0f, 10f, 10f), "item1")

        val results = rtree.search(AABB(20f, 20f, 30f, 30f))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `test overlapping AABB`() {
        val aabb1 = AABB(0f, 0f, 10f, 10f)
        val aabb2 = AABB(10f, 10f, 20f, 20f)
        val aabb3 = AABB(5f, 5f, 15f, 15f)

        assertTrue(aabb1.intersects(aabb3))
        assertTrue(aabb2.intersects(aabb3))
        assertTrue(aabb1.intersects(aabb2))
    }

    @Test
    fun `test multiple levels of splitting`() {
        val rtree = Rtree<String>(maxEntries = 3, minEntries = 1)

        for (i in 0..9) {
            rtree.insert(AABB(i * 10f, i * 10f, (i + 1) * 10f, (i + 1) * 10f), "item$i")
        }

        val allResults = rtree.search(AABB(0f, 0f, 100f, 100f))
        assertEquals(10, allResults.size)

        val areaResults = rtree.search(AABB(25f, 25f, 35f, 35f))
        assertEquals(2, areaResults.size)
        assertTrue(areaResults.contains("item2"))
        assertTrue(areaResults.contains("item3"))
    }

    @Test
    fun `test AABB contains`() {
        val outer = AABB(0f, 0f, 20f, 20f)
        val inner = AABB(5f, 5f, 15f, 15f)
        val overlapping = AABB(15f, 15f, 25f, 25f)

        assertTrue(outer.contains(inner))
        assertFalse(outer.contains(overlapping))
        assertFalse(inner.contains(outer))
    }

    @Test
    fun `test boundary conditions`() {
        val rtree = Rtree<String>()

        // Test with zero dimensions (point)
        rtree.insert(AABB(0f, 0f, 0f, 0f), "zero")
        println("Tree size after insert: "+rtree.size())

        // Search for the same point
        val zeroResults1 = rtree.search(AABB(0f, 0f, 0f, 0f))
        println("zeroResults1: $zeroResults1")
        assertEquals(1, zeroResults1.size)
        assertTrue(zeroResults1.contains("zero"))

        // Search in area containing the point
        val zeroResults2 = rtree.search(AABB(-1f, -1f, 1f, 1f))
        println("zeroResults2: $zeroResults2")
        assertEquals(1, zeroResults2.size)
        assertTrue(zeroResults2.contains("zero"))

        // Test with negative coordinates
        rtree.insert(AABB(-10f, -10f, -5f, -5f), "negative")
        println("Tree size after second insert: "+rtree.size())

        // Full intersection
        val negativeResults1 = rtree.search(AABB(-15f, -15f, 0f, 0f))
        println("negativeResults1: $negativeResults1")
        assertEquals(2, negativeResults1.size)
        assertTrue(negativeResults1.contains("negative"))
        assertTrue(negativeResults1.contains("zero"))

        // Partial intersection
        val negativeResults2 = rtree.search(AABB(-7f, -7f, -3f, -3f))
        println("negativeResults2: $negativeResults2")
        assertEquals(1, negativeResults2.size)
        assertTrue(negativeResults2.contains("negative"))

        // Boundary intersection (touching)
        val negativeResults4 = rtree.search(AABB(-5f, -5f, 0f, 0f))
        println("negativeResults4: $negativeResults4")
        assertEquals(2, negativeResults4.size)
        assertTrue(negativeResults4.contains("negative"))
        assertTrue(negativeResults4.contains("zero"))

        // Test with very large numbers
        rtree.insert(AABB(1e6f, 1e6f, 1e7f, 1e7f), "large")
        val largeResults = rtree.search(AABB(5e5f, 5e5f, 2e6f, 2e6f))
        println("largeResults: $largeResults")
        assertEquals(1, largeResults.size)
        assertTrue(largeResults.contains("large"))

        // Test with very small numbers
        rtree.insert(AABB(1e-6f, 1e-6f, 1e-5f, 1e-5f), "small")
        val smallResults = rtree.search(AABB(0f, 0f, 1e-4f, 1e-4f))
        println("smallResults: $smallResults")
        assertTrue(smallResults.contains("small"))
        assertTrue(smallResults.contains("zero"))
        assertEquals(2, smallResults.size)
    }

    @Test
    fun `test empty tree operations`() {
        val rtree = Rtree<String>()

        // Check search in empty tree
        val emptyResults = rtree.search(AABB(0f, 0f, 10f, 10f))
        assertTrue(emptyResults.isEmpty())

        // Check size of empty tree
        assertEquals(0, rtree.size())
    }

    @Test
    fun `test exact same bounding boxes`() {
        val rtree = Rtree<String>()
        val aabb = AABB(0f, 0f, 10f, 10f)

        // Insert several elements with the same bbox
        rtree.insert(aabb, "item1")
        rtree.insert(aabb, "item2")
        rtree.insert(aabb, "item3")

        val results = rtree.search(aabb)
        assertEquals(3, results.size)
        assertTrue(results.containsAll(listOf("item1", "item2", "item3")))
    }

    @Test
    fun `test boundary intersection`() {
        val rtree = Rtree<String>()

        // Create elements that touch boundaries
        rtree.insert(AABB(0f, 0f, 10f, 10f), "item1")
        rtree.insert(AABB(10f, 0f, 20f, 10f), "item2") // Touches on X
        rtree.insert(AABB(0f, 10f, 10f, 20f), "item3") // Touches on Y
        rtree.insert(AABB(10f, 10f, 20f, 20f), "item4") // Touches on corner

        // Search in area including all elements
        val results = rtree.search(AABB(0f, 0f, 20f, 20f))
        assertEquals(4, results.size)
    }

    @Test
    fun `test deep tree structure`() {
        val rtree = Rtree<String>()
        for (i in 0..500) {
            rtree.insert(AABB(i.toFloat(), i.toFloat(), (i + 10).toFloat(), (i + 10).toFloat()), "item$i")
        }
        val results = rtree.search(AABB(5f, 5f, 15f, 15f))
        assertTrue(results.size >= 15)
        for (i in 0..14) {
            assertTrue(results.contains("item$i"))
        }
    }

    @Test
    fun `test degenerate cases`() {
        val rtree = Rtree<String>()
        for (i in 0..5) {
            rtree.insert(AABB(0f, i * 10f, 10f, (i + 1) * 10f), "xline$i")
        }
        for (i in 0..5) {
            rtree.insert(AABB(i * 10f, 0f, (i + 1) * 10f, 10f), "yline$i")
        }
        val xResults = rtree.search(AABB(0f, 0f, 10f, 60f))
        assertTrue(xResults.size >= 7)
        for (i in 0..5) {
            assertTrue(xResults.contains("xline$i"))
        }
        val yResults = rtree.search(AABB(0f, 0f, 60f, 10f))
        assertTrue(yResults.size >= 7)
        for (i in 0..5) {
            assertTrue(yResults.contains("yline$i"))
        }
    }

    @Test
    fun `test node removal and rebalancing`() {
        val rtree = Rtree<String>(maxEntries = 4, minEntries = 2)

        // Fill the tree
        for (i in 0..5) {
            rtree.insert(AABB(i * 10f, i * 10f, (i + 1) * 10f, (i + 1) * 10f), "item$i")
        }

        // Check that all elements are accessible
        val results = rtree.search(AABB(0f, 0f, 60f, 60f))
        assertEquals(6, results.size)

        // Check search in separate areas
        val areaResults = rtree.search(AABB(0f, 0f, 15f, 15f))
        assertEquals(2, areaResults.size)
        assertTrue(areaResults.contains("item0"))
        assertTrue(areaResults.contains("item1"))
    }

    @Test
    fun `test non intersecting search`() {
        val rtree = Rtree<String>()

        // Insert elements in a specific area
        rtree.insert(AABB(0f, 0f, 10f, 10f), "item1")
        rtree.insert(AABB(20f, 20f, 30f, 30f), "item2")

        // Search in area not intersecting with any element
        val results = rtree.search(AABB(40f, 40f, 50f, 50f))
        assertTrue(results.isEmpty())

        // Search in partially intersecting area
        val partialResults = rtree.search(AABB(5f, 5f, 25f, 25f))
        assertTrue(partialResults.isNotEmpty())
    }

    @Test
    fun `test large dataset performance`() {
        val rtree = Rtree<String>(maxEntries = 16, minEntries = 4) // Reduce node size for better balance
        val numElements = 50_000
        val gridSize = 100 // Grid size 100x100

        // List to store all elements for naive search
        val allElements = mutableListOf<Pair<AABB, String>>()

        println("Starting insertion of $numElements elements...")

        // Create and shuffle indices for random insertion order
        val indices = (0 until numElements).shuffled()

        // Insert elements in random order with varying sizes
        for (i in indices) {
            val x = (i % gridSize) * 10
            val y = (i / gridSize) * 10
            // Add random element size from 5 to 15
            val size = 5 + (i % 11)
            val bounds = AABB(
                x.toFloat(),
                y.toFloat(),
                (x + size).toFloat(),
                (y + size).toFloat()
            )
            rtree.insert(bounds, "item$i")
            allElements.add(bounds to "item$i")

            if (i % 1000 == 0) {
                println("Inserted $i elements, current depth: ${rtree.depth()}")
                println("Sample bounds for item$i: $bounds")
            }
        }

        println("Final tree depth: ${rtree.depth()}")
        println("Tree size: ${rtree.size()}")

        // Check size
        assertEquals(numElements, rtree.size())

        // Check depth - for 50,000 elements with maxEntries=16 we expect depth around 4-5
        val expectedMinDepth = 4
        val expectedMaxDepth = 6
        assertTrue(
            rtree.depth() in expectedMinDepth..expectedMaxDepth,
            "Tree depth should be between $expectedMinDepth and $expectedMaxDepth for $numElements elements with maxEntries=16"
        )

        // Search in different areas
        val searchAreas = listOf(
            // Exact matches with elements
            AABB(0f, 0f, 10f, 10f),      // Should find elements at (0,0)
            AABB(500f, 500f, 510f, 510f), // Should find elements at (500,500)
            AABB(990f, 990f, 1000f, 1000f), // Should find elements at (990,990)

            // Large search areas
            AABB(0f, 0f, 100f, 100f),    // Should find all elements in 100x100 square
            AABB(400f, 400f, 600f, 600f), // Should find all elements in 200x200 square

            // Random search areas
            AABB(123f, 456f, 133f, 466f), // Random area
            AABB(789f, 321f, 799f, 331f), // Random area

            // Edge cases
            AABB(-10f, -10f, 0f, 0f),    // Area outside grid
            AABB(1000f, 1000f, 1010f, 1010f) // Area outside grid
        )

        // Create results string
        val results = StringBuilder()
        var totalRtreeTime = 0L
        var totalNaiveTime = 0L
        var totalElementsFound = 0

        for (area in searchAreas) {
            println("\nSearching in area: $area")

            // R-tree search
            val rtreeStartTime = Clock.System.now()
            val rtreeResults = rtree.search(area)
            val rtreeEndTime = Clock.System.now()
            val rtreeTime = rtreeEndTime - rtreeStartTime
            totalRtreeTime += rtreeTime.inWholeMilliseconds

            // Naive search
            val naiveStartTime = Clock.System.now()
            val naiveResults = allElements
                .filter { (bounds, _) -> bounds.intersects(area) }
                .map { it.second }
                .toSet()
            val naiveEndTime = Clock.System.now()
            val naiveTime = naiveEndTime - naiveStartTime
            totalNaiveTime += naiveTime.inWholeMilliseconds
            totalElementsFound += naiveResults.size

            // Print detailed results
            println("R-tree found ${rtreeResults.size} elements")
            println("Naive search found ${naiveResults.size} elements")
            if (rtreeResults.isNotEmpty()) {
                println("Sample R-tree results: ${rtreeResults.take(5)}")
            }
            if (naiveResults.isNotEmpty()) {
                println("Sample naive results: ${naiveResults.take(5)}")
            }

            results.append("\nSearch in area $area:\n")
            results.append("R-tree: found ${rtreeResults.size} elements in ${rtreeTime.inWholeMilliseconds}ms\n")
            results.append("Naive: found ${naiveResults.size} elements in ${naiveTime.inWholeMilliseconds}ms\n")
            results.append("Speedup: ${naiveTime.inWholeMilliseconds.toDouble() / rtreeTime.inWholeMilliseconds}x\n")

            // Verify correctness
            assertEquals(naiveResults, rtreeResults, "Search results should match")

            // Verify that R-tree is indeed faster
            assertTrue(rtreeTime < naiveTime, "R-tree should be faster than naive search")
        }

        // Print summary statistics
        results.append("\nSummary Statistics:\n")
        results.append("Total R-tree time: ${totalRtreeTime}ms\n")
        results.append("Total Naive time: ${totalNaiveTime}ms\n")
        results.append("Average speedup: ${totalNaiveTime.toDouble() / totalRtreeTime}x\n")
        results.append("Tree depth: ${rtree.depth()}\n")
        results.append("Total elements: ${rtree.size()}\n")
        results.append("Average elements per search: ${totalElementsFound.toDouble() / searchAreas.size}\n")

        println(results.toString())
    }

    @Test
    fun `test simple dataset`() {
        val rtree = Rtree<String>()

        // Create a simple grid of elements
        for (i in 0..2) {
            for (j in 0..2) {
                val bounds = AABB(
                    i * 10f, j * 10f,
                    (i + 1) * 10f, (j + 1) * 10f
                )
                val item = "item_${i}_${j}"
                rtree.insert(bounds, item)
                println("Inserted $item with bounds: $bounds")
            }
        }

        // Test search in different areas
        val testAreas = listOf(
            // Search in the middle element (should find all 9 elements due to boundary touching)
            AABB(10f, 10f, 20f, 20f) to listOf(
                "item_0_0", "item_0_1", "item_0_2",
                "item_1_0", "item_1_1", "item_1_2",
                "item_2_0", "item_2_1", "item_2_2"
            ),
            // Search in the corner (should find 4 elements due to boundary touching)
            AABB(0f, 0f, 10f, 10f) to listOf(
                "item_0_0", "item_0_1",
                "item_1_0", "item_1_1"
            ),
            // Search overlapping two elements (should find 4 elements due to boundary touching)
            AABB(5f, 5f, 15f, 15f) to listOf(
                "item_0_0", "item_0_1",
                "item_1_0", "item_1_1"
            ),
            // Search outside (should find item_2_2 because it touches at point (30,30))
            AABB(30f, 30f, 40f, 40f) to listOf("item_2_2")
        )

        for ((area, expectedItems) in testAreas) {
            println("\nSearching in area: $area")
            val results = rtree.search(area)
            println("Found items: $results")
            println("Expected items: $expectedItems")

            assertEquals(
                expectedItems.toSet(),
                results,
                "Search in area $area should find exactly $expectedItems"
            )
        }
    }
} 