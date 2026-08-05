package com.example.auto_measure_luoji.feature.voice.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

internal class SherpaOnnxHotwordRecognizer(
    private val context: Context,
    private val modelPaths: ThirdPartyVoiceModelPaths,
) {
    var onStatusChanged: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onHotwordHit: ((ChineseVoiceHotwordHit) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var nativeEngine: SherpaOnnxHotwordNative? = null
    @Volatile
    private var active = false
    private var lastHitCategory: ChineseVoiceHotwordCategory? = null
    private var lastHitAtMs: Long = 0L

    fun start() {
        if (active) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatusChanged?.invoke("缺少录音权限，无法启动第三方语音识别")
            onListeningChanged?.invoke(false)
            return
        }

        active = true
        onListeningChanged?.invoke(true)
        listenJob = scope.launch {
            try {
                runLoop()
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(TAG, "third-party hotword recognizer failed", e)
                withContext(Dispatchers.Main.immediate) {
                    active = false
                    onListeningChanged?.invoke(false)
                    onStatusChanged?.invoke("第三方语音识别失败：${e.message ?: "未知错误"}")
                }
            } finally {
                releaseAudioAndEngine()
            }
        }
    }

    fun stop() {
        active = false
        listenJob?.cancel()
        listenJob = null
        audioRecord?.stopSafely()
        onListeningChanged?.invoke(false)
        onStatusChanged?.invoke("第三方语音识别已停止")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private suspend fun runLoop() {
        val engine = SherpaOnnxHotwordNative(
            modelPath = modelPaths.senseVoiceModelPath,
            tokensPath = modelPaths.senseVoiceTokensPath,
            vadPath = modelPaths.vadModelPath,
        )
        nativeEngine = engine
        val recorder = createAudioRecord()
        audioRecord = recorder

        withContext(Dispatchers.Main.immediate) {
            onStatusChanged?.invoke("第三方语音识别中（SenseVoice+VAD）：等待“测距”或“扫描”")
        }

        recorder.startRecording()
        val shortBuffer = ShortArray(VAD_WINDOW_SIZE)
        while (active) {
            val read = recorder.read(shortBuffer, 0, shortBuffer.size)
            if (read > 0) {
                val samples = FloatArray(read)
                for (i in 0 until read) {
                    samples[i] = shortBuffer[i] / PCM_FLOAT_SCALE
                }
                processRecognizedTexts(engine.acceptWaveform(samples))
            } else if (read < 0) {
                throw IllegalStateException("AudioRecord 读取失败：$read")
            }
        }
        processRecognizedTexts(engine.flush())
    }

    private suspend fun processRecognizedTexts(texts: List<String>) {
        texts.forEach { text ->
            Log.d(TAG, "SenseVoice result: $text")
            matchChineseHotword(text)?.let { category ->
                emitHit(category, text)
            }
        }
    }

    private suspend fun emitHit(category: ChineseVoiceHotwordCategory, text: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (category == lastHitCategory && now - lastHitAtMs < HIT_DEBOUNCE_MS) return
        lastHitCategory = category
        lastHitAtMs = now
        withContext(Dispatchers.Main.immediate) {
            onHotwordHit?.invoke(ChineseVoiceHotwordHit(category, text))
            onStatusChanged?.invoke("语音命中：${category.displayName}（第三方：$text）")
        }
    }

    private fun matchChineseHotword(text: String): ChineseVoiceHotwordCategory? {
        val normalized = text.filterNot { it.isWhitespace() || it in CHINESE_PUNCTUATION }
        return when {
            normalized.contains("扫描旗杆") -> ChineseVoiceHotwordCategory.SCAN
            normalized.contains("扫描") -> ChineseVoiceHotwordCategory.SCAN
            normalized.contains("测距") -> ChineseVoiceHotwordCategory.DISTANCE
            else -> null
        }
    }

    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("AudioRecord 不支持 16kHz PCM16 单声道")
        }
        val bufferSize = max(minBuffer, SAMPLE_RATE * BYTES_PER_PCM16_SAMPLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.also { recorder ->
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("AudioRecord 初始化失败")
            }
        }
    }

    private fun releaseAudioAndEngine() {
        audioRecord?.releaseSafely()
        audioRecord = null
        nativeEngine?.release()
        nativeEngine = null
    }

    private fun AudioRecord.stopSafely() {
        try {
            stop()
        } catch (_: Exception) {
        }
    }

    private fun AudioRecord.releaseSafely() {
        try {
            stop()
        } catch (_: Exception) {
        }
        try {
            release()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "SherpaHotword"
        const val SAMPLE_RATE = 16_000
        const val VAD_WINDOW_SIZE = 512
        const val BYTES_PER_PCM16_SAMPLE = 2
        const val PCM_FLOAT_SCALE = 32768f
        const val HIT_DEBOUNCE_MS = 1800L
        val CHINESE_PUNCTUATION = setOf('，', '。', '！', '？', '、', ',', '.', '!', '?', ';', '；', ':', '：')
    }
}
