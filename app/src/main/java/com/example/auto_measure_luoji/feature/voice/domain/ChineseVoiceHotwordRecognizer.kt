package com.example.auto_measure_luoji.feature.voice.domain

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class ChineseVoiceHotwordCategory(val displayName: String) {
    DISTANCE("测距"),
    SCAN("扫描"),
}

data class ChineseVoiceHotwordHit(
    val category: ChineseVoiceHotwordCategory,
    val sourceText: String,
)

data class VoiceModelDownloadState(
    val inProgress: Boolean,
    val progressPercent: Int?,
    val message: String,
    val downloadAvailable: Boolean = true,
    val fallbackActive: Boolean = false,
)

class ChineseVoiceHotwordRecognizer(
    private val context: Context,
) : RecognitionListener {
    var onStatusChanged: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null
    var onLanguageModelRequired: ((String) -> Unit)? = null
    var onLanguageModelDownloadChanged: ((VoiceModelDownloadState) -> Unit)? = null
    var onHotwordHit: ((ChineseVoiceHotwordHit) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val recognizerHolder = lazy(LazyThreadSafetyMode.NONE) {
        SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(this@ChineseVoiceHotwordRecognizer)
        }
    }
    private val recognizer: SpeechRecognizer
        get() = recognizerHolder.value
    private val thirdPartyModelDownloader = ThirdPartyVoiceModelDownloader(context)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var thirdPartyRecognizer: SherpaOnnxHotwordRecognizer? = null
    private var thirdPartyStartJob: Job? = null
    private var active = false
    private var supportChecked = false
    private var currentlyListening = false
    private var destroyed = false
    private var preferOfflineRecognition = true
    private var usingThirdPartyRecognition = false
    private var systemLanguageModelDownloadUnsupported = prefs.getBoolean(KEY_SYSTEM_MODEL_DOWNLOAD_UNSUPPORTED, false)
    private var thirdPartyDownloadJob: Job? = null
    private var lastHitCategory: ChineseVoiceHotwordCategory? = null
    private var lastHitAtMs: Long = 0L
    private var sessionId: Long = 0L
    private var sessionWatchdog: Runnable? = null
    private var pendingStartRunnable: Runnable? = null
    private var recognizerReleaseUntilMs: Long = 0L
    private var speechStartedInSession = false

    fun start() {
        if (destroyed) return
        active = true
        onListeningChanged?.invoke(true)
        thirdPartyStartJob?.cancel()
        thirdPartyStartJob = downloadScope.launch {
            if (startThirdPartyRecognitionIfReady()) return@launch

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                promptThirdPartyDownloadForUnavailableSystem()
                return@launch
            }

            usingThirdPartyRecognition = false
            if (systemLanguageModelDownloadUnsupported) {
                supportChecked = true
                preferOfflineRecognition = false
                startListeningInternal()
                return@launch
            }
            if (supportChecked || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                startListeningInternal()
            } else {
                checkChineseLanguageModel()
            }
        }
    }

    private fun promptThirdPartyDownloadForUnavailableSystem() {
        active = false
        currentlyListening = false
        usingThirdPartyRecognition = false
        onListeningChanged?.invoke(false)
        onLanguageModelDownloadChanged?.invoke(
            VoiceModelDownloadState(
                inProgress = false,
                progressPercent = null,
                message = "系统语音识别不可用，且第三方语音包未就绪；请下载第三方语音包后使用本地识别",
                downloadAvailable = true,
                fallbackActive = true,
            )
        )
        onStatusChanged?.invoke("语音识别不可用：请下载第三方语音包")
    }

    fun stop() {
        active = false
        currentlyListening = false
        usingThirdPartyRecognition = false
        thirdPartyStartJob?.cancel()
        thirdPartyStartJob = null
        thirdPartyRecognizer?.stop()
        onListeningChanged?.invoke(false)
        mainHandler.removeCallbacksAndMessages(null)
        sessionWatchdog = null
        pendingStartRunnable = null
        sessionId++
        speechStartedInSession = false
        recognizerReleaseUntilMs = android.os.SystemClock.elapsedRealtime() + CANCEL_RELEASE_DELAY_MS
        cancelSystemRecognizerIfCreated()
        onStatusChanged?.invoke("语音识别已停止")
    }

    fun requestChineseLanguageModelDownload() {
        if (systemLanguageModelDownloadUnsupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestThirdPartyVoiceModelDownload()
            return
        }

        active = true
        currentlyListening = false
        onListeningChanged?.invoke(false)
        onLanguageModelDownloadChanged?.invoke(VoiceModelDownloadState(true, null, "正在请求下载中文语音包..."))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            recognizer.triggerModelDownload(
                buildRecognizerIntent(preferOffline = true),
                ContextCompat.getMainExecutor(context),
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {
                        onLanguageModelDownloadChanged?.invoke(
                            VoiceModelDownloadState(true, completedPercent.coerceIn(0, 100), "中文语音包下载中")
                        )
                    }

                    override fun onSuccess() {
                        supportChecked = true
                        onLanguageModelDownloadChanged?.invoke(VoiceModelDownloadState(false, 100, "中文语音包下载完成"))
                        onStatusChanged?.invoke("中文语音包下载完成，开始持续识别")
                        if (active) startListeningInternal()
                    }

                    override fun onScheduled() {
                        onLanguageModelDownloadChanged?.invoke(
                            VoiceModelDownloadState(true, null, "中文语音包已加入系统下载队列，请保持网络可用")
                        )
                    }

                    override fun onError(error: Int) {
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                            systemLanguageModelDownloadUnsupported = true
                            persistSystemLanguageModelUnsupported()
                            fallbackToOnlineRecognition("当前系统语音服务不支持下载中文离线语言包（${speechErrorName(error)}=$error）")
                            requestThirdPartyVoiceModelDownload()
                        } else {
                            onLanguageModelDownloadChanged?.invoke(
                                VoiceModelDownloadState(false, null, "中文语音包下载失败：${speechErrorName(error)}=$error")
                            )
                            onStatusChanged?.invoke("中文语音包下载失败：${speechErrorName(error)}=$error")
                        }
                    }
                },
            )
        } else {
            recognizer.triggerModelDownload(buildRecognizerIntent(preferOffline = true))
            onLanguageModelDownloadChanged?.invoke(
                VoiceModelDownloadState(true, null, "中文语音包下载已触发，当前系统不返回百分比进度")
            )
        }
    }

    fun requestThirdPartyVoiceModelDownload() {
        if (destroyed) return
        val currentJob = thirdPartyDownloadJob
        if (currentJob?.isActive == true) {
            onLanguageModelDownloadChanged?.invoke(
                VoiceModelDownloadState(
                    inProgress = true,
                    progressPercent = null,
                    message = "第三方语音包正在下载中，已支持断点续传",
                    downloadAvailable = true,
                    fallbackActive = true,
                )
            )
            return
        }

        active = true
        preferOfflineRecognition = false
        onListeningChanged?.invoke(true)
        onLanguageModelDownloadChanged?.invoke(
            VoiceModelDownloadState(
                inProgress = true,
                progressPercent = null,
                message = "正在连接第三方语音包服务器...",
                downloadAvailable = true,
                fallbackActive = true,
            )
        )

        thirdPartyDownloadJob = downloadScope.launch {
            try {
                if (thirdPartyModelDownloader.isReady()) {
                    val paths = thirdPartyModelDownloader.getModelPaths()
                    onLanguageModelDownloadChanged?.invoke(
                        VoiceModelDownloadState(
                            inProgress = false,
                            progressPercent = 100,
                            message = "第三方语音包已就绪，正在使用 SenseVoice+VAD 本地识别",
                            downloadAvailable = true,
                            fallbackActive = true,
                        )
                    )
                    if (active) startThirdPartyRecognition(paths, "第三方语音包已就绪，切换到 SenseVoice+VAD")
                    return@launch
                }

                thirdPartyModelDownloader.downloadAll { progress ->
                    downloadScope.launch {
                        onLanguageModelDownloadChanged?.invoke(
                            VoiceModelDownloadState(
                                inProgress = true,
                                progressPercent = progress.progressPercent,
                                message = progress.message,
                                downloadAvailable = true,
                                fallbackActive = true,
                            )
                        )
                    }
                }
                onLanguageModelDownloadChanged?.invoke(
                    VoiceModelDownloadState(
                        inProgress = false,
                        progressPercent = 100,
                        message = "第三方语音包下载并解压完成，正在使用 SenseVoice+VAD 本地识别",
                        downloadAvailable = true,
                        fallbackActive = true,
                    )
                )
                onStatusChanged?.invoke("第三方语音包下载完成，切换到 SenseVoice+VAD")
                if (active) {
                    startThirdPartyRecognition(
                        thirdPartyModelDownloader.getModelPaths(),
                        "第三方语音识别中（SenseVoice+VAD）：等待“测距”或“扫描”",
                    )
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(TAG, "third-party voice model download failed", e)
                onLanguageModelDownloadChanged?.invoke(
                    VoiceModelDownloadState(
                        inProgress = false,
                        progressPercent = null,
                        message = "第三方语音包下载失败：${e.message ?: "未知错误"}；再次点击下载会从已下载部分继续",
                        downloadAvailable = true,
                        fallbackActive = true,
                    )
                )
                onStatusChanged?.invoke("第三方语音包下载失败，可再次点击下载断点续传")
                if (active && !currentlyListening && SpeechRecognizer.isRecognitionAvailable(context)) {
                    scheduleStartAfterReleaseGuard()
                }
            }
        }
    }

    private suspend fun startThirdPartyRecognitionIfReady(): Boolean {
        if (!thirdPartyModelDownloader.isReady()) {
            val prepared = thirdPartyModelDownloader.prepareDownloadedPackageIfPossible { progress ->
                downloadScope.launch {
                    onLanguageModelDownloadChanged?.invoke(
                        VoiceModelDownloadState(
                            inProgress = true,
                            progressPercent = progress.progressPercent,
                            message = progress.message,
                            downloadAvailable = true,
                            fallbackActive = true,
                        )
                    )
                }
            }
            if (!prepared) return false
        }
        val paths = thirdPartyModelDownloader.getModelPaths()
        startThirdPartyRecognition(paths, "第三方语音包已就绪，使用 SenseVoice+VAD 本地识别")
        return true
    }

    private fun startThirdPartyRecognition(paths: ThirdPartyVoiceModelPaths, message: String) {
        if (!active || destroyed) return
        usingThirdPartyRecognition = true
        preferOfflineRecognition = false
        supportChecked = true
        currentlyListening = false
        speechStartedInSession = false
        sessionId++
        clearSegmentWatchdog()
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRunnable = null
        recognizerReleaseUntilMs = android.os.SystemClock.elapsedRealtime()
        cancelSystemRecognizerIfCreated()

        thirdPartyRecognizer?.destroy()
        thirdPartyRecognizer = SherpaOnnxHotwordRecognizer(context, paths).apply {
            onStatusChanged = { status -> this@ChineseVoiceHotwordRecognizer.onStatusChanged?.invoke(status) }
            onListeningChanged = { listening -> this@ChineseVoiceHotwordRecognizer.onListeningChanged?.invoke(listening) }
            onHotwordHit = { hit -> this@ChineseVoiceHotwordRecognizer.onHotwordHit?.invoke(hit) }
        }
        onStatusChanged?.invoke(message)
        thirdPartyRecognizer?.start()
    }

    fun destroy() {
        destroyed = true
        downloadScope.cancel()
        stop()
        thirdPartyRecognizer?.destroy()
        thirdPartyRecognizer = null
        if (recognizerHolder.isInitialized()) {
            try {
                recognizer.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun cancelSystemRecognizerIfCreated() {
        if (!recognizerHolder.isInitialized()) return
        try {
            recognizer.cancel()
        } catch (_: Exception) {
        }
    }

    private fun startListeningInternal() {
        if (!active || destroyed || usingThirdPartyRecognition) return
        if (currentlyListening) {
            Log.d(TAG, "skip startListening: previous session is still active")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < recognizerReleaseUntilMs) {
            scheduleStart(recognizerReleaseUntilMs - now)
            return
        }
        currentlyListening = true
        speechStartedInSession = false
        onListeningChanged?.invoke(true)
        val modeText = if (preferOfflineRecognition) "离线" else "系统默认"
        val currentSessionId = ++sessionId
        Log.d(TAG, "startListening: session=$currentSessionId mode=$modeText")
        onStatusChanged?.invoke("语音识别中（$modeText，系统循环）：等待“测距”或“扫描”")
        try {
            recognizer.startListening(buildRecognizerIntent(preferOfflineRecognition))
            scheduleSegmentWatchdog(currentSessionId)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            currentlyListening = false
            clearSegmentWatchdog()
            scheduleRestart("语音识别启动失败，正在重试")
        }
    }

    private fun scheduleRestart(message: String? = null) {
        if (!active || destroyed) return
        currentlyListening = false
        speechStartedInSession = false
        sessionId++
        clearSegmentWatchdog()
        message?.let { onStatusChanged?.invoke(it) }
        val delayMs = if (message?.contains("ERROR_RECOGNIZER_BUSY") == true) {
            BUSY_RESTART_DELAY_MS
        } else {
            RESTART_DELAY_MS
        }
        scheduleStart(delayMs)
    }

    private fun scheduleSegmentWatchdog(startedSessionId: Long) {
        clearSegmentWatchdog()
        val watchdog = Runnable {
            if (!active || destroyed || !currentlyListening || sessionId != startedSessionId) return@Runnable
            try {
                recognizer.cancel()
            } catch (_: Exception) {
            }
            recognizerReleaseUntilMs = android.os.SystemClock.elapsedRealtime() + CANCEL_RELEASE_DELAY_MS
            currentlyListening = false
            speechStartedInSession = false
            sessionId++
            scheduleStart(CANCEL_RELEASE_DELAY_MS, "语音监听达到 30 秒上限，继续下一轮监听")
        }
        sessionWatchdog = watchdog
        mainHandler.postDelayed(watchdog, VAD_MAX_SEGMENT_MS + RESTART_DELAY_MS)
    }

    private fun clearSegmentWatchdog() {
        sessionWatchdog?.let { mainHandler.removeCallbacks(it) }
        sessionWatchdog = null
    }

    private fun scheduleStartAfterReleaseGuard() {
        val now = android.os.SystemClock.elapsedRealtime()
        scheduleStart((recognizerReleaseUntilMs - now).coerceAtLeast(RESTART_DELAY_MS))
    }

    private fun scheduleStart(delayMs: Long, message: String? = null) {
        if (!active || destroyed) return
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        message?.let { onStatusChanged?.invoke(it) }
        val runnable = Runnable {
            pendingStartRunnable = null
            startListeningInternal()
        }
        pendingStartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    private fun checkChineseLanguageModel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            supportChecked = true
            startListeningInternal()
            return
        }

        onStatusChanged?.invoke("正在检查中文语音包...")
        recognizer.checkRecognitionSupport(
            buildRecognizerIntent(preferOffline = true),
            ContextCompat.getMainExecutor(context),
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    supportChecked = true
                    when {
                        recognitionSupport.installedOnDeviceLanguages.hasChineseLanguage() -> startListeningInternal()
                        recognitionSupport.pendingOnDeviceLanguages.hasChineseLanguage() -> {
                            onLanguageModelDownloadChanged?.invoke(
                                VoiceModelDownloadState(true, null, "中文语音包正在系统下载队列中")
                            )
                        }
                        recognitionSupport.supportedOnDeviceLanguages.hasChineseLanguage() -> {
                            active = false
                            currentlyListening = false
                            onListeningChanged?.invoke(false)
                            onLanguageModelRequired?.invoke("需要下载中文语音包后才能进行离线持续识别")
                        }
                        else -> fallbackToOnlineRecognition("当前系统语音服务不支持中文离线语言包")
                    }
                }

                override fun onError(error: Int) {
                    supportChecked = true
                    Log.w(TAG, "checkRecognitionSupport failed: ${speechErrorName(error)}=$error")
                    fallbackToOnlineRecognition("中文语音包支持检查失败（${speechErrorName(error)}=$error）")
                }
            },
        )
    }

    override fun onReadyForSpeech(params: Bundle?) {
        val modeText = if (preferOfflineRecognition) "离线" else "系统默认"
        onStatusChanged?.invoke("语音识别中（$modeText，系统循环）：请说“测距”或“扫描”")
    }

    override fun onBeginningOfSpeech() {
        speechStartedInSession = true
        onStatusChanged?.invoke("正在听取语音...")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        if (speechStartedInSession) {
            onStatusChanged?.invoke("正在解析语音...")
        } else {
            onStatusChanged?.invoke("等待语音输入...")
        }
    }

    override fun onError(error: Int) {
        if (!active) return
        Log.w(
            TAG,
            "onError: ${speechErrorName(error)}=$error, preferOffline=$preferOfflineRecognition, " +
                "speechStarted=$speechStartedInSession, currentlyListening=$currentlyListening",
        )
        val message = when (error) {
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "中文语音包不可用，请下载中文语言包"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限，无法语音识别"
            SpeechRecognizer.ERROR_NO_MATCH -> "未命中热词，继续识别"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音，继续识别"
            else -> "语音识别错误 $error，继续识别"
        }

        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
            if (preferOfflineRecognition) {
                fallbackToOnlineRecognition("中文离线语音包不可用（${speechErrorName(error)}=$error）")
            } else {
                currentlyListening = false
                speechStartedInSession = false
                sessionId++
                clearSegmentWatchdog()
                systemLanguageModelDownloadUnsupported = true
                persistSystemLanguageModelUnsupported()
                onLanguageModelDownloadChanged?.invoke(
                    VoiceModelDownloadState(
                        inProgress = false,
                        progressPercent = null,
                        message = "系统默认识别返回语言错误（${speechErrorName(error)}=$error），继续重试；可下载第三方 SenseVoice/VAD 语音包。",
                        downloadAvailable = true,
                        fallbackActive = true,
                    )
                )
                onStatusChanged?.invoke(message)
                scheduleStart(LANGUAGE_ERROR_RESTART_DELAY_MS)
            }
        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            stop()
            onStatusChanged?.invoke(message)
        } else {
            scheduleRestart(message)
        }
    }

    override fun onResults(results: Bundle?) {
        handleRecognitionBundle(results)
        scheduleRestart()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        handleRecognitionBundle(partialResults)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun handleRecognitionBundle(bundle: Bundle?) {
        val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        texts.firstNotNullOfOrNull { text -> matchChineseHotword(text)?.let { it to text } }
            ?.let { (category, text) -> emitHit(category, text) }
    }

    private fun fallbackToOnlineRecognition(reason: String) {
        Log.w(TAG, "fallbackToOnlineRecognition: $reason")
        supportChecked = true
        systemLanguageModelDownloadUnsupported = true
        persistSystemLanguageModelUnsupported()
        preferOfflineRecognition = false
        usingThirdPartyRecognition = false
        active = true
        currentlyListening = false
        speechStartedInSession = false
        sessionId++
        clearSegmentWatchdog()
        recognizerReleaseUntilMs = android.os.SystemClock.elapsedRealtime() + CANCEL_RELEASE_DELAY_MS
        onListeningChanged?.invoke(true)
        onLanguageModelDownloadChanged?.invoke(
            VoiceModelDownloadState(
                inProgress = false,
                progressPercent = null,
                message = "$reason，已切换为系统默认中文识别；可下载第三方 SenseVoice/VAD 语音包（支持断点续传）。",
                downloadAvailable = true,
                fallbackActive = true,
            )
        )
        onStatusChanged?.invoke("$reason，已切换为系统默认中文识别")
        try {
            recognizer.cancel()
        } catch (_: Exception) {
        }
        scheduleStart(CANCEL_RELEASE_DELAY_MS)
    }

    private fun persistSystemLanguageModelUnsupported() {
        prefs.edit().putBoolean(KEY_SYSTEM_MODEL_DOWNLOAD_UNSUPPORTED, true).apply()
    }

    private fun emitHit(category: ChineseVoiceHotwordCategory, text: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (category == lastHitCategory && now - lastHitAtMs < HIT_DEBOUNCE_MS) return
        lastHitCategory = category
        lastHitAtMs = now
        onHotwordHit?.invoke(ChineseVoiceHotwordHit(category, text))
        onStatusChanged?.invoke("语音命中：${category.displayName}（$text）")
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

    private fun List<String>.hasChineseLanguage(): Boolean {
        return any { lang ->
            val normalized = lang.lowercase()
            normalized == "zh" || normalized.startsWith("zh-") || normalized.startsWith("cmn")
        }
    }

    private fun buildRecognizerIntent(preferOffline: Boolean): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, CHINESE_LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, CHINESE_LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    private fun speechErrorName(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "ERROR_UNKNOWN"
        }
    }

    private companion object {
        const val TAG = "ChineseVoiceHotword"
        const val PREFS_NAME = "voice_hotword"
        const val KEY_SYSTEM_MODEL_DOWNLOAD_UNSUPPORTED = "system_model_download_unsupported"
        const val CHINESE_LANGUAGE = "zh-CN"
        const val RESTART_DELAY_MS = 450L
        const val BUSY_RESTART_DELAY_MS = 1_500L
        const val LANGUAGE_ERROR_RESTART_DELAY_MS = 2_000L
        const val CANCEL_RELEASE_DELAY_MS = 1_200L
        const val HIT_DEBOUNCE_MS = 1800L
        const val VAD_MAX_SEGMENT_MS = 30_000L
        val CHINESE_PUNCTUATION = setOf('，', '。', '！', '？', '、', ',', '.', '!', '?', ';', '；', ':', '：')
    }
}
