package ovh.plrapps.mapcompose.maplibre.spec.style.background

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import kotlinx.serialization.Serializable

@Serializable
data class BackgroundLayout(
    override val visibility: String? = "visible"
) : LayoutInterface