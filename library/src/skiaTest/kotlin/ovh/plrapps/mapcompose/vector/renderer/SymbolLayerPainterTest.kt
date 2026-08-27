package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.write
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GLYPH_BORDER
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolPaint
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlinx.serialization.json.Json

/**
 * What `SymbolLayerPainter` produces for a feature: which symbols, how big, and where.
 *
 * The painter had no direct test at all. It is driven here through the *glyph* path rather than the
 * Compose text fallback, because a `TextMeasurer` needs a font resolver and a real font, while a
 * fake glyph range is a handful of bytes -- and the glyph path is the one that is meant to run.
 *
 * In `skiaTest` because rasterizing a label allocates an `ImageBitmap`.
 */
class SymbolLayerPainterTest {

    private companion object {
        const val CANVAS = 512
        const val EXTENT = 4096
        const val ADVANCE = 12
        val DENSITY = Density(1f)
        val STACK = listOf("Test Regular")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** A range where every printable ASCII codepoint is a 10x12 block advancing half an em. */
    private fun glyphRange(): ByteArray = GlyphPbfFixtures.glyphsFile(
        GlyphPbfFixtures.fontStack(
            name = STACK.single(),
            range = "0-255",
            glyphs = (33..126).map { code ->
                GlyphPbfFixtures.glyph(
                    id = code, width = 10, height = 12, left = 1, top = 12, advance = ADVANCE,
                    bitmap = GlyphPbfFixtures.solidBitmap(10, 12),
                )
            } + GlyphPbfFixtures.glyph(id = 32, width = 0, height = 0, left = 0, top = 0, advance = ADVANCE),
        )
    )

    private fun glyphManager(): GlyphManager {
        val bytes = glyphRange()
        return GlyphManager(
            urlTemplate = "test://{fontstack}/{range}.pbf",
            loadResource = { _: String -> Buffer().apply { write(bytes) } as RawSource },
        )
    }

    private fun spriteManager(width: Int = 16, height: Int = 16, pixelRatio: Float = 1f): SpriteManager {
        val sheet = renderToBitmap(size = maxOf(width, height)) { drawRect(color = Color.Red) }
        return SpriteManager(
            spriteIndex = mapOf(
                "marker" to Sprite(
                    width = width, height = height, x = 0, y = 0, pixelRatio = pixelRatio,
                )
            ),
            spriteImage = sheet,
        )
    }

    private fun painter(sprites: SpriteManager? = spriteManager()): SymbolLayerPainter =
        SymbolLayerPainter(
            textMeasurerState = MutableStateFlow<TextMeasurer?>(null),
            spriteManager = sprites,
            configuration = MapLibreConfiguration(
                style = MapLibreStyle(),
                tileSources = emptyMap(),
                spriteManager = sprites,
                glyphManager = glyphManager(),
            ),
            pathCache = LruCache(64),
            mutex = Mutex(),
        )

    private fun layer(layoutJson: String, paintJson: String = "{}"): SymbolLayer = SymbolLayer(
        id = "test",
        sourceLayer = "test",
        layout = json.decodeFromString(SymbolLayout.serializer(), layoutJson),
        paint = json.decodeFromString(SymbolPaint.serializer(), paintJson),
    )

    /** A point at the middle of the tile. */
    private fun point(vararg points: Pair<Int, Int>): Tile.Feature =
        Mvt.pointFeature(*(if (points.isEmpty()) arrayOf(EXTENT / 2 to EXTENT / 2) else points))

    private suspend fun produce(
        layer: SymbolLayer,
        feature: Tile.Feature = point(),
        properties: Map<String, Any?> = emptyMap(),
    ): List<Symbol> = painter().produceSymbol(
        feature = feature,
        style = layer,
        canvasSize = CANVAS,
        extent = EXTENT,
        zoom = 10.0,
        featureProperties = EvalFeature(
            type = if (feature.type == Tile.GeomType.POINT) "Point" else "LineString",
            properties = properties,
        ),
        actualZoom = 10.0,
        id = "f1",
        density = DENSITY,
        layerIndex = 0,
    )

    // region text-field

    @Test
    fun `a literal text-field is rendered as written`() {
        runTest {
            // It used to be replaced wholesale by the feature's `name` whenever it had no braces.
            val symbols = produce(
                layer("""{"text-field":"Peak","text-font":["Test Regular"]}"""),
                properties = mapOf("name" to "Something Else"),
            )
            val text = assertNotNull(symbols.filterIsInstance<Symbol.Text>().singleOrNull())
            // Four glyphs at half an em of a 16 px em.
            assertEquals(4 * ADVANCE * 16f / 24f, text.value.width)
        }
    }

    @Test
    fun `a token text-field is still expanded`() {
        runTest {
            val symbols = produce(
                layer("""{"text-field":"{name}","text-font":["Test Regular"]}"""),
                properties = mapOf("name" to "abcdef"),
            )
            val text = assertNotNull(symbols.filterIsInstance<Symbol.Text>().singleOrNull())
            assertEquals(6 * ADVANCE * 16f / 24f, text.value.width)
        }
    }

    @Test
    fun `a get expression drives the label`() {
        runTest {
            val symbols = produce(
                layer("""{"text-field":["get","label"],"text-font":["Test Regular"]}"""),
                properties = mapOf("label" to "ab"),
            )
            assertEquals(1, symbols.filterIsInstance<Symbol.Text>().size)
        }
    }

    @Test
    fun `text-transform is applied before shaping`() {
        runTest {
            // Every glyph is the same size in the fake font, so the transform is observed by the
            // label still rendering rather than by its width.
            val symbols = produce(
                layer(
                    """{"text-field":"ab","text-font":["Test Regular"],"text-transform":"uppercase"}"""
                )
            )
            assertEquals(1, symbols.filterIsInstance<Symbol.Text>().size)
        }
    }

    @Test
    fun `text-letter-spacing widens the label`() {
        runTest {
            val plain = produce(layer("""{"text-field":"abc","text-font":["Test Regular"]}"""))
            val spaced = produce(
                layer("""{"text-field":"abc","text-font":["Test Regular"],"text-letter-spacing":0.5}""")
            )
            val plainWidth = plain.filterIsInstance<Symbol.Text>().single().value.width
            val spacedWidth = spaced.filterIsInstance<Symbol.Text>().single().value.width
            assertTrue(spacedWidth > plainWidth, "expected $spacedWidth > $plainWidth")
        }
    }

    @Test
    fun `text-max-width wraps the label`() {
        runTest {
            val wide = produce(layer("""{"text-field":"aaa bbb","text-font":["Test Regular"]}"""))
            val wrapped = produce(
                layer("""{"text-field":"aaa bbb","text-font":["Test Regular"],"text-max-width":2}""")
            )
            val wideArt = wide.filterIsInstance<Symbol.Text>().single().value
            val wrappedArt = wrapped.filterIsInstance<Symbol.Text>().single().value
            assertTrue(wrappedArt.width < wideArt.width)
            assertTrue(wrappedArt.height > wideArt.height)
        }
    }

    @Test
    fun `a zero text-opacity drops the label`() {
        runTest {
            val symbols = produce(
                layer("""{"text-field":"ab","text-font":["Test Regular"]}""", """{"text-opacity":0}""")
            )
            assertTrue(symbols.isEmpty())
        }
    }

    // endregion

    // region anchors

    private suspend fun textCenterFor(anchor: String): Pair<Float, Float> {
        val symbols = produce(
            layer("""{"text-field":"abcd","text-font":["Test Regular"],"text-anchor":"$anchor"}""")
        )
        val placement = symbols.filterIsInstance<Symbol.Text>().single().placement.textPlacement!!
        return placement.position.x to placement.position.y
    }

    @Test
    fun `text-anchor top hangs the label below the point`() {
        runTest {
            val center = CANVAS / 2f
            val (x, y) = textCenterFor("top")
            assertEquals(center, x)
            assertTrue(y > center, "top must put the box below the point, but centre y was $y")
        }
    }

    @Test
    fun `text-anchor bottom puts the label above the point`() {
        runTest {
            val center = CANVAS / 2f
            val (_, y) = textCenterFor("bottom")
            assertTrue(y < center, "bottom must put the box above the point, but centre y was $y")
        }
    }

    @Test
    fun `text-anchor left puts the label to the right of the point`() {
        runTest {
            val center = CANVAS / 2f
            val (x, _) = textCenterFor("left")
            assertTrue(x > center, "left must put the box right of the point, but centre x was $x")
        }
    }

    @Test
    fun `text-anchor center leaves the label on the point`() {
        runTest {
            val center = CANVAS / 2f
            val (x, y) = textCenterFor("center")
            assertEquals(center, x)
            assertEquals(center, y)
        }
    }

    @Test
    fun `an absent text-anchor is center`() {
        runTest {
            assertEquals(textCenterFor("center"), run {
                val symbols = produce(layer("""{"text-field":"abcd","text-font":["Test Regular"]}"""))
                val p = symbols.filterIsInstance<Symbol.Text>().single().placement.textPlacement!!
                p.position.x to p.position.y
            })
        }
    }

    @Test
    fun `text-offset moves the label in ems`() {
        runTest {
            val symbols = produce(
                layer("""{"text-field":"ab","text-font":["Test Regular"],"text-offset":[1,0]}""")
            )
            val placement = symbols.filterIsInstance<Symbol.Text>().single().placement.textPlacement!!
            // One em at the default 16 px text-size.
            assertEquals(CANVAS / 2f + 16f, placement.position.x)
        }
    }

    // endregion

    // region icons

    @Test
    fun `icon-padding widens the collision box but not the icon`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker","icon-padding":8}"""))
            val sprite = assertNotNull(symbols.filterIsInstance<Symbol.Sprite>().singleOrNull())
            // The icon is drawn at its own size...
            assertEquals(16, sprite.drawSize.width)
            assertEquals(16, sprite.drawSize.height)
            // ...while the box it reserves is padded on every side.
            assertEquals(16f + 16f, sprite.placement.spritePlacement.bounds.width)
        }
    }

    @Test
    fun `icon-size scales the icon`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker","icon-size":2}"""))
            assertEquals(32, symbols.filterIsInstance<Symbol.Sprite>().single().drawSize.width)
        }
    }

    @Test
    fun `a hidpi sheet draws its icons at their layout size`() {
        runTest {
            // The same icon on an @2x sheet: 32 sheet pixels, still 16 layout pixels. Without the
            // pixelRatio division every icon of such a sheet was drawn at double size.
            val sprites = spriteManager(width = 32, height = 32, pixelRatio = 2f)
            val symbols = painter(sprites).produceSymbol(
                feature = point(),
                style = layer("""{"icon-image":"marker"}"""),
                canvasSize = CANVAS,
                extent = EXTENT,
                zoom = 10.0,
                featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
                actualZoom = 10.0,
                id = "f1",
                density = DENSITY,
            )
            assertEquals(16, symbols.filterIsInstance<Symbol.Sprite>().single().drawSize.width)
        }
    }

    @Test
    fun `icon-opacity is carried to the draw rather than only culling`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker"}"""), properties = emptyMap())
            assertEquals(1f, symbols.filterIsInstance<Symbol.Sprite>().single().opacity)

            val faded = painter().produceSymbol(
                feature = point(),
                style = layer("""{"icon-image":"marker"}""", """{"icon-opacity":0.5}"""),
                canvasSize = CANVAS, extent = EXTENT, zoom = 10.0,
                featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
                actualZoom = 10.0, id = "f1", density = DENSITY,
            )
            assertEquals(0.5f, faded.filterIsInstance<Symbol.Sprite>().single().opacity)
        }
    }

    @Test
    fun `a zero icon-opacity drops the icon`() {
        runTest {
            val symbols = painter().produceSymbol(
                feature = point(),
                style = layer("""{"icon-image":"marker"}""", """{"icon-opacity":0}"""),
                canvasSize = CANVAS, extent = EXTENT, zoom = 10.0,
                featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
                actualZoom = 10.0, id = "f1", density = DENSITY,
            )
            assertTrue(symbols.isEmpty())
        }
    }

    @Test
    fun `icon-anchor top hangs the icon below the point`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker","icon-anchor":"top"}"""))
            val sprite = symbols.filterIsInstance<Symbol.Sprite>().single()
            assertTrue(sprite.placement.spritePlacement.position.y > CANVAS / 2f)
        }
    }

    @Test
    fun `icon-offset moves the icon`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker","icon-offset":[5,-3]}"""))
            val position = symbols.filterIsInstance<Symbol.Sprite>().single().placement.spritePlacement.position
            assertEquals(CANVAS / 2f + 5f, position.x)
            assertEquals(CANVAS / 2f - 3f, position.y)
        }
    }

    @Test
    fun `icon-translate moves the icon`() {
        runTest {
            val symbols = painter().produceSymbol(
                feature = point(),
                style = layer("""{"icon-image":"marker"}""", """{"icon-translate":[4,6]}"""),
                canvasSize = CANVAS, extent = EXTENT, zoom = 10.0,
                featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
                actualZoom = 10.0, id = "f1", density = DENSITY,
            )
            val position = symbols.filterIsInstance<Symbol.Sprite>().single().placement.spritePlacement.position
            assertEquals(CANVAS / 2f + 4f, position.x)
            assertEquals(CANVAS / 2f + 6f, position.y)
        }
    }

    @Test
    fun `an unknown icon-image produces nothing`() {
        runTest {
            assertTrue(produce(layer("""{"icon-image":"missing"}""")).isEmpty())
        }
    }

    @Test
    fun `icon-text-fit stretches the icon around the label`() {
        runTest {
            val plain = produce(
                layer("""{"icon-image":"marker","text-field":"aaaaaa","text-font":["Test Regular"]}""")
            ).filterIsInstance<Symbol.SpriteWithText>().single()
            val fitted = produce(
                layer(
                    """{"icon-image":"marker","text-field":"aaaaaa","text-font":["Test Regular"],""" +
                        """"icon-text-fit":"width"}"""
                )
            ).filterIsInstance<Symbol.SpriteWithText>().single()

            assertEquals(false, plain.textInsideIcon)
            assertTrue(fitted.textInsideIcon)
            assertTrue(
                fitted.spriteSize.width > plain.spriteSize.width,
                "the icon must grow to hold the label",
            )
            assertEquals(plain.spriteSize.height, fitted.spriteSize.height)
        }
    }

    // endregion

    // region placement

    @Test
    fun `a multipoint feature yields one symbol per point`() {
        runTest {
            val symbols = produce(
                layer("""{"icon-image":"marker"}"""),
                feature = point(1000 to 1000, 2000 to 2000, 3000 to 3000),
            )
            assertEquals(3, symbols.filterIsInstance<Symbol.Sprite>().size)
            assertEquals(3, symbols.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `symbol-placement line labels a line feature`() {
        runTest {
            val line = Mvt.lineFeature(listOf(0 to 2048, 4096 to 2048))
            val symbols = produce(
                layer("""{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line"}"""),
                feature = line,
            )
            assertTrue(symbols.filterIsInstance<Symbol.Text>().isNotEmpty())
        }
    }

    @Test
    fun `symbol-placement line-center places exactly one label`() {
        runTest {
            val line = Mvt.lineFeature(listOf(0 to 2048, 4096 to 2048))
            val spaced = produce(
                layer(
                    """{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line",""" +
                        """"symbol-spacing":80}"""
                ),
                feature = line,
            )
            val centered = produce(
                layer("""{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line-center"}"""),
                feature = line,
            )
            // `line-center` used to decode nothing at all, so the feature was dropped.
            assertEquals(1, centered.filterIsInstance<Symbol.Text>().size)
            assertTrue(spaced.filterIsInstance<Symbol.Text>().size > 1)
        }
    }

    @Test
    fun `symbol-spacing repeats a label along a line`() {
        runTest {
            val line = Mvt.lineFeature(listOf(0 to 2048, 4096 to 2048))
            val wide = produce(
                layer(
                    """{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line",""" +
                        """"symbol-spacing":400}"""
                ),
                feature = line,
            ).filterIsInstance<Symbol.Text>().size
            val tight = produce(
                layer(
                    """{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line",""" +
                        """"symbol-spacing":60}"""
                ),
                feature = line,
            ).filterIsInstance<Symbol.Text>().size
            // Only one label per feature used to be emitted, whatever the spacing.
            assertTrue(tight > wide, "expected more labels at a tighter spacing, got $tight vs $wide")
        }
    }

    @Test
    fun `symbol-placement line labels a polygon ring`() {
        runTest {
            val polygon = Mvt.polygonFeature(Mvt.clockwiseRing(200, 200, 3800, 3800))
            val symbols = produce(
                layer("""{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line"}"""),
                feature = polygon,
            )
            assertTrue(symbols.filterIsInstance<Symbol.Text>().isNotEmpty())
        }
    }

    @Test
    fun `symbol-avoid-edges drops a label that crosses the tile edge`() {
        runTest {
            val atEdge = point(20 to EXTENT / 2)
            val kept = produce(
                layer("""{"text-field":"abcdef","text-font":["Test Regular"]}"""),
                feature = atEdge,
            )
            val dropped = produce(
                layer("""{"text-field":"abcdef","text-font":["Test Regular"],"symbol-avoid-edges":true}"""),
                feature = atEdge,
            )
            assertTrue(kept.isNotEmpty())
            assertTrue(dropped.isEmpty())
        }
    }

    @Test
    fun `symbol-sort-key becomes the placement priority`() {
        runTest {
            val symbols = produce(
                layer("""{"text-field":"ab","text-font":["Test Regular"],"symbol-sort-key":7}""")
            )
            val placement = symbols.filterIsInstance<Symbol.Text>().single().placement.spritePlacement
            assertEquals(7.0, placement.inLayerPriority)
            assertTrue(placement.hasSortKey)
        }
    }

    @Test
    fun `a layer without a sort key says so`() {
        runTest {
            val symbols = produce(layer("""{"text-field":"ab","text-font":["Test Regular"]}"""))
            assertEquals(false, symbols.first().placement.spritePlacement.hasSortKey)
        }
    }

    @Test
    fun `symbol-z-order reaches the placement`() {
        runTest {
            val symbols = produce(
                layer("""{"text-field":"ab","text-font":["Test Regular"],"symbol-z-order":"viewport-y"}""")
            )
            assertEquals("viewport-y", symbols.first().placement.spritePlacement.zOrder)
        }
    }

    @Test
    fun `a feature with both an icon and a label produces one combined symbol`() {
        runTest {
            val symbols = produce(
                layer("""{"icon-image":"marker","text-field":"ab","text-font":["Test Regular"]}""")
            )
            assertEquals(1, symbols.size)
            assertTrue(symbols.single() is Symbol.SpriteWithText)
        }
    }

    @Test
    fun `text-variable-anchor produces one candidate per anchor`() {
        runTest {
            val symbols = produce(
                layer(
                    """{"icon-image":"marker","text-field":"ab","text-font":["Test Regular"],""" +
                        """"text-variable-anchor":["top","bottom","left"]}"""
                )
            )
            val combined = symbols.filterIsInstance<Symbol.SpriteWithText>().single()
            assertEquals(3, combined.textCandidates.size)
            assertContentEquals(
                listOf(true, true, false),
                combined.textCandidates.map { it.dy != 0f },
            )
        }
    }

    @Test
    fun `a label whose glyphs the server lacks is dropped rather than crashing`() {
        runTest {
            // Nothing in the fake range covers CJK.
            val symbols = produce(layer("""{"text-field":"中文","text-font":["Test Regular"]}"""))
            assertTrue(symbols.isEmpty())
        }
    }

    // endregion
}
