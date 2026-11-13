package com.lucas.asrcapsule.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PcmUtils {

    /**
     * 将 16 位小端 PCM 字节数组转换为 sherpa-onnx 所需的归一化 FloatArray (-1.0f ~ 1.0f)
     */
    fun pcm16ToFloatArray(pcmBytes: ByteArray, length: Int = pcmBytes.size): FloatArray {
        val shortCount = length / 2
        val floatArray = FloatArray(shortCount)
        val byteBuffer = ByteBuffer.wrap(pcmBytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until shortCount) {
            val sample = byteBuffer.short
            floatArray[i] = sample / 32768.0f
        }
        return floatArray
    }

    /**
     * 将 PCM 数据流写入本地临时文件，避免大音频通过 Binder 传输导致 TransactionTooLargeException
     */
    fun writePcmToFile(file: File, pcmData: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            fos.write(pcmData)
            fos.flush()
        }
    }
}
