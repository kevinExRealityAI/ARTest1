package com.example.arsurvey

import android.media.Image
import android.opengl.Matrix
import com.google.ar.core.CameraIntrinsics
import java.nio.FloatBuffer

/**
 * Back-projects a raw depth image into world-space 3D points.
 *
 * For each pixel we recover the camera-space ray from the intrinsics, scale it by the measured
 * depth, then transform by the camera pose to get a world point. Camera space follows ARCore's
 * convention (+X right, +Y up, −Z forward), so a point in front of the camera has negative Z.
 *
 * Pure and allocation-light: it only reads the images and writes into the caller-owned [out]
 * buffer (two tiny scratch arrays aside), so it is safe to call every processed frame.
 */
object DepthPointCloud {

    /**
     * Writes `x, y, z` world coordinates (metres) for each accepted pixel into [out] starting at
     * position 0, and returns the number of points written. [out] must hold at least
     * `depth.width * depth.height * 3` floats.
     */
    fun backproject(
        depth: Image,
        confidence: Image,
        intrinsics: CameraIntrinsics,
        cameraPoseMatrix: FloatArray,
        out: FloatBuffer,
        minConfidence: Int,
        minMeters: Float,
        maxMeters: Float,
        floorY: Float,
        minHeightAboveFloor: Float,
    ): Int {
        // ARCore's world +Y is gravity-aligned, so keeping points whose world Y is above the floor
        // height drops the ground. When no floor is known, callers pass -inf so nothing is dropped.
        val minWorldY = floorY + minHeightAboveFloor
        val width = depth.width
        val height = depth.height

        // Intrinsics are given for the full sensor image; scale them to the depth resolution.
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        val sensor = intrinsics.imageDimensions
        val fx = focal[0] * width / sensor[0]
        val fy = focal[1] * height / sensor[1]
        val cx = principal[0] * width / sensor[0]
        val cy = principal[1] * height / sensor[1]

        val depthPlane = depth.planes[0]
        val depthBuffer = depthPlane.buffer
        val depthRowStride = depthPlane.rowStride
        val depthPixelStride = depthPlane.pixelStride

        val confPlane = confidence.planes[0]
        val confBuffer = confPlane.buffer
        val confRowStride = confPlane.rowStride
        val confPixelStride = confPlane.pixelStride

        val cameraPoint = FloatArray(4)
        val worldPoint = FloatArray(4)

        out.clear()
        var count = 0

        for (y in 0 until height) {
            val depthRow = y * depthRowStride
            val confRow = y * confRowStride
            for (x in 0 until width) {
                val c = confBuffer.get(confRow + x * confPixelStride).toInt() and 0xFF
                if (c < minConfidence) continue

                val di = depthRow + x * depthPixelStride
                val low = depthBuffer.get(di).toInt() and 0xFF
                val high = depthBuffer.get(di + 1).toInt() and 0xFF
                val mm = (high shl 8) or low
                if (mm == 0) continue

                val meters = mm / 1000f
                if (meters < minMeters || meters > maxMeters) continue

                cameraPoint[0] = meters * (x - cx) / fx
                cameraPoint[1] = meters * (cy - y) / fy // image y is down, camera y is up
                cameraPoint[2] = -meters
                cameraPoint[3] = 1f
                Matrix.multiplyMV(worldPoint, 0, cameraPoseMatrix, 0, cameraPoint, 0)

                if (worldPoint[1] < minWorldY) continue // on or below the floor

                out.put(worldPoint[0])
                out.put(worldPoint[1])
                out.put(worldPoint[2])
                count++
            }
        }

        out.position(0)
        return count
    }
}
