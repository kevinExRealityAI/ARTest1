package com.example.arsurvey

import java.nio.FloatBuffer

/**
 * The object's dimensions for a single frame, derived from its isolated point cloud.
 *
 * Extents are in metres. [lengthMeters] is always the longer horizontal side and [widthMeters] the
 * shorter one, so the naming is stable no matter how the object is turned relative to world axes.
 * [centerX]/[centerZ] locate the footprint centre on the ground plane; the stabilizer uses them to
 * track which side of the object the camera has observed.
 */
data class ObjectBox(
    val lengthMeters: Float,
    val widthMeters: Float,
    val heightMeters: Float,
    val centerX: Float,
    val centerZ: Float,
    val pointCount: Int,
)

/**
 * Computes a gravity-aligned bounding box for the isolated object.
 *
 * ARCore's world is gravity-aligned (+Y up), so the box we want is axis-aligned in Y and we read the
 * height directly as `highest point − floor`. The horizontal footprint is taken as the axis-aligned
 * min/max spread in world X and Z. That is the simplest footprint that needs no eigen/PCA maths, and
 * it is exact when the object's sides run parallel to the world X/Z axes.
 *
 * Known limitation, deliberately not solved yet: if the object is yawed relative to those axes, the
 * X/Z spread over-reports both horizontal sides (the classic rotated-rectangle problem). The fix is a
 * 2D minimum-area rectangle over the footprint — added only once real measurements show the error
 * matters, per the project's "complexity must earn its place" rule.
 *
 * Pure and allocation-free apart from the returned [ObjectBox]; it reads points by absolute index, so
 * the buffer's position is irrelevant.
 */
object BoundingBox {

    /**
     * @param points compacted object cloud: `x, y, z` triples starting at index 0.
     * @param count number of points (not floats) to read.
     * @param floorY world height of the supporting surface; the box height is measured up from here.
     * @param minPoints reject the frame (return null) below this many points — too little to trust.
     */
    fun compute(
        points: FloatBuffer,
        count: Int,
        floorY: Float,
        minPoints: Int,
    ): ObjectBox? {
        if (count < minPoints) return null

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (i in 0 until count) {
            val base = i * 3
            val x = points.get(base)
            val y = points.get(base + 1)
            val z = points.get(base + 2)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
            if (y > maxY) maxY = y
        }

        val extentX = maxX - minX
        val extentZ = maxZ - minZ
        val height = maxY - floorY

        return ObjectBox(
            lengthMeters = maxOf(extentX, extentZ),
            widthMeters = minOf(extentX, extentZ),
            heightMeters = if (height > 0f) height else 0f,
            centerX = (minX + maxX) * 0.5f,
            centerZ = (minZ + maxZ) * 0.5f,
            pointCount = count,
        )
    }
}
