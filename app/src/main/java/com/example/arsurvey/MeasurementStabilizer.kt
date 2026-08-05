package com.example.arsurvey

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Turns the stream of noisy per-frame boxes into one stable, trustworthy measurement.
 *
 * This is the pipeline's "Temporal Accumulator" and "Measurement Smoothing" stages together. They
 * are kept in one small class on purpose: both are just reducers over the same per-frame box stream,
 * and splitting a handful of scalar fields across two classes would add wiring without adding
 * clarity. It holds only scalar state and allocates nothing per frame (aside from the returned
 * [Measurement]), so it is cheap to call on every processed frame.
 *
 * Three ideas, each matching a line of the spec:
 *  - Smoothing: each dimension is an exponential moving average, so a single jumpy frame nudges the
 *    number instead of replacing it ("prefer stable measurements over instant ones").
 *  - Coverage: the horizon around the object is split into [SECTORS] wedges; we mark the wedge the
 *    camera is currently in, so coverage rises as the surveyor actually walks around the object.
 *    This is the "visible coverage" metric, and it is what makes the box trustworthy — a face never
 *    seen cannot be measured.
 *  - Stability: we only call the result stable once the smoothed dimensions stop moving for several
 *    consecutive updates, which is when we let the UI lock the read-out.
 */
class MeasurementStabilizer {

    private var seeded = false
    private var smoothedLength = 0f
    private var smoothedWidth = 0f
    private var smoothedHeight = 0f

    private var sectorMask = 0
    private var stableStreak = 0

    /** Clears all history; call when tracking is lost or the target changes so stale data cannot leak in. */
    fun reset() {
        seeded = false
        smoothedLength = 0f
        smoothedWidth = 0f
        smoothedHeight = 0f
        sectorMask = 0
        stableStreak = 0
    }

    /**
     * Folds one frame's [box] into the running estimate and returns the current measurement.
     *
     * @param frameConfidencePercent mean depth confidence (0..100) for the frame, used as one input
     *   to the reported confidence level.
     * @param cameraX world X of the camera, for angular coverage.
     * @param cameraZ world Z of the camera, for angular coverage.
     */
    fun update(
        box: ObjectBox,
        frameConfidencePercent: Float,
        cameraX: Float,
        cameraZ: Float,
        nowMs: Long,
    ): Measurement {
        // Record which side of the object the camera is looking from.
        val angle = atan2(cameraZ - box.centerZ, cameraX - box.centerX) // -PI..PI
        val sector = (((angle / (2f * Math.PI.toFloat())) + 0.5f) * SECTORS).toInt()
            .coerceIn(0, SECTORS - 1)
        sectorMask = sectorMask or (1 shl sector)
        val coveragePercent = 100 * Integer.bitCount(sectorMask) / SECTORS

        if (!seeded) {
            smoothedLength = box.lengthMeters
            smoothedWidth = box.widthMeters
            smoothedHeight = box.heightMeters
            seeded = true
            stableStreak = 0
        } else {
            val nextLength = ema(smoothedLength, box.lengthMeters)
            val nextWidth = ema(smoothedWidth, box.widthMeters)
            val nextHeight = ema(smoothedHeight, box.heightMeters)

            // Steady means every dimension barely moved this update.
            val steady = settled(smoothedLength, nextLength) &&
                settled(smoothedWidth, nextWidth) &&
                settled(smoothedHeight, nextHeight)
            stableStreak = if (steady) stableStreak + 1 else 0

            smoothedLength = nextLength
            smoothedWidth = nextWidth
            smoothedHeight = nextHeight
        }

        val stable = stableStreak >= STABLE_TARGET
        return Measurement(
            lengthCm = toCm(smoothedLength),
            widthCm = toCm(smoothedWidth),
            heightCm = toCm(smoothedHeight),
            confidence = confidenceOf(stable, coveragePercent, frameConfidencePercent),
            coveragePercent = coveragePercent,
            pointCount = box.pointCount,
            stable = stable,
            timestampMs = nowMs,
        )
    }

    private fun confidenceOf(stable: Boolean, coverage: Int, frameConfidence: Float): MeasurementConfidence {
        if (!stable) return MeasurementConfidence.LOW
        return when {
            coverage >= HIGH_COVERAGE && frameConfidence >= HIGH_FRAME_CONFIDENCE -> MeasurementConfidence.HIGH
            coverage >= MEDIUM_COVERAGE -> MeasurementConfidence.MEDIUM
            else -> MeasurementConfidence.LOW
        }
    }

    /** True once two successive smoothed values differ by less than the stability threshold. */
    private fun settled(previous: Float, next: Float): Boolean =
        abs(next - previous) < STABLE_DELTA_METERS

    private fun ema(previous: Float, measured: Float): Float =
        SMOOTHING_ALPHA * measured + (1f - SMOOTHING_ALPHA) * previous

    private fun toCm(meters: Float): Int = (meters * 100f + 0.5f).toInt()

    private companion object {
        // Weight given to each new frame. Low enough to reject single-frame noise, high enough to
        // converge within a couple of seconds at ~4 processed frames per second.
        const val SMOOTHING_ALPHA = 0.3f

        // A dimension is "settled" once it moves less than 5 mm between updates.
        const val STABLE_DELTA_METERS = 0.005f

        // Consecutive settled updates required before we call the measurement stable.
        const val STABLE_TARGET = 4

        // Horizon wedges for the coverage metric (30° each).
        const val SECTORS = 12

        const val HIGH_COVERAGE = 66
        const val MEDIUM_COVERAGE = 33
        const val HIGH_FRAME_CONFIDENCE = 40f // mean confidence %, empirically a "clean" depth frame
    }
}
