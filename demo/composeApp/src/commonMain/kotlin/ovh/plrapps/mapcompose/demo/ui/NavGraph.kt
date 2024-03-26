package ovh.plrapps.mapcompose.demo.ui

import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.ui.screens.AddingMarkerDemo
import ovh.plrapps.mapcompose.demo.ui.screens.AnimationDemo
import ovh.plrapps.mapcompose.demo.ui.screens.CalloutDemo
import ovh.plrapps.mapcompose.demo.ui.screens.CenteringOnMarkerDemo
import ovh.plrapps.mapcompose.demo.ui.screens.CustomDraw
import ovh.plrapps.mapcompose.demo.ui.screens.HttpTilesDemo
import ovh.plrapps.mapcompose.demo.ui.screens.LayersDemoSimple
import ovh.plrapps.mapcompose.demo.ui.screens.MapDemoSimple
import ovh.plrapps.mapcompose.demo.ui.screens.MarkersClusteringDemo
import ovh.plrapps.mapcompose.demo.ui.screens.MarkersLazyLoadingDemo
import ovh.plrapps.mapcompose.demo.ui.screens.OsmDemo
import ovh.plrapps.mapcompose.demo.ui.screens.PathsDemo
import ovh.plrapps.mapcompose.demo.ui.screens.RotationDemo
import ovh.plrapps.mapcompose.demo.ui.screens.VisibleAreaPaddingDemo

const val HOME = "home"

enum class MainDestinations() {
    MAP_ALONE {
        override val title = "Simple map"
        override val screen = MapDemoSimple
    },
    LAYERS_DEMO {
        override val title = "Layers demo"
        override val screen = LayersDemoSimple
    },
    MAP_WITH_ROTATION_CONTROLS {
        override val title = "Map with rotation controls"
        override val screen = RotationDemo
    },
    ADDING_MARKERS{
        override val title = "Adding markers"
        override val screen = AddingMarkerDemo
    },
    CENTERING_ON_MARKER{
        override val title = "Centering on marker"
        override val screen = CenteringOnMarkerDemo
    },
    PATHS{
        override val title = "Map with paths"
        override val screen = PathsDemo
    },
    CUSTOM_DRAW{
        override val title = "Map with custom drawings"
        override val screen = CustomDraw
    },
    CALLOUT_DEMO{
        override val title = "Callout (tap markers)"
        override val screen = CalloutDemo
    },
    ANIMATION_DEMO{
        override val title = "Animation demo"
        override val screen = AnimationDemo
    },
    OSM_DEMO{
        override val title = "Open Street Map demo"
        override val screen = OsmDemo
    },
    HTTP_TILES_DEMO{
        override val title = "Remote HTTP tiles"
        override val screen = HttpTilesDemo
    },
    VISIBLE_AREA_PADDING{
        override val title = "Visible area padding"
        override val screen = VisibleAreaPaddingDemo
    },
    MARKERS_CLUSTERING{
        override val title = "Markers clustering"
        override val screen = MarkersClusteringDemo
    },
    MARKERS_LAZY_LOADING{
        override val title = "Markers lazy loading"
        override val screen = MarkersLazyLoadingDemo
    };

    abstract val title: String
    abstract val screen: Screen
}

