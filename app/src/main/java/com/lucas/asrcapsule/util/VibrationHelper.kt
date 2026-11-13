package com.lucas.asrcapsule.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 声触反馈引擎
 * 融合：
 * 1. 线性马达段落震感
 * 2. 毫秒级低分贝机械微音合成（遵循系统静音/振动模式）
 */
object VibrationHelper {

    private const val SAMPLE_RATE = 44100
    private const val SOUND_START = 1
    private const val SOUND_COMMIT = 2
    private const val SOUND_CANCEL = 3

    private val soundExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var trackStart: AudioTrack? = null
    @Volatile
    private var trackCommit: AudioTrack? = null
    @Volatile
    private var trackCancel: AudioTrack? = null
    @Volatile
    private var isAudioInitialized = false

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * 会话开始：清脆起振
     */
    fun performStart(context: Context) {
        if (!PreferenceHelper(context).isFeedbackEnabled) return

        playMicroSound(context, SOUND_START)

        try {
            val vibrator = getVibrator(context) ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(10, 140))
        } catch (_: Exception) {}
    }

    /**
     * 静音收尾预告
     */
    fun performPreEnd(context: Context) {
        if (!PreferenceHelper(context).isFeedbackEnabled) return

        try {
            val vibrator = getVibrator(context) ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(8, 65))
        } catch (_: Exception) {}
    }

    /**
     * 会话完成落笔：双咬合确认震感
     */
    fun performEnd(context: Context) {
        if (!PreferenceHelper(context).isFeedbackEnabled) return

        playMicroSound(context, SOUND_COMMIT)

        try {
            val vibrator = getVibrator(context) ?: return
            val timings = longArrayOf(0, 8, 25, 12)
            val amplitudes = intArrayOf(0, 160, 0, 220)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (_: Exception) {}
    }

    /**
     * 取消丢弃：消散微颤
     */
    fun performCancel(context: Context) {
        if (!PreferenceHelper(context).isFeedbackEnabled) return

        playMicroSound(context, SOUND_CANCEL)

        try {
            val vibrator = getVibrator(context) ?: return
            val timings = longArrayOf(0, 12, 40, 8)
            val amplitudes = intArrayOf(0, 160, 0, 80)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (_: Exception) {}
    }

    /**
     * 未识别到有效语音 / 异常报错
     */
    fun performError(context: Context) {
        if (!PreferenceHelper(context).isFeedbackEnabled) return

        try {
            val vibrator = getVibrator(context) ?: return
            val timings = longArrayOf(0, 8, 70, 8)
            val amplitudes = intArrayOf(0, 140, 0, 90)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (_: Exception) {}
    }

    /**
     * 用户在主页开启开关时的即时试听/试触
     */
    fun performPreview(context: Context, enabled: Boolean) {
        if (!enabled) return

        playMicroSound(context, SOUND_START)

        try {
            val vibrator = getVibrator(context) ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(10, 140))
        } catch (_: Exception) {}
    }

    fun performClickVibration(context: Context) = performStart(context)

    private fun playMicroSound(context: Context, soundType: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                return
            }

            soundExecutor.execute {
                try {
                    ensureAudioTracks()
                    val track = when (soundType) {
                        SOUND_START -> trackStart
                        SOUND_COMMIT -> trackCommit
                        SOUND_CANCEL -> trackCancel
                        else -> null
                    }
                    track?.let {
                        if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            it.stop()
                        }
                        it.reloadStaticData()
                        it.play()
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun ensureAudioTracks() {
        if (isAudioInitialized) return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val startBuffer = generateStartPcm()
        trackStart = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(startBuffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        trackStart?.write(startBuffer, 0, startBuffer.size)

        val commitBuffer = generateCommitPcm()
        trackCommit = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(commitBuffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        trackCommit?.write(commitBuffer, 0, commitBuffer.size)

        val cancelBuffer = generateCancelPcm()
        trackCancel = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(cancelBuffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        trackCancel?.write(cancelBuffer, 0, cancelBuffer.size)

        isAudioInitialized = true
    }

    private fun generateStartPcm(): ShortArray {
        val samples = (SAMPLE_RATE * 0.015).toInt()
        val buffer = ShortArray(samples)
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-t * 260.0)
            val sample = (sin(2.0 * PI * 1250.0 * t) * 0.7 + sin(2.0 * PI * 2500.0 * t) * 0.3) * env
            buffer[i] = (sample * 0.22 * Short.MAX_VALUE).toInt().toShort()
        }
        return buffer
    }

    private fun generateCommitPcm(): ShortArray {
        val samples = (SAMPLE_RATE * 0.035).toInt()
        val buffer = ShortArray(samples)
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            var sample = 0.0
            if (t < 0.008) {
                val env = exp(-t * 350.0)
                sample = sin(2.0 * PI * 1400.0 * t) * env * 0.20
            } else if (t in 0.016..0.034) {
                val t2 = t - 0.016
                val env = exp(-t2 * 280.0)
                sample = (sin(2.0 * PI * 1850.0 * t2) * 0.75 + sin(2.0 * PI * 3700.0 * t2) * 0.25) * env * 0.25
            }
            buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return buffer
    }

    private fun generateCancelPcm(): ShortArray {
        val samples = (SAMPLE_RATE * 0.020).toInt()
        val buffer = ShortArray(samples)
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-t * 180.0)
            val freq = (650.0 - t * 15000.0).coerceAtLeast(200.0)
            val sample = sin(2.0 * PI * freq * t) * env * 0.16
            buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return buffer
    }
}
