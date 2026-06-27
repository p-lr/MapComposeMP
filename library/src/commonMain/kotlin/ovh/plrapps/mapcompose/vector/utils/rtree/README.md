# R-tree Implementation

## Description
Implementation of a classical R-tree for efficient AABB (Axis-Aligned Bounding Box) intersection queries.
In this implementation, boundary touching is considered as intersection, which aligns with classical R-tree behavior.

## Key Features
- AABB (Axis-Aligned Bounding Box) support
- Boundary touching is considered as intersection
- Efficient intersection queries
- Support for points (zero-sized AABBs)
- Support for negative coordinates
- Support for large and small numbers
- Multi-level structure with automatic node splitting
- Configurable node size (maxEntries and minEntries)

## Performance Characteristics
- Average search speedup: 6.2x compared to naive search
- Optimal for small to medium search areas (5-7x speedup)
- Efficient for large datasets (50,000+ elements)
- Tree depth scales logarithmically with dataset size
- Memory efficient due to configurable node size

## Tests
The implementation is covered by comprehensive tests that verify:

### Basic Operations
- Element insertion and search
- Empty tree operations
- Size tracking
- Non-intersecting area queries

### Edge Cases
- Points (zero-sized AABBs)
- Negative coordinates
- Very large numbers (1e6 - 1e7)
- Very small numbers (1e-6 - 1e-5)
- Boundary intersections (touching edges)
- Identical AABBs

### Structural Tests
- Node splitting when exceeding maxEntries
- Multi-level tree structure
- Deep trees (500+ elements)
- Degenerate cases (lines)
- Tree rebalancing

### Performance
- Search in large datasets (50,000 elements)
- Efficiency with multiple intersections
- Deep structure operations
- Comparison with naive search
- Various search area sizes

## Usage
```kotlin
// Create tree with custom node size
val rtree = Rtree<String>(maxEntries = 16, minEntries = 4)

// Insert elements
rtree.insert(AABB(0f, 0f, 10f, 10f), "item1")

// Search for intersections
val results = rtree.search(AABB(5f, 5f, 15f, 15f))

// Get size
val size = rtree.size()

// Get tree depth
val depth = rtree.depth()
```

## Running Tests
```bash
./gradlew :composeApp:cleanJvmTest :composeApp:jvmTest --tests "ovh.plrapps.mapcompose.maplibre.utils.rtree.RtreeTest"
```

## Notes
- Implementation uses Kotlin Compose structures
- R-tree is used for fast candidate selection by AABB
- Precise intersection checks should be performed separately (e.g., using OBB) only for selected candidates
- Default node size (maxEntries=16, minEntries=4) provides good balance between search performance and memory usage
- Tree depth is automatically maintained during insertion

## Algorithm
Detailed description of the R-tree algorithm can be found on [Wikipedia](https://en.wikipedia.org/wiki/R-tree)
