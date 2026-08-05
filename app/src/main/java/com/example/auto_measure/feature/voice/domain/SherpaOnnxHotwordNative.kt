package com.example.auto_measure.feature.voice.domain

internal class SherpaOnnxHotwordNative(
    modelPath: String,
    tokensPath: String,
    vadPath: String,
) {
    private var handle: Long = nativeCreate(modelPath, tokensPath, vadPath)

    fun acceptWaveform(samples: FloatArray): List<String> {
        val currentHandle = handle
        if (currentHandle == 0L) return emptyList()
        return nativeAcceptWaveform(currentHandle, samples).filter { it.isNotBlank() }
    }

    fun flush(): List<String> {
        val currentHandle = handle
        if (currentHandle == 0L) return emptyList()
        return nativeFlush(currentHandle).filter { it.isNotBlank() }
    }

    fun release() {
        val currentHandle = handle
        if (currentHandle == 0L) return
        handle = 0L
        nativeDestroy(currentHandle)
    }

    private external fun nativeCreate(modelPath: String, tokensPath: String, vadPath: String): Long
    private external fun nativeAcceptWaveform(handle: Long, samples: FloatArray): Array<String>
    private external fun nativeFlush(handle: Long): Array<String>
    private external fun nativeDestroy(handle: Long)

    private companion object {
        init {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("sherpa-onnx-cxx-api")
            System.loadLibrary("sherpa-onnx-c-api")
            System.loadLibrary("sherpa_hotword_jni")
        }
    }
}
