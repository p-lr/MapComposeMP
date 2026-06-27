package ovh.plrapps.mapcompose.vector.renderer.collision

/**
 * Controls whether a symbol can overlap other symbols.
 *
 * Mirrors the MapLibre `icon-overlap` / `text-overlap` spec values.
 * The older `icon-allow-overlap` / `text-allow-overlap` booleans map to [Never] (false) / [Always] (true).
 */
enum class OverlapMode {
    /** Symbol is hidden when it collides with any other visible symbol. (default) */
    Never,

    /** Symbol is always visible regardless of collisions. Blocks [Never] symbols placed later. */
    Always,

    /**
     * Symbol is visible unless it collides with a [Never] symbol.
     * Two [Cooperative] symbols may overlap each other freely.
     */
    Cooperative,
}
