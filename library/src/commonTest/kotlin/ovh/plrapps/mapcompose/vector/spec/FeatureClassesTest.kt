package ovh.plrapps.mapcompose.vector.spec

import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionResult
import ovh.plrapps.mapcompose.vector.spec.style.filter.featureFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [FeatureClasses] groups the features an expression cannot tell apart, and
 * `isPropertyDependentOnly` says which expressions that is true of. The renderer evaluates such an
 * expression once per class, so both halves have to be exact: a class that is too coarse, or an
 * expression wrongly admitted, draws one feature with another's verdict.
 */
class FeatureClassesTest {

    private fun feature(type: Tile.GeomType, vararg tags: Int, id: Long? = null) =
        Tile.Feature(id = id, tags = tags, type = type, geometry = IntArray(0))

    @Test
    fun `features with the same type and tags share a class`() {
        val classes = FeatureClasses.of(
            listOf(
                feature(Tile.GeomType.LINESTRING, 0, 1, 2, 3),
                feature(Tile.GeomType.LINESTRING, 0, 2),
                feature(Tile.GeomType.LINESTRING, 0, 1, 2, 3),
            )
        )

        assertEquals(2, classes.count)
        assertEquals(classes.classOf[0], classes.classOf[2])
        assertNotEquals(classes.classOf[0], classes.classOf[1])
    }

    @Test
    fun `the geometry type separates features with the same tags`() {
        val classes = FeatureClasses.of(
            listOf(
                feature(Tile.GeomType.LINESTRING, 0, 1),
                feature(Tile.GeomType.POLYGON, 0, 1),
            )
        )

        assertEquals(2, classes.count)
    }

    @Test
    fun `an id does not separate features`() {
        val classes = FeatureClasses.of(
            listOf(
                feature(Tile.GeomType.POINT, 0, 1, id = 1),
                feature(Tile.GeomType.POINT, 0, 1, id = 2),
            )
        )

        assertEquals(1, classes.count)
    }

    private fun followsFeatureClass(filter: Any?): Boolean =
        when (val r = featureFilter(filter, rootKey = "layers[0].filter")) {
            is ExpressionResult.Success -> r.value!!.followsFeatureClass
            is ExpressionResult.Error -> error("filter did not compile: ${r.errors}")
        }

    @Test
    fun `a filter over properties and the geometry type follows the class`() {
        assertTrue(followsFeatureClass(listOf("==", listOf("get", "class"), "motorway")))
        assertTrue(
            followsFeatureClass(
                listOf(
                    "all",
                    listOf("==", listOf("geometry-type"), "LineString"),
                    listOf("match", listOf("get", "class"), listOf("a", "b"), true, false),
                    listOf(">=", listOf("zoom"), 12.0),
                )
            )
        )
        // Legacy syntax, which compiles to the internal `filter-*` operators.
        assertTrue(followsFeatureClass(listOf("all", listOf("==", "\$type", "Polygon"), listOf("in", "class", "a", "b"))))
        assertTrue(followsFeatureClass(listOf("has", "name")))
    }

    @Test
    fun `a filter that reads the id does not follow the class`() {
        assertFalse(followsFeatureClass(listOf("==", listOf("id"), 7.0)))
        assertFalse(followsFeatureClass(listOf("all", listOf("has", listOf("get", "x")), listOf("==", listOf("id"), 7.0))))
        // Legacy syntax.
        assertFalse(followsFeatureClass(listOf("==", "\$id", 7.0)))
        assertFalse(followsFeatureClass(listOf("has", "\$id")))
        assertFalse(followsFeatureClass(listOf("in", "\$id", 1.0, 2.0)))
    }

    @Test
    fun `a filter that reads the geometry does not follow the class`() {
        val polygon = mapOf(
            "type" to "Polygon",
            "coordinates" to listOf(listOf(listOf(0.0, 0.0), listOf(1.0, 0.0), listOf(1.0, 1.0), listOf(0.0, 0.0))),
        )
        assertFalse(followsFeatureClass(listOf("within", polygon)))
    }
}
