This is an experimental Kotlin Multiplatform port of [MapCompose](https://github.com/p-lr/MapCompose).

Some optimizations are temporarily disabled, such as:
- "Bitmap" pooling
- Subsampling

The library is almost fully functional without those optimizations. Memory usage is only higher.
Important things to note:

 - Public API, especially `TileStreamProvider`, is likely to change before 1.0. So this project should
be considered alpha.
- Some features show some regressions, such as marker clustering.
- Paths aren't working yet.

Work is underway to address known issues.