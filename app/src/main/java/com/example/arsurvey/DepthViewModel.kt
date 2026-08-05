package com.example.arsurvey

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class DepthStatus {
    INITIALIZING,
    DEPTH_NOT_SUPPORTED,
    TRACKING_PAUSED,
    DEPTH_WARMING_UP,
    OK,
    ERROR,
}

data class DepthUiState(
    val status: DepthStatus,
    val message: String = "",
    val stats: DepthStats? = null,
    val pointCount: Int = 0,
    val floorFound: Boolean = false,
    val measurement: Measurement? = null,
)

/**
 * Owns the ARCore [Session] and turns each frame's raw depth into a [DepthUiState].
 *
 * Depth processing is throttled to [PROCESS_INTERVAL_MS] because a fresh reading a few times per
 * second is plenty for a debug read-out, and it keeps the render loop smooth. Every acquired
 * [android.media.Image] is closed via `use { }`.
 */
class DepthViewModel : ViewModel() {

    var session: Session? = null
        private set

    private var depthSupported = false
    private var lastProcessMs = 0L

    // Latest world-space point cloud, produced on the GL thread and read by the renderer on the
    // same thread. Regenerated at most every PROCESS_INTERVAL_MS; [cloudVersion] bumps each time so
    // the renderer knows when to re-upload the vertices.
    private var cloudBuffer: FloatBuffer? = null
    val cloudPoints: FloatBuffer? get() = cloudBuffer
    var cloudPointCount = 0
        private set
    var cloudVersion = 0
        private set
    private val cameraPoseMatrix = FloatArray(16)

    // Accumulates per-frame boxes into one stable measurement (temporal accumulator + smoothing).
    private val stabilizer = MeasurementStabilizer()

    private val _uiState = MutableStateFlow(
        DepthUiState(DepthStatus.INITIALIZING, "Starting AR session…")
    )
    val uiState: StateFlow<DepthUiState> = _uiState.asStateFlow()

    /**
     * Creates and configures the session. Call only after camera permission is granted and ARCore
     * is confirmed installed. Safe to call repeatedly; it is a no-op once a session exists.
     */
    fun ensureSession(context: Context) {
        if (session != null) return

        val newSession = Session(context)
        depthSupported = newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

        val config = Config(newSession).apply {
            depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL // only need the floor
        }
        newSession.configure(config)
        session = newSession

        _uiState.value = if (depthSupported) {
            DepthUiState(DepthStatus.DEPTH_WARMING_UP, "Depth supported. Waiting for the first depth image…")
        } else {
            DepthUiState(DepthStatus.DEPTH_NOT_SUPPORTED, "This device does not support the ARCore Depth API.")
        }
    }

    fun resume() = session?.resume()

    fun pause() = session?.pause()

    /**
     * Called on the GL thread once per rendered frame. Cheap and re-entrant: it bails out quickly
     * when depth is unsupported, tracking is paused, or the throttle interval has not elapsed.
     */
    fun onFrame(frame: Frame) {
        if (!depthSupported) return

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            // Pose is unreliable while tracking is lost, so any accumulated box would be corrupted.
            stabilizer.reset()
            _uiState.value = DepthUiState(DepthStatus.TRACKING_PAUSED, "Move the phone slowly to start tracking…")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessMs < PROCESS_INTERVAL_MS) return
        lastProcessMs = now

        try {
            frame.acquireRawDepthImage16Bits().use { depth ->
                frame.acquireRawDepthConfidenceImage().use { confidence ->
                    val stats = DepthStats.fromImages(depth, confidence)

                    val floorY = findFloorY()

                    val buffer = ensureCloudBuffer(depth.width * depth.height)
                    frame.camera.pose.toMatrix(cameraPoseMatrix, 0)
                    val aboveFloorCount = DepthPointCloud.backproject(
                        depth = depth,
                        confidence = confidence,
                        intrinsics = frame.camera.textureIntrinsics,
                        cameraPoseMatrix = cameraPoseMatrix,
                        out = buffer,
                        minConfidence = MIN_CONFIDENCE,
                        minMeters = MIN_METERS,
                        maxMeters = MAX_METERS,
                        floorY = floorY ?: Float.NEGATIVE_INFINITY,
                        minHeightAboveFloor = MIN_HEIGHT_ABOVE_FLOOR,
                    )

                    // Camera position is the translation column of its pose matrix.
                    cloudPointCount = ObjectIsolator.isolate(
                        points = buffer,
                        count = aboveFloorCount,
                        cameraX = cameraPoseMatrix[12],
                        cameraY = cameraPoseMatrix[13],
                        cameraZ = cameraPoseMatrix[14],
                        radius = OBJECT_RADIUS,
                    )
                    cloudVersion++

                    // Measure only once the floor is known: without it we cannot separate the object
                    // from the ground, so height and footprint would both be meaningless.
                    var measurement: Measurement? = null
                    val message: String
                    if (floorY != null) {
                        val box = BoundingBox.compute(buffer, cloudPointCount, floorY, MIN_BOX_POINTS)
                        if (box != null) {
                            measurement = stabilizer.update(
                                box = box,
                                frameConfidencePercent = stats.averageConfidencePercent,
                                cameraX = cameraPoseMatrix[12],
                                cameraZ = cameraPoseMatrix[14],
                                nowMs = System.currentTimeMillis(),
                            )
                            message = if (measurement.stable) "Measurement stable." else "Measuring… move around the object."
                        } else {
                            message = "Point at the object — too few depth points yet."
                        }
                    } else {
                        stabilizer.reset()
                        message = "Searching for the floor… aim at the ground near the object."
                    }

                    _uiState.value = DepthUiState(
                        DepthStatus.OK, message, stats, cloudPointCount,
                        floorFound = floorY != null, measurement = measurement,
                    )
                }
            }
        } catch (e: NotYetAvailableException) {
            _uiState.value = DepthUiState(DepthStatus.DEPTH_WARMING_UP, "Depth not ready for this frame yet…")
        } catch (e: Exception) {
            _uiState.value = DepthUiState(DepthStatus.ERROR, "Depth error: ${e.javaClass.simpleName}")
        }
    }

    override fun onCleared() {
        session?.close()
        session = null
    }

    /**
     * World-Y of the supporting surface: the lowest horizontal, upward-facing plane ARCore is
     * actively tracking. Returns null until such a plane exists. Because ARCore's world is
     * gravity-aligned, that plane's height is all we need to separate the object from the ground.
     */
    private fun findFloorY(): Float? {
        val currentSession = session ?: return null
        var lowestY: Float? = null
        for (plane in currentSession.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            if (plane.subsumedBy != null) continue // superseded by a merged plane
            val y = plane.centerPose.ty()
            if (lowestY == null || y < lowestY) lowestY = y
        }
        return lowestY
    }

    /** Allocates (once) a direct buffer large enough for [pointCapacity] world points. */
    private fun ensureCloudBuffer(pointCapacity: Int): FloatBuffer {
        val existing = cloudBuffer
        if (existing != null && existing.capacity() >= pointCapacity * 3) return existing
        return ByteBuffer.allocateDirect(pointCapacity * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { cloudBuffer = it }
    }

    private companion object {
        const val PROCESS_INTERVAL_MS = 250L

        // Keep only confident, plausibly-close returns; this drops far-field depth noise.
        const val MIN_CONFIDENCE = 1 // 0..255; 0 means "no depth", so require at least 1
        const val MIN_METERS = 0.15f
        const val MAX_METERS = 5.0f
        const val MIN_HEIGHT_ABOVE_FLOOR = 0.03f // 3 cm, to drop the floor itself and its noise
        const val OBJECT_RADIUS = 0.6f // metres kept around the nearest point (the target object)
        const val MIN_BOX_POINTS = 40 // below this the object is too sparsely seen to measure
    }
}
