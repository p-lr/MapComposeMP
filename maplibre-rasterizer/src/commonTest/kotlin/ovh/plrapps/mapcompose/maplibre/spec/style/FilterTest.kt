package ovh.plrapps.mapcompose.maplibre.spec.style

import kotlinx.serialization.json.*
import ovh.plrapps.mapcompose.maplibre.spec.style.Filter.Companion.TYPE_PROPERTY
import kotlin.test.*

class FilterTest {
    @Test
    fun testEquals() {
        val filter = Filter.Eq(
            FilterOperand.Get("name"),
            FilterOperand.Literal("test")
        )
        assertTrue(filter.process(mapOf("name" to "test"), 0.0))
        assertFalse(filter.process(mapOf("name" to "other"), 0.0))
    }

    @Test
    fun testNotEquals() {
        val filter = Filter.Neq(
            FilterOperand.Get("name"),
            FilterOperand.Literal("test")
        )
        assertFalse(filter.process(mapOf("name" to "test"), 0.0))
        assertTrue(filter.process(mapOf("name" to "other"), 0.0))
    }

    @Test
    fun testGreaterThan() {
        val filter = Filter.Gt(
            FilterOperand.Get("count"),
            FilterOperand.Literal(5)
        )
        assertTrue(filter.process(mapOf("count" to 10), 0.0))
        assertFalse(filter.process(mapOf("count" to 3), 0.0))
    }

    @Test
    fun testLessThan() {
        val filter = Filter.Lt(
            FilterOperand.Get("count"),
            FilterOperand.Literal(5)
        )
        assertTrue(filter.process(mapOf("count" to 3), 0.0))
        assertFalse(filter.process(mapOf("count" to 10), 0.0))
    }

    @Test
    fun testIn() {
        val filter = Filter.InList(
            FilterOperand.Get("name"),
            listOf(
                FilterOperand.Literal("test1"),
                FilterOperand.Literal("test2")
            )
        )
        assertTrue(filter.process(mapOf("name" to "test1"), 0.0))
        assertTrue(filter.process(mapOf("name" to "test2"), 0.0))
        assertFalse(filter.process(mapOf("name" to "other"), 0.0))
    }

    @Test
    fun testNotIn() {
        val filter = Filter.NotInList(
            FilterOperand.Get("name"),
            listOf(
                FilterOperand.Literal("test1"),
                FilterOperand.Literal("test2")
            )
        )
        assertFalse(filter.process(mapOf("name" to "test1"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test2"), 0.0))
        assertTrue(filter.process(mapOf("name" to "other"), 0.0))
    }

    @Test
    fun testHas() {
        val filter = Filter.Has("name")
        assertTrue(filter.process(mapOf("name" to "test"), 0.0))
        assertFalse(filter.process(mapOf("other" to "test"), 0.0))
    }

    @Test
    fun testNotHas() {
        val filter = Filter.NotHas("name")
        assertFalse(filter.process(mapOf("name" to "test"), 0.0))
        assertTrue(filter.process(mapOf("other" to "test"), 0.0))
    }

    @Test
    fun testAll() {
        val filter = Filter.All(
            listOf(
                Filter.Eq(
                    FilterOperand.Get("name"),
                    FilterOperand.Literal("test")
                ),
                Filter.InList(
                    FilterOperand.Get("type"),
                    listOf(FilterOperand.Literal("point"))
                )
            )
        )
        assertTrue(filter.process(mapOf("name" to "test", "type" to "point"), 0.0))
        assertFalse(filter.process(mapOf("name" to "other", "type" to "point"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test", "type" to "line"), 0.0))
    }

    @Test
    fun testAny() {
        val filter = Filter.AnyOf(
            listOf(
                Filter.Eq(
                    FilterOperand.Get("name"),
                    FilterOperand.Literal("test1")
                ),
                Filter.Eq(
                    FilterOperand.Get("name"),
                    FilterOperand.Literal("test2")
                )
            )
        )
        assertTrue(filter.process(mapOf("name" to "test1"), 0.0))
        assertTrue(filter.process(mapOf("name" to "test2"), 0.0))
        assertFalse(filter.process(mapOf("name" to "other"), 0.0))
    }

    @Test
    fun testNone() {
        val filter = Filter.None(
            listOf(
                Filter.Eq(
                    FilterOperand.Get("name"),
                    FilterOperand.Literal("test1")
                ),
                Filter.Eq(
                    FilterOperand.Get("name"),
                    FilterOperand.Literal("test2")
                )
            )
        )
        assertFalse(filter.process(mapOf("name" to "test1"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test2"), 0.0))
        assertTrue(filter.process(mapOf("name" to "other"), 0.0))
    }

    @Test
    fun testNestedFilters() {
        val filter = Filter.All(
            listOf(
                Filter.AnyOf(
                    listOf(
                        Filter.Eq(
                            FilterOperand.Get("name"),
                            FilterOperand.Literal("test1")
                        ),
                        Filter.Eq(
                            FilterOperand.Get("name"),
                            FilterOperand.Literal("test2")
                        )
                    )
                ),
                Filter.NotInList(
                    FilterOperand.Get("type"),
                    listOf(FilterOperand.Literal("line"))
                )
            )
        )
        assertTrue(filter.process(mapOf("name" to "test1", "type" to "point"), 0.0))
        assertTrue(filter.process(mapOf("name" to "test2", "type" to "point"), 0.0))
        assertFalse(filter.process(mapOf("name" to "other", "type" to "point"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test1", "type" to "line"), 0.0))
    }

    @Test
    fun testDeeplyNestedFilters() {
        val filter = Filter.All(
            listOf(
                Filter.AnyOf(
                    listOf(
                        Filter.Eq(
                            FilterOperand.Get("name"),
                            FilterOperand.Literal("test1")
                        ),
                        Filter.Eq(
                            FilterOperand.Get("name"),
                            FilterOperand.Literal("test2")
                        )
                    )
                ),
                Filter.All(
                    listOf(
                        Filter.NotInList(
                            FilterOperand.Get("type"),
                            listOf(FilterOperand.Literal("line"))
                        ),
                        Filter.AnyOf(
                            listOf(
                                Filter.Eq(
                                    FilterOperand.Get("color"),
                                    FilterOperand.Literal("red")
                                ),
                                Filter.Eq(
                                    FilterOperand.Get("color"),
                                    FilterOperand.Literal("blue")
                                )
                            )
                        )
                    )
                )
            )
        )
        assertTrue(filter.process(mapOf("name" to "test1", "type" to "point", "color" to "red"), 0.0))
        assertTrue(filter.process(mapOf("name" to "test2", "type" to "point", "color" to "blue"), 0.0))
        assertFalse(filter.process(mapOf("name" to "other", "type" to "point", "color" to "red"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test1", "type" to "line", "color" to "red"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test1", "type" to "point", "color" to "green"), 0.0))
    }

    @Test
    fun testComplexNestedFilters() {
        val filter = Filter.All(
            listOf(
                Filter.AnyOf(
                    listOf(
                        Filter.Eq(
                            FilterOperand.Get("name"),
                            FilterOperand.Literal("test1")
                        ),
                        Filter.Eq(
                            FilterOperand.Get("name"),
                            FilterOperand.Literal("test2")
                        )
                    )
                ),
                Filter.All(
                    listOf(
                        Filter.NotInList(
                            FilterOperand.Get("type"),
                            listOf(FilterOperand.Literal("line"))
                        ),
                        Filter.AnyOf(
                            listOf(
                                Filter.Eq(
                                    FilterOperand.Get("color"),
                                    FilterOperand.Literal("red")
                                ),
                                Filter.Eq(
                                    FilterOperand.Get("color"),
                                    FilterOperand.Literal("blue")
                                )
                            )
                        )
                    )
                ),
                Filter.AnyOf(
                    listOf(
                        Filter.Eq(
                            FilterOperand.Get("size"),
                            FilterOperand.Literal("small")
                        ),
                        Filter.Eq(
                            FilterOperand.Get("size"),
                            FilterOperand.Literal("medium")
                        )
                    )
                )
            )
        )
        assertTrue(filter.process(mapOf("name" to "test1", "type" to "point", "color" to "red", "size" to "small"), 0.0))
        assertTrue(filter.process(mapOf("name" to "test2", "type" to "point", "color" to "blue", "size" to "medium"), 0.0))
        assertFalse(filter.process(mapOf("name" to "other", "type" to "point", "color" to "red", "size" to "small"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test1", "type" to "line", "color" to "red", "size" to "small"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test1", "type" to "point", "color" to "green", "size" to "small"), 0.0))
        assertFalse(filter.process(mapOf("name" to "test1", "type" to "point", "color" to "red", "size" to "large"), 0.0))
    }

    @Test
    fun testType() {
        val filter = Filter.Type("point")
        assertTrue(filter.process(mapOf("type" to "point"), 0.0))
        assertFalse(filter.process(mapOf("type" to "line"), 0.0))
    }

    @Test
    fun testTypeWithOtherFilters() {
        val filter = Filter.All(
            listOf(
                Filter.Type("point"),
                Filter.Eq(
                    FilterOperand.Get("type"),
                    FilterOperand.Literal("point")
                )
            )
        )
        assertTrue(filter.process(mapOf("type" to "point"), 0.0))
        assertFalse(filter.process(mapOf("type" to "line"), 0.0))
    }

    @Test
    fun testTypeWithNestedFilters() {
        val filter = Filter.All(
            listOf(
                Filter.AnyOf(
                    listOf(
                        Filter.Type("point"),
                        Filter.Type("line")
                    )
                )
            )
        )
        assertTrue(filter.process(mapOf("type" to "point"), 0.0))
        assertTrue(filter.process(mapOf("type" to "line"), 0.0))
        assertFalse(filter.process(mapOf("type" to "polygon"), 0.0))
    }

    @Test
    fun testParseFilterJson() {
        val json = """
            [
                "all",
                ["in", "class", "residential", "suburb", "neighbourhood"]
            ]
        """.trimIndent()

        val filter = Json.decodeFromString<Filter>(json)
        assertTrue(filter is Filter.All)
        assertEquals(1, filter.filters.size)
        
        val inFilter = filter.filters[0]
        assertTrue(inFilter is Filter.InList)
        assertTrue(inFilter.key is FilterOperand.Get)
        assertEquals("class", inFilter.key.property)
        assertEquals(3, inFilter.values.size)
        assertTrue(inFilter.values.all { it is FilterOperand.Literal })
        assertEquals("residential", (inFilter.values[0] as FilterOperand.Literal).value)
        assertEquals("suburb", (inFilter.values[1] as FilterOperand.Literal).value)
        assertEquals("neighbourhood", (inFilter.values[2] as FilterOperand.Literal).value)
    }

    @Test
    fun testParseComplexFilterJson() {
        val json = """
            [
                "all",
                ["==", ["get", "type"], "Point"],
                ["in", ["get", "class"], "bar", "cafe", "restaurant"],
                ["has", "name"]
            ]
        """.trimIndent()

        val filter = Json.decodeFromString<Filter>(json)
        assertTrue(filter is Filter.All)
        assertEquals(3, filter.filters.size)
        
        val typeFilter = filter.filters[0]
        assertTrue(typeFilter is Filter.Eq)
        assertTrue(typeFilter.left is FilterOperand.Get)
        assertEquals("type", (typeFilter.left as FilterOperand.Get).property)
        assertTrue(typeFilter.right is FilterOperand.Literal)
        assertEquals("Point", (typeFilter.right as FilterOperand.Literal).value)
        
        val inFilter = filter.filters[1]
        assertTrue(inFilter is Filter.InList)
        assertTrue(inFilter.key is FilterOperand.Get)
        assertEquals("class", (inFilter.key as FilterOperand.Get).property)
        assertEquals(3, inFilter.values.size)
        assertTrue(inFilter.values.all { it is FilterOperand.Literal })
        assertEquals("bar", (inFilter.values[0] as FilterOperand.Literal).value)
        assertEquals("cafe", (inFilter.values[1] as FilterOperand.Literal).value)
        assertEquals("restaurant", (inFilter.values[2] as FilterOperand.Literal).value)
        
        val hasFilter = filter.filters[2]
        assertTrue(hasFilter is Filter.Has)
        assertEquals("name", hasFilter.key)
    }

    @Test
    fun testParseNestedFilterJson() {
        val json = """
            [
                "all",
                ["any", ["==", ["get", "class"], "primary"], ["==", ["get", "class"], "secondary"]],
                ["!in", ["get", "class"], "bridge", "tunnel"]
            ]
        """.trimIndent()

        val filter = Json.decodeFromString<Filter>(json)
        assertTrue(filter is Filter.All)
        assertEquals(2, filter.filters.size)
        
        val anyFilter = filter.filters[0]
        assertTrue(anyFilter is Filter.AnyOf)
        assertEquals(2, anyFilter.filters.size)
        
        val notInFilter = filter.filters[1]
        assertTrue(notInFilter is Filter.NotInList)
        assertTrue(notInFilter.key is FilterOperand.Get)
        assertEquals("class", (notInFilter.key as FilterOperand.Get).property)
        assertEquals(2, notInFilter.values.size)
        assertTrue(notInFilter.values.all { it is FilterOperand.Literal })
        assertEquals("bridge", (notInFilter.values[0] as FilterOperand.Literal).value)
        assertEquals("tunnel", (notInFilter.values[1] as FilterOperand.Literal).value)
    }

    @Test
    fun testParseTypeAndClassFilter() {
        val json = """
            [
                "all",
                ["==", "${TYPE_PROPERTY}", "Polygon"],
                ["in", "class", "industrial", "garages", "dam"]
            ]
        """.trimIndent()

        val filter = Json.decodeFromString<Filter>(json)
        assertTrue(filter is Filter.All)
        assertEquals(2, filter.filters.size)
        
        val typeFilter = filter.filters[0]
        assertTrue(typeFilter is Filter.Eq)
        assertTrue(typeFilter.left is FilterOperand.Get)
        assertEquals(TYPE_PROPERTY, (typeFilter.left as FilterOperand.Get).property)
        assertTrue(typeFilter.right is FilterOperand.Literal)
        assertEquals("Polygon", (typeFilter.right as FilterOperand.Literal).value)
        
        val inFilter = filter.filters[1]
        assertTrue(inFilter is Filter.InList)
        assertTrue(inFilter.key is FilterOperand.Get)
        assertEquals("class", (inFilter.key as FilterOperand.Get).property)
        assertEquals(3, inFilter.values.size)
        assertTrue(inFilter.values.all { it is FilterOperand.Literal })
        assertEquals("industrial", (inFilter.values[0] as FilterOperand.Literal).value)
        assertEquals("garages", (inFilter.values[1] as FilterOperand.Literal).value)
        assertEquals("dam", (inFilter.values[2] as FilterOperand.Literal).value)
    }

    @Test
    fun testParseRiverBrunnelFilter() {
        val json = """
            [
                "all",
                ["in", "class", "river", "stream", "canal"],
                ["==", "brunnel", "tunnel"]
            ]
        """.trimIndent()

        val filter = Json.decodeFromString<Filter>(json)
        assertTrue(filter is Filter.All)
        assertEquals(2, filter.filters.size)

        val inFilter = filter.filters[0]
        assertTrue(inFilter is Filter.InList)
        assertTrue(inFilter.key is FilterOperand.Get)
        assertEquals("class", (inFilter.key as FilterOperand.Get).property)
        assertEquals(3, inFilter.values.size)
        assertTrue(inFilter.values.all { it is FilterOperand.Literal })
        assertEquals("river", (inFilter.values[0] as FilterOperand.Literal).value)
        assertEquals("stream", (inFilter.values[1] as FilterOperand.Literal).value)
        assertEquals("canal", (inFilter.values[2] as FilterOperand.Literal).value)

        val eqFilter = filter.filters[1]
        assertTrue(eqFilter is Filter.Eq)
        assertTrue(eqFilter.left is FilterOperand.Get)
        assertEquals("brunnel", (eqFilter.left as FilterOperand.Get).property)
        assertTrue(eqFilter.right is FilterOperand.Literal)
        assertEquals("tunnel", (eqFilter.right as FilterOperand.Literal).value)
    }
} 