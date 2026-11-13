package com.lucas.asrcapsule.audio

import java.io.ByteArrayOutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 20ms 帧能量 VAD (语音活动检测器)
 * 纯计算、无 Android 框架依赖、高精度、高响应
 * 规格：
 * - 16000Hz / 16bit / 单声道
 * - 20ms = 320 samples = 640 bytes
 * - 双阈值滞回 + 自适应噪声底跟踪
 * - 预卷 200ms / 尾垫 200ms
 */
class VadDetector(
    private val config: VadConfig = VadConfig()
) {

    data class VadConfig(
        val sampleRate: Int = 16000,
        val frameMs: Int = 20,                          // 20ms 帧长
        val initialBlankFrames: Int = 6,                // 启动前 6 帧 (120ms) 屏蔽按键回弹声与起振马达杂音
        val initialNoiseFloorDbfs: Double = -52.0,     // 初始噪声底 -52 dBFS
        val minNoiseFloorDbfs: Double = -62.0,
        val maxNoiseFloorDbfs: Double = -28.0,
        val speechStartDeltaDb: Double = 9.5,          // 噪声底 + 9.5dB 起步
        val speechEndDeltaDb: Double = 6.0,            // 噪声底 + 6dB 结束滞回
        val minSpeechStartDbfs: Double = -40.0,        // 启始阈值下限 (过滤轻微呼吸与握持摩擦)
        val minSpeechEndDbfs: Double = -44.0,          // 结束阈值下限
        val confirmFramesStart: Int = 3,               // 连续 3 帧 (60ms) 确认发音，拒绝瞬态咔哒声
        val hangoverFramesEnd: Int = 16,               // 连续 16 帧 (320ms) 确认静音
        val minSpeechFrames: Int = 10,                 // 最短有效有声帧 200ms (10 帧真实发声，不含尾静音)
        val maxSpeechFrames: Int = 1500,               // 最长单段语音 30s (1500 帧)
        val preRollFrames: Int = 10,                   // 预卷 200ms (10 帧)
        val postRollFrames: Int = 10                   // 尾垫 200ms (10 帧)
    )

    data class Segment(
        val token: Int,
        val pcm: ByteArray,
        val durationMs: Long,
        val isFinal: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Segment
            return token == other.token && pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int {
            var result = token
            result = 31 * result + pcm.contentHashCode()
            return result
        }
    }

    sealed class VadEvent {
        data class SpeechStart(val timestampMs: Long) : VadEvent()
        data class SpeechEnd(val segment: Segment) : VadEvent()
        data class LevelUpdate(val level: Float, val dbfs: Float) : VadEvent()
    }

    private val samplesPerFrame = (config.sampleRate * config.frameMs) / 1000 // 320 samples
    private val bytesPerFrame = samplesPerFrame * 2                           // 640 bytes

    private var noiseFloor = config.initialNoiseFloorDbfs
    private var isSpeaking = false
    private var speechConfirmCount = 0
    private var hangoverCount = 0
    private var speechFrameCount = 0
    private var voicedFrameCount = 0

    private var pendingChunk = ByteArrayOutputStream()
    private val recentHistoryFrames = ArrayDeque<ByteArray>() // 保留预卷的历史帧
    private var activeSpeechBuffer = ByteArrayOutputStream()

    private var tokenSequence = 0
    private var processedFrames = 0L

    fun reset() {
        noiseFloor = config.initialNoiseFloorDbfs
        isSpeaking = false
        speechConfirmCount = 0
        hangoverCount = 0
        speechFrameCount = 0
        voicedFrameCount = 0
        pendingChunk.reset()
        recentHistoryFrames.clear()
        activeSpeechBuffer.reset()
        tokenSequence = 0
        processedFrames = 0L
    }

    /**
     * 推入采集到的 PCM 数据并提取 VAD 事件
     */
    fun push(pcm: ByteArray, length: Int): List<VadEvent> {
        val events = mutableListOf<VadEvent>()
        pendingChunk.write(pcm, 0, length)

        val fullData = pendingChunk.toByteArray()
        var offset = 0

        while (offset + bytesPerFrame <= fullData.size) {
            val frameBytes = fullData.copyOfRange(offset, offset + bytesPerFrame)
            offset += bytesPerFrame
            val frameEvents = processFrame(frameBytes)
            events.addAll(frameEvents)
        }

        pendingChunk.reset()
        if (offset < fullData.size) {
            pendingChunk.write(fullData, offset, fullData.size - offset)
        }

        return events
    }

    private fun processFrame(frameBytes: ByteArray): List<VadEvent> {
        val events = mutableListOf<VadEvent>()
        processedFrames++

        // 0. 屏蔽启动前 6 帧 (120ms)：彻底剔除实体按键松开回弹声、马达起振嗡鸣与麦克风上电瞬态脉冲
        if (processedFrames <= config.initialBlankFrames) {
            events.add(VadEvent.LevelUpdate(0f, config.initialNoiseFloorDbfs.toFloat()))
            return events
        }

        // 1. 计算 RMS 与 dBFS
        var sumSquares = 0.0
        var i = 0
        while (i < frameBytes.size) {
            val s = (frameBytes[i].toInt() and 0xFF) or (frameBytes[i + 1].toInt() shl 8)
            val sample = s.toShort().toDouble()
            sumSquares += sample * sample
            i += 2
        }

        val rms = sqrt(sumSquares / samplesPerFrame)
        val dbfs = 20.0 * log10((rms / 32768.0).coerceAtLeast(1e-9))
        // 对数分贝曲线映射：将实际人声范围 (-50dBFS ~ -16dBFS) 平滑映射到 0.0 ~ 1.0，让声波柱饱满起伏跳动
        val dbfsClamped = ((dbfs - (-50.0)) / 34.0).coerceIn(0.0, 1.0)
        val normalizedLevel = sqrt(dbfsClamped).toFloat()

        events.add(VadEvent.LevelUpdate(normalizedLevel, dbfs.toFloat()))

        // 2. 自适应更新噪声底（使用平滑滤波，防止单帧极小值或瞬态杂音拉偏阈值）
        if (!isSpeaking) {
            if (processedFrames <= config.initialBlankFrames + 8) {
                val safeInitDbfs = dbfs.coerceIn(config.minNoiseFloorDbfs, -36.0)
                noiseFloor = noiseFloor * 0.65 + safeInitDbfs * 0.35
            } else if (dbfs < noiseFloor) {
                noiseFloor += (dbfs - noiseFloor) * 0.005 // 缓慢下降
            } else if (dbfs < noiseFloor + config.speechEndDeltaDb) {
                noiseFloor += (dbfs - noiseFloor) * 0.02  // 仅在非说话能量区间内平稳跟随背景噪声
            }
            noiseFloor = noiseFloor.coerceIn(config.minNoiseFloorDbfs, config.maxNoiseFloorDbfs)
        }

        // 3. 动态阈值判定
        val startThresh = max(noiseFloor + config.speechStartDeltaDb, config.minSpeechStartDbfs)
        val endThresh = max(noiseFloor + config.speechEndDeltaDb, config.minSpeechEndDbfs)

        // 4. 状态转移
        if (!isSpeaking) {
            // 缓存历史帧供段首预卷
            recentHistoryFrames.addLast(frameBytes)
            if (recentHistoryFrames.size > config.preRollFrames) {
                recentHistoryFrames.removeFirst()
            }

            if (dbfs >= startThresh) {
                speechConfirmCount++
                if (speechConfirmCount >= config.confirmFramesStart) {
                    isSpeaking = true
                    voicedFrameCount = speechConfirmCount
                    speechConfirmCount = 0
                    hangoverCount = 0
                    speechFrameCount = recentHistoryFrames.size

                    activeSpeechBuffer.reset()
                    // 写入预卷帧，绝不吞开头第一个字
                    for (historyFrame in recentHistoryFrames) {
                        activeSpeechBuffer.write(historyFrame)
                    }

                    val nowMs = processedFrames * config.frameMs
                    events.add(VadEvent.SpeechStart(nowMs))
                }
            } else {
                speechConfirmCount = 0
            }
        } else {
            // 正在说话中
            activeSpeechBuffer.write(frameBytes)
            speechFrameCount++

            if (dbfs < endThresh) {
                hangoverCount++
                if (hangoverCount >= config.hangoverFramesEnd || speechFrameCount >= config.maxSpeechFrames) {
                    // 满足 320ms 静音或达到单段 30s 上限 -> 触发语音结束
                    isSpeaking = false
                    hangoverCount = 0
                    speechConfirmCount = 0

                    // 严格校验真实有声帧数 (voicedFrameCount)，剔除尾静音帧充数导致的短促杂音/呼吸声误触发
                    if (voicedFrameCount >= config.minSpeechFrames) {
                        val segmentPcm = activeSpeechBuffer.toByteArray()
                        val durationMs = (speechFrameCount * config.frameMs).toLong()
                        tokenSequence++
                        val segment = Segment(
                            token = tokenSequence,
                            pcm = segmentPcm,
                            durationMs = durationMs
                        )
                        events.add(VadEvent.SpeechEnd(segment))
                    }
                    // 无论有效与否，语句段归档后必须立即重置缓冲区与帧计数，杜绝末尾 flush 重复合并产生复读
                    activeSpeechBuffer.reset()
                    speechFrameCount = 0
                    voicedFrameCount = 0
                }
            } else {
                hangoverCount = 0
                voicedFrameCount++
            }
        }

        return events
    }

    /**
     * 强制切出当前正在说的数据段 (例如会话结束时或 90s 上限)
     */
    fun flushCurrentSegment(isFinal: Boolean = false): Segment? {
        val hasValidVoice = activeSpeechBuffer.size() > 0 && voicedFrameCount >= config.minSpeechFrames
        val segment = if (hasValidVoice) {
            tokenSequence++
            Segment(
                token = tokenSequence,
                pcm = activeSpeechBuffer.toByteArray(),
                durationMs = (speechFrameCount * config.frameMs).toLong(),
                isFinal = isFinal
            )
        } else {
            null
        }
        activeSpeechBuffer.reset()
        isSpeaking = false
        speechConfirmCount = 0
        hangoverCount = 0
        speechFrameCount = 0
        voicedFrameCount = 0
        return segment
    }

    fun isSpeakingNow(): Boolean = isSpeaking
}
