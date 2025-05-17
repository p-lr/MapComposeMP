package ovh.plrapps.mapcompose.maplibre.renderer.collision

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

class LineLabelPlacement {
    companion object {
        fun calculatePlacements(
            points: List<Pair<Float, Float>>,
            textWidth: Float,
            spacing: Float
        ): List<Pair<Pair<Float, Float>, Float>> {
            if (points.size < 2) return emptyList()
            
            val placements = mutableListOf<Pair<Pair<Float, Float>, Float>>()
            var distance = 0f
            
            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]
                
                val dx = p2.first - p1.first
                val dy = p2.second - p1.second
                val segmentLength = sqrt(dx * dx + dy * dy)
                
                if (segmentLength == 0f) continue
                
                val angle = atan2(dy, dx) * 180f / PI.toFloat()
                
                // We start from half the interval from the beginning of the segment
                var currentDistance = spacing / 2
                
                while (currentDistance + textWidth / 2 <= segmentLength) {
                    val t = currentDistance / segmentLength
                    val x = p1.first + t * dx
                    val y = p1.second + t * dy
                    
                    placements.add((x to y) to angle)
                    currentDistance += spacing
                }
                
                distance += segmentLength
            }
            
            return placements
        }
    }
} 