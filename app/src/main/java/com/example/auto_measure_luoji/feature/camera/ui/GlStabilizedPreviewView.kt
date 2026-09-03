package com.example.auto_measure_luoji.feature.camera.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * 实验用 OpenGL 相机预览 View。
 *
 * Camera2 仍然只看到一个 SurfaceTexture；这里把相机帧作为 OES 外部纹理渲染到屏幕，
 * 再在 GPU 顶点矩阵上叠加自研 EIS 的逐帧缩放、平移和 Roll 旋转。
 */
class GlStabilizedPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewRenderer = CameraPreviewRenderer()
    private var cameraSurfaceTexture: SurfaceTexture? = null

    var onCameraSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null
        set(value) {
            field = value
            val texture = cameraSurfaceTexture ?: return
            mainHandler.post { value?.invoke(texture) }
        }

    var onCameraSurfaceTextureDestroyed: ((SurfaceTexture) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        previewRenderer.onSurfaceTextureCreated = { texture ->
            cameraSurfaceTexture = texture
            texture.setOnFrameAvailableListener {
                requestRender()
            }
            Log.i(TAG, "gl-preview: camera texture ready")
            mainHandler.post {
                onCameraSurfaceTextureAvailable?.invoke(texture)
            }
        }
        previewRenderer.onSurfaceTextureReleased = { texture ->
            if (cameraSurfaceTexture === texture) {
                cameraSurfaceTexture = null
            }
            Log.i(TAG, "gl-preview: camera texture released")
            mainHandler.post {
                onCameraSurfaceTextureDestroyed?.invoke(texture)
            }
        }
        setRenderer(previewRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setPreviewTransform(
        scale: Float,
        translationX: Float,
        translationY: Float,
        rotationDegrees: Float,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        queueEvent {
            previewRenderer.setPreviewTransform(
                scale = scale,
                translationX = translationX,
                translationY = translationY,
                rotationDegrees = rotationDegrees,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
            )
        }
        requestRender()
    }

    fun resetPreviewTransform() {
        setPreviewTransform(
            scale = 1f,
            translationX = 0f,
            translationY = 0f,
            rotationDegrees = 0f,
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
        )
    }

    fun releaseCameraTexture() {
        cameraSurfaceTexture ?: return
        cameraSurfaceTexture = null
        queueEvent {
            previewRenderer.releaseCameraTexture()
        }
    }

    override fun onDetachedFromWindow() {
        releaseCameraTexture()
        super.onDetachedFromWindow()
    }

    private class CameraPreviewRenderer : Renderer {
        var onSurfaceTextureCreated: ((SurfaceTexture) -> Unit)? = null
        var onSurfaceTextureReleased: ((SurfaceTexture) -> Unit)? = null

        private val vertexBuffer = floatBufferOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        // SurfaceTexture.getTransformMatrix() 已包含相机缓冲区到 OES 纹理的方向变换；
        // 这里保持标准坐标，避免先手动翻转再被 SurfaceTexture 矩阵二次叠加。
        private val texCoordBuffer = floatBufferOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )
        private val surfaceTextureMatrix = FloatArray(16)
        private val displayMatrix = FloatArray(16)
        private val transformLock = Any()

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var textureMatrixHandle = 0
        private var displayMatrixHandle = 0
        private var textureSamplerHandle = 0
        private var oesTextureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var transform = PreviewTransform()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
            displayMatrixHandle = GLES20.glGetUniformLocation(program, "uDisplayMatrix")
            textureSamplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
            oesTextureId = createOesTexture()
            surfaceTexture = SurfaceTexture(oesTextureId).also { texture ->
                onSurfaceTextureCreated?.invoke(texture)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val texture = surfaceTexture ?: return
            try {
                texture.updateTexImage()
                texture.getTransformMatrix(surfaceTextureMatrix)
            } catch (e: RuntimeException) {
                Log.w(TAG, "gl-preview: updateTexImage failed: ${e.message}")
                return
            }

            val currentTransform = synchronized(transformLock) { transform }
            buildDisplayMatrix(currentTransform, displayMatrix)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(textureSamplerHandle, 0)
            GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, surfaceTextureMatrix, 0)
            GLES20.glUniformMatrix4fv(displayMatrixHandle, 1, false, displayMatrix, 0)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                FLOAT_STRIDE_BYTES,
                vertexBuffer,
            )

            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(
                texCoordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                FLOAT_STRIDE_BYTES,
                texCoordBuffer,
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        fun setPreviewTransform(
            scale: Float,
            translationX: Float,
            translationY: Float,
            rotationDegrees: Float,
            viewWidth: Float,
            viewHeight: Float,
        ) {
            synchronized(transformLock) {
                transform = PreviewTransform(
                    scale = scale.coerceAtLeast(1f),
                    translationX = translationX,
                    translationY = translationY,
                    rotationDegrees = rotationDegrees,
                    viewWidth = viewWidth.coerceAtLeast(1f),
                    viewHeight = viewHeight.coerceAtLeast(1f),
                )
            }
        }

        fun releaseCameraTexture() {
            val texture = surfaceTexture ?: return
            surfaceTexture = null
            try {
                texture.release()
            } catch (_: Exception) {
            }
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
            onSurfaceTextureReleased?.invoke(texture)
        }

        private fun buildDisplayMatrix(source: PreviewTransform, out: FloatArray) {
            Matrix.setIdentityM(out, 0)
            val txNdc = 2f * source.translationX / source.viewWidth
            val tyNdc = -2f * source.translationY / source.viewHeight
            Matrix.translateM(out, 0, txNdc, tyNdc, 0f)

            // Android View 的正角度在屏幕坐标系里表现为顺时针；OpenGL NDC 的 Y 轴方向相反。
            val glRotationDegrees = -source.rotationDegrees
            val radians = Math.toRadians(glRotationDegrees.toDouble())
            val cosValue = cos(radians).toFloat()
            val sinValue = sin(radians).toFloat()
            out[0] = source.scale * cosValue
            out[1] = source.scale * sinValue
            out[4] = -source.scale * sinValue
            out[5] = source.scale * cosValue
            out[12] = txNdc
            out[13] = tyNdc
        }

        private fun createOesTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR.toFloat(),
            )
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR.toFloat(),
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            return textureId
        }

        private fun createProgram(vertexShader: String, fragmentShader: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
            val linkedProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(linkedProgram, vertex)
            GLES20.glAttachShader(linkedProgram, fragment)
            GLES20.glLinkProgram(linkedProgram)
            val status = IntArray(1)
            GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(linkedProgram)
                GLES20.glDeleteProgram(linkedProgram)
                throw IllegalStateException("GL program link failed: $log")
            }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return linkedProgram
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
                throw IllegalStateException("GL shader compile failed: $log")
            }
            return shader
        }

        private data class PreviewTransform(
            val scale: Float = 1f,
            val translationX: Float = 0f,
            val translationY: Float = 0f,
            val rotationDegrees: Float = 0f,
            val viewWidth: Float = 1f,
            val viewHeight: Float = 1f,
        )
    }

    private companion object {
        private const val TAG = "GlPreview"
        private const val FLOAT_STRIDE_BYTES = 2 * 4
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            uniform mat4 uDisplayMatrix;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = uDisplayMatrix * aPosition;
                vTexCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private fun floatBufferOf(vararg values: Float): FloatBuffer {
            return ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
        }
    }
}
