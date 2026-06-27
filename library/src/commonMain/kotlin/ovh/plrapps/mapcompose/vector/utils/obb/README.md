# OBB (Oriented Bounding Box) Implementation

## Description
Implementation of Oriented Bounding Box for precise intersection detection. OBB is a rectangle that can be rotated to any angle, providing a tighter fit around objects compared to AABB.

## Key Features
- Support for rotated rectangles
- Precise intersection detection
- Efficient collision checking
- Support for 2D space
- Support for any rotation angle
- Support for any size

## Usage
```kotlin
// Create OBB with center point, size and rotation
val obb = OBB(
    center = Point(10f, 10f),
    size = Size(20f, 10f),
    rotation = 45f // degrees
)

// Check intersection with another OBB
val otherObb = OBB(
    center = Point(15f, 15f),
    size = Size(10f, 10f),
    rotation = 0f
)

val isIntersecting = obb.intersects(otherObb)

// Get corners of OBB
val corners = obb.getCorners()

// Get AABB that contains this OBB
val aabb = obb.getAABB()
```

## Algorithm
OBB intersection test uses the Separating Axis Theorem (SAT):
1. Project both OBBs onto each other's axes
2. If there is a gap in any projection, the OBBs do not intersect
3. If there is no gap in any projection, the OBBs intersect

## Performance
- O(n) complexity for intersection test, where n is the number of axes to check
- For 2D OBB, we need to check 4 axes (2 from each OBB)
- Much more precise than AABB, but slower
- Best used after AABB/R-tree filtering

## Integration with R-tree
1. R-tree with AABB finds potential intersections
2. OBB performs precise intersection check only for candidates
3. This two-step approach provides both speed and accuracy

## Notes
- OBB is more computationally expensive than AABB
- Should be used only for precise collision detection
- Best used in combination with R-tree for optimal performance
- Rotation is specified in degrees for simplicity
- All coordinates and sizes are in float for precision

## Running Tests
```bash
./gradlew :composeApp:cleanJvmTest :composeApp:jvmTest --tests "ovh.plrapps.mapcompose.maplibre.utils.obb.ObbTest"
```
