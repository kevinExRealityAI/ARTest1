package com.example.arsurvey

/** How much to trust the current measurement, derived from coverage, stability and point count. */
enum class MeasurementConfidence { LOW, MEDIUM, HIGH }

/**
 * A published object measurement: the three dimensions plus the quality metrics the spec asks every
 * measurement to carry (confidence, visible coverage, timestamp).
 *
 * Dimensions are whole centimetres — the depth data does not justify sub-centimetre precision, and
 * rounding keeps the read-out from flickering on the last digit. [stable] is false while the numbers
 * are still settling and true once they have held steady; the UI locks the read-out on true.
 */
data class Measurement(
    val lengthCm: Int,
    val widthCm: Int,
    val heightCm: Int,
    val confidence: MeasurementConfidence,
    val coveragePercent: Int,
    val pointCount: Int,
    val stable: Boolean,
    val timestampMs: Long,
)
