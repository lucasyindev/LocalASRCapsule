package com.lucas.asrcapsule.model

/**
 * 语音胶囊生命周期状态枚举 (按设计规格 12 状态)
 */
enum class CapsuleState {
    /** 空闲隐藏，无后台 UI 占用 (0 窗口、0 回调) */
    IDLE,

    /** 呼出瞬间点燃/微岛流出 (380ms)，音频已开录 */
    IGNITING,

    /** 倾听中，声浪/地平线光随声音能量跳动 */
    LISTENING,

    /** 停顿缝隙 (GAP)，语音停顿后收拢，并行启动 flush (1200ms) 与 end (3000ms) 计时 */
    GAP,

    /** 本地 SenseVoice 极速推理中 (扫光动效，~140ms) */
    THINKING,

    /** 落笔注入文本 (墨洇动效，240ms)，会话依然保持活跃继续倾听下句 */
    FLUSHING,

    /** 收尾预告 (2750ms)，微亮 + 12ms 极轻触觉，仍可被打断 */
    PRE_END,

    /** 会话自然收尾 (3000ms)，收拢下沉 */
    ENDING,

    /** 会话平滑关闭完成，准备销毁浮窗 (240ms) */
    CLOSED,

    /** 用户手势或长按主动取消本次未落笔语音 (下坠 + 120ms 淡红闪) */
    CANCELLED,

    /** 2500ms 内未检测到有效发音，温和退出 (琥珀脉冲 + "没听清") */
    NO_SPEECH,

    /** 推理异常或引擎通信异常 (琥珀脉冲 + "再说一次？") */
    ERROR;

    /**
     * 会话是否正处于活跃状态（未彻底结束）
     */
    val isSessionActive: Boolean
        get() = this == IGNITING || this == LISTENING || this == GAP ||
                this == THINKING || this == FLUSHING || this == PRE_END

    /**
     * 是否正在录音 / 接收用户声音
     */
    val isListening: Boolean
        get() = this == IGNITING || this == LISTENING || this == GAP || this == PRE_END

    // 兼容历史命名
    companion object {
        val RECORDING = LISTENING
        val RECOGNIZING = THINKING
        val SUCCESS = FLUSHING
    }
}
