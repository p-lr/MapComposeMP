@file:Suppress("unused")

package ovh.plrapps.mapcompose.api

import ovh.plrapps.mapcompose.core.AboveAll
import ovh.plrapps.mapcompose.core.AboveLayer
import ovh.plrapps.mapcompose.core.BelowAll
import ovh.plrapps.mapcompose.core.BelowLayer
import ovh.plrapps.mapcompose.core.Layer
import ovh.plrapps.mapcompose.core.LayerPlacement
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.vector.core.VectorTileStreamProvider
import ovh.plrapps.mapcompose.core.makeLayerId
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.utils.swap
import ovh.plrapps.mapcompose.vector.core.VectorLayer
import ovh.plrapps.mapcompose.vector.core.magnifyingFactorForDensity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first


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

/**
 * Adds a layer rendered from MapLibre vector tiles.
 *
 * The map's `levelCount` is the deepest tile `z` this layer will be asked for, and it should be
 * chosen for the deepest zoom the app wants to offer rather than for the style's sources' `maxzoom`.
 * A source that runs out of tiles is *overzoomed*, as it is in maplibre-gl-js: the deepest ancestor
 * tile is re-rendered at the display zoom rather than the layer going blank. Setting `levelCount` to
 * a source's `maxzoom + 1` therefore caps the map at that source's data, which is almost never what
 * a vector style wants.
 *
 * @param superSamplingFactor Renders each tile this many times larger and filters it back down,
 * trading `factor²` rasterization cost for cleaner hairlines and text. Tiles are already rasterized
 * at the size they are drawn at, so `1` — the default — is never stretched; raising it only refines
 * the minification that happens between pyramid levels.
 * @param adaptTileScaleToDensity Sets the map's magnifying factor from the screen density, so that
 * one style pixel lands on one density-independent pixel. A vector style is authored in CSS pixels,
 * which is the unit a path's `width` and a marker's offset already use here; tile content, though,
 * is drawn at `tileSize * relativeScale` *device* pixels whatever the density is. Without this the
 * map renders at `1 / density` of its intended size — thin roads under correctly-sized labels — and
 * MapCompose asks for a higher tile `z` than MapLibre would for the same view. Left at its default
 * this is applied once, and only when the map is still at the default magnifying factor of `0`, so
 * a caller that chose its own keeps it.
 */
suspend fun MapState.addVectorLayer(
    vectorTileStreamProvider: VectorTileStreamProvider,
    initialOpacity: Float = 1f,
    placement: LayerPlacement = AboveAll,
    superSamplingFactor: Int = 1,
    adaptTileScaleToDensity: Boolean = true,
): String {
    if (adaptTileScaleToDensity && visibleTilesResolver.magnifyingFactor == 0) {
        /* Launched rather than awaited: the density only arrives when MapUI first composes, and
         * blocking here would deadlock a caller that awaits this layer's id before showing the map.
         * setMagnifyingFactor re-resolves the visible tiles, so arriving late costs one re-render. */
        scope.launch {
            val density = densityState.filterNotNull().first()
            if (visibleTilesResolver.magnifyingFactor == 0) {
                setMagnifyingFactor(magnifyingFactorForDensity(density.density))
            }
        }
    }

    val vectorLayer = VectorLayer(
        mapState = this,
        vectorTileStreamProvider = vectorTileStreamProvider,
        superSamplingFactor = superSamplingFactor.coerceAtLeast(1),
    )
    val tileStreamProvider = vectorLayer.makeTileStreamProvider()
    // TODO: honor placement parameter
    return addLayer(tileStreamProvider)
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
 * Define the list of layers using a builder.
 *
 * @return The list of layer ids, in the order of addition.
 */
fun MapState.buildLayers(builder: LayersBuilder.() -> Unit): List<String> {
    val builderInternal = LayersBuilderInternal()
    builderInternal.apply(builder)
    setLayers(builderInternal.layers)

    return builderInternal.layers.map { it.id }
}

interface LayersBuilder {
    fun addLayer(tileStreamProvider: TileStreamProvider, initialOpacity: Float = 1f)
}

/**
 * Utility function to automatically refresh tiles after a change of layers.
 */
private fun MapState.setLayers(layers: List<Layer>) {
    tileCanvasState.setLayers(layers)
    renderVisibleTilesThrottled()
}
