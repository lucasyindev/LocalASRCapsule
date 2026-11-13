package com.lucas.asrcapsule.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureManager(
    private val sampleRate: Int = 16000,
    var onPcmChunk: ((ByteArray, Int) -> Unit)? = null
) {

    companion object {
        private const val TAG = "AudioCaptureManager"
    }

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    /**
     * 第 0 毫秒极速开录抢跑
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording.get()) return true

        val bufferSize = minBufferSize.coerceAtLeast(sampleRate * 2 / 10) // 100ms 缓冲区

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "VOICE_RECOGNITION 音频源不可用，回退至标准 MIC")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                return false
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ByteArray(2048) // 约 64ms 数据块

                while (isRecording.get()) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        onPcmChunk?.invoke(buffer, readBytes)
                    }
                }
            }, "AudioRecordThread").apply {
                start()
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "启动录音异常: ${e.message}", e)
            stopRecording()
            return false
        }
    }

    /**
     * 停止录音并释放硬件音频通道
     */
    fun stopRecording() {
        if (!isRecording.getAndSet(false)) {
            return
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音异常: ${e.message}")
        } finally {
            audioRecord = null
            recordThread = null
        }
    }

    fun isRecordingNow(): Boolean = isRecording.get()
}
