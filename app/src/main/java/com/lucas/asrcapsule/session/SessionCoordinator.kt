package com.lucas.asrcapsule.session

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lucas.asrcapsule.audio.VadDetector
import com.lucas.asrcapsule.model.CapsuleState
import com.lucas.asrcapsule.overlay.AuraTokens
import java.util.ArrayDeque

/**
 * 语音会话状态机单一事实来源 (Single Source of Truth)
 * 严格按照 interaction-spec.md §4 与 §10 落地：
 * - 1200ms 落笔 (FLUSH) 与 3000ms 收尾 (END) 双轨并行计时器
 * - 会话最长 90s，支持持续 "边想边说"
 * - 任意恢复说话立即清掉两路计时，无缝回到 LISTENING
 * - 会话内长按 ≥400ms 取消当前句（已落笔文字保留）
 * - 会话内双击立即收尾落笔
 */
class SessionCoordinator(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val listener: SessionListener
) {

    companion object {
        private const val TAG = "SessionCoordinator"
    }

    interface SessionListener {
        fun onStateChanged(state: CapsuleState, detail: String? = null)
        fun onLevelChanged(level: Float)
        fun onDispatchAsr(segment: VadDetector.Segment, onResult: (text: String?, error: String?) -> Unit)
        fun onInjectText(text: String, isFinal: Boolean)
        fun onHapticRequest(type: String)
        fun onSessionClosed()
        fun onFlushCurrentSegment(): VadDetector.Segment?
    }

    private var currentState = CapsuleState.IDLE
    private var sessionId = 0

    // 计时器 Runnable
    private var igniteRunnable: Runnable? = null
    private var maxSessionRunnable: Runnable? = null
    private var stateTransitionRunnable: Runnable? = null

    // 分段与 ASR 队列
    private val pendingSegmentQueue = ArrayDeque<VadDetector.Segment>()
    private var hasSpokenInSession = false
    private var totalInjectedSegments = 0

    fun getState(): CapsuleState = currentState
    fun isSessionActive(): Boolean = currentState.isSessionActive

    /**
     * 开始新会话 (双击音量键或外部按键触发)
     */
    fun startSession() {
        cancelAllTimers()
        sessionId++
        pendingSegmentQueue.clear()
        hasSpokenInSession = false
        totalInjectedSegments = 0

        Log.i(TAG, "[$sessionId] 开启语音会话 -> IGNITING (用户掌控时序，永不自动静音退出)")
        transitionTo(CapsuleState.IGNITING)
        listener.onHapticRequest("start")

        // 1. 点燃 380ms 动效后进入 LISTENING
        igniteRunnable = Runnable {
            if (currentState == CapsuleState.IGNITING) {
                transitionTo(CapsuleState.LISTENING)
            }
        }.also { mainHandler.postDelayed(it, AuraTokens.IGNITE_MS) }

        // 2. 90s 会话硬上限防极端遗忘
        maxSessionRunnable = Runnable {
            if (isSessionActive()) {
                Log.i(TAG, "[$sessionId] 会话达 90s 上限，执行落笔与收尾")
                endSessionNow(isForced = true)
            }
        }.also { mainHandler.postDelayed(it, AuraTokens.MAX_SESSION_MS) }
    }

    /**
     * 处理 VAD 事件流
     */
    fun onVadEvent(event: VadDetector.VadEvent) {
        when (event) {
            is VadDetector.VadEvent.LevelUpdate -> {
                if (currentState == CapsuleState.LISTENING) {
                    listener.onLevelChanged(event.level)
                } else if (currentState == CapsuleState.GAP) {
                    // GAP 阶段维持微小底电平
                    listener.onLevelChanged(0f)
                }
            }

            is VadDetector.VadEvent.SpeechStart -> {
                hasSpokenInSession = true
                if (currentState == CapsuleState.GAP || currentState == CapsuleState.PRE_END) {
                    Log.i(TAG, "[$sessionId] 停顿后恢复说话 -> LISTENING")
                    transitionTo(CapsuleState.LISTENING)
                }
            }

            is VadDetector.VadEvent.SpeechEnd -> {
                val segment = event.segment
                Log.i(TAG, "[$sessionId] 语句片段归档 (token=${segment.token}, dur=${segment.durationMs}ms) -> GAP 静待用户指令")
                pendingSegmentQueue.offer(segment)
                transitionTo(CapsuleState.GAP)
            }
        }
    }

    /**
     * 用户主动触发结束与落笔 (点击屏幕 或 单击音量键)
     */
    fun endSessionNow(isForced: Boolean = false) {
        if (!isSessionActive()) return
        Log.i(TAG, "[$sessionId] 用户主动结束会话 -> 开始提取音频与推理落笔")
        cancelAllTimers()

        // 1. 抓取末尾正在说话中未静音切片的音频并立即准备推理（推理由 THINKING 状态负责停录）
        val trailingSegment = listener.onFlushCurrentSegment()
        if (trailingSegment != null) {
            pendingSegmentQueue.offer(trailingSegment)
        }

        if (pendingSegmentQueue.isEmpty()) {
            if (!hasSpokenInSession) {
                // 用户呼出后未开口发音再次按键 -> 视为主动关闭（硬开关行为），立即隐退消失！
                Log.i(TAG, "[$sessionId] 用户未开口说话再次按键 -> 视为主动关闭，立即隐退")
                dismissImmediately()
            } else {
                executeEnding()
            }
            return
        }

        // 2. 合并会话内的所有音频段，统一送入 SenseVoice 进行满血上下文推理
        val combinedPcmList = mutableListOf<ByteArray>()
        var totalBytes = 0
        while (pendingSegmentQueue.isNotEmpty()) {
            val seg = pendingSegmentQueue.poll() ?: continue
            combinedPcmList.add(seg.pcm)
            totalBytes += seg.pcm.size
        }

        if (totalBytes == 0) {
            executeEnding()
            return
        }

        val mergedPcm = ByteArray(totalBytes)
        var offset = 0
        for (chunk in combinedPcmList) {
            System.arraycopy(chunk, 0, mergedPcm, offset, chunk.size)
            offset += chunk.size
        }

        val finalSegment = VadDetector.Segment(
            token = ++sessionId,
            pcm = mergedPcm,
            durationMs = (totalBytes / 32).toLong(), // 16kHz 16-bit mono = 32 bytes/ms
            isFinal = true
        )

        transitionTo(CapsuleState.THINKING)

        val currentSid = sessionId
        listener.onDispatchAsr(finalSegment) { text, error ->
            mainHandler.post {
                if (currentSid != sessionId) return@post
                if (error != null) {
                    Log.e(TAG, "[$currentSid] ASR 识别失败: $error")
                    handleError(error)
                    return@post
                }
                val cleanText = formatSegmentPunctuation(text ?: "", isFinal = true)
                if (cleanText.isNotEmpty()) {
                    totalInjectedSegments++
                    listener.onInjectText(cleanText, isFinal = true)
                    executeEnding()
                } else {
                    handleNoSpeech()
                }
            }
        }
    }

    private fun executeEnding() {
        transitionTo(CapsuleState.ENDING)
        listener.onHapticRequest("end")

        mainHandler.postDelayed({
            transitionTo(CapsuleState.CLOSED)
            mainHandler.postDelayed({
                transitionTo(CapsuleState.IDLE)
                listener.onSessionClosed()
            }, 50L)
        }, 50L)
    }

    /**
     * 会话内长按 ≥400ms -> 取消当前会话（丢弃全部录音，不修改笔记）
     */
    fun cancelCurrentSentence() {
        if (!isSessionActive()) return
        Log.i(TAG, "[$sessionId] 用户长按取消会话 -> CANCELLED")
        sessionId++
        pendingSegmentQueue.clear()
        cancelAllTimers()

        transitionTo(CapsuleState.CANCELLED, detail = "已取消")
        listener.onHapticRequest("cancel")

        mainHandler.postDelayed({
            transitionTo(CapsuleState.CLOSED)
            mainHandler.postDelayed({
                transitionTo(CapsuleState.IDLE)
                listener.onSessionClosed()
            }, 100L)
        }, AuraTokens.DUR_CANCELLED)
    }

    /**
     * 用户按键主动关闭（硬开关行为） -> 瞬时退场消失
     */
    fun dismissImmediately() {
        if (!isSessionActive()) return
        Log.i(TAG, "[$sessionId] 用户主动按键关闭 -> 瞬时退场")
        sessionId++
        pendingSegmentQueue.clear()
        cancelAllTimers()

        transitionTo(CapsuleState.CANCELLED, detail = "已取消")
        listener.onHapticRequest("cancel")

        mainHandler.postDelayed({
            transitionTo(CapsuleState.CLOSED)
            mainHandler.postDelayed({
                transitionTo(CapsuleState.IDLE)
                listener.onSessionClosed()
            }, 30L)
        }, 50L)
    }

    private fun handleNoSpeech() {
        cancelAllTimers()
        transitionTo(CapsuleState.NO_SPEECH, detail = "没听清")
        listener.onHapticRequest("error")

        mainHandler.postDelayed({
            transitionTo(CapsuleState.CLOSED)
            mainHandler.postDelayed({
                transitionTo(CapsuleState.IDLE)
                listener.onSessionClosed()
            }, 100L)
        }, AuraTokens.DUR_NO_SPEECH)
    }

    private fun handleError(message: String) {
        transitionTo(CapsuleState.ERROR, detail = "再说一次？")
        listener.onHapticRequest("error")

        mainHandler.postDelayed({
            transitionTo(CapsuleState.CLOSED)
            mainHandler.postDelayed({
                transitionTo(CapsuleState.IDLE)
                listener.onSessionClosed()
            }, 100L)
        }, AuraTokens.DUR_ERROR)
    }

    private fun transitionTo(nextState: CapsuleState, detail: String? = null) {
        currentState = nextState
        listener.onStateChanged(nextState, detail)
    }

    private fun formatSegmentPunctuation(rawText: String, isFinal: Boolean): String {
        var text = rawText.trim()
        if (text.isEmpty()) return ""

        // 智能转换半角标点为全角：保护小数点 (3.14)、版本号 (v1.2)、域名/文件名 (Node.js) 与千分位数字 (1,000)
        text = text.replace(Regex("(?<![0-9]),(?![0-9])"), "，")
            .replace(Regex("(?<![0-9a-zA-Z])\\.(?![0-9a-zA-Z])"), "。")
            .replace("?", "？")
            .replace("!", "！")

        // 去除段首不合理的逗号/句号/顿号
        while (text.startsWith("，") || text.startsWith("。") || text.startsWith("、")) {
            text = text.substring(1).trim()
        }

        if (text.isEmpty()) return ""

        // 短词条/标题/搜索词优化：若总长度 <= 10 且中间无分句标点，去除模型自带的末尾句号，避免污染搜索框、标题栏或简短回复
        if (isFinal && text.endsWith("。") && text.length <= 10) {
            val body = text.substring(0, text.length - 1)
            val hasInternalClause = body.contains("，") || body.contains("。") || body.contains("！") || body.contains("？") || body.contains("；")
            if (!hasInternalClause) {
                text = body
            }
        }

        return text
    }

    private fun cancelTimer(runnable: Runnable?) {
        runnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun cancelAllTimers() {
        cancelTimer(igniteRunnable)
        cancelTimer(maxSessionRunnable)
        cancelTimer(stateTransitionRunnable)
    }
}
