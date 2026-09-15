package ovh.plrapps.mapcompose.vector.symbol

import ovh.plrapps.mapcompose.vector.renderer.LabelArt
import ovh.plrapps.mapcompose.vector.renderer.Mvt
import ovh.plrapps.mapcompose.vector.renderer.assertColorEquals
import ovh.plrapps.mapcompose.vector.renderer.pixelAt
import ovh.plrapps.mapcompose.vector.renderer.renderToBitmap
import ovh.plrapps.mapcompose.vector.ui.symbols.scaledAlign
import ovh.plrapps.mapcompose.vector.ui.symbols.scaledSize

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolPaint
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlinx.serialization.json.Json

/**
 * What `SymbolLayerLayout` produces for a feature: which symbols, how big, and where.
 *
 * The painter had no direct test at all. It is driven here through the *glyph* path rather than the
 * Compose text fallback, because a `TextMeasurer` needs a font resolver and a real font, while a
 * fake glyph range is a handful of bytes -- and the glyph path is the one that is meant to run.
 *
 * In `skiaTest` because rasterizing a label allocates an `ImageBitmap`.
 */
class SymbolLayerLayoutTest {

    private companion object {
        const val CANVAS = 512
        const val EXTENT = 4096
        const val ADVANCE = 12
        /**
         * A font's ascent in glyph units: how far a glyph's pen sits above the baseline.
         *
         * `Glyph.top` is negative-upward from the pen, so a real font stack has
         * `top = height - ascent` -- the value decoded from any published range. A fixture with
         * `top = height` has the opposite sign and hides a whole line of vertical error.
         */
        const val ASCENT = 22
        val DENSITY = Density(1f)
        val STACK = listOf("Test Regular")

        /**
         * Size data for a layer whose sizes are constants, which is every fixture that does not
         * build its own. Only the placement pass reads it, so what it holds cannot move a symbol
         * these tests assert the position of.
         */
        val SIZES = SymbolSizes(
            tileZoom = 9.0,
            textSizeData = SizeData.Constant(StyleSpecDefaults.TEXT_SIZE),
            iconSizeData = SizeData.Constant(StyleSpecDefaults.ICON_SIZE),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A range where every printable ASCII codepoint is a 10x12 block advancing half an em.
     *
     * [extra] adds codepoints outside it -- a CJK ideograph, say. The fixture is served for every
     * `{range}` the manager asks for and its glyphs are taken whatever range it declares, so one
     * file covers them all.
     */
    private fun glyphRange(extra: Iterable<Int> = emptyList()): ByteArray = GlyphPbfFixtures.glyphsFile(
        GlyphPbfFixtures.fontStack(
            name = STACK.single(),
            range = "0-255",
            glyphs = ((33..126) + extra).map { code ->
                GlyphPbfFixtures.glyph(
                    id = code, width = 10, height = 12, left = 1, top = 12 - ASCENT, advance = ADVANCE,
                    bitmap = GlyphPbfFixtures.solidBitmap(10, 12),
                )
            } + GlyphPbfFixtures.glyph(id = 32, width = 0, height = 0, left = 0, top = 0, advance = ADVANCE),
        )
    )

    private fun glyphManager(extra: Iterable<Int> = emptyList()): GlyphManager {
        val bytes = glyphRange(extra)
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

    private fun painter(
        sprites: SpriteManager? = spriteManager(),
        glyphs: GlyphManager = glyphManager(),
    ): SymbolLayerLayout =
        SymbolLayerLayout(
            textMeasurerState = MutableStateFlow<TextMeasurer?>(null),
            spriteManager = sprites,
            configuration = MapLibreConfiguration(
                style = MapLibreStyle(),
                tileSources = emptyMap(),
                spriteManager = sprites,
                glyphManager = glyphs,
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
        compareText: MutableMap<String, MutableList<Pair<Float, Float>>>? = null,
        sprites: SpriteManager? = spriteManager(),
        glyphs: GlyphManager = glyphManager(),
    ): List<SymbolInstance> = painter(sprites, glyphs).produceSymbol(
        feature = feature,
        style = layer,
        canvasSize = CANVAS,
        extent = EXTENT,
        tileZ = 10.0,
        featureProperties = EvalFeature(
            type = if (feature.type == Tile.GeomType.POINT) "Point" else "LineString",
            properties = properties,
        ),
        actualZoom = 10.0,
        id = "f1",
        density = DENSITY,
        layerIndex = 0,
        sizes = symbolSizesFor(layer, 10.0),
        compareText = compareText,
    )

    @Test
    fun `a layer that declares no paint still lays out its symbols`() = runTest {
        // Regression: `style.paint ?: return null` made an omitted `paint` object suppress the
        // whole layer, where upstream always populates one from the spec (`style_layer.ts`), so
        // omitting it and writing `"paint": {}` are the same thing.
        val noPaint = SymbolLayer(
            id = "test",
            sourceLayer = "test",
            layout = json.decodeFromString(
                SymbolLayout.serializer(),
                """{"text-field":"A","text-font":["Test Regular"],"text-size":16}""",
            ),
        )

        val symbols = produce(noPaint)

        assertNotNull(
            symbols.filterIsInstance<SymbolInstance.Text>().firstOrNull(),
            "a symbol layer with no paint block still produces its label",
        )
    }

    @Test
    fun `an overzoomed bucket positions its symbols from the ancestor tile`() = runTest {
        /* Upstream lays a symbol layer out over the *canonical* tile and magnifies it
         * (`reparseOverscaled`), so an overzoomed bucket is addressed by the ancestor: its x/y/z and
         * its on-screen size, which is one map tile's size times the span. The layer is still
         * evaluated at the display zoom -- that is `actualZoom` here, and it is deliberately not the
         * ancestor's. */
        val span = 4
        val symbols = painter(sprites = null).produceSymbol(
            feature = point(),
            style = layer("""{"text-field":"A","text-font":["Test Regular"],"text-size":16}"""),
            canvasSize = CANVAS * span,
            extent = EXTENT,
            tileZ = 14.0,
            featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
            actualZoom = 16.0,
            id = "f1",
            tileX = 3,
            tileY = 5,
            density = DENSITY,
            layerIndex = 0,
            sizes = SIZES,
        )

        val symbol = symbols.single()
        val worldTiles = 1 shl 14
        assertEquals(3.5 / worldTiles, symbol.global.x, 1e-9, "the ancestor's centre column")
        assertEquals(5.5 / worldTiles, symbol.global.y, 1e-9, "the ancestor's centre row")
    }

    @Test
    fun `a line label is drawn centred on the line it follows`() {
        runTest {
            // The end-to-end check for the pen origin. The box lands on the line, and the ink has to
            // land on the box -- if the shaper puts the ink below its box, a rotated street name
            // ends up beside the road instead of on it.
            val line = Mvt.lineFeature(listOf(0 to EXTENT / 2, EXTENT to EXTENT / 2))
            val symbols = produce(
                layer("""{"text-field":"Street","text-font":["Test Regular"],"symbol-placement":"line"}"""),
                feature = line,
            )

            val text = assertNotNull(symbols.filterIsInstance<SymbolInstance.Text>().firstOrNull())
            assertEquals(
                CANVAS / 2f,
                text.placement.textPlacement!!.position.y,
                "the label's box sits on the line",
            )

            val art = text.value as LabelArt.Glyphs
            val pixels = art.rendered.bitmap.toPixelMap()
            var top = -1
            var bottom = -1
            for (y in 0 until pixels.height) {
                val painted = (0 until pixels.width).any { pixels[it, y].alpha > 0.5f }
                if (painted) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            assertTrue(top >= 0, "the label drew nothing")

            val inkCentre = (top + bottom) / 2f - art.rendered.boxTop
            val boxCentre = art.rendered.boxHeight / 2f
            assertTrue(
                abs(inkCentre - boxCentre) < 0.2f * 16f,
                "ink centre $inkCentre should sit within a fifth of an em of the box centre $boxCentre"
            )
        }
    }

    // region text-field

    @Test
    fun `a literal text-field is rendered as written`() {
        runTest {
            // It used to be replaced wholesale by the feature's `name` whenever it had no braces.
            val symbols = produce(
                layer("""{"text-field":"Peak","text-font":["Test Regular"]}"""),
                properties = mapOf("name" to "Something Else"),
            )
            val text = assertNotNull(symbols.filterIsInstance<SymbolInstance.Text>().singleOrNull())
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
            val text = assertNotNull(symbols.filterIsInstance<SymbolInstance.Text>().singleOrNull())
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
            assertEquals(1, symbols.filterIsInstance<SymbolInstance.Text>().size)
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
            assertEquals(1, symbols.filterIsInstance<SymbolInstance.Text>().size)
        }
    }

    @Test
    fun `text-letter-spacing widens the label`() {
        runTest {
            val plain = produce(layer("""{"text-field":"abc","text-font":["Test Regular"]}"""))
            val spaced = produce(
                layer("""{"text-field":"abc","text-font":["Test Regular"],"text-letter-spacing":0.5}""")
            )
            val plainWidth = plain.filterIsInstance<SymbolInstance.Text>().single().value.width
            val spacedWidth = spaced.filterIsInstance<SymbolInstance.Text>().single().value.width
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
            val wideArt = wide.filterIsInstance<SymbolInstance.Text>().single().value
            val wrappedArt = wrapped.filterIsInstance<SymbolInstance.Text>().single().value
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
        val placement = symbols.filterIsInstance<SymbolInstance.Text>().single().placement.textPlacement!!
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
                val p = symbols.filterIsInstance<SymbolInstance.Text>().single().placement.textPlacement!!
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
            val placement = symbols.filterIsInstance<SymbolInstance.Text>().single().placement.textPlacement!!
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
            val sprite = assertNotNull(symbols.filterIsInstance<SymbolInstance.Sprite>().singleOrNull())
            // The icon is drawn at its own size...
            assertEquals(16, sprite.drawSize.width)
            assertEquals(16, sprite.drawSize.height)
            // ...while the box it reserves is padded on every side.
            assertEquals(16f + 16f, sprite.placement.spritePlacement.bounds.width)
        }
    }

    @Test
    fun `icon-color does not recolour a plain icon`() {
        runTest {
            // `icon-color` is SDF-only. Upstream's ordinary-icon program is
            // `fragColor = texture(u_texture, v_tex) * alpha` (`symbol_icon.fragment.glsl`) -- no
            // colour uniform -- so a multicolour PNG icon keeps its own texels. This used to tint
            // the cut-out with a `SrcIn` fill, turning every such icon into a flat silhouette.
            val symbols = produce(layer("""{"icon-image":"marker"}""", """{"icon-color":"#00ff00"}"""))
            val sprite = assertNotNull(symbols.filterIsInstance<SymbolInstance.Sprite>().singleOrNull())
            assertColorEquals(Color.Red, sprite.value.pixelAt(4, 4))
        }
    }

    @Test
    fun `icon-size scales the icon`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker","icon-size":2}"""))
            assertEquals(32, symbols.filterIsInstance<SymbolInstance.Sprite>().single().drawSize.width)
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
                tileZ = 10.0,
                featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
                actualZoom = 10.0,
                id = "f1",
                density = DENSITY,
                sizes = SIZES,
            )
            assertEquals(16, symbols.filterIsInstance<SymbolInstance.Sprite>().single().drawSize.width)
        }
    }

    @Test
    fun `icon-opacity is carried to the draw rather than only culling`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker"}"""), properties = emptyMap())
            assertEquals(1f, symbols.filterIsInstance<SymbolInstance.Sprite>().single().opacity)

            val faded = painter().produceSymbol(
                feature = point(),
                style = layer("""{"icon-image":"marker"}""", """{"icon-opacity":0.5}"""),
                canvasSize = CANVAS, extent = EXTENT, tileZ = 10.0,
                featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
                actualZoom = 10.0, id = "f1", density = DENSITY,
                sizes = SIZES,
            )
            assertEquals(0.5f, faded.filterIsInstance<SymbolInstance.Sprite>().single().opacity)
        }
    }

    @Test
    fun `a zero icon-opacity drops the icon`() {
        runTest {
            val symbols = painter().produceSymbol(
                feature = point(),
                style = layer("""{"icon-image":"marker"}""", """{"icon-opacity":0}"""),
                canvasSize = CANVAS, extent = EXTENT, tileZ = 10.0,
                featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
                actualZoom = 10.0, id = "f1", density = DENSITY,
                sizes = SIZES,
            )
            assertTrue(symbols.isEmpty())
        }
    }

    @Test
    fun `icon-anchor top hangs the icon below the point`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker","icon-anchor":"top"}"""))
            val sprite = symbols.filterIsInstance<SymbolInstance.Sprite>().single()
            assertTrue(sprite.placement.spritePlacement.position.y > CANVAS / 2f)
        }
    }

    @Test
    fun `icon-offset moves the icon`() {
        runTest {
            val symbols = produce(layer("""{"icon-image":"marker","icon-offset":[5,-3]}"""))
            val position = symbols.filterIsInstance<SymbolInstance.Sprite>().single().placement.spritePlacement.position
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
                canvasSize = CANVAS, extent = EXTENT, tileZ = 10.0,
                featureProperties = EvalFeature(type = "Point", properties = emptyMap()),
                actualZoom = 10.0, id = "f1", density = DENSITY,
                sizes = SIZES,
            )
            val position = symbols.filterIsInstance<SymbolInstance.Sprite>().single().placement.spritePlacement.position
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
            ).filterIsInstance<SymbolInstance.SpriteWithText>().single()
            val fitted = produce(
                layer(
                    """{"icon-image":"marker","text-field":"aaaaaa","text-font":["Test Regular"],""" +
                        """"icon-text-fit":"width"}"""
                )
            ).filterIsInstance<SymbolInstance.SpriteWithText>().single()

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
            assertEquals(3, symbols.filterIsInstance<SymbolInstance.Sprite>().size)
            assertEquals(3, symbols.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `a repeat of the same label within half a symbol-spacing is dropped`() {
        runTest {
            // Upstream's `anchorIsTooClose`, bucket-scoped: the same map is shared by every feature
            // of one style layer in one tile, so a road split into two MVT features does not carry
            // its name twice over the same stretch.
            val style = layer(
                """{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line",""" +
                    """"symbol-spacing":4000}"""
            )
            val shared = mutableMapOf<String, MutableList<Pair<Float, Float>>>()
            val first = produce(
                style,
                feature = Mvt.lineFeature(listOf(200 to 2048, 3900 to 2048)),
                compareText = shared,
            )
            val second = produce(
                style,
                feature = Mvt.lineFeature(listOf(200 to 2100, 3900 to 2100)),
                compareText = shared,
            )
            assertTrue(first.filterIsInstance<SymbolInstance.Text>().isNotEmpty())
            assertTrue(
                second.filterIsInstance<SymbolInstance.Text>().isEmpty(),
                "the second copy of the label should have been suppressed",
            )
        }
    }

    @Test
    fun `a line reaching past the tile is clipped before it is labelled`() {
        runTest {
            // The MVT buffer carries a road beyond the tile; an anchor out there belongs to the
            // neighbour, which lays it out itself.
            val symbols = produce(
                layer(
                    """{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line",""" +
                        """"symbol-spacing":60}"""
                ),
                feature = Mvt.lineFeature(listOf(-2000 to 2048, 6000 to 2048)),
            )
            val texts = symbols.filterIsInstance<SymbolInstance.Text>()
            assertTrue(texts.isNotEmpty())
            assertTrue(
                texts.all { it.placement.spritePlacement.position.x in 0f..CANVAS.toFloat() },
                "an anchor landed outside the tile",
            )
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
            assertTrue(symbols.filterIsInstance<SymbolInstance.Text>().isNotEmpty())
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
            assertEquals(1, centered.filterIsInstance<SymbolInstance.Text>().size)
            assertTrue(spaced.filterIsInstance<SymbolInstance.Text>().size > 1)
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
            ).filterIsInstance<SymbolInstance.Text>().size
            val tight = produce(
                layer(
                    """{"text-field":"ab","text-font":["Test Regular"],"symbol-placement":"line",""" +
                        """"symbol-spacing":60}"""
                ),
                feature = line,
            ).filterIsInstance<SymbolInstance.Text>().size
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
            assertTrue(symbols.filterIsInstance<SymbolInstance.Text>().isNotEmpty())
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
            val placement = symbols.filterIsInstance<SymbolInstance.Text>().single().placement.spritePlacement
            assertEquals(7.0, placement.inLayerPriority)
        }
    }

    @Test
    fun `a constant sort key does not order the layer`() {
        /* Upstream's `!sortKey.isConstant()`: a literal is the same for every feature, so it says
         * nothing about their order, and `symbol-z-order: auto` falls through to y. */
        val constant = layer("""{"text-field":"ab","text-font":["Test Regular"],"symbol-sort-key":7}""")
        val dataDriven = layer(
            """{"text-field":"ab","text-font":["Test Regular"],"symbol-sort-key":["get","rank"]}"""
        )

        assertEquals(false, symbolOrderingFor(constant, zoom = 6.0).hasSortKey)
        assertTrue(symbolOrderingFor(dataDriven, zoom = 6.0).sortFeaturesByKey)
    }

    @Test
    fun `a layer without a sort key says so`() {
        assertEquals(
            false,
            symbolOrderingFor(layer("""{"text-field":"ab","text-font":["Test Regular"]}"""), zoom = 6.0)
                .hasSortKey,
        )
    }

    @Test
    fun `symbol-z-order reaches the bucket's ordering`() {
        val ordering = symbolOrderingFor(
            layer("""{"text-field":"ab","text-font":["Test Regular"],"symbol-z-order":"viewport-y"}"""),
            zoom = 6.0,
        )
        assertEquals("viewport-y", ordering.zOrder)
        assertTrue(ordering.placeByViewportY)
        assertEquals(false, ordering.sortFeaturesByY, "the draw order needs `canOverlap` too")
    }

    @Test
    fun `canOverlap follows text-overlap and icon-allow-overlap and the ignore-placement pair`() {
        fun ordering(layout: String) = symbolOrderingFor(layer(layout), zoom = 6.0)

        assertEquals(false, ordering("""{"text-field":"ab","text-font":["Test Regular"]}""").canOverlap)
        assertTrue(ordering("""{"text-field":"ab","text-font":["Test Regular"],"text-overlap":"always"}""").canOverlap)
        assertTrue(ordering("""{"text-field":"ab","text-font":["Test Regular"],"icon-allow-overlap":true}""").canOverlap)
        assertTrue(
            ordering("""{"text-field":"ab","text-font":["Test Regular"],"text-ignore-placement":true}""").canOverlap
        )
    }

    @Test
    fun `symbol-z-order auto orders by y only where the layer allows overlap`() {
        val plain = symbolOrderingFor(layer("""{"text-field":"ab","text-font":["Test Regular"]}"""), zoom = 6.0)
        val overlapping = symbolOrderingFor(
            layer("""{"text-field":"ab","text-font":["Test Regular"],"text-allow-overlap":true}"""),
            zoom = 6.0,
        )

        assertTrue(plain.zOrderByViewportY, "auto with no data-driven sort key")
        assertEquals(false, plain.sortFeaturesByY)
        assertTrue(overlapping.sortFeaturesByY)
    }

    @Test
    fun `a feature with both an icon and a label produces one combined symbol`() {
        runTest {
            val symbols = produce(
                layer("""{"icon-image":"marker","text-field":"ab","text-font":["Test Regular"]}""")
            )
            assertEquals(1, symbols.size)
            assertTrue(symbols.single() is SymbolInstance.SpriteWithText)
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
            val combined = symbols.filterIsInstance<SymbolInstance.SpriteWithText>().single()
            assertEquals(3, combined.textCandidates.size)
            assertContentEquals(
                listOf(true, true, false),
                combined.textCandidates.map { it.dy != 0f },
            )
        }
    }

    @Test
    fun `the icon's size does not enter the label's offset`() {
        runTest {
            /* Upstream's `symbol_layout.ts` anchors the icon and the label at the *same* point: the
             * label is moved by `text-anchor` and `text-offset` alone, never by how big the icon
             * beside it is. This port used to add half the icon's height on top, which hung every
             * label about a line too low. */
            val style = layer(
                """{"icon-image":"marker","text-field":"ab","text-font":["Test Regular"],""" +
                    """"text-anchor":"top"}"""
            )
            val small = produce(style, sprites = spriteManager(width = 16, height = 16))
                .filterIsInstance<SymbolInstance.SpriteWithText>().single()
            val large = produce(style, sprites = spriteManager(width = 64, height = 64))
                .filterIsInstance<SymbolInstance.SpriteWithText>().single()

            assertTrue(large.spriteSize.height > small.spriteSize.height)
            // A `top` anchor lands the box's top edge on the point, so its centre is half a box down.
            assertEquals(small.textSize.height / 2f, small.textOffset.y)
            assertEquals(small.textOffset, large.textOffset)
        }
    }

    @Test
    fun `the default text-anchor draws the label on the icon`() {
        runTest {
            val combined = produce(
                layer("""{"icon-image":"marker","text-field":"ab","text-font":["Test Regular"]}""")
            ).filterIsInstance<SymbolInstance.SpriteWithText>().single()
            // `text-anchor`'s spec default is `center`: the label's box is centred on the point,
            // which is the icon's own centre. Nothing pushes it clear -- that is `text-offset`'s job.
            assertEquals(Offset.Zero, combined.textOffset)
        }
    }

    @Test
    fun `text-offset moves the label by ems of text-size`() {
        runTest {
            val combined = produce(
                layer(
                    """{"icon-image":"marker","text-field":"ab","text-font":["Test Regular"],""" +
                        """"text-anchor":"top","text-offset":[0,1]}"""
                )
            ).filterIsInstance<SymbolInstance.SpriteWithText>().single()
            // One em of the default `text-size`, at density 1, below the top-anchored box's centre.
            assertEquals(combined.textSize.height / 2f + StyleSpecDefaults.TEXT_SIZE.toFloat(), combined.textOffset.y)
        }
    }

    @Test
    fun `the composite box lands the icon on the feature's point`() {
        runTest {
            /* SymbolComposer draws a symbol from its box's top-left corner, offset by `align`, so
             * `align` has to name where the icon's centre sits within the union of the two boxes. */
            val combined = produce(
                layer(
                    """{"icon-image":"marker","text-field":"ab","text-font":["Test Regular"],""" +
                        """"text-anchor":"top"}"""
                )
            ).filterIsInstance<SymbolInstance.SpriteWithText>().single()
            val size = combined.scaledSize(iconScale = 1f, textScale = 1f)
            val align = combined.scaledAlign(iconScale = 1f, textScale = 1f)

            assertEquals(
                maxOf(combined.spriteSize.width, combined.textSize.width) / 2f,
                -align.x * size.width,
            )
            assertEquals(combined.spriteSize.height / 2f, -align.y * size.height)
            // The label hangs below, so the box is the icon's height plus what the label adds.
            assertEquals(
                combined.spriteSize.height / 2f + combined.textOffset.y + combined.textSize.height / 2f,
                size.height,
            )
        }
    }

    @Test
    fun `a zoom-driven text-offset reaches the label`() {
        runTest {
            /* Real styles express `text-offset` as an `interpolate` over `["literal", [x, y]]`
             * stops -- swisstopo's `poi_lm` is one. That failed to compile against the length-less
             * `array<number>` this port derives for a `List<Double>` property, so the offset was
             * silently the spec default and the label sat on its icon. */
            val combined = produce(
                layer(
                    """{"icon-image":"marker","text-field":"ab","text-font":["Test Regular"],""" +
                        """"text-anchor":"top","text-size":16,""" +
                        """"text-offset":["interpolate",["exponential",0.8],["zoom"],""" +
                        """13,["literal",[0,1.1]],18,["literal",[0,1.7]]]}"""
                )
            ).filterIsInstance<SymbolInstance.SpriteWithText>().single()
            // `produce` renders at zoom 10, below the first stop, so the offset clamps to 1.1 em.
            assertEquals(combined.textSize.height / 2f + 1.1f * 16f, combined.textOffset.y)
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

    // region text-writing-mode

    /** The one ideograph the fixture font serves, so a CJK label shapes at all. */
    private val cjk = 0x4E2D

    private fun cjkLayer(writingMode: String, extra: String = "") = layer(
        """{"text-field":"${Char(cjk)}${Char(cjk)}","text-font":["Test Regular"],"text-size":16,""" +
            """"text-writing-mode":$writingMode$extra}"""
    )

    @Test
    fun `a cjk point label carries both settings for the placement pass to choose between`() = runTest {
        /* `text-writing-mode` is a preference order, so both shapings have to exist before anything
         * can prefer one -- upstream's `shapedTextOrientations`. The horizontal one stays the
         * instance's own `value`, whichever end of the list the style put first. */
        val symbols = produce(cjkLayer("""["horizontal","vertical"]"""), glyphs = glyphManager(listOf(cjk)))

        val label = symbols.filterIsInstance<SymbolInstance.Text>().single()
        val stacked = assertNotNull(label.verticalSetting, "the stacked setting is built")
        assertContentEquals(listOf("horizontal", "vertical"), label.writingModes)
        assertTrue(
            stacked.value.width < label.value.width && stacked.value.height > label.value.height,
            "the instance's own setting is the flat one and the second is the stacked one",
        )
    }

    @Test
    fun `the order the style wrote does not change what is built`() = runTest {
        val symbols = produce(cjkLayer("""["vertical","horizontal"]"""), glyphs = glyphManager(listOf(cjk)))

        val label = symbols.filterIsInstance<SymbolInstance.Text>().single()
        val stacked = assertNotNull(label.verticalSetting)
        assertContentEquals(listOf("vertical", "horizontal"), label.writingModes)
        assertTrue(
            stacked.value.width < label.value.width,
            "the instance's own setting is still the flat one, for placement to prefer or not",
        )
    }

    @Test
    fun `a label a style never lists vertical for carries one setting`() = runTest {
        val symbols = produce(cjkLayer("""["horizontal"]"""), glyphs = glyphManager(listOf(cjk)))

        val label = symbols.filterIsInstance<SymbolInstance.Text>().single()
        assertTrue(label.verticalSetting == null, "nothing to fall back to, and nothing rasterized")
        assertTrue(label.writingModes.isEmpty())
    }

    @Test
    fun `latin text gets no stacked setting however the style asks`() = runTest {
        val symbols = produce(
            layer("""{"text-field":"AB","text-font":["Test Regular"],"text-size":16,"text-writing-mode":["vertical","horizontal"]}""")
        )

        val label = symbols.filterIsInstance<SymbolInstance.Text>().single()
        assertTrue(label.verticalSetting == null, "there is no glyph rotation to set it with")
    }

    @Test
    fun `a line label is never stacked`() = runTest {
        /* Upstream's other vertical branch -- a line label under `textAlongLine && keepUpright` --
         * rotates each glyph along the path, which this port cannot do. */
        val symbols = produce(
            cjkLayer("""["vertical","horizontal"]""", ""","symbol-placement":"line""""),
            feature = Mvt.lineFeature(listOf(0 to EXTENT / 2, EXTENT to EXTENT / 2)),
            glyphs = glyphManager(listOf(cjk)),
        )

        val labels = symbols.filterIsInstance<SymbolInstance.Text>()
        assertTrue(labels.isNotEmpty(), "the line is labelled")
        assertTrue(labels.all { it.verticalSetting == null }, "and always flat")
    }

    // endregion
}
