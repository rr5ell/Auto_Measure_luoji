package com.example.auto_measure_luoji.feature.camera.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLUtils
import com.example.auto_measure_luoji.feature.camera.domain.gldetection.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import android.hardware.DataSpace
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import com.example.auto_measure_luoji.feature.camera.domain.FrameTransformQueue
import com.example.auto_measure_luoji.feature.camera.domain.previewfps.PreviewFrameRate
import com.example.auto_measure_luoji.feature.camera.domain.GyroPreviewStabilizer
import com.example.auto_measure_luoji.feature.camera.domain.PreviewMotionEstimator
import com.example.auto_measure_luoji.feature.camera.domain.RenderedPreviewFrame
import com.example.auto_measure_luoji.feature.camera.ui.hdr.HlgPreviewGl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * 实验用 OpenGL 相机预览 View。
 *
 * Camera2 仍然只看到一个 SurfaceTexture；这里把相机帧作为 OES 外部纹理渲染到屏幕，
 * 再在 GPU 顶点矩阵上叠加自研 EIS 的逐帧缩放、平移和 Roll 旋转。
 */
class GlStabilizedPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val hdrEnabled: Boolean = false,
) : GLSurfaceView(context, attrs) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val previewFrameRate = PreviewFrameRate()
    private val previewRenderer = CameraPreviewRenderer(hdrEnabled, previewFrameRate)
    private val diagnosticRecordingRequested = AtomicBoolean(false)
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private val detectionIds = AtomicLong()

    fun setDetectionEnabled(enabled: Boolean) {
        queueEvent { previewRenderer.setDetectionEnabled(enabled) }
        requestRender()
    }

    /** 注册异步结果来源，不要求该 YUV 帧被 SurfaceTexture 消费过。 */
    suspend fun acquireDetectionFrame(timestampNs: Long, width: Int, height: Int, rotation: Int,
        capturedElapsedNs: Long = SystemClock.elapsedRealtimeNanos()): GlDetectionTicket? {
        if (timestampNs <= 0L || width <= 0 || height <= 0 ||
            rotation !in listOf(0, 90, 180, 270)) return null
        val ticket = GlDetectionTicket(detectionIds.incrementAndGet(), timestampNs, width, height, rotation)
        var acquired = false
        try {
            val ready = CompletableDeferred<Boolean>()
            queueEvent { previewRenderer.requestDetection(ticket, capturedElapsedNs, ready) }
            requestRender()
            acquired = withTimeoutOrNull(100L) { ready.await() } == true
            return ticket.takeIf { acquired }
        } finally {
            if (!acquired) cancelDetectionFrame(ticket)
        }
    }

    suspend fun presentDetection(ticket: GlDetectionTicket, boxes: List<GlDetectionBox>): GlDetectionPresentation? {
        val shown = CompletableDeferred<GlDetectionPresentation?>()
        queueEvent { previewRenderer.completeDetection(ticket, boxes.take(100).toList(), shown) }
        requestRender()
        return try {
            withTimeoutOrNull(1000L) { shown.await() }?.also { result ->
                // 即使相机停止出帧，也安排一次重绘清掉过期框；不阻塞 GL 线程。
                val delayMs = ((result.expiresElapsedNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000L + 1L).coerceAtLeast(1L)
                mainHandler.postDelayed({ requestRender() }, delayMs)
            }
        } finally {
            cancelDetectionFrame(ticket)
        }
    }

    fun cancelDetectionFrame(ticket: GlDetectionTicket) {
        queueEvent { previewRenderer.cancelDetection(ticket) }
        requestRender()
    }

    var onCameraSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null
        set(value) {
            field = value
            val texture = cameraSurfaceTexture ?: return
            mainHandler.post { value?.invoke(texture) }
        }

    var onCameraSurfaceTextureDestroyed: ((SurfaceTexture) -> Unit)? = null
    @Volatile var onDiagnosticFrame: ((RenderedPreviewFrame) -> Boolean)? = null
    @Volatile var canAcceptDiagnosticFrame: (() -> Boolean)? = null
    @Volatile var onPreviewError: ((String) -> Unit)? = null

    init {
        if (hdrEnabled) HlgPreviewGl.configure(this, ::reportPreviewError)
        else setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        previewRenderer.onFailure = ::reportPreviewError
        previewRenderer.onSurfaceTextureCreated = { texture ->
            cameraSurfaceTexture = texture
            texture.setOnFrameAvailableListener {
                previewRenderer.frameAvailable.set(true)
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
        previewRenderer.diagnosticConsumer = { diagnosticRecordingRequested.get() && onDiagnosticFrame?.invoke(it) == true }
        previewRenderer.diagnosticReady = { diagnosticRecordingRequested.get() && canAcceptDiagnosticFrame?.invoke() == true }
    }

    private fun reportPreviewError(message: String) {
        Log.e(TAG, "hdr-preview: $message")
        mainHandler.post { onPreviewError?.invoke(message) }
    }

    fun setFrameSynchronized(enabled: Boolean) {
        queueEvent { previewRenderer.setFrameSynchronized(enabled) }
        requestRender()
    }

    fun setDiagnosticRecording(enabled: Boolean) {
        diagnosticRecordingRequested.set(enabled)
        queueEvent { previewRenderer.diagnosticsEnabled = enabled }
    }

    fun setPreviewTransform(
        scale: Float,
        translationX: Float,
        translationY: Float,
        rotationDegrees: Float,
        viewWidth: Float,
        viewHeight: Float,
        sourceTimestampNs: Long = 0L,
        gyroTransform: GyroPreviewStabilizer.Transform = GyroPreviewStabilizer.Transform.Identity,
        rawTranslationX: Float = 0f,
        rawTranslationY: Float = 0f,
    ) {
        queueEvent {
            previewRenderer.setPreviewTransform(
                scale = scale,
                translationX = translationX,
                translationY = translationY,
                rotationDegrees = rotationDegrees,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                sourceTimestampNs = sourceTimestampNs,
                gyroTransform = gyroTransform,
                rawTranslationX = rawTranslationX,
                rawTranslationY = rawTranslationY,
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

    private class CameraPreviewRenderer(
        private val hdrEnabled: Boolean,
        private val previewFrameRate: PreviewFrameRate,
    ) : Renderer {
        var onSurfaceTextureCreated: ((SurfaceTexture) -> Unit)? = null
        var onSurfaceTextureReleased: ((SurfaceTexture) -> Unit)? = null
        var onFailure: ((String) -> Unit)? = null
        val frameAvailable = AtomicBoolean(false)
        var diagnosticConsumer: ((RenderedPreviewFrame) -> Boolean)? = null
        var diagnosticReady: (() -> Boolean)? = null
        var diagnosticsEnabled = false

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
        private val pendingTransforms = FrameTransformQueue<PreviewTransform>()
        private var frameSynchronized = false
        private var textureTimestampNs = 0L
        private var renderedTimestampNs = 0L
        private var hasOutput = false
        private var drawSequence = 0L
        private var unmatchedDraws = 0L
        private var repeatedDraws = 0L
        private var droppedTextureFrames = 0L
        private var diagnosticDrops = 0L
        private var viewportWidth = 0
        private var viewportHeight = 0
        private var copyProgram = 0
        private val outputTarget = RenderTarget(hdrEnabled)
        private val rawTarget = RenderTarget(hdrEnabled)
        private val sampleTarget = RenderTarget()
        private val diagnosticOutputTarget = RenderTarget()
        private var failed = false
        private var hdrDataSpace = 0
        private var hdrFullRange = false
        private data class CapturedFrame(
            val timestampNs: Long,
            val target: RenderTarget,
            val sampling: FloatArray,
            val realtimeTransform: PreviewTransform,
        )
        private data class FrameMetadata(
            val sampling: FloatArray,
            val elapsedNs: Long,
        )
        private data class DetectionWork(
            val ticket: GlDetectionTicket,
            val metadata: FrameMetadata,
        )
        private val frameMetadata = FrameTransformQueue<FrameMetadata>()
        private var displayedSampling: FloatArray? = null
        private var displayedTransform: PreviewTransform? = null
        private val asyncDetection = GlAsyncDetectionState()
        private var detectionEnabled = false
        private var detectionWork: DetectionWork? = null
        private var boxProgram = 0
        private var labelTexture = 0
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Color.WHITE }
        private var labelBitmap: Bitmap? = null

        fun setDetectionEnabled(enabled: Boolean) {
            if (detectionEnabled == enabled) return
            clearDetection()
            detectionEnabled = enabled
        }

        fun requestDetection(ticket: GlDetectionTicket, capturedElapsedNs: Long, ready: CompletableDeferred<Boolean>) {
            if (!detectionEnabled || failed || detectionWork != null || capturedElapsedNs <= 0L) {
                ready.complete(false)
                return
            }
            // SurfaceTexture 允许跳帧。异步框使用当前显示矩阵，不能以来源帧未渲染为由拒绝注册。
            // 有来源元数据时取更早的时间，避免注册动作给慢推理结果续期。
            val metadata = frameMetadata.exact(ticket.timestampNs)
            detectionWork = DetectionWork(ticket, FrameMetadata(metadata?.sampling ?: identity,
                minOf(metadata?.elapsedNs ?: capturedElapsedNs, capturedElapsedNs)))
            ready.complete(true)
        }

        fun completeDetection(ticket: GlDetectionTicket, boxes: List<GlDetectionBox>,
            shown: CompletableDeferred<GlDetectionPresentation?>) {
            val work = detectionWork
            if (!detectionEnabled || work?.ticket !== ticket) {
                shown.complete(null)
                return
            }
            detectionWork = null
            val accepted = asyncDetection.offer(
                GlAsyncDetectionState.Result(ticket, work.metadata.elapsedNs, boxes),
                renderedTimestampNs, SystemClock.elapsedRealtimeNanos(),
            )
            shown.complete(if (accepted) detectionPresentation() else null)
        }

        fun cancelDetection(ticket: GlDetectionTicket) {
            if (detectionWork?.ticket === ticket) detectionWork = null
        }

        private fun clearDetection() {
            detectionWork = null
            asyncDetection.clear()
        }

        private fun detectionPresentation(): GlDetectionPresentation? {
            if (!detectionEnabled) return null
            val result = asyncDetection.current(renderedTimestampNs, SystemClock.elapsedRealtimeNanos()) ?: return null
            val sampling = displayedSampling ?: return null
            val frame = displayedTransform ?: return null
            return try {
                val matrix = FloatArray(16)
                buildDisplayMatrix(frame, matrix)
                // 原图框在短时窗口内沿用，只更新当前显示裁切/镜像/EIS。
                // 这不是目标运动预测，来源时间戳与当前预览时间戳必须分开保留。
                val coordinates = GlDetectionCoordinates(sampling, matrix, viewportWidth, viewportHeight, result.ticket)
                GlDetectionPresentation(result.ticket.timestampNs, viewportWidth, viewportHeight,
                    result.boxes.map { coordinates.box(it) }, renderedTimestampNs, result.expiresElapsedNs)
            } catch (error: IllegalArgumentException) {
                asyncDetection.clear()
                Log.w(TAG, "gl-yuv: invalid asynchronous coordinates", error)
                null
            }
        }
        private val capturedFrames = java.util.ArrayDeque<CapturedFrame>()
        private val spareTargets = java.util.ArrayDeque<RenderTarget>()
        private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private var pixels: ByteBuffer? = null

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            failed = false
            try {
                createSurface()
            } catch (error: RuntimeException) {
                fail(error)
            }
        }

        private fun fail(error: RuntimeException) {
            if (!failed) onFailure?.invoke(error.message ?: "预览渲染失败")
            failed = true
        }

        private fun createSurface() {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = if (hdrEnabled) createProgram(HlgPreviewGl.VERTEX_SHADER, HlgPreviewGl.FRAGMENT_SHADER)
                else createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            copyProgram = createProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
            if (hdrEnabled) {
                val bits = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_RED_BITS, bits, 0)
                check(bits[0] >= 10) { "HDR 显示表面只有 ${bits[0]} 位精度" }
                hdrDataSpace = 0
                Log.i(TAG, "hdr-preview: renderer=HLG10 rgbBits=${bits[0]} fbo=RGB10_A2")
            }
            clearDetection()
            frameMetadata.clear()
            displayedSampling = null
            displayedTransform = null
            boxProgram = 0
            labelTexture = 0
            outputTarget.forget()
            rawTarget.forget()
            sampleTarget.forget()
            diagnosticOutputTarget.forget()
            capturedFrames.clear()
            spareTargets.clear()
            pendingTransforms.clear()
            textureTimestampNs = 0L
            renderedTimestampNs = 0L
            hasOutput = false
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
            clearDetection()
            frameMetadata.clear()
            displayedSampling = null
            displayedTransform = null
            viewportWidth = width
            viewportHeight = height
            renderedTimestampNs = 0L
            hasOutput = false
            capturedFrames.forEach { it.target.release() }
            spareTargets.forEach { it.release() }
            capturedFrames.clear()
            spareTargets.clear()
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (failed) return
            try {
                val previousSequence = drawSequence
                drawFrame()
                if (drawSequence != previousSequence) previewFrameRate.onFrame(renderedTimestampNs)
            } catch (error: RuntimeException) {
                fail(error)
            }
        }

        @androidx.annotation.RequiresApi(33)
        @android.annotation.SuppressLint("WrongConstant")
        private fun validateHdrDataSpace(texture: SurfaceTexture) {
            // SurfaceTexture 的 DataSpace 注解包含深度/JPEG 等取值，解析 API 的注解范围更窄。
            // 先拆出原始位域，再明确检查 BT.2020、HLG 和合法范围，不接受未知色彩标记。
            val dataSpace = texture.dataSpace
            if (dataSpace != hdrDataSpace) {
                val standard = DataSpace.getStandard(dataSpace)
                val transfer = DataSpace.getTransfer(dataSpace)
                val range = DataSpace.getRange(dataSpace)
                Log.i(TAG, "hdr-preview: sourceDataSpace=0x${dataSpace.toString(16)} " +
                    "standard=$standard transfer=$transfer range=$range")
                check(standard == DataSpace.STANDARD_BT2020 && transfer == DataSpace.TRANSFER_HLG &&
                    (range == DataSpace.RANGE_FULL || range == DataSpace.RANGE_LIMITED)) {
                    "相机返回的图像不是可识别的 BT.2020 HLG 数据"
                }
                hdrFullRange = range == DataSpace.RANGE_FULL
                hdrDataSpace = dataSpace
            }
            check(hdrDataSpace != 0) { "相机未提供 HDR 图像的色彩标记" }
        }

        private fun drawFrame() {
            val texture = surfaceTexture ?: return
            if (viewportWidth <= 0 || viewportHeight <= 0) return
            try {
                if (frameAvailable.getAndSet(false)) {
                    val previousTimestamp = textureTimestampNs
                    texture.updateTexImage()
                    textureTimestampNs = texture.timestamp
                    texture.getTransformMatrix(surfaceTextureMatrix)
                    if (hdrEnabled && Build.VERSION.SDK_INT >= 33 && textureTimestampNs > 0L) {
                        validateHdrDataSpace(texture)
                    }
                    if (previousTimestamp > 0L && textureTimestampNs < previousTimestamp) {
                        clearDetection()
                        frameMetadata.clear()
                        displayedSampling = null
                        displayedTransform = null
                        pendingTransforms.clear()
                        renderedTimestampNs = 0L
                        capturedFrames.forEach { spareTargets.addLast(it.target) }
                        capturedFrames.clear()
                    }
                    if (textureTimestampNs > 0L && textureTimestampNs != previousTimestamp) {
                        val target = if (capturedFrames.size >= 3) {
                            droppedTextureFrames++
                            capturedFrames.removeFirst().target
                        } else if (spareTargets.isNotEmpty()) spareTargets.removeFirst() else RenderTarget(hdrEnabled)
                        target.ensure(viewportWidth, viewportHeight)
                        renderOes(target, PreviewTransform(viewWidth = viewportWidth.toFloat(), viewHeight = viewportHeight.toFloat()))
                        val sampling = surfaceTextureMatrix.copyOf()
                        frameMetadata.offer(textureTimestampNs, FrameMetadata(sampling, SystemClock.elapsedRealtimeNanos()))
                        capturedFrames.addLast(CapturedFrame(textureTimestampNs, target,
                            sampling, synchronized(transformLock) { transform }))
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "gl-preview: updateTexImage failed: ${e.message}")
                if (hdrEnabled) fail(e)
                return
            }

            // 预览只受相机帧及 EIS 同帧矩阵驱动，永远不等待 YOLO 结果。
            val captured = if (frameSynchronized) {
                capturedFrames.lastOrNull { pendingTransforms.exact(it.timestampNs) != null }
            } else capturedFrames.peekLast()
            val currentTransform = if (frameSynchronized && captured != null) {
                pendingTransforms.exact(captured.timestampNs)
            } else {
                synchronized(transformLock) { transform }
            }
            val alreadyRendered = captured == null && capturedFrames.isEmpty()
            var diagnostic: RenderedPreviewFrame? = null
            if (captured != null && currentTransform != null && captured.timestampNs > renderedTimestampNs) {
                outputTarget.ensure(viewportWidth, viewportHeight)
                renderCaptured(captured.target, outputTarget, currentTransform)
                renderedTimestampNs = captured.timestampNs
                displayedSampling = captured.sampling
                displayedTransform = currentTransform
                hasOutput = true
                drawSequence++
                if (diagnosticsEnabled) {
                    if (diagnosticReady?.invoke() == true) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        rawTarget.ensure(viewportWidth, viewportHeight)
                        // RAW 和输出使用相同的基础缩放/裁切，只有动态补偿不同。
                        renderCaptured(captured.target, rawTarget, currentTransform.copy(
                            translationX = currentTransform.rawTranslationX,
                            translationY = currentTransform.rawTranslationY,
                            rotationDegrees = 0f,
                        ))
                        val raw = readLuma(rawTarget)
                        // 诊断开关才创建额外目标；正常预览没有全尺寸回读或额外合成 FBO。
                        diagnosticOutputTarget.ensure(viewportWidth, viewportHeight)
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, diagnosticOutputTarget.framebufferId)
                        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
                        drawTexture(outputTarget.textureId, false, identity, identity)
                        drawDetectionBoxes(detectionPresentation()?.boxes.orEmpty())
                        val output = readLuma(diagnosticOutputTarget)
                        diagnostic = RenderedPreviewFrame(
                            sourceTimestampNs = captured.timestampNs,
                            transformTimestampNs = if (frameSynchronized) currentTransform.sourceTimestampNs else 0L,
                            drawSequence = drawSequence,
                            drawElapsedNs = SystemClock.elapsedRealtimeNanos(),
                            viewWidth = viewportWidth, viewHeight = viewportHeight,
                            readbackCostMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f,
                            unmatchedDraws = unmatchedDraws, repeatedDraws = repeatedDraws,
                            droppedTextureFrames = droppedTextureFrames, diagnosticDrops = diagnosticDrops,
                            transform = currentTransform.gyroTransform, raw = raw, output = output,
                        )
                    } else diagnosticDrops++
                }
                if (drawSequence % 30L == 0L) {
                    Log.i(TAG, "frame-bound: draw=$drawSequence texture=${captured.timestampNs} " +
                        "transform=${currentTransform.sourceTimestampNs} sync=$frameSynchronized " +
                        "unmatched=$unmatchedDraws repeated=$repeatedDraws dropped=$droppedTextureFrames")
                }
                val drawnTimestampNs = captured.timestampNs
                while (capturedFrames.isNotEmpty() && capturedFrames.first.timestampNs <= drawnTimestampNs) {
                    val consumed = capturedFrames.removeFirst()
                    if (consumed.timestampNs != drawnTimestampNs) droppedTextureFrames++
                    spareTargets.addLast(consumed.target)
                }
            } else if (alreadyRendered) {
                repeatedDraws++
            } else if (textureTimestampNs > 0L) {
                unmatchedDraws++
            }

            // 缺少同时间戳补偿时保留上一张已完成的 FBO，图像和矩阵一起保持。
            // 不能在 updateTexImage() 取到新图像后套用上一帧矩阵。
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (outputTarget.textureId != 0 && hasOutput) {
                drawTexture(outputTarget.textureId, false, identity, identity)
            }
            // 检测框独立画到屏幕，不烘焙到预览缓存，过期/关闭时一帧即可清除。
            if (hasOutput) drawDetectionBoxes(detectionPresentation()?.boxes.orEmpty())
            diagnostic?.let { if (diagnosticConsumer?.invoke(it) != true) diagnosticDrops++ }
            if (hdrEnabled) {
                val error = GLES20.glGetError()
                check(error == GLES20.GL_NO_ERROR) { "HDR 渲染错误：GL 0x${error.toString(16)}" }
            }
        }

        private fun drawTexture(textureId: Int, external: Boolean, textureMatrix: FloatArray, vertexMatrix: FloatArray,
            sampleWidth: Int = 0, sampleHeight: Int = 0) {
            val activeProgram = if (external) program else copyProgram
            val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            val positionHandle = GLES20.glGetAttribLocation(activeProgram, "aPosition")
            val texCoordHandle = GLES20.glGetAttribLocation(activeProgram, "aTexCoord")
            GLES20.glUseProgram(activeProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(activeProgram, "uTexture"), 0)
            if (external && hdrEnabled) {
                GLES20.glUniform1i(GLES20.glGetUniformLocation(activeProgram, "uFullRange"), if (hdrFullRange) 1 else 0)
            }
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(activeProgram, "uTextureMatrix"), 1, false, textureMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(activeProgram, "uDisplayMatrix"), 1, false, vertexMatrix, 0)
            if (!external) GLES20.glUniform2f(GLES20.glGetUniformLocation(activeProgram, "uDownsampleStep"),
                if (sampleWidth > 0) 1f / sampleWidth else 0f,
                if (sampleHeight > 0) 1f / sampleHeight else 0f)

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
            GLES20.glBindTexture(target, 0)
        }

        private fun renderOes(target: RenderTarget, frame: PreviewTransform) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebufferId)
            GLES20.glViewport(0, 0, target.width, target.height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            buildDisplayMatrix(frame, displayMatrix)
            drawTexture(oesTextureId, true, surfaceTextureMatrix, displayMatrix)
        }

        private fun renderCaptured(input: RenderTarget, output: RenderTarget, frame: PreviewTransform) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output.framebufferId)
            GLES20.glViewport(0, 0, output.width, output.height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            buildDisplayMatrix(frame, displayMatrix)
            drawTexture(input.textureId, false, identity, displayMatrix)
        }


        /** 在 GL 线程合成异步框与标签；图像刷新不等待此结果。 */
        private fun drawDetectionBoxes(boxes: List<GlDetectionQuad>) {
            if (boxes.isEmpty()) return
            if (boxProgram == 0) boxProgram = createProgram(
                "attribute vec4 aPosition; void main(){gl_Position=aPosition;}",
                "precision mediump float; void main(){gl_FragColor=vec4(0.1,1.0,0.3,1.0);}",
            )
            for (box in boxes) {
                val vertices = box.points.flatMap { listOf(
                    2f * it.x / viewportWidth - 1f, 1f - 2f * it.y / viewportHeight,
                ) }.toFloatArray()
                val buffer = floatBufferOf(*vertices)
                GLES20.glUseProgram(boxProgram)
                val position = GLES20.glGetAttribLocation(boxProgram, "aPosition")
                GLES20.glEnableVertexAttribArray(position)
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, FLOAT_STRIDE_BYTES, buffer)
                GLES20.glLineWidth(3f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
                GLES20.glDisableVertexAttribArray(position)

                if (box.label.isBlank()) continue
                // 仅上传一块复用的小文字纹理，不对全分辨率预览执行 GPU -> CPU 回读。
                val bitmap = labelBitmap ?: Bitmap.createBitmap(512, 48, Bitmap.Config.ARGB_8888).also { labelBitmap = it }
                bitmap.eraseColor(Color.TRANSPARENT)
                val text = box.label.take(48)
                val labelWidth = (labelPaint.measureText(text) + 12f).coerceIn(20f, 512f)
                Canvas(bitmap).apply {
                    drawRect(0f, 0f, labelWidth, 48f, Paint().apply { color = 0xCC000000.toInt() })
                    drawText(text, 6f, 33f, labelPaint)
                }
                if (labelTexture == 0) {
                    val id = IntArray(1)
                    GLES20.glGenTextures(1, id, 0)
                    labelTexture = id[0]
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTexture)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                } else {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTexture)
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
                }
                val left = box.points.minOf { it.x }.coerceIn(0f, (viewportWidth - labelWidth).coerceAtLeast(0f))
                val top = (box.points.minOf { it.y } - 48f).coerceIn(0f, (viewportHeight - 48f).coerceAtLeast(0f))
                // 文字位图左上角为原点；NDC 为左下角，采样矩阵单独翻转 Y。
                val vertex = identity.copyOf().apply {
                    this[0] = 512f / viewportWidth; this[5] = 48f / viewportHeight
                    this[12] = 2f * (left + 256f) / viewportWidth - 1f
                    this[13] = 1f - 2f * (top + 24f) / viewportHeight
                }
                val sample = identity.copyOf().apply { this[5] = -1f; this[13] = 1f }
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                drawTexture(labelTexture, false, sample, vertex)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
        }

        private fun readLuma(target: RenderTarget): PreviewMotionEstimator.LumaFrame {
            val width = PreviewMotionEstimator.DEFAULT_SAMPLE_WIDTH
            val height = (width.toFloat() * viewportHeight / viewportWidth).roundToInt().coerceAtLeast(1)
            sampleTarget.ensure(width, height)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleTarget.framebufferId)
            GLES20.glViewport(0, 0, width, height)
            drawTexture(target.textureId, false, identity, identity, width, height)
            val buffer = pixels?.takeIf { it.capacity() == width * height * 4 }
                ?: ByteBuffer.allocateDirect(width * height * 4).also { pixels = it }
            buffer.clear()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            val luma = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val index = ((height - 1 - y) * width + x) * 4
                val r = buffer.get(index).toInt() and 255
                val g = buffer.get(index + 1).toInt() and 255
                val b = buffer.get(index + 2).toInt() and 255
                luma[y * width + x] = (77 * r + 150 * g + 29 * b) shr 8
            }
            val sourceWidth = 720
            val sourceHeight = (720f * viewportHeight / viewportWidth).roundToInt()
            return PreviewMotionEstimator.LumaFrame(sourceWidth, sourceHeight, width, height,
                width.toFloat() / sourceWidth, height.toFloat() / sourceHeight, luma, 0L)
        }

        fun setFrameSynchronized(enabled: Boolean) {
            if (frameSynchronized == enabled) return
            clearDetection()
            frameSynchronized = enabled
            pendingTransforms.clear()
            renderedTimestampNs = 0L
        }

        fun setPreviewTransform(
            scale: Float,
            translationX: Float,
            translationY: Float,
            rotationDegrees: Float,
            viewWidth: Float,
            viewHeight: Float,
            sourceTimestampNs: Long,
            gyroTransform: GyroPreviewStabilizer.Transform,
            rawTranslationX: Float,
            rawTranslationY: Float,
        ) {
            synchronized(transformLock) {
                transform = PreviewTransform(
                    scale = scale.coerceAtLeast(1f),
                    translationX = translationX,
                    translationY = translationY,
                    rotationDegrees = rotationDegrees,
                    viewWidth = viewWidth.coerceAtLeast(1f),
                    viewHeight = viewHeight.coerceAtLeast(1f),
                    sourceTimestampNs = sourceTimestampNs,
                    gyroTransform = gyroTransform,
                    rawTranslationX = rawTranslationX,
                    rawTranslationY = rawTranslationY,
                )
                if (sourceTimestampNs > 0L) pendingTransforms.offer(sourceTimestampNs, transform)
            }
        }

        fun releaseCameraTexture() {
            clearDetection()
            labelBitmap?.recycle()
            labelBitmap = null
            if (boxProgram != 0) GLES20.glDeleteProgram(boxProgram)
            if (labelTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(labelTexture), 0)
            boxProgram = 0
            labelTexture = 0
            val texture = surfaceTexture ?: return
            surfaceTexture = null
            outputTarget.release()
            rawTarget.release()
            sampleTarget.release()
            diagnosticOutputTarget.release()
            frameMetadata.clear()
            displayedSampling = null
            displayedTransform = null
            capturedFrames.forEach { it.target.release() }
            spareTargets.forEach { it.release() }
            capturedFrames.clear()
            spareTargets.clear()
            pendingTransforms.clear()
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
            // NDC 的两个单位轴对应不同像素长度。像素空间旋转需用 W/H、H/W
            // 换算交叉项，否则竖屏中一次 Roll 会变成带拉伸的剪切。
            out[1] = source.scale * sinValue * source.viewWidth / source.viewHeight
            out[4] = -source.scale * sinValue * source.viewHeight / source.viewWidth
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
            val sourceTimestampNs: Long = 0L,
            val gyroTransform: GyroPreviewStabilizer.Transform = GyroPreviewStabilizer.Transform.Identity,
            val rawTranslationX: Float = 0f,
            val rawTranslationY: Float = 0f,
        )

        private class RenderTarget(private val tenBit: Boolean = false) {
            var textureId = 0
            var framebufferId = 0
            var width = 0
            var height = 0

            fun ensure(w: Int, h: Int) {
                if (textureId != 0 && width == w && height == h) return
                release()
                width = w
                height = h
                val id = IntArray(1)
                GLES20.glGenTextures(1, id, 0)
                textureId = id[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                if (tenBit) {
                    GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, w, h, 0,
                        GLES20.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null)
                } else {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                }
                GLES20.glGenFramebuffers(1, id, 0)
                framebufferId = id[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, textureId, 0)
                check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            }

            fun release() {
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (framebufferId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
                forget()
            }

            fun forget() { textureId = 0; framebufferId = 0; width = 0; height = 0 }
        }
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
            precision highp float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;

            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
        private const val COPY_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform vec2 uDownsampleStep;
            varying vec2 vTexCoord;
            void main() {
                if (uDownsampleStep.x == 0.0) {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                } else {
                    vec4 sum = vec4(0.0);
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            vec2 offset = (vec2(float(x), float(y)) - 1.5) * uDownsampleStep * 0.25;
                            sum += texture2D(uTexture, vTexCoord + offset);
                        }
                    }
                    gl_FragColor = sum * 0.0625;
                }
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
