package ovh.plrapps.mapcompose.vector.spec.style

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics

/**
 * A source's `promoteId`: the feature property that stands in for the feature's id.
 *
 * The spec (`source_vector.promoteId`, `source_geojson.promoteId`) allows two shapes -- a bare
 * property name covering every source layer, or an object naming one property per source layer --
 * so this is what `Source.promoteId`'s raw JSON normalizes to. See [Source.promoteIdSpec].
 *
 * Read by `renderer/BaseRenderer.buildEvalFeature`, which is this port's seam for upstream's
 * `FeatureIndex.getId` (`src/data/feature_index.ts`).
 */
sealed interface PromoteId {

    /** The property this source promotes on [sourceLayer], or `null` if it promotes none there. */
    fun propertyFor(sourceLayer: String): String?

    /** `"promoteId": "ref"` -- one property, every source layer. */
    data class Single(val property: String) : PromoteId {
        override fun propertyFor(sourceLayer: String): String = property
    }

    /**
     * `"promoteId": {"roads": "ref"}` -- one property per source layer.
     *
     * A `geojson` source has a single synthetic layer, so its key is
     * `GeoJsonTiler.LAYER_NAME` (`_geojsonTileLayer`), which is upstream's name for it too.
     */
    data class PerSourceLayer(val byLayer: Map<String, String>) : PromoteId {
        override fun propertyFor(sourceLayer: String): String? = byLayer[sourceLayer]
    }
}

/**
 * The source's `promoteId`, normalized, or `null` when it declares none.
 *
 * The property is kept as raw JSON for the reason `sprite` and `font-faces` are: the spec allows
 * either a string or an object in the same field and kotlinx-serialization has no union. Typed as a
 * `String?`, as it was, the *object* form -- which is what a multi-layer `vector` source writes --
 * threw out of the decode, and `getMapLibreConfiguration` turns any decode failure into a
 * `Result.failure` that blanks the whole map.
 *
 * So nothing here throws either: a shape the spec does not allow is reported through
 * [StyleDiagnostics] and the source simply keeps its protobuf ids. [location] is the source's
 * diagnostic path, `sources.<name>`.
 */
fun Source.promoteIdSpec(location: String): PromoteId? {
    val element = promoteId ?: return null
    when (element) {
        is JsonNull -> return null

        is JsonPrimitive -> {
            if (!element.isString) {
                StyleDiagnostics.report(
                    location = location,
                    message = "promoteId must be a property name or an object of them, " +
                            "found ${element.content}; ids are not promoted for this source",
                )
                return null
            }
            return PromoteId.Single(element.content)
        }

        is JsonObject -> {
            val byLayer = mutableMapOf<String, String>()
            for ((sourceLayer, value) in element) {
                val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name == null) {
                    StyleDiagnostics.report(
                        location = location,
                        message = "promoteId.$sourceLayer must be a property name; " +
                                "ids are not promoted for that source layer",
                    )
                    continue
                }
                byLayer[sourceLayer] = name
            }
            return byLayer.takeIf { it.isNotEmpty() }?.let { PromoteId.PerSourceLayer(it) }
        }

        else -> {
            StyleDiagnostics.report(
                location = location,
                message = "promoteId must be a property name or an object of them; " +
                        "ids are not promoted for this source",
            )
            return null
        }
    }
}
