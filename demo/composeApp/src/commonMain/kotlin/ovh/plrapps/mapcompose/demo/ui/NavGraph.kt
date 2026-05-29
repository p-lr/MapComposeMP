package ovh.plrapps.mapcompose.demo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.demo.ui.screens.AddingMarkerDemo
import ovh.plrapps.mapcompose.demo.ui.screens.AnimationDemo
import ovh.plrapps.mapcompose.demo.ui.screens.CalloutDemo
import ovh.plrapps.mapcompose.demo.ui.screens.CenteringOnMarkerDemo
import ovh.plrapps.mapcompose.demo.ui.screens.CustomDraw
import ovh.plrapps.mapcompose.demo.ui.screens.HomeScreenCommonUi
import ovh.plrapps.mapcompose.demo.ui.screens.HttpTilesDemo
import ovh.plrapps.mapcompose.demo.ui.screens.InfiniteScrollDemo
import ovh.plrapps.mapcompose.demo.ui.screens.LayersDemoSimple
import ovh.plrapps.mapcompose.demo.ui.screens.MapDemoSimple
import ovh.plrapps.mapcompose.demo.ui.screens.MarkersClusteringDemo
import ovh.plrapps.mapcompose.demo.ui.screens.MarkersLazyLoadingDemo
import ovh.plrapps.mapcompose.demo.ui.screens.OsmDemo
import ovh.plrapps.mapcompose.demo.ui.screens.PathsDemo
import ovh.plrapps.mapcompose.demo.ui.screens.RotationDemo
import ovh.plrapps.mapcompose.demo.ui.screens.VisibleAreaPaddingDemo

/* Type-safe navigation routes. Each demo screen and the home list has its own route object. */
@Serializable object Home
@Serializable object MapAlone
@Serializable object LayersDemoRoute
@Serializable object RotationRoute
@Serializable object AddingMarkerRoute
@Serializable object CenteringOnMarkerRoute
@Serializable object PathsRoute
@Serializable object CustomDrawRoute
@Serializable object CalloutRoute
@Serializable object AnimationRoute
@Serializable object InfiniteScrollRoute
@Serializable object OsmRoute
@Serializable object HttpTilesRoute
@Serializable object VisibleAreaPaddingRoute
@Serializable object MarkersClusteringRoute
@Serializable object MarkersLazyLoadingRoute

enum class MainDestinations(val title: String, val route: Any) {
    MAP_ALONE("Simple map", MapAlone),
    LAYERS_DEMO("Layers demo", LayersDemoRoute),
    MAP_WITH_ROTATION_CONTROLS("Map with rotation controls", RotationRoute),
    ADDING_MARKERS("Adding markers", AddingMarkerRoute),
    CENTERING_ON_MARKER("Centering on marker", CenteringOnMarkerRoute),
    PATHS("Map with paths", PathsRoute),
    CUSTOM_DRAW("Map with custom drawings", CustomDrawRoute),
    CALLOUT_DEMO("Callout (tap markers)", CalloutRoute),
    ANIMATION_DEMO("Animation demo", AnimationRoute),
    INFINITE_SCROLL_DEMO("Infinite scroll demo", InfiniteScrollRoute),
    OSM_DEMO("Open Street Map demo", OsmRoute),
    HTTP_TILES_DEMO("Remote HTTP tiles", HttpTilesRoute),
    VISIBLE_AREA_PADDING("Visible area padding", VisibleAreaPaddingRoute),
    MARKERS_CLUSTERING("Markers clustering", MarkersClusteringRoute),
    MARKERS_LAZY_LOADING("Markers lazy loading", MarkersLazyLoadingRoute);
}

/**
 * Registers every demo screen as a destination. Shared by all platforms, which build their own
 * [NavHost] around it (Android/iOS start on [Home], desktop renders this beside an always-visible
 * demo list).
 */
fun NavGraphBuilder.demoDestinations() {
    composable<MapAlone> { MapDemoSimple.Content() }
    composable<LayersDemoRoute> { LayersDemoSimple.Content() }
    composable<RotationRoute> { RotationDemo.Content() }
    composable<AddingMarkerRoute> { AddingMarkerDemo.Content() }
    composable<CenteringOnMarkerRoute> { CenteringOnMarkerDemo.Content() }
    composable<PathsRoute> { PathsDemo.Content() }
    composable<CustomDrawRoute> { CustomDraw.Content() }
    composable<CalloutRoute> { CalloutDemo.Content() }
    composable<AnimationRoute> { AnimationDemo.Content() }
    composable<InfiniteScrollRoute> { InfiniteScrollDemo.Content() }
    composable<OsmRoute> { OsmDemo.Content() }
    composable<HttpTilesRoute> { HttpTilesDemo.Content() }
    composable<VisibleAreaPaddingRoute> { VisibleAreaPaddingDemo.Content() }
    composable<MarkersClusteringRoute> { MarkersClusteringDemo.Content() }
    composable<MarkersLazyLoadingRoute> { MarkersLazyLoadingDemo.Content() }
}

/**
 * Stack-based navigation host used by Android and iOS: starts on the [Home] demo list and pushes
 * the selected demo onto the back stack.
 */
@Composable
fun DemoNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(navController, startDestination = Home, modifier = modifier) {
        composable<Home> {
            HomeScreenCommonUi(onNavigate = { navController.navigate(it) })
        }
        demoDestinations()
    }
}
