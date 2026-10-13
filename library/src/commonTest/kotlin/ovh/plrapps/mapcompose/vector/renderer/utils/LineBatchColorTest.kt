package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals

class LineBatchColorTest {

    @Test
    fun `the integer packing matches Color copy and toArgb bit for bit`() {
        val colors = listOf(
            Color.Red,
            Color(0x80123456),
            Color(0x01FEDCBA),
            Color(0xFFFFFFFF),
            Color(0x7F000000),
        )
        val alphas = (0..100).map { it / 100f } + listOf(1f / 3f, 2f / 3f, 0.999f, 0.001f)
        val opacities = listOf(1f, 0.5f, 0.3f, 0.75f, 0.01f)
        for (color in colors) {
            val rgb = color.toArgb() and 0x00FFFFFF
            for (vertexAlpha in alphas) {
                for (opacity in opacities) {
                    val expected = color.copy(alpha = color.alpha * vertexAlpha * opacity).toArgb()
                    assertEquals(
                        expected,
                        flatVertexArgb(rgb, color.alpha, vertexAlpha, opacity),
                        "color=$color vertexAlpha=$vertexAlpha opacity=$opacity",
                    )
                }
            }
        }
    }
}
