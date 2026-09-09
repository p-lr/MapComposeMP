package ovh.plrapps.mapcompose.vector.spec.style

import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue

interface LayoutInterface {
    /**
     * `visibility`, an expression over `["global-state", ...]` -- see
     * `ovh.plrapps.mapcompose.vector.spec.style.props.visibilitySpec`. Read through
     * `BaseRenderer.isLayerVisible`.
     */
    val visibility: ExpressionOrValue<String>?
}
