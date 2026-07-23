package com.example.arsurvey

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.launch

/**
 * Single AR screen: renders the camera and shows live Depth API statistics.
 *
 * The Activity owns everything that needs an [android.app.Activity] context — camera permission and
 * the ARCore install flow — then hands session ownership to [DepthViewModel]. It never touches the
 * ARCore frame directly; the renderer feeds frames to the view model.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: DepthViewModel by viewModels()

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var overlay: TextView
    private lateinit var renderer: CameraRenderer

    private var arcoreInstallRequested = false

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) overlay.text = getString(R.string.camera_denied)
        // If granted, onResume runs next and continues setup.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface_view)
        overlay = findViewById(R.id.overlay_text)

        renderer = CameraRenderer(viewModel)
        surfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { overlay.text = format(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCamera.launch(Manifest.permission.CAMERA)
            return
        }

        try {
            when (ArCoreApk.getInstance().requestInstall(this, !arcoreInstallRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    arcoreInstallRequested = true
                    return // Returns to this Activity once the user finishes the install prompt.
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            viewModel.ensureSession(this)
            viewModel.resume()
        } catch (e: UnavailableException) {
            overlay.text = "ARCore unavailable: ${e.javaClass.simpleName}"
            return
        } catch (e: CameraNotAvailableException) {
            overlay.text = "Camera not available. Close other camera apps and retry."
            return
        }

        renderer.displayRotation = currentRotation()
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        viewModel.pause()
    }

    private fun currentRotation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return rotation ?: Surface.ROTATION_0
    }

    private fun format(state: DepthUiState): String {
        val header = "[${state.status}] ${state.message}"
        val stats = state.stats ?: return header
        return buildString {
            appendLine(header)
            appendLine("depth image     : ${stats.depthWidth} x ${stats.depthHeight}")
            appendLine("confidence image: ${stats.confidenceWidth} x ${stats.confidenceHeight}")
            appendLine(
                "valid pixels    : ${stats.validPixels} / ${stats.totalPixels}" +
                    " (${"%.1f".format(stats.validPercent)}%)"
            )
            appendLine(
                "avg confidence  : ${"%.0f".format(stats.averageConfidence)} / 255" +
                    " (${"%.1f".format(stats.averageConfidencePercent)}%)"
            )
            appendLine("depth range     : ${stats.minDepthMm}–${stats.maxDepthMm} mm")
            appendLine("floor           : ${if (state.floorFound) "found" else "searching…"}")
            append("object points 3D: ${state.pointCount}")
        }
    }
}
