@file:Suppress("unused")

package ovh.plrapps.mapcompose.api

import androidx.compose.ui.unit.dp
import kotlinx.io.Buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.io.buffered
import kotlinx.io.readString
import ovh.plrapps.mapcompose.core.*
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.utils.swap
import ovh.plrapps.mapcompose.utils.throttle
import ovh.plrapps.mapcompose.vector.VectorRasterizer
import ovh.plrapps.mapcompose.vector.data.extension.toBytes
import ovh.plrapps.mapcompose.vector.data.extension.toMVTViewport
import ovh.plrapps.mapcompose.vector.data.getMapLibreConfiguration


/**
 * Add a layer. By default, the layer is added on top of the layer stack (see [AboveAll]).
 * Optionally, the layer can be added at the bottom of the stack, or above / below an existing layer.
 *
 * Note that [initialOpacity] is taken into account _only_ if the layer being added isn't the lowest
 * one, or the only one. However, if later on another layer is added below this layer, the
 * [initialOpacity] will be taken into account.
 *
 * @return The id of the created layer
 */
fun MapState.addLayer(
    tileStreamProvider: TileStreamProvider,
    initialOpacity: Float = 1f,
    placement: LayerPlacement = AboveAll
): String {
    val layers = tileCanvasState.layerFlow.value.toMutableList()
    val id = makeLayerId()
    val layer = Layer(id, tileStreamProvider, initialOpacity)

    val newLayers = when (placement) {
        AboveAll -> {
            layers + layer
        }
        is AboveLayer -> {
            val existingLayerIndex = layers.indexOfFirst { it.id == placement.layerId }
            if (existingLayerIndex != -1 && existingLayerIndex < layers.lastIndex) {
                layers.add(existingLayerIndex + 1, layer)
            }
            layers
        }
        BelowAll -> {
            layers.add(0, layer)
            layers
        }
        is BelowLayer -> {
            val existingLayerIndex = layers.indexOfFirst { it.id == placement.layerId }
            if (existingLayerIndex != -1) {
                layers.add(existingLayerIndex, layer)
            }
            layers
        }
    }

    setLayers(newLayers)

    return id
}

fun MapState.addVectorLayer(
    vectorTileStreamProvider: VectorTileStreamProvider,
    initialOpacity: Float = 1f,
    placement: LayerPlacement = AboveAll
): String {
    var layerName: String? = null

    var rasterizer: VectorRasterizer? = null
    val getRasterizer: suspend () -> VectorRasterizer = suspend {
        val style = vectorTileStreamProvider.loadResources(vectorTileStreamProvider.styleUrl)
        val configuration = getMapLibreConfiguration(style?.buffered()?.readString() ?: "", loadResource = vectorTileStreamProvider::loadResources).getOrThrow()

        VectorRasterizer(
            configuration = configuration,
            densityState = this.densityState,
            fontFamilyResolverState = this.fontFamilyResolverState,
            textMeasurerState = this.textMeasurerState,
            getTileStream = vectorTileStreamProvider::getTileStream
        )
    }

    val tileStreamProvider = TileStreamProvider { row, col, zoomLvl ->
        if (rasterizer == null) {
            rasterizer = getRasterizer()
        }
        val density = this.densityState.value ?: return@TileStreamProvider null
        val tilePx = with(density) { 512.dp.toPx() }.toInt()

        val imageBitmap = rasterizer.getTile(
            x = col,
            y = row,
            zoom = zoomLvl.toDouble(),
            tileSize = tilePx
        )

        val bytes = imageBitmap.toBytes()
            ?: return@TileStreamProvider null

        return@TileStreamProvider Buffer().apply {
            write(bytes)
        }
    }

    apply {
        layerName = addLayer(tileStreamProvider)
    }.apply {
        viewportInfoFlow
            .throttle(250)
            .map { viewportInfo ->
                viewportInfo ?: return@map
                rasterizer ?: return@map

                val zoomLvl = viewportInfo.zoom // Используем zoom из ViewportInfo!
                val density = this.densityState.value ?: return@map
                val tilePx = with(density) { 512.dp.toPx() }.toInt()

                val nextSymbols = rasterizer.produceSymbols(
                    viewport = viewportInfo.toMVTViewport(),
                    tileSize = tilePx,
                    z = zoomLvl.toDouble()
                ).getOrElse { e ->
                    println("[ERROR] produceSymbols(): ${e.message}")
                    return@map
                }

                rasterizer.updateSymbols(
                    nextSymbols = nextSymbols,
                    state = this
                )

            }
            .catch {
                println("error: ${it.message}")
            }
    }

    return layerName ?: throw IllegalStateException("Layer name cannot be null")
}

/**
 * Replaces a layer. If the layer doesn't exist, no layer is added.
 *
 * @return The id of the added layer, or null if [layerId] doesn't match with any existing layer
 */
fun MapState.replaceLayer(
    layerId: String,
    tileStreamProvider: TileStreamProvider,
    initialOpacity: Float = 1f
): String? {
    val layers = tileCanvasState.layerFlow.value.toMutableList()

    val index = layers.indexOfFirst {
        it.id == layerId
    }

    val id = makeLayerId()

    return if (index != -1) {
        layers[index] = Layer(id, tileStreamProvider, initialOpacity)
        setLayers(layers)
        id
    } else null
}

/**
 * Moves a layer up in the layer stack, making it drawn on top of the layer which was previously
 * above it.
 */
fun MapState.moveLayerUp(layerId: String) {
    val layers = tileCanvasState.layerFlow.value.toMutableList()

    val index = layers.indexOfFirst {
        it.id == layerId
    }

    if (index < layers.lastIndex) {
        layers.swap(index + 1, index)
        setLayers(layers)
    }
}

/**
 * Moves a layer down in the layer stack, making it drawn below the layer which was previously
 * below it.
 */
fun MapState.moveLayerDown(layerId: String) {
    val layers = tileCanvasState.layerFlow.value.toMutableList()

    val index = layers.indexOfFirst {
        it.id == layerId
    }

    if (index > 0) {
        layers.swap(index - 1, index)
        setLayers(layers)
    }
}

/**
 * Remove the top layer from the stack.
 */
fun MapState.removeLastLayer() {
    val layers = tileCanvasState.layerFlow.value.toMutableList()
    val remainingLayers = layers.subList(0, layers.size - 1)
    setLayers(remainingLayers)
}

/**
 * Remove the top [n] layers from the stack.
 * @param n The number of layers to remove.
 */
fun MapState.removeLastLayers(n: Int) {
    val layers = tileCanvasState.layerFlow.value.toMutableList()
    val remainingLayers = layers.subList(0, (layers.size - n).coerceAtLeast(0))
    setLayers(remainingLayers)
}

/**
 * Reorder layers in the order of the provided list of ids. Layers listed first will be drawn before
 * subsequent layers (so the later will be above).
 * Existing layers not included in the provided list will be removed
 */
fun MapState.reorderLayers(layerIds: List<String>) {
    val layerForId = tileCanvasState.layerFlow.value.associateBy { it.id }
    val layers = layerIds.mapNotNull { layerForId[it] }

    setLayers(layers)
}

/**
 * Remove all layers.
 */
fun MapState.removeAllLayers() {
    setLayers(emptyList())
}

/**
 * Remove some layers.
 */
fun MapState.removeLayers(layerIds: List<String>) {
    val remainingLayers = tileCanvasState.layerFlow.value.filterNot {
        it.id in layerIds
    }
    setLayers(remainingLayers)
}

/**
 * Remove a layer.
 */
fun MapState.removeLayer(layerId: String) {
    val remainingLayers = tileCanvasState.layerFlow.value.filterNot {
        it.id == layerId
    }
    setLayers(remainingLayers)
}

/**
 * Dynamically update the opacity of a layer. If the layer is the lowest one or the only one, the
 * new opacity won't have effect until a layer is added below it.
 */
fun MapState.setLayerOpacity(layerId: String, opacity: Float) {
    val newLayers = tileCanvasState.layerFlow.value.map {
        if (it.id == layerId) {
            it.copy(alpha = opacity.coerceIn(0f..1f))
        } else it
    }
    setLayers(newLayers)
}

/**
 * Utility function to automatically refresh tiles after a change of layers.
 */
private fun MapState.setLayers(layers: List<Layer>) {
    tileCanvasState.setLayers(layers)
    renderVisibleTilesThrottled()
}
