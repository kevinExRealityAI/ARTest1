package com.example.arsurvey

import android.opengl.GLES20

/** Compiles and links a GLES 2.0 program. A failure here is a developer error, so it throws. */
fun buildGlProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileGlShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileGlShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertex)
    GLES20.glAttachShader(program, fragment)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
    GLES20.glDeleteShader(vertex)
    GLES20.glDeleteShader(fragment)
    return program
}

private fun compileGlShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] != 0) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
    return shader
}
