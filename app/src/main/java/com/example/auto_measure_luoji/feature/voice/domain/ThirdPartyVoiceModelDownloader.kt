package com.example.auto_measure_luoji.feature.voice.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class ThirdPartyVoiceModelProgress(
    val progressPercent: Int?,
    val message: String,
)

data class ThirdPartyVoiceModelPaths(
    val senseVoiceModelPath: String,
    val senseVoiceTokensPath: String,
    val vadModelPath: String,
)

class ThirdPartyVoiceModelDownloader(
    private val context: Context,
) {
    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        modelPaths().allFilesExist()
    }

    suspend fun getModelPaths(): ThirdPartyVoiceModelPaths = withContext(Dispatchers.IO) {
        modelPaths()
    }

    suspend fun prepareDownloadedPackageIfPossible(
        onProgress: (ThirdPartyVoiceModelProgress) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (modelPaths().allFilesExist()) return@withContext true
        val dir = modelsDir()
        val archiveFile = File(dir, SENSE_VOICE_ARCHIVE_NAME)
        val vadFile = File(dir, VAD_FILE_NAME)
        if (!archiveFile.isFile || !vadFile.isFile) return@withContext false
        extractSenseVoiceIfNeeded(
            archiveFile = archiveFile,
            outputDir = dir,
            onProgress = onProgress,
        )
        modelPaths().allFilesExist()
    }

    suspend fun downloadAll(onProgress: (ThirdPartyVoiceModelProgress) -> Unit) {
        withContext(Dispatchers.IO) {
            val dir = modelsDir().apply { mkdirs() }
            downloadFile(
                spec = ModelFileSpec(
                    displayName = "Silero VAD",
                    url = VAD_URL,
                    targetFile = File(dir, VAD_FILE_NAME),
                    globalStart = 0,
                    globalWeight = 5,
                ),
                onProgress = onProgress,
            )
            downloadFile(
                spec = ModelFileSpec(
                    displayName = "SenseVoice INT8",
                    url = SENSE_VOICE_URL,
                    targetFile = File(dir, SENSE_VOICE_ARCHIVE_NAME),
                    globalStart = 5,
                    globalWeight = 95,
                ),
                onProgress = onProgress,
            )
            extractSenseVoiceIfNeeded(
                archiveFile = File(dir, SENSE_VOICE_ARCHIVE_NAME),
                outputDir = dir,
                onProgress = onProgress,
            )
            if (!modelPaths().allFilesExist()) {
                throw IllegalStateException("第三方语音包解压不完整：缺少 model.int8.onnx 或 tokens.txt")
            }
            onProgress(ThirdPartyVoiceModelProgress(100, "第三方语音包下载完成"))
        }
    }

    private fun modelsDir(): File {
        return File(context.filesDir, MODELS_DIR_NAME)
    }

    private fun modelPaths(): ThirdPartyVoiceModelPaths {
        val dir = modelsDir()
        val senseVoiceDir = File(dir, SENSE_VOICE_DIR)
        return ThirdPartyVoiceModelPaths(
            senseVoiceModelPath = File(senseVoiceDir, SENSE_VOICE_MODEL_FILE_NAME).absolutePath,
            senseVoiceTokensPath = File(senseVoiceDir, SENSE_VOICE_TOKENS_FILE_NAME).absolutePath,
            vadModelPath = File(dir, VAD_FILE_NAME).absolutePath,
        )
    }

    private fun ThirdPartyVoiceModelPaths.allFilesExist(): Boolean {
        return File(vadModelPath).isFile &&
            File(senseVoiceModelPath).isFile &&
            File(senseVoiceTokensPath).isFile
    }

    private suspend fun extractSenseVoiceIfNeeded(
        archiveFile: File,
        outputDir: File,
        onProgress: (ThirdPartyVoiceModelProgress) -> Unit,
    ) {
        if (modelPaths().allFilesExist()) {
            onProgress(ThirdPartyVoiceModelProgress(99, "SenseVoice 已解压"))
            return
        }
        if (!archiveFile.isFile) {
            throw IllegalStateException("SenseVoice 压缩包不存在，无法解压")
        }

        onProgress(ThirdPartyVoiceModelProgress(98, "正在解压 SenseVoice 模型..."))
        val baseDir = outputDir.canonicalFile
        BufferedInputStream(FileInputStream(archiveFile)).use { fileInput ->
            BZip2CompressorInputStream(fileInput).use { bzInput ->
                TarArchiveInputStream(bzInput).use { tarInput ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tarInput.nextTarEntry ?: break
                        val target = File(baseDir, entry.name).canonicalFile
                        if (!target.path.startsWith(baseDir.path + File.separator)) {
                            throw IllegalStateException("SenseVoice 压缩包包含非法路径：${entry.name}")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                tarInput.copyTo(output, BUFFER_SIZE)
                            }
                        }
                    }
                }
            }
        }
        onProgress(ThirdPartyVoiceModelProgress(99, "SenseVoice 模型解压完成"))
    }

    private suspend fun downloadFile(
        spec: ModelFileSpec,
        onProgress: (ThirdPartyVoiceModelProgress) -> Unit,
    ) {
        if (spec.targetFile.isFile) {
            onProgress(ThirdPartyVoiceModelProgress(spec.globalStart + spec.globalWeight, "${spec.displayName} 已存在"))
            return
        }

        val partFile = File(spec.targetFile.absolutePath + PART_SUFFIX)
        partFile.parentFile?.mkdirs()
        var existingBytes = partFile.length().coerceAtLeast(0L)
        var connection = openConnection(spec.url, existingBytes)

        if (connection.responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
            partFile.delete()
            existingBytes = 0L
            connection.disconnect()
            connection = openConnection(spec.url, existingBytes)
        }

        val append = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!append && existingBytes > 0L) {
            partFile.delete()
            existingBytes = 0L
        }

        val totalBytes = resolveTotalBytes(connection, existingBytes)
        val mode = if (append) "断点续传" else "开始下载"
        onProgress(
            ThirdPartyVoiceModelProgress(
                spec.toGlobalPercent(existingBytes, totalBytes),
                "$mode ${spec.displayName}",
            )
        )

        try {
            connection.inputStream.use { input ->
                RandomAccessFile(partFile, "rw").use { output ->
                    if (append) {
                        output.seek(existingBytes)
                    } else {
                        output.setLength(0L)
                    }

                    val buffer = ByteArray(BUFFER_SIZE)
                    var received = existingBytes
                    var lastProgressEmitBytes = existingBytes
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read.toLong()
                        if (received - lastProgressEmitBytes >= PROGRESS_EMIT_BYTES ||
                            (totalBytes > 0L && received >= totalBytes)
                        ) {
                            lastProgressEmitBytes = received
                            onProgress(
                                ThirdPartyVoiceModelProgress(
                                    spec.toGlobalPercent(received, totalBytes),
                                    spec.progressText(received, totalBytes),
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (totalBytes > 0L && partFile.length() < totalBytes) {
            throw IllegalStateException("${spec.displayName} 下载中断，已保留 ${formatMb(partFile.length())}，再次下载会继续")
        }

        if (spec.targetFile.exists()) spec.targetFile.delete()
        if (!partFile.renameTo(spec.targetFile)) {
            throw IllegalStateException("${spec.displayName} 下载完成但保存失败")
        }
    }

    private fun openConnection(url: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        if (resumeFrom > 0L) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        connection.connect()
        if (connection.responseCode !in SUCCESS_CODES) {
            throw IllegalStateException("服务器返回 HTTP ${connection.responseCode}")
        }
        return connection
    }

    private fun resolveTotalBytes(connection: HttpURLConnection, existingBytes: Long): Long {
        parseContentRangeTotal(connection.getHeaderField("Content-Range"))?.let { return it }
        val contentLength = connection.contentLengthLong
        return when {
            contentLength > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL -> existingBytes + contentLength
            contentLength > 0L -> contentLength
            else -> -1L
        }
    }

    private fun parseContentRangeTotal(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash == contentRange.lastIndex) return null
        return contentRange.substring(slash + 1).toLongOrNull()
    }

    private data class ModelFileSpec(
        val displayName: String,
        val url: String,
        val targetFile: File,
        val globalStart: Int,
        val globalWeight: Int,
    ) {
        fun toGlobalPercent(received: Long, total: Long): Int? {
            if (total <= 0L) return null
            val filePercent = (received.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            return (globalStart + filePercent * globalWeight).roundToInt().coerceIn(0, 100)
        }

        fun progressText(received: Long, total: Long): String {
            val receivedMb = formatMb(received)
            return if (total > 0L) {
                "$displayName 下载中：$receivedMb / ${formatMb(total)}"
            } else {
                "$displayName 下载中：$receivedMb"
            }
        }
    }

    private companion object {
        const val MODELS_DIR_NAME = "sherpa_models"
        const val PART_SUFFIX = ".part"
        const val BUFFER_SIZE = 128 * 1024
        const val PROGRESS_EMIT_BYTES = 512 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val USER_AGENT = "AutoMeasure/voice-model-downloader"
        val SUCCESS_CODES = setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL, HTTP_RANGE_NOT_SATISFIABLE)

        const val VAD_FILE_NAME = "silero_vad.onnx"
        const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$VAD_FILE_NAME"

        const val SENSE_VOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        const val SENSE_VOICE_MODEL_FILE_NAME = "model.int8.onnx"
        const val SENSE_VOICE_TOKENS_FILE_NAME = "tokens.txt"
        const val SENSE_VOICE_ARCHIVE_NAME = "$SENSE_VOICE_DIR.tar.bz2"
        const val SENSE_VOICE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$SENSE_VOICE_ARCHIVE_NAME"
    }
}

private fun formatMb(bytes: Long): String {
    return "%.1f MB".format(bytes.toDouble().coerceAtLeast(0.0) / 1024.0 / 1024.0)
}
