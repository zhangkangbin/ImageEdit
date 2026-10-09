package com.kang.imageeditapp.render

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.kang.imageeditapp.model.EditRecipe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/** EGL pbuffer context plus bounded framebuffer, owned by ImageRenderEngine's worker thread. */
internal class HeadlessGlRenderer : AutoCloseable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var framebuffer = 0
    private var sourceTexture = 0
    private var curveTexture = 0
    private var targetTexture = 0
    private var allocatedWidth = 0
    private var allocatedHeight = 0
    private var positionLocation = 0
    private var textureCoordinateLocation = 0
    private var adjustment1Location = 0
    private var adjustment2Location = 0
    private var hslLocation = 0
    private var readback = ByteBuffer.allocateDirect(0)
    private var colors = IntArray(0)
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    val textureLimit: Int
    val tileLimit: Int

    init {
        try {
            createContext()
            val limit = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
            textureLimit = limit[0]
            GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, limit, 0)
            // Reserve space for interpolation borders in the decoded input texture.
            tileLimit = min(1024, min(textureLimit / 2, limit[0])).coerceAtLeast(1)
            createProgram()
            val ids = IntArray(3)
            GLES20.glGenTextures(3, ids, 0)
            sourceTexture = ids[0]
            curveTexture = ids[1]
            targetTexture = ids[2]
            val framebuffers = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffers, 0)
            framebuffer = framebuffers[0]
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            checkGl("初始化图片处理器")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun createContext() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "设备未提供图片处理所需的 OpenGL 显示" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "无法初始化图片处理器" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "设备不支持图片编辑所需的 OpenGL ES 2.0"
        }
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "无法创建图片处理上下文" }
        surface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "无法创建图片处理画布" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "无法启用图片处理器" }
    }

    private fun createProgram() {
        val highPrecisionRange = IntArray(2)
        val precision = IntArray(1)
        GLES20.glGetShaderPrecisionFormat(
            GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT,
            highPrecisionRange, 0, precision, 0,
        )
        val fragmentSource = if (precision[0] == 0) {
            ColorShader.FRAGMENT.replace("precision highp float;", "precision mediump float;")
        } else ColorShader.FRAGMENT
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, ColorShader.VERTEX)
        val fragmentShader = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        } catch (error: Throwable) {
            GLES20.glDeleteShader(vertexShader)
            throw error
        }
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "无法连接图片处理程序：${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glUseProgram(program)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
        adjustment1Location = GLES20.glGetUniformLocation(program, "uAdjust1")
        adjustment2Location = GLES20.glGetUniformLocation(program, "uAdjust2")
        hslLocation = GLES20.glGetUniformLocation(program, "uHsl[0]")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uSource"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCurves"), 1)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("设备无法编译图片处理程序：$log")
        }
        return shader
    }

    fun setRecipe(recipe: EditRecipe) {
        GLES20.glUseProgram(program)
        val adjustment = recipe.adjustments
        GLES20.glUniform4f(
            adjustment1Location, adjustment.exposure.coerceIn(-2f, 2f),
            adjustment.brightness.coerceIn(-1f, 1f), adjustment.contrast.coerceIn(-1f, 1f),
            adjustment.saturation.coerceIn(-1f, 1f),
        )
        GLES20.glUniform4f(
            adjustment2Location, adjustment.temperature.coerceIn(-1f, 1f),
            adjustment.tint.coerceIn(-1f, 1f), adjustment.shadows.coerceIn(-1f, 1f),
            adjustment.highlights.coerceIn(-1f, 1f),
        )
        val hsl = FloatArray(24)
        recipe.hsl.take(8).forEachIndexed { index, value ->
            hsl[index * 3] = value.hue.coerceIn(-1f, 1f)
            hsl[index * 3 + 1] = value.saturation.coerceIn(-1f, 1f)
            hsl[index * 3 + 2] = value.lightness.coerceIn(-1f, 1f)
        }
        GLES20.glUniform3fv(hslLocation, 8, hsl, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        configureTexture(curveTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, CurveLut.SIZE, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, CurveLut.create(recipe.curves),
        )
        checkGl("设置调色参数")
    }

    fun uploadSource(bitmap: Bitmap) {
        check(bitmap.width <= textureLimit && bitmap.height <= textureLimit) { "图片分块超出设备纹理限制" }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        configureTexture(sourceTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        checkGl("读取图片分块")
    }

    /** Coordinates: top-left, bottom-left, top-right, bottom-right in decoded texture space. */
    fun renderTile(
        output: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        textureCoordinates: FloatArray,
    ) {
        ensureTarget(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTexture)
        vertices.clear()
        val positions = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f)
        for (index in 0..3) {
            vertices.put(positions[index * 2])
            vertices.put(positions[index * 2 + 1])
            vertices.put(textureCoordinates[index * 2])
            vertices.put(textureCoordinates[index * 2 + 1])
        }
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
        GLES20.glVertexAttribPointer(textureCoordinateLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        readback.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
        checkGl("渲染图片分块")
        for (y in 0 until height) {
            val sourceRow = (height - 1 - y) * width * 4
            for (x in 0 until width) {
                val index = sourceRow + x * 4
                val red = readback.get(index).toInt() and 255
                val green = readback.get(index + 1).toInt() and 255
                val blue = readback.get(index + 2).toInt() and 255
                val alpha = readback.get(index + 3).toInt() and 255
                colors[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        output.setPixels(colors, 0, width, left, top, width, height)
    }

    private fun configureTexture(texture: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun ensureTarget(width: Int, height: Int) {
        if (width == allocatedWidth && height == allocatedHeight) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        configureTexture(targetTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targetTexture, 0,
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "无法创建图片分块画布"
        }
        allocatedWidth = width
        allocatedHeight = height
        val pixels = width * height
        if (readback.capacity() < pixels * 4) readback = ByteBuffer.allocateDirect(pixels * 4)
        if (colors.size < pixels) colors = IntArray(pixels)
    }

    private fun checkGl(action: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$action 失败（OpenGL 错误 $error）" }
    }

    override fun close() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, surface, surface, context)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(3, intArrayOf(sourceTexture, curveTexture, targetTexture), 0)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        readback = ByteBuffer.allocateDirect(0)
        colors = IntArray(0)
    }
}
