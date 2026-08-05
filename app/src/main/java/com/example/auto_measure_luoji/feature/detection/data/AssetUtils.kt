package com.example.auto_measure_luoji.feature.detection.data

import android.content.Context
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object AssetUtils {
    fun loadMappedFile(context: Context, filePath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filePath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun loadLabels(context: Context, filePath: String): List<String> {
        return context.assets.open(filePath).bufferedReader().useLines { it.toList() }
    }
}
