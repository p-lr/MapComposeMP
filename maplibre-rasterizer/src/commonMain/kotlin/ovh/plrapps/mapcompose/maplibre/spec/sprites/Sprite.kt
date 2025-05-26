package ovh.plrapps.mapcompose.maplibre.spec.sprites

import kotlinx.serialization.Serializable

@Serializable
data class Sprite(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val stretchX: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null,
    val stretchY: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null,
    val sdf: Boolean = false,
)