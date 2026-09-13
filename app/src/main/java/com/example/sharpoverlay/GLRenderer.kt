package com.example.sharpoverlay

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class SharpMode { CAS, RCAS, DYNAMIC }

/**
 * Рендерит входящий SurfaceTexture (кадр экрана от MediaProjection) через
 * выбранный fragment shader резкости и выводит результат на выходной Surface.
 *
 * Всё работает в одном GL-контексте с EXTERNAL_OES текстурой как источником,
 * это самый дешёвый путь без лишних копирований в CPU-память.
 */
class GLRenderer(private val context: Context) {

    var mode: SharpMode = SharpMode.CAS
    var sharpness: Float = 0.5f

    private var programCas = 0
    private var programRcas = 0
    private var programDynamic = 0

    private var oesTextureId = 0
    var surfaceTexture: SurfaceTexture? = null
        private set

    private val vertexBuffer: FloatBuffer
    private val quadCoords = floatArrayOf(
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f
    )

    private var texelWidth = 1
    private var texelHeight = 1

    init {
        val bb = ByteBuffer.allocateDirect(quadCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(quadCoords)
        vertexBuffer.position(0)
    }

    fun setupGL(width: Int, height: Int) {
        texelWidth = width
        texelHeight = height

        val vertexSrc = context.assets.open("shaders/vertex.glsl").bufferedReader().readText()
        val casSrc = context.assets.open("shaders/cas.glsl").bufferedReader().readText()
        val rcasSrc = context.assets.open("shaders/rcas.glsl").bufferedReader().readText()
        val dynSrc = context.assets.open("shaders/dynamic_sharp.glsl").bufferedReader().readText()

        programCas = buildProgram(vertexSrc, casSrc)
        programRcas = buildProgram(vertexSrc, rcasSrc)
        programDynamic = buildProgram(vertexSrc, dynSrc)

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        oesTextureId = texIds[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    /** Вызывать при получении onFrameAvailable, перед drawFrame. */
    fun updateTexImage() {
        surfaceTexture?.updateTexImage()
    }

    fun drawFrame() {
        // Примечание: для простоты используем стандартный fragment shader,
        // рассчитанный на sampler2D. Для реального OES-сэмплинга во fragment
        // shader нужно расширение GL_OES_EGL_image_external — оно подключается
        // в шейдере через `#extension GL_OES_EGL_image_external_essl3 : require`
        // и заменой sampler2D -> samplerExternalOES. См. README по интеграции.

        val program = when (mode) {
            SharpMode.CAS -> programCas
            SharpMode.RCAS -> programRcas
            SharpMode.DYNAMIC -> programDynamic
        }

        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)

        val texelLoc = GLES30.glGetUniformLocation(program, "uTexelSize")
        GLES30.glUniform2f(texelLoc, 1.0f / texelWidth, 1.0f / texelHeight)

        val sharpLoc = GLES30.glGetUniformLocation(program, "uSharpness")
        GLES30.glUniform1f(sharpLoc, sharpness)

        val posLoc = 0
        val texLoc = 1
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(posLoc)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(texLoc)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        // Патчим fragment shader под samplerExternalOES для реального захвата экрана
        val patched = fragmentSrc
            .replaceFirst(
                "#version 300 es",
                "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require"
            )
            .replace("sampler2D uTexture", "samplerExternalOES uTexture")

        val vShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, patched)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vShader)
        GLES30.glAttachShader(program, fShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            throw RuntimeException("Program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log\n---\n$src")
        }
        return shader
    }

    fun release() {
        surfaceTexture?.release()
    }
}
