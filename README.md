[![Maven Central](https://img.shields.io/maven-central/v/ovh.plrapps/mapcompose-mp)](https://central.sonatype.com/artifact/ovh.plrapps/mapcompose-mp)
[![GitHub License](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat)](http://www.apache.org/licenses/LICENSE-2.0)

🎉 News:
Road to `v1.0.0`:
- [x] Support for hardware bitmaps on android
- [x] Improve rendering of layers
- [ ] Support infinite scroll (tile looping)
- [ ] Backport apis and fixes from the android native library

# MapCompose-mp

MapCompose-mp is a fast, memory efficient compose multiplatform library to display tiled maps with minimal effort.
It shows the visible part of a tiled map with support of markers and paths, and various gestures
(flinging, dragging, scaling, and rotating).
Target platforms are iOS, desktop (Windows, MacOs, Linux), Android, and WebAssembly.
It's a multiplatform port of [MapCompose](https://github.com/p-lr/MapCompose).

An example of setting up on desktop:

```kotlin
/* Inside your view-model */
val tileStreamProvider = TileStreamProvider { row, col, zoomLvl ->
    FileInputStream(File("path/{$zoomLvl}/{$row}/{$col}.jpg")).asSource() // or it can be a remote HTTP fetch
}

val state = MapState(4, 4096, 4096).apply {
    addLayer(tileStreamProvider)
    enableRotation()
}

/* Inside a composable */
@Composable
fun MapContainer(
    modifier: Modifier = Modifier, viewModel: YourViewModel
) {
    MapUI(modifier, state = viewModel.state)
}
```

This project holds the source code of this library, plus a demo app - which is useful to get started.
To test the demo, just clone the repo and launch the demo app from Android Studio.

## Clustering

Marker clustering regroups markers of close proximity into clusters. The video below shows how it works.

https://github.com/p-lr/MapCompose/assets/15638794/de48cb1b-396b-44d3-b47a-e3d719e8f38a

The sample below shows the relevant part of the code. We can still add regular markers (not managed by a clusterer), such as the red marker in the video.
See the [full code](demo/composeApp/src/commonMain/kotlin/ovh/plrapps/mapcompose/demo/viewmodels/MarkersClusteringVM.kt).

```kotlin
/* Add clusterer */
state.addClusterer("default") { ids ->
   { Cluster(size = ids.size) }
}

/* Add marker managed by the clusterer */
state.addMarker(
    id = "marker",
    x = 0.2,
    y = 0.3,
    renderingStrategy = RenderingStrategy.Clustering("default"),
) {
    Marker()
}
```

There's an example in the demo app.


## Installation

Add this to your commonMain dependencies:
```kotlin
sourceSets {
  commonMain.dependencies {
      implementation("ovh.plrapps:mapcompose-mp:0.11.0")
  }
}
```

## Basics

MapCompose is optimized to display maps that have several levels, like this:

<p align="center">
<img src="doc/readme-files/pyramid.png" width="400">
</p>

Each next level is twice bigger than the former, and provides more details. Overall, this looks like
a pyramid. Another common name is "deep-zoom" map.
This library comes with a demo app featuring various use-cases such as using markers, paths,
map rotation, etc. All examples use the same map stored in the assets, which is a great example of
deep-zoom map.

MapCompose can also be used with single level maps.

### Usage

With Jetpack Compose, we have to change the way we think about views. In the previous `View`
system, we had references on views and mutated their state directly. While that could be done right,
the state often ended-up scattered between views own state and application state. Sometimes, it was
difficult to predict how views were rendered because there were so many things to take into account.

Now, the rendering is a function of a state. If that state changes, the "view" updates accordingly.

In a typical application, you create a `MapState` instance inside a `ViewModel` (or whatever
component which survives device rotation). Your `MapState` should then be passed to the `MapUI`
composable. The code sample at the top of this readme shows an example. Then, whenever you need to
update the map (add a marker, a path, change the scale, etc.), you invoke APIs on your `MapState`
instance. As its name suggests, `MapState` also _owns_ the state. Therefore, composables will always
render consistently - even after a device rotation.

All public APIs are located under the [api](library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api)
package. The following sections provide details on the `MapState` class, and give examples of how to
add markers, callouts, and paths.

### MapState

The `MapState` class expects three parameters for its construction:
* `levelCount`: The number of levels of the map,
* `fullWidth`: The width of the map at scale 1.0, which is the width of last level,
* `fullHeight`: The height of the map at scale 1.0, which is the height of last level

### Layers

MapCompose supports layers - e.g it's possible to add several tile pyramids. Each level is made of
the superposition of tiles from all pyramids at the given level. For example, at the second level
(starting from the lowest scale), tiles would look like the image below when three layers are added.

<p align="center">
<img src="doc/readme-files/layer.png" width="200">
</p>

Your implementation of the `TileStreamProvider` interface (see below) is what defines a tile
pyramid. It provides `RawSource`s of image files (png, jpg). MapCompose will request tiles using
the convention that the origin is at the top-left corner. For example, the tile requested with
`row` = 0, and `col = 0` will be positioned at the top-left corner.

N.B: `RawSource` is a kotlinx.io concept very similar to Java's InputStream.

```kotlin
fun interface TileStreamProvider {
    suspend fun getTileStream(row: Int, col: Int, zoomLvl: Int): RawSource?
}
```

Depending on your configuration, your `TileStreamProvider` implementation might fetch local files,
as well as performing remote HTTP requests - it's up to you. You don't have to worry about threading,
MapCompose takes care of that (the main thread isn't blocked by `getTileStream` calls). However, in
case of HTTP requests, it's advised to create a `MapState` with a higher than default `workerCount`.
That optional parameter defines the size of the dedicated thread pool for fetching tiles, and defaults
to the number of cores minus one. Typically, you would want to set `workerCount` to 16 when performing
HTTP requests. Otherwise, you can safely leave it to its default.

To add a layer, use the `addLayer` on your `MapState` instance. There are others APIs for reordering,
removing, setting alpha - all dynamically.

### Markers

To add a marker, use the [addMarker](https://github.com/p-lr/MapComposeMP/blob/c5480ef10d0d8506ab55e58c3069877865c14aaa/library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api/MarkerApi.kt#L48)
API, like so:

```kotlin
/* Add a marker at the center of the map */
mapState.addMarker("id", x = 0.5, y = 0.5) {
    Icon(
        painter = painterResource(id = R.drawable.map_marker),
        contentDescription = null,
        modifier = Modifier.size(50.dp),
        tint = Color(0xCC2196F3)
    )
}
```

<p align="center">
<img src="doc/readme-files/marker.png">
</p>

A marker is a composable that you supply (in the example above, it's an `Icon`). It can be
whatever composable you like. A marker does not scale, but it's position updates as the map scales,
so it's always attached to the original position. A marker has an anchor point defined - the point
which is fixed relatively to the map. This anchor point is defined using relative offsets, which are
applied to the width and height of the marker. For example, to have a marker centered horizontally
and aligned at the bottom edge (like a typical map pin would do), you'd pass -0.5f and -1.0f as
relative offsets (left position is offset by half the width, and top is offset by the full height).
If necessary, an absolute offset expressed in pixels can be applied, in addition to the
relative offset.

Markers can be moved, removed, and be draggable. See the following APIs: [moveMarker](https://github.com/p-lr/MapComposeMP/blob/c5480ef10d0d8506ab55e58c3069877865c14aaa/library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api/MarkerApi.kt#L289),
[removeMarker](https://github.com/p-lr/MapComposeMP/blob/c5480ef10d0d8506ab55e58c3069877865c14aaa/library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api/MarkerApi.kt#L271),
[enableMarkerDrag](https://github.com/p-lr/MapComposeMP/blob/c5480ef10d0d8506ab55e58c3069877865c14aaa/library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api/MarkerApi.kt#L302).

### Callouts

Callouts are typically message popups which are, like markers, attached to a specific position.
However, they automatically dismiss on touch down. This default behavior can be changed.
To add a callout, use [addCallout](https://github.com/p-lr/MapComposeMP/blob/c5480ef10d0d8506ab55e58c3069877865c14aaa/library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api/MarkerApi.kt#L514).

<p align="center">
<img src="doc/readme-files/callout.png">
</p>

Callouts can be programmatically removed (if automatic dismiss was disabled).

### Paths

To add a path, use the `addPath` api:

```kotlin
mapState.addPath("pathId", color = Color(0xFF448AFF)) {
  addPoints(points)
}
```

The demo app shows a complete example.

<p align="center">
<img src="doc/readme-files/path.png">
</p>

## Animate state change

It's pretty common to programmatically animate the scroll and/or the scale, or even the rotation of
the map.

*scroll and/or scale animation*

When animating the scale, we generally do so while maintaining the center of the screen at
a specific position. Likewise, when animating the scroll position, we can do so with or without
animating the scale altogether, using [scrollTo](https://github.com/p-lr/MapComposeMP/blob/c5480ef10d0d8506ab55e58c3069877865c14aaa/library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api/LayoutApi.kt#L299)
and [snapScrollTo](https://github.com/p-lr/MapComposeMP/blob/c5480ef10d0d8506ab55e58c3069877865c14aaa/library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api/LayoutApi.kt#L269).

*rotation animation*

For animating the rotation while keeping the current scale and scroll, use the
[rotateTo](https://github.com/p-lr/MapComposeMP/blob/c5480ef10d0d8506ab55e58c3069877865c14aaa/library/src/commonMain/kotlin/ovh/plrapps/mapcompose/api/LayoutApi.kt#L226) API.

Both `scrollTo` and `rotateTo` are suspending functions. Therefore, you know exactly when
an animation finishes, and you can easily chain animations inside a coroutine.

```kotlin
// Inside a ViewModel
viewModelScope.launch {
    mapState.scrollTo(0.8, 0.8, destScale = 2f)
    mapState.rotateTo(180f, TweenSpec(2000, easing = FastOutSlowInEasing))
}
```

For a detailed example, see the "AnimationDemo".

## Differences with Android native MapCompose

The api is mostly the same as the native library. There's one noticeable difference:
`TileStreamProvider` returns `RawSource` instead of `InputStream`.

Some apis expect `dp` values instead of pixels.

Some optimizations are temporarily disabled, such as:
- "Bitmap" pooling on ios and desktop
- Subsampling

## Contributors

Special thanks to Roger (@rkreienbuehl) who made the first proof-of-concept, starting from 
MapCompose code base.

Marcin (@Nohus) has contributed and fixed some issues. He also thoroughly tested the layers
feature – which made `MapCompose` better.

