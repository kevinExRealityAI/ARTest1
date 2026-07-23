package com.example.arsurvey

import android.media.Image

/**
 * Immutable summary of one raw-depth / confidence image pair.
 *
 * A "valid" pixel is one whose confidence byte is non-zero. ARCore documents a confidence of 0 as
 * "no valid depth estimate", so confidence is the single, unambiguous validity signal — we do not
 * try to reinterpret the raw 16-bit depth encoding here.
 */
data class DepthStats(
    val depthWidth: Int,
    val depthHeight: Int,
    val confidenceWidth: Int,
    val confidenceHeight: Int,
    val validPixels: Int,
    val totalPixels: Int,
    /** Mean confidence (0..255) over valid pixels; 0 when there are none. */
    val averageConfidence: Float,
    val minDepthMm: Int,
    val maxDepthMm: Int,
) {
    val validPercent: Float
        get() = if (totalPixels == 0) 0f else 100f * validPixels / totalPixels

    val averageConfidencePercent: Float
        get() = 100f * averageConfidence / 255f

    companion object {
        /**
         * Scans a raw depth (16-bit) and confidence (8-bit) image of matching resolution.
         *
         * Runs on the GL thread. It only reads from the supplied buffers and allocates nothing
         * beyond the returned [DepthStats], so it is safe to call every processed frame. The caller
         * owns closing both [Image]s.
         */
        fun fromImages(depth: Image, confidence: Image): DepthStats {
            val width = depth.width
            val height = depth.height

            val depthPlane = depth.planes[0]
            val depthBuffer = depthPlane.buffer
            val depthRowStride = depthPlane.rowStride
            val depthPixelStride = depthPlane.pixelStride

            val confPlane = confidence.planes[0]
            val confBuffer = confPlane.buffer
            val confRowStride = confPlane.rowStride
            val confPixelStride = confPlane.pixelStride

            var valid = 0
            var confidenceSum = 0L
            var minMm = Int.MAX_VALUE
            var maxMm = 0

            for (y in 0 until height) {
                val depthRow = y * depthRowStride
                val confRow = y * confRowStride
                for (x in 0 until width) {
                    val c = confBuffer.get(confRow + x * confPixelStride).toInt() and 0xFF
                    if (c == 0) continue

                    val di = depthRow + x * depthPixelStride
                    val low = depthBuffer.get(di).toInt() and 0xFF
                    val high = depthBuffer.get(di + 1).toInt() and 0xFF
                    val mm = (high shl 8) or low // raw depth is little-endian uint16 millimetres
                    if (mm == 0) continue

                    valid++
                    confidenceSum += c
                    if (mm < minMm) minMm = mm
                    if (mm > maxMm) maxMm = mm
                }
            }

            return DepthStats(
                depthWidth = width,
                depthHeight = height,
                confidenceWidth = confidence.width,
                confidenceHeight = confidence.height,
                validPixels = valid,
                totalPixels = width * height,
                averageConfidence = if (valid == 0) 0f else confidenceSum.toFloat() / valid,
                minDepthMm = if (valid == 0) 0 else minMm,
                maxDepthMm = maxMm,
            )
        }
    }
}
