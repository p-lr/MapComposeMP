This is a Kotlin Multiplatform port of [MapCompose](https://github.com/p-lr/MapCompose).

Some optimizations are temporarily disabled, such as:
- "Bitmap" pooling
- Subsampling

The library is still fully functional without those optimizations. Memory usage is only higher.
Public API, especially `TileStreamProvider`, is likely to change before 1.0. So this project should
be considered alpha.

* `/composeApp` is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - `commonMain` is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    `iosMain` would be the right folder for such calls.

* `/iosApp` contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform, 
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.


Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…