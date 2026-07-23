package com.example.arsurvey

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the ARCore camera image full-screen, drives the session's per-frame update, and overlays
 * the world-space depth point cloud.
 *
 * Each frame it renders the camera background, hands the frame to [viewModel] (which refreshes the
 * depth stats and, a few times per second, the point cloud), then draws whatever the latest cloud
 * is using the current frame's view-projection. Because the cloud is in world space, drawing a
 * slightly stale cloud with the live camera matrices still keeps the points glued to the scene.
 * The screen is portrait-locked, so display geometry only changes when the surface resizes.
 */
class CameraRenderer(
    private val viewModel: DepthViewModel,
) : GLSurfaceView.Renderer {

    /** Set by the Activity before the surface resumes; portrait lock means it stays constant. */
    @Volatile
    var displayRotation = 0

    private val pointCloudRenderer = PointCloudRenderer()

    private var textureId = -1
    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportDirty = false

    // Full-screen quad in normalized device coordinates and its (per-frame) camera texture coords.
    private val ndcQuad = floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f)
    private val quadBuffer = ndcQuad.toDirectBuffer()
    private val texCoordBuffer = FloatArray(8).toDirectBuffer()
    private val texCoordScratch = FloatArray(8)

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = buildGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")

        pointCloudRenderer.init()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = viewModel.session ?: return
        session.setCameraTextureName(textureId)

        if (viewportDirty) {
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            viewportDirty = false
        }

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            return // Skip this frame; the session recovers on a later update.
        }

        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcQuad,
                Coordinates2d.TEXTURE_NORMALIZED, texCoordScratch,
            )
            texCoordBuffer.apply { position(0); put(texCoordScratch); position(0) }
        }

        drawCameraBackground()

        // Refresh stats + cloud (throttled internally), then draw the cloud with live matrices.
        viewModel.onFrame(frame)

        val camera = frame.camera
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        pointCloudRenderer.draw(
            viewModel.cloudPoints,
            viewModel.cloudPointCount,
            viewModel.cloudVersion,
            viewProjectionMatrix,
        )
    }

    private fun drawCameraBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        fun FloatArray.toDirectBuffer(): FloatBuffer =
            ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(this@toDirectBuffer); position(0) }
    }
}
