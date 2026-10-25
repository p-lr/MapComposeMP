package ovh.plrapps.mapcompose.ui.paths

/**
 *  On iOS, we lose hardware acceleration when enabling antialias, which significantly degrades
 *  performance. In practice, iOS devices have high enough pixel density, so disabling it is hardly
 *  noticeable.
 */
internal actual val pathAntiAlias: Boolean = false
