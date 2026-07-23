package com.example.arsurvey

import android.opengl.GLES20
import java.nio.FloatBuffer

/**
 * Draws a world-space point cloud as GL points.
 *
 * The cloud only changes a few times per second, so its vertices are re-uploaded to the GPU only
 * when [version] changes; every frame after that just re-draws the cached buffer with the current
 * view-projection matrix, which keeps the points glued to the world as the camera moves.
 */
class PointCloudRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var pointSizeUniform = 0

    private var vbo = 0
    private var uploadedVersion = -1
    private var uploadedCount = 0

    /** Must be called on the GL thread once a context exists (e.g. from onSurfaceCreated). */
    fun init() {
        program = buildGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        pointSizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
    }

    fun draw(points: FloatBuffer?, pointCount: Int, version: Int, mvpMatrix: FloatArray) {
        if (points == null || pointCount <= 0) return

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        if (version != uploadedVersion) {
            points.position(0)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, pointCount * BYTES_PER_POINT, points, GLES20.GL_DYNAMIC_DRAW)
            uploadedVersion = version
            uploadedCount = pointCount
        }

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorUniform, 0.15f, 1f, 0.35f, 1f)
        GLES20.glUniform1f(pointSizeUniform, 6f)

        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, uploadedCount)
        GLES20.glDisableVertexAttribArray(positionAttrib)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        const val BYTES_PER_POINT = 3 * 4 // x, y, z floats

        const val VERTEX_SHADER = """
            uniform mat4 u_MvpMatrix;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                gl_PointSize = u_PointSize;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
