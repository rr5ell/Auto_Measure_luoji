package com.example.auto_measure_luoji.feature.detection.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.example.auto_measure_luoji.feature.camera.domain.YuvFrameProcessor
import com.example.auto_measure_luoji.feature.camera.domain.YuvPreprocessNative
import com.example.auto_measure_luoji.feature.detection.data.AssetUtils
import com.example.auto_measure_luoji.feature.detection.model.DetectResult
import com.example.auto_measure_luoji.feature.detection.model.Detection
import com.example.auto_measure_luoji.feature.detection.model.Timings
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import com.google.android.gms.tflite.java.TfLite
import com.google.android.gms.tasks.Tasks
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class YoloPostprocessOptions(
    val confidenceThreshold: Float,
    val topConfidenceOnly: Boolean,
) {
    val maxDetections: Int
        get() = if (topConfidenceOnly) TOP_ONLY_MAX_DETECTIONS else ORIGINAL_MAX_DETECTIONS

    fun normalized(): YoloPostprocessOptions = copy(
        confidenceThreshold = confidenceThreshold.coerceIn(MIN_CONFIDENCE_THRESHOLD, MAX_CONFIDENCE_THRESHOLD),
    )

    companion object {
        const val ORIGINAL_CONFIDENCE_THRESHOLD = 0.10f
        const val DEFAULT_TOP_CONFIDENCE_THRESHOLD = 0.25f
        const val MIN_CONFIDENCE_THRESHOLD = 0.0f
        const val MAX_CONFIDENCE_THRESHOLD = 1.0f
        const val TOP_ONLY_MAX_DETECTIONS = 1
        const val ORIGINAL_MAX_DETECTIONS = 100

        fun original(): YoloPostprocessOptions = YoloPostprocessOptions(
            confidenceThreshold = ORIGINAL_CONFIDENCE_THRESHOLD,
            topConfidenceOnly = false,
        )

        fun topOnly(threshold: Float = DEFAULT_TOP_CONFIDENCE_THRESHOLD): YoloPostprocessOptions =
            YoloPostprocessOptions(
                confidenceThreshold = threshold,
                topConfidenceOnly = true,
            )
    }
}

class YoloDetector(
    private val context: Context,
    private val modelAssetPath: String,
    private val labelsAssetPath: String? = null,
    private val useGpu: Boolean = true,
    private val numThreads: Int = 4,
) : Closeable {

    companion object {
        private const val TAG = "YoloDetector"
        private const val IOU_THRESHOLD = 0.45f
        private const val INV255 = 1f / 255f
        private const val PAD_VAL = 114f * INV255
    }

    private enum class Backend { LOCAL_GPU, GMS_GPU, CPU }

    private interface Runner : Closeable {
        fun getInputTensor(i: Int): org.tensorflow.lite.Tensor
        fun getOutputTensor(i: Int): org.tensorflow.lite.Tensor
        fun getOutputTensorCount(): Int
        fun run(input: Any, output: Any)
    }

    private class LocalRunner(private val itp: Interpreter) : Runner {
        override fun getInputTensor(i: Int) = itp.getInputTensor(i)
        override fun getOutputTensor(i: Int) = itp.getOutputTensor(i)
        override fun getOutputTensorCount() = itp.outputTensorCount
        override fun run(input: Any, output: Any) = itp.run(input, output)
        override fun close() = itp.close()
    }

    private class ApiRunner(private val itp: InterpreterApi) : Runner {
        override fun getInputTensor(i: Int) = itp.getInputTensor(i)
        override fun getOutputTensor(i: Int) = itp.getOutputTensor(i)
        override fun getOutputTensorCount() = itp.getOutputTensorCount()
        override fun run(input: Any, output: Any) = itp.run(input, output)
        override fun close() = itp.close()
    }

    private data class LbMeta(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val inputW: Int,
        val inputH: Int,
    ) {
        fun mapBoxToOriginal(boxOnInput: RectF, origW: Int, origH: Int): RectF {
            val x1 = ((boxOnInput.left - padX) / scale).coerceIn(0f, origW.toFloat())
            val y1 = ((boxOnInput.top - padY) / scale).coerceIn(0f, origH.toFloat())
            val x2 = ((boxOnInput.right - padX) / scale).coerceIn(0f, origW.toFloat())
            val y2 = ((boxOnInput.bottom - padY) / scale).coerceIn(0f, origH.toFloat())
            return RectF(x1, y1, x2, y2)
        }
    }

    private data class LbPlan(
        val srcW: Int, val srcH: Int,
        val dstW: Int, val dstH: Int,
        val nw: Int, val nh: Int,
        val padX: Int, val padY: Int,
        val xMap: IntArray, val yMap: IntArray,
        val meta: LbMeta,
    )

    private data class YuvNativePaddingKey(
        val bufferIdentity: Int,
        val bufferCapacity: Int,
        val imageW: Int,
        val imageH: Int,
        val rotation: Int,
        val dstW: Int,
        val dstH: Int,
    )

    private var backend: Backend = Backend.CPU
    private var runner: Runner? = null
    private var gpuDelegate: GpuDelegate? = null

    private val labels: List<String> = run {
        if (labelsAssetPath.isNullOrBlank()) emptyList()
        else AssetUtils.loadLabels(context, labelsAssetPath)
    }

    private var cachedInputBuffer: ByteBuffer? = null
    private var cachedSrcPixels: IntArray? = null
    private var cachedFloatInput: FloatArray? = null
    private var cachedPlan: LbPlan? = null
    private var yuvNativePaddingKey: YuvNativePaddingKey? = null

    init {
        val model = AssetUtils.loadMappedFile(context, modelAssetPath)
        runner = if (useGpu) {
            tryCreateGmsGpuRunner(model)
                ?: tryCreateLocalGpuRunner(model)
                ?: createCpuRunner(model)
        } else {
            createCpuRunner(model)
        }
        Log.i(TAG, "Backend=$backend, model=$modelAssetPath")
    }

    val runtimeInfo: String get() = "backend=$backend"

    private fun tryCreateLocalGpuRunner(model: ByteBuffer): Runner? {
        return try {
            // 先检查 CompatibilityList，如果支持则用最优配置
            val compatList = CompatibilityList()
            val options = Interpreter.Options()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                gpuDelegate = GpuDelegate(delegateOptions)
            } else {
                // CompatibilityList 可能误判，直接尝试默认配置
                Log.w(TAG, "CompatibilityList 不支持，尝试默认 GPU 配置")
                gpuDelegate = GpuDelegate()
            }
            options.addDelegate(gpuDelegate)
            Log.i(TAG, "本地GPU已启用")
            backend = Backend.LOCAL_GPU
            LocalRunner(Interpreter(model, options))
        } catch (t: Throwable) {
            Log.e(TAG, "本地GPU初始化失败", t)
            try { gpuDelegate?.close() } catch (_: Throwable) {}
            gpuDelegate = null
            null
        }
    }

    private fun tryCreateGmsGpuRunner(model: ByteBuffer): Runner? {
        return try {
            val available = Tasks.await(
                TfLiteGpu.isGpuDelegateAvailable(context.applicationContext),
                20, TimeUnit.SECONDS
            )
            if (!available) {
                Log.w(TAG, "GMS GPU不可用")
                return null
            }
            val initOpts = TfLiteInitializationOptions.builder()
                .setEnableGpuDelegateSupport(true)
                .build()
            Tasks.await(
                TfLite.initialize(context.applicationContext, initOpts),
                20, TimeUnit.SECONDS
            )
            val options = InterpreterApi.Options()
                .setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
                .addDelegateFactory(GpuDelegateFactory())
            Log.i(TAG, "GMS GPU已启用")
            backend = Backend.GMS_GPU
            ApiRunner(InterpreterApi.create(model, options))
        } catch (t: Throwable) {
            Log.e(TAG, "GMS GPU初始化失败", t)
            null
        }
    }

    private fun createCpuRunner(model: ByteBuffer): Runner {
        val options = Interpreter.Options().apply { setNumThreads(numThreads) }
        Log.i(TAG, "CPU已启用, 线程数=$numThreads")
        backend = Backend.CPU
        return LocalRunner(Interpreter(model, options))
    }

    /**
     * 从 YUV_420_888 Image 直接检测（native 预处理）
     *
     * @param image    YUV_420_888 格式的 Image
     * @param rotation 传感器旋转角度
     */
    fun detectFromYuv(
        image: android.media.Image,
        rotation: Int,
        postprocessOptions: YoloPostprocessOptions = YoloPostprocessOptions.topOnly(),
    ): DetectResult {
        val r = runner ?: error("Runner is null")
        val t0 = SystemClock.elapsedRealtimeNanos()
        val timings = Timings()

        // 1. input shape
        val inputTensor = r.getInputTensor(0)
        val inShape = inputTensor.shape()
        val inType = inputTensor.dataType()
        val inputH = inShape[1]
        val inputW = inShape[2]

        val srcW = if (rotation == 90 || rotation == 270) image.height else image.width
        val srcH = if (rotation == 90 || rotation == 270) image.width else image.height
        Log.i(TAG, "YUV输入: ${image.width}x${image.height} (旋转${rotation}°) -> ${inputW}x${inputH}")

        // 2. 预处理：优先 native，JNI 绑定不可用时回退到 Kotlin 实现，避免 YUV 模式直接闪退。
        val tPre0 = SystemClock.elapsedRealtimeNanos()
        val inputBuffer = ensureInputBuffer(inType, inputW, inputH)
        if (inType == DataType.FLOAT32) {
            val paddingKey = YuvNativePaddingKey(
                bufferIdentity = System.identityHashCode(inputBuffer),
                bufferCapacity = inputBuffer.capacity(),
                imageW = image.width,
                imageH = image.height,
                rotation = rotation,
                dstW = inputW,
                dstH = inputH,
            )
            val refillPadding = yuvNativePaddingKey != paddingKey
            val nativeResult = try {
                YuvPreprocessNative.processImage(
                    image = image,
                    rotation = rotation,
                    dstW = inputW,
                    dstH = inputH,
                    outBuffer = inputBuffer,
                    refillPadding = refillPadding,
                )
            } catch (error: LinkageError) {
                Log.e(TAG, "YUV native预处理绑定失败，回退Kotlin实现", error)
                null
            }
            if (nativeResult == 0) {
                yuvNativePaddingKey = paddingKey
            } else {
                yuvNativePaddingKey = null
                if (nativeResult != null) {
                    Log.w(TAG, "YUV native预处理返回$nativeResult，回退Kotlin实现")
                }
                YuvFrameProcessor.fillInputFromYuv(image, rotation, inputW, inputH, inputBuffer)
            }
        } else {
            error("YUV 输入仅支持 FLOAT32，当前: $inType")
        }
        timings.preprocessMs = (SystemClock.elapsedRealtimeNanos() - tPre0) / 1_000_000L

        // 3. inference
        val outTensor = r.getOutputTensor(0)
        val outShape = outTensor.shape()
        val outType = outTensor.dataType()
        val outputObj = createOutputBuffer(outTensor)

        val tInf0 = SystemClock.elapsedRealtimeNanos()
        r.run(inputBuffer, outputObj)
        timings.inferenceMs = (SystemClock.elapsedRealtimeNanos() - tInf0) / 1_000_000L

        // 4. postprocess
        val tPost0 = SystemClock.elapsedRealtimeNanos()
        val plan = YuvFrameProcessor.getOrBuildPlan(srcW, srcH, inputW, inputH)
        val detections = parseAndNms(outputObj, outTensor, outShape, outType,
            LbMeta(plan.scale.toFloat(), plan.padX.toFloat(), plan.padY.toFloat(), inputW, inputH),
            srcW, srcH, postprocessOptions)
        timings.postprocessMs = (SystemClock.elapsedRealtimeNanos() - tPost0) / 1_000_000L

        timings.totalMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
        val options = postprocessOptions.normalized()
        Log.d(
            TAG,
            "YUV检测: 后端=$backend 耗时=${timings.totalMs}ms 目标=${detections.size}个 " +
                "阈值=${String.format(Locale.US, "%.2f", options.confidenceThreshold)} 单框=${options.topConfidenceOnly}",
        )

        return DetectResult(detections, timings)
    }

    fun detect(
        bitmap: Bitmap,
        postprocessOptions: YoloPostprocessOptions = YoloPostprocessOptions.topOnly(),
    ): DetectResult {
        val r = runner ?: error("Runner is null")
        val t0 = SystemClock.elapsedRealtimeNanos()
        val timings = Timings()

        // 1. input shape
        val inputTensor = r.getInputTensor(0)
        val inShape = inputTensor.shape()
        val inType = inputTensor.dataType()
        val inputH = inShape[1]
        val inputW = inShape[2]
        Log.i(TAG, "Input: ${bitmap.width}x${bitmap.height} -> ${inputW}x${inputH} type=$inType")

        // 2. preprocess
        val tPre0 = SystemClock.elapsedRealtimeNanos()
        val plan = getOrBuildPlan(bitmap.width, bitmap.height, inputW, inputH)
        val srcPixels = getSrcPixelsCached(bitmap)
        val inputBuffer = ensureInputBuffer(inType, inputW, inputH)
        yuvNativePaddingKey = null
        if (inType == DataType.FLOAT32) {
            fillInputFloatFast(srcPixels, plan, inputW, inputH, inputBuffer)
        } else if (inType == DataType.UINT8 || inType == DataType.INT8) {
            fillInputByteFast(srcPixels, plan, inputW, inputH, inputBuffer)
        } else {
            error("Unsupported input type=$inType")
        }
        timings.preprocessMs = (SystemClock.elapsedRealtimeNanos() - tPre0) / 1_000_000L

        // 3. inference
        val outTensor = r.getOutputTensor(0)
        val outShape = outTensor.shape()
        val outType = outTensor.dataType()
        val outputObj = createOutputBuffer(outTensor)

        val tInf0 = SystemClock.elapsedRealtimeNanos()
        r.run(inputBuffer, outputObj)
        timings.inferenceMs = (SystemClock.elapsedRealtimeNanos() - tInf0) / 1_000_000L

        // 3.5 dump raw output for debug
        val f2d = OutputReader.toFloat2DAuto(outputObj, outTensor, outShape, outType)
        var maxConf = 0f
        var maxIdx = 0
        for (i in 0 until f2d.n) {
            val conf = f2d.data[i][f2d.c - 1]
            if (conf > maxConf) { maxConf = conf; maxIdx = i }
        }
        Log.i(TAG, "Output layout=${f2d.layout}, N=${f2d.n}, C=${f2d.c}, maxConf=${String.format("%.6f", maxConf)}@row$maxIdx")
        for (i in 0 until minOf(5, f2d.n)) {
            val sb = StringBuilder()
            sb.append("raw[$i]=[")
            for (j in 0 until minOf(8, f2d.c)) {
                if (j > 0) sb.append(", ")
                sb.append(String.format("%.4f", f2d.data[i][j]))
            }
            if (f2d.c > 8) sb.append(", ...")
            sb.append("]")
            Log.i(TAG, sb.toString())
        }
        // dump the row with max confidence
        if (maxIdx >= 5) {
            val sb = StringBuilder()
            sb.append("raw_max[$maxIdx]=[")
            for (j in 0 until minOf(8, f2d.c)) {
                if (j > 0) sb.append(", ")
                sb.append(String.format("%.4f", f2d.data[maxIdx][j]))
            }
            sb.append("]")
            Log.i(TAG, sb.toString())
        }

        // 4. postprocess
        val tPost0 = SystemClock.elapsedRealtimeNanos()
        val detections = parseAndNms(
            outputObj,
            outTensor,
            outShape,
            outType,
            plan.meta,
            bitmap.width,
            bitmap.height,
            postprocessOptions,
        )
        timings.postprocessMs = (SystemClock.elapsedRealtimeNanos() - tPost0) / 1_000_000L

        timings.totalMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
        val options = postprocessOptions.normalized()
        Log.d(
            TAG,
            "检测: 后端=$backend 耗时=${timings.totalMs}ms 目标=${detections.size}个 " +
                "阈值=${String.format(Locale.US, "%.2f", options.confidenceThreshold)} 单框=${options.topConfidenceOnly}",
        )

        return DetectResult(detections, timings)
    }

    private fun getOrBuildPlan(srcW: Int, srcH: Int, dstW: Int, dstH: Int): LbPlan {
        val p = cachedPlan
        if (p != null && p.srcW == srcW && p.srcH == srcH && p.dstW == dstW && p.dstH == dstH) return p

        val r = min(dstW.toFloat() / srcW.toFloat(), dstH.toFloat() / srcH.toFloat())
        val nw = max(1, (srcW * r).roundToInt())
        val nh = max(1, (srcH * r).roundToInt())
        val padX = (dstW - nw) / 2
        val padY = (dstH - nh) / 2

        val xMap = IntArray(nw) { x -> ((x * srcW) / nw).coerceAtMost(srcW - 1) }
        val yMap = IntArray(nh) { y -> ((y * srcH) / nh).coerceAtMost(srcH - 1) }

        val meta = LbMeta(r, padX.toFloat(), padY.toFloat(), dstW, dstH)
        val plan = LbPlan(srcW, srcH, dstW, dstH, nw, nh, padX, padY, xMap, yMap, meta)
        cachedPlan = plan
        return plan
    }

    /**
     * 获取源图像素（与 yolov14 完全一致）
     * 直接从 bitmap 提取像素，不做额外缩放
     */
    private fun getSrcPixelsCached(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val need = w * h
        val arr = cachedSrcPixels?.takeIf { it.size == need } ?: IntArray(need).also { cachedSrcPixels = it }
        bitmap.getPixels(arr, 0, w, 0, 0, w, h)
        return arr
    }

    private fun ensureInputBuffer(type: DataType, w: Int, h: Int): ByteBuffer {
        val channels = 3
        val needBytes = when (type) {
            DataType.FLOAT32 -> 4 * w * h * channels
            DataType.UINT8, DataType.INT8 -> w * h * channels
            else -> error("Unsupported input type=$type")
        }
        val buf = cachedInputBuffer
        if (buf == null || buf.capacity() != needBytes) {
            cachedInputBuffer = ByteBuffer.allocateDirect(needBytes).order(ByteOrder.nativeOrder())
        }
        return cachedInputBuffer!!.apply { rewind() }
    }

    private fun fillInputFloatFast(
        srcPixels: IntArray, plan: LbPlan,
        dstW: Int, dstH: Int, outBuffer: ByteBuffer,
    ) {
        val need = dstW * dstH * 3
        val fa = cachedFloatInput?.takeIf { it.size == need } ?: FloatArray(need).also { cachedFloatInput = it }
        val srcW = plan.srcW
        val padX = plan.padX
        val padY = plan.padY
        val nw = plan.nw
        val nh = plan.nh
        val xMap = plan.xMap
        val yMap = plan.yMap
        var di = 0

        for (y in 0 until dstH) {
            val dy = y - padY
            if (dy < 0 || dy >= nh) {
                for (x in 0 until dstW) { fa[di++] = PAD_VAL; fa[di++] = PAD_VAL; fa[di++] = PAD_VAL }
                continue
            }
            val sy = yMap[dy]
            val rowBase = sy * srcW
            for (x in 0 until dstW) {
                val dx = x - padX
                if (dx < 0 || dx >= nw) {
                    fa[di++] = PAD_VAL; fa[di++] = PAD_VAL; fa[di++] = PAD_VAL
                    continue
                }
                val sx = xMap[dx]
                val p = srcPixels[rowBase + sx]
                fa[di++] = ((p shr 16) and 0xFF).toFloat() * INV255
                fa[di++] = ((p shr 8) and 0xFF).toFloat() * INV255
                fa[di++] = (p and 0xFF).toFloat() * INV255
            }
        }

        outBuffer.rewind()
        outBuffer.asFloatBuffer().put(fa)
        outBuffer.rewind()
    }

    private fun fillInputByteFast(
        srcPixels: IntArray, plan: LbPlan,
        dstW: Int, dstH: Int, outBuffer: ByteBuffer,
    ) {
        val srcW = plan.srcW
        val padX = plan.padX
        val padY = plan.padY
        val nw = plan.nw
        val nh = plan.nh
        val xMap = plan.xMap
        val yMap = plan.yMap
        val padByte = 114.toByte()
        outBuffer.rewind()

        for (y in 0 until dstH) {
            val dy = y - padY
            if (dy < 0 || dy >= nh) {
                for (x in 0 until dstW) { outBuffer.put(padByte); outBuffer.put(padByte); outBuffer.put(padByte) }
                continue
            }
            val sy = yMap[dy]
            val rowBase = sy * srcW
            for (x in 0 until dstW) {
                val dx = x - padX
                if (dx < 0 || dx >= nw) {
                    outBuffer.put(padByte); outBuffer.put(padByte); outBuffer.put(padByte)
                    continue
                }
                val sx = xMap[dx]
                val p = srcPixels[rowBase + sx]
                outBuffer.put(((p shr 16) and 0xFF).toByte())
                outBuffer.put(((p shr 8) and 0xFF).toByte())
                outBuffer.put((p and 0xFF).toByte())
            }
        }
        outBuffer.rewind()
    }

    private fun parseAndNms(
        outputObj: Any, outTensor: org.tensorflow.lite.Tensor,
        outShape: IntArray, outType: DataType,
        lb: LbMeta, origW: Int, origH: Int,
        postprocessOptions: YoloPostprocessOptions,
    ): List<Detection> {
        val options = postprocessOptions.normalized()
        val threshold = options.confidenceThreshold
        val f2d = OutputReader.toFloat2DAuto(outputObj, outTensor, outShape, outType)
        val raw = f2d.data
        val n = f2d.n
        val c = f2d.c

        val candidates = ArrayList<Detection>(n)

        when {
            c == 6 -> {
                for (i in 0 until n) {
                    val score = raw[i][4]
                    if (score < threshold) continue
                    val boxOnInput = RectF(raw[i][0], raw[i][1], raw[i][2], raw[i][3])
                    val boxOnOrig = lb.mapBoxToOriginal(boxOnInput, origW, origH)
                    val cls = raw[i][5].toInt()
                    val label = labels.getOrNull(cls) ?: "class_$cls"
                    candidates.add(Detection(cls, label, score, boxOnOrig))
                }
            }
            c == 5 -> {
                for (i in 0 until n) {
                    var cx = raw[i][0]; var cy = raw[i][1]
                    var w = raw[i][2]; var h = raw[i][3]
                    val conf = raw[i][4]
                    if (cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f) {
                        cx *= lb.inputW.toFloat(); cy *= lb.inputH.toFloat()
                        w *= lb.inputW.toFloat(); h *= lb.inputH.toFloat()
                    }
                    if (conf < threshold) continue
                    val boxOnInput = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                    val boxOnOrig = lb.mapBoxToOriginal(boxOnInput, origW, origH)
                    candidates.add(Detection(0, labels.getOrNull(0) ?: "class_0", conf, boxOnOrig))
                }
            }
            c > 6 -> {
                val numClasses = c - 5
                for (i in 0 until n) {
                    var cx = raw[i][0]; var cy = raw[i][1]
                    var w = raw[i][2]; var h = raw[i][3]
                    val obj = raw[i][4]
                    if (cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f) {
                        cx *= lb.inputW.toFloat(); cy *= lb.inputH.toFloat()
                        w *= lb.inputW.toFloat(); h *= lb.inputH.toFloat()
                    }
                    var best = 0f; var bestId = 0
                    for (k in 0 until numClasses) {
                        val p = raw[i][5 + k]
                        if (p > best) { best = p; bestId = k }
                    }
                    val score = obj * best
                    if (score < threshold) continue
                    val boxOnInput = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                    val boxOnOrig = lb.mapBoxToOriginal(boxOnInput, origW, origH)
                    candidates.add(Detection(bestId, labels.getOrNull(bestId) ?: "class_$bestId", score, boxOnOrig))
                }
            }
            else -> {
                Log.w(TAG, "Unsupported output lastDim=$c, shape=${outShape.contentToString()}")
                return emptyList()
            }
        }

        return Nms.classAwareNms(candidates, IOU_THRESHOLD, options.maxDetections)
    }

    private fun createOutputBuffer(outTensor: org.tensorflow.lite.Tensor): Any {
        val shape = outTensor.shape()
        val d1 = shape[1]
        val d2 = shape[2]
        return when (outTensor.dataType()) {
            DataType.FLOAT32 -> Array(1) { Array(d1) { FloatArray(d2) } }
            DataType.UINT8, DataType.INT8 -> Array(1) { Array(d1) { ByteArray(d2) } }
            else -> throw IllegalStateException("Unsupported output type=${outTensor.dataType()}")
        }
    }

    override fun close() {
        try { runner?.close() } catch (_: Throwable) {}
        runner = null
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        gpuDelegate = null
        cachedInputBuffer = null
        cachedSrcPixels = null
        cachedFloatInput = null
        cachedPlan = null
        Log.i(TAG, "已关闭, 后端=$backend")
    }
}
