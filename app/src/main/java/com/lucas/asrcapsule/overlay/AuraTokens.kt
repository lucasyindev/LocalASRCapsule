package com.lucas.asrcapsule.overlay

/**
 * 「侧键小药丸」设计令牌与会话计时规格
 */
object AuraTokens {
    // 语义色
    const val COLOR_REC = 0xFFFF453A.toInt()         // 录音红
    const val COLOR_OK = 0xFF30D158.toInt()          // 确认绿
    const val COLOR_AMBER = 0xFFFF9F0A.toInt()       // 警告琥珀

    // 会话计时与动效时长 (ms)
    const val IGNITE_MS = 380L
    const val MAX_SESSION_MS = 90000L
    const val DUR_CANCELLED = 520L
    const val DUR_NO_SPEECH = 1100L
    const val DUR_ERROR = 1400L
}
