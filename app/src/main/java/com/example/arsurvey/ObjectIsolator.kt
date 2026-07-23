package com.example.arsurvey

import java.nio.FloatBuffer

/**
 * Keeps only the points belonging to the object the surveyor is pointing at.
 *
 * A standalone object being surveyed is the closest thing in front of the camera, so we seed from
 * the above-floor point nearest the camera and keep everything within [radius] of it, dropping
 * distant walls and furniture. This is a deliberately blunt spatial gate: no clustering, no
 * connectivity — just a sphere around the seed, which is enough to separate one standalone object.
 *
 * Compacts the cloud in place (write index never overtakes read index) and returns the surviving
 * point count. Allocation-free.
 */
object ObjectIsolator {

    fun isolate(
        points: FloatBuffer,
        count: Int,
        cameraX: Float,
        cameraY: Float,
        cameraZ: Float,
        radius: Float,
    ): Int {
        if (count == 0) return 0

        // Seed: the point closest to the camera.
        var seedIndex = 0
        var bestDistanceSq = Float.MAX_VALUE
        for (i in 0 until count) {
            val base = i * 3
            val dx = points.get(base) - cameraX
            val dy = points.get(base + 1) - cameraY
            val dz = points.get(base + 2) - cameraZ
            val distanceSq = dx * dx + dy * dy + dz * dz
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq
                seedIndex = i
            }
        }

        val seedBase = seedIndex * 3
        val seedX = points.get(seedBase)
        val seedY = points.get(seedBase + 1)
        val seedZ = points.get(seedBase + 2)
        val radiusSq = radius * radius

        var write = 0
        for (i in 0 until count) {
            val base = i * 3
            val x = points.get(base)
            val y = points.get(base + 1)
            val z = points.get(base + 2)
            val dx = x - seedX
            val dy = y - seedY
            val dz = z - seedZ
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                val out = write * 3
                points.put(out, x)
                points.put(out + 1, y)
                points.put(out + 2, z)
                write++
            }
        }

        points.position(0)
        return write
    }
}
