package com.lucas.asrcapsule.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.lucas.asrcapsule.aidl.IASRCallback
import com.lucas.asrcapsule.aidl.IASRService
import com.lucas.asrcapsule.audio.AudioCaptureManager
import com.lucas.asrcapsule.audio.PcmUtils
import com.lucas.asrcapsule.audio.VadDetector
import com.lucas.asrcapsule.model.CapsuleState
import com.lucas.asrcapsule.model.ModelManager
import com.lucas.asrcapsule.overlay.ArtifactOverlayManager
import com.lucas.asrcapsule.session.SessionCoordinator
import com.lucas.asrcapsule.util.VibrationHelper
import java.io.File

/**
 * 主进程轻量无障碍服务 (< 5MB 内存常驻)
 * 职责：
 * 1. 亮屏/解锁态下的 一加左侧独立键 (KEYCODE_781) 监听与开关启停
 * 2. 20ms 帧能量 VAD 与 SessionCoordinator 状态机调度
 * 3. 原位光标直填 (AccessibilityInputConnection -> Node 注入 -> 剪贴板兜底 + 5s 延迟补射)
 */
class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceAccessibility"
        var instance: VoiceAccessibilityService? = null
            private set
    }

    private lateinit var overlayManager: ArtifactOverlayManager
    private lateinit var audioCapture: AudioCaptureManager
    private lateinit var sessionCoordinator: SessionCoordinator
    private val vadDetector = VadDetector()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var asrService: IASRService? = null
    private var isBoundWorker = false
    private var cachedTargetNode: AccessibilityNodeInfo? = null

    // 5 秒焦点智能延迟补射 (Delayed Auto-Inject)
    private var pendingDelayedInjectText: String? = null
    private var pendingDelayedInjectTimestamp: Long = 0L

    // 亮屏与解锁守卫时间戳 (防止按右侧电源键亮屏/解锁瞬间误触左侧独立键)
    @Volatile
    private var lastScreenWakeTimestamp: Long = 0L

    private fun isScreenInteractiveAndUnlocked(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val interactive = powerManager?.isInteractive == true
        val unlocked = keyguardManager?.isKeyguardLocked == false
        return interactive && unlocked
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "屏幕熄灭 -> 立即中止活动会话并静默重置窗口")
                    isSystemWindowsInitialized = false
                    knownSystemWindowIds.clear()
                    activeSeedlingWindowId = -1
                    if (sessionCoordinator.isSessionActive()) {
                        sessionCoordinator.dismissImmediately()
                    }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    lastScreenWakeTimestamp = SystemClock.uptimeMillis()
                    isSystemWindowsInitialized = false
                    knownSystemWindowIds.clear()
                    activeSeedlingWindowId = -1
                }
            }
        }
    }

    fun reloadModelIfNeeded() {
        val worker = asrService ?: return
        val modelFile = ModelManager.getModelFile(this) ?: return
        val tokensFile = ModelManager.getTokensFile(this) ?: return
        try {
            worker.initModel(modelFile.absolutePath, tokensFile.absolutePath)
            Log.i(TAG, "已热更新加载模型: ${modelFile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "热更新模型异常: ${e.message}")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "已连接到 :asr_worker 子进程")
            asrService = IASRService.Stub.asInterface(service)
            val modelFile = ModelManager.getModelFile(this@VoiceAccessibilityService)
            val tokensFile = ModelManager.getTokensFile(this@VoiceAccessibilityService)
            if (modelFile != null && tokensFile != null) {
                try {
                    asrService?.initModel(modelFile.absolutePath, tokensFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "预热模型异常: ${e.message}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, ":asr_worker 子进程已断开或自杀")
            asrService = null
            isBoundWorker = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        overlayManager = ArtifactOverlayManager(this)

        sessionCoordinator = SessionCoordinator(
            mainHandler = mainHandler,
            listener = object : SessionCoordinator.SessionListener {
                override fun onStateChanged(state: CapsuleState, detail: String?) {
                    overlayManager.setPhase(state, detail)
                    if (state == CapsuleState.THINKING) {
                        audioCapture.stopRecording()
                    }
                    if (state == CapsuleState.CLOSED || state == CapsuleState.IDLE) {
                        audioCapture.stopRecording()
                        overlayManager.notifySessionClosed()
                        mainHandler.postDelayed({
                            unbindAsrWorker()
                        }, 800L)
                    }
                }

                override fun onLevelChanged(level: Float) {
                    overlayManager.setLevel(level)
                }

                override fun onDispatchAsr(
                    segment: VadDetector.Segment,
                    onResult: (text: String?, error: String?) -> Unit
                ) {
                    sendSegmentToWorker(segment, onResult)
                }

                override fun onInjectText(text: String, isFinal: Boolean) {
                    handleTextOutput(text)
                }

                override fun onHapticRequest(type: String) {
                    when (type) {
                        "start" -> VibrationHelper.performStart(this@VoiceAccessibilityService)
                        "pre_end" -> VibrationHelper.performPreEnd(this@VoiceAccessibilityService)
                        "end" -> VibrationHelper.performEnd(this@VoiceAccessibilityService)
                        "cancel" -> VibrationHelper.performCancel(this@VoiceAccessibilityService)
                        "error" -> VibrationHelper.performError(this@VoiceAccessibilityService)
                    }
                }

                override fun onSessionClosed() {
                    audioCapture.stopRecording()
                }

                override fun onFlushCurrentSegment(): VadDetector.Segment? {
                    return vadDetector.flushCurrentSegment(isFinal = true)
                }
            }
        )

        audioCapture = AudioCaptureManager(
            sampleRate = 16000,
            onPcmChunk = { buffer, readBytes ->
                if (sessionCoordinator.isSessionActive()) {
                    val events = vadDetector.push(buffer, readBytes)
                    for (event in events) {
                        sessionCoordinator.onVadEvent(event)
                    }
                }
            }
        )

        Log.i(TAG, "VoiceAccessibilityService 已就绪，亮屏守卫与原位直填通道已装载")

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        // 熄屏或未解锁状态下绝不拦截按键，绝不触发录音
        if (!isScreenInteractiveAndUnlocked()) {
            return super.onKeyEvent(event)
        }

        // 一加左侧独立按键 (KEYCODE_781 单击)：
        // 未录音时开启会话，录音中再次单击立即截断落笔；长按 KEYCODE_782 交由系统原生处理。
        if (event.keyCode == 781) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.i(TAG, "⚡ 捕获一加左侧独立键单击 (KEYCODE_781) -> 触发落笔开关")
                handleTriggerAction()
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    /**
     * 响应左侧独立键单击开关：
     * - 熄屏或锁屏 -> 一律静默忽略
     * - 会话激活中 -> 截断落笔提交
     * - 会话空闲中 -> 立即开启录音
     */
    fun handleTriggerAction() {
        if (!isScreenInteractiveAndUnlocked()) {
            Log.d(TAG, "屏幕熄灭或处于锁屏状态，拒绝触发录音")
            return
        }
        if (sessionCoordinator.isSessionActive()) {
            sessionCoordinator.endSessionNow()
        } else {
            startSessionFlow()
        }
    }

    private fun startSessionFlow() {
        // 1. 第 0 毫秒捕获当前正在激活的输入框与光标，锁定目标（绝不收起软键盘，绝不打断光标）
        cachedTargetNode = findTargetEditableNode()
        Log.i(TAG, "第 0 毫秒捕获目标输入框光标节点: ${cachedTargetNode?.className}, pkg=${cachedTargetNode?.packageName}, isFocused=${cachedTargetNode?.isFocused}")

        // 2. 挂载左侧物理键微呼吸药丸
        overlayManager.show()

        // 3. 绑定推理进程并重置 VAD
        bindAsrWorker()
        vadDetector.reset()

        // 4. 先驱动会话状态机（立即发出起振反馈），随后开启音频采集，配合 VAD 前 120ms 屏蔽窗隔离按键与震动杂音
        sessionCoordinator.startSession()
        audioCapture.startRecording()
    }

    private fun sendSegmentToWorker(
        segment: VadDetector.Segment,
        onResult: (text: String?, error: String?) -> Unit
    ) {
        val modelFile = ModelManager.getModelFile(this)
        val tokensFile = ModelManager.getTokensFile(this)
        if (modelFile == null || tokensFile == null) {
            onResult(null, "未安装模型")
            return
        }

        fun tryDispatch(attempt: Int) {
            val worker = asrService
            if (worker != null) {
                dispatchSegmentInference(worker, segment, modelFile.absolutePath, tokensFile.absolutePath, onResult)
            } else if (attempt < 6) {
                bindAsrWorker()
                mainHandler.postDelayed({
                    tryDispatch(attempt + 1)
                }, 200L)
            } else {
                onResult(null, "引擎连接超时")
            }
        }

        tryDispatch(0)
    }

    private fun dispatchSegmentInference(
        worker: IASRService,
        segment: VadDetector.Segment,
        modelPath: String,
        tokensPath: String,
        onResult: (text: String?, error: String?) -> Unit
    ) {
        try {
            worker.initModel(modelPath, tokensPath)

            val callback = object : IASRCallback.Stub() {
                override fun onAsrSuccess(text: String?) {
                    onResult(text ?: "", null)
                }

                override fun onAsrError(errorMessage: String?) {
                    onResult(null, errorMessage ?: "识别异常")
                }
            }

            val pcmData = segment.pcm
            if (pcmData.size > 512 * 1024) {
                val tempPcmFile = File(cacheDir, "temp_rec_${System.currentTimeMillis()}.pcm")
                PcmUtils.writePcmToFile(tempPcmFile, pcmData)
                worker.recognizeFile(tempPcmFile.absolutePath, 16000, callback)
            } else {
                val floatSamples = PcmUtils.pcm16ToFloatArray(pcmData)
                worker.recognizeSamples(floatSamples, 16000, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "分发分段推理异常: ${e.message}", e)
            onResult(null, "通信异常: ${e.message}")
        }
    }

    /**
     * 校验无障碍节点是否为真实合法的输入框（兼容新版微信隐藏类名及自定义富文本控件）
     */
    private fun isRealEditableNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val pkg = node.packageName?.toString() ?: ""
        if (pkg == "com.lucas.asrcapsule" || pkg == "com.android.systemui" || pkg == "com.oplus.gesture") {
            return false
        }

        val cls = node.className?.toString() ?: ""
        val isEditClass = cls.contains("EditText", ignoreCase = true)
        val isEditable = node.isEditable
        val supportsSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        val supportsPaste = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }

        return isEditable || isEditClass || supportsSetText || supportsPaste
    }

    /**
     * 提取输入框内的真实用户文本，彻底剔除灰色占位提示词 (Hint / Placeholder，如笔记软件开头的 "Title")
     */
    private fun extractRealEditableText(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) {
            return ""
        }
        val rawText = node.text?.toString() ?: return ""
        if (rawText.isEmpty()) return ""

        val hintText = node.hintText?.toString()
        if (!hintText.isNullOrEmpty() && rawText == hintText) {
            return ""
        }

        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        if (selStart <= 0 && selEnd <= 0) {
            val trimmed = rawText.trim()
            if (trimmed.equals("Title", ignoreCase = true) ||
                trimmed.equals("Note", ignoreCase = true) ||
                trimmed.equals("Ask anything", ignoreCase = true) ||
                trimmed == "标题" ||
                trimmed == "输入标题" ||
                trimmed == "输入内容" ||
                trimmed == "记事"
            ) {
                return ""
            }
        }

        return rawText
    }

    /**
     * 处理识别文本输出：
     * 1. 第一顺位：AccessibilityInputConnection.commitText 原位直填当前光标（绝不破坏微信/笔记的键盘和光标）
     * 2. 第二顺位：无障碍节点注入（兼容未通过 InputConnection 接管的编辑器）
     * 3. 焦点恢复重试：若因侧边光晕动画瞬间遮挡导致首次失败，自动延时 80ms 重试（最多 3 次）
     * 4. 仅当确实不在任何输入框时才写入剪贴板并轻提示
     */
    private fun handleTextOutput(text: String, attempt: Int = 0) {
        if (injectViaInputMethodConnection(text)) {
            onTextInjectionSucceeded(text)
            return
        }

        var targetNode = cachedTargetNode
        if (targetNode != null) {
            try {
                if (!targetNode.refresh() || !isRealEditableNode(targetNode)) {
                    targetNode = null
                }
            } catch (_: Exception) {
                targetNode = null
            }
        }
        if (targetNode == null) {
            targetNode = findTargetEditableNode()
        }

        if (targetNode != null && isRealEditableNode(targetNode) && injectTextToNode(targetNode, text)) {
            onTextInjectionSucceeded(text)
            return
        }

        if (attempt < 3) {
            Log.d(TAG, "首次直填未命中光标，80ms 后进行第 ${attempt + 1} 次焦点恢复重试")
            mainHandler.postDelayed({
                handleTextOutput(text, attempt + 1)
            }, 80L)
            return
        }

        Log.w(TAG, "无活动光标，已复制识别结果到剪贴板并开启 5s 延迟补射")
        copyToClipboard(text)
        pendingDelayedInjectText = text
        pendingDelayedInjectTimestamp = System.currentTimeMillis()
        cachedTargetNode = null

        overlayManager.showTranscriptionResult(text, isClipboard = true)
        Toast.makeText(this, "未检测到输入框，已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun onTextInjectionSucceeded(text: String) {
        Log.i(TAG, "✓ 文字已成功直填当前光标处 (绝不清除焦点，保留软键盘)")
        pendingDelayedInjectText = null
        pendingDelayedInjectTimestamp = 0L
        cachedTargetNode = null
        overlayManager.showTranscriptionResult(text, isClipboard = false)
    }

    /**
     * 通过 Android 13+ (API 33+) 官方 AccessibilityInputConnection 通道直注文字
     */
    private fun injectViaInputMethodConnection(text: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                val ic = inputMethod?.currentInputConnection
                if (ic != null) {
                    ic.commitText(text, 1, null)
                    Log.i(TAG, "✓ 通过系统级 AccessibilityInputConnection.commitText 直接落墨成功 (len=${text.length})")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "AccessibilityInputConnection 直注异常: ${e.message}")
            }
        }
        return false
    }

    private fun injectTextToNode(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!isRealEditableNode(node)) {
            Log.w(TAG, "尝试向非真实输入框节点注入，直接拒绝: class=${node.className}")
            return false
        }

        val existingText = extractRealEditableText(node)

        try {
            if (existingText.isEmpty()) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    try {
                        val selArgs = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                    } catch (_: Exception) {}
                    Log.i(TAG, "✓ 空输入框通过 ACTION_SET_TEXT 原位直注成功 (len=${text.length})")
                    return true
                }
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val prevClip = try { clipboard.primaryClip } catch (_: Exception) { null }

            clipboard.setPrimaryClip(ClipData.newPlainText("caret_direct", text))
            val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

            mainHandler.postDelayed({
                try {
                    if (prevClip != null) {
                        clipboard.setPrimaryClip(prevClip)
                    } else {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip()
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                } catch (_: Exception) {}
            }, 250L)

            if (pasteSuccess) {
                Log.i(TAG, "✓ 通过 ACTION_PASTE 在活动光标处无损插入成功 (已计划恢复剪贴板)")
                return true
            }

            val selStart = node.textSelectionStart
            val selEnd = node.textSelectionEnd
            val newText: String
            val newCursor: Int

            if (existingText.isEmpty()) {
                newText = text
                newCursor = text.length
            } else if (selStart in 0..existingText.length) {
                val safeStart = selStart.coerceIn(0, existingText.length)
                val safeEnd = selEnd.coerceIn(safeStart, existingText.length)
                newText = existingText.substring(0, safeStart) + text + existingText.substring(safeEnd)
                newCursor = safeStart + text.length
            } else {
                newText = "$existingText$text"
                newCursor = newText.length
            }

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
                )
            }
            val setSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (setSuccess) {
                try {
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                } catch (_: Exception) {}

                Log.i(TAG, "✓ 通过 ACTION_SET_TEXT 兜底拼接注入成功 (len=${text.length})")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "节点文本注入异常: ${e.message}", e)
        }

        return false
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("asr_capsule", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun findBestEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = -1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val pkg = node.packageName?.toString() ?: ""
                if (pkg == "com.lucas.asrcapsule" || pkg == "com.android.systemui") {
                    return
                }

                if (isRealEditableNode(node)) {
                    val isEditClass = node.className?.contains("EditText", ignoreCase = true) == true
                    val isEditable = node.isEditable
                    val supportsSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    val isKnownInputId = resId.contains("bkk") || resId.contains("edit") || resId.contains("input")

                    var score = 50
                    if (supportsSetText) score += 60
                    if (node.isFocused) score += 200
                    if (node.isAccessibilityFocused) score += 80
                    if (isKnownInputId) score += 40
                    if (isEditClass) score += 30
                    if (isEditable) score += 20
                    if (node.isVisibleToUser) score += 20

                    if (score > bestScore) {
                        bestScore = score
                        bestNode = node
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    traverse(child)
                }
            } catch (_: Exception) {}
        }

        traverse(root)
        return bestNode
    }

    private fun findTargetEditableNode(): AccessibilityNodeInfo? {
        try {
            val globalFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (globalFocus != null && isRealEditableNode(globalFocus)) {
                Log.i(TAG, "✓ 通道 1 命中真实全局 FOCUS_INPUT: class=${globalFocus.className}, pkg=${globalFocus.packageName}")
                return globalFocus
            }
        } catch (e: Exception) {
            Log.w(TAG, "通道 1 检索全局 FOCUS_INPUT 异常: ${e.message}")
        }

        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val activeFocus = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (activeFocus != null && isRealEditableNode(activeFocus)) {
                    Log.i(TAG, "✓ 通道 2 命中 rootInActiveWindow 真实输入框: class=${activeFocus.className}, pkg=${activeFocus.packageName}")
                    return activeFocus
                }
            }
        } catch (_: Exception) {}

        try {
            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                for (win in windowList) {
                    val root = win.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg == "com.tencent.mm") {
                        val byId = root.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/bkk")
                        if (!byId.isNullOrEmpty()) {
                            val wechatInput = byId.firstOrNull { isRealEditableNode(it) } ?: byId[0]
                            Log.i(TAG, "✓ 通道 3 靶向命中微信输入框 [com.tencent.mm:id/bkk]: class=${wechatInput.className}")
                            return wechatInput
                        }

                        val bestInWechat = findBestEditableNode(root)
                        if (bestInWechat != null) {
                            Log.i(TAG, "✓ 通道 3 微信全树命中最佳编辑框: class=${bestInWechat.className}")
                            return bestInWechat
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "通道 3 微信靶向查找异常: ${e.message}")
        }

        try {
            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                val sortedWindows = windowList.sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                        .thenByDescending { it.isActive }
                        .thenByDescending { it.isFocused }
                )

                for (win in sortedWindows) {
                    if (win.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                        win.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                        win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        continue
                    }
                    val root = win.root ?: continue
                    val found = findBestEditableNode(root)
                    if (found != null && isRealEditableNode(found)) {
                        Log.i(TAG, "✓ 通道 4 联合评分捕获到输入框: class=${found.className}, pkg=${found.packageName}")
                        return found
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "通道 4 联合评分遍历异常: ${e.message}")
        }

        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val found = findBestEditableNode(activeRoot)
                if (found != null && isRealEditableNode(found)) {
                    Log.i(TAG, "✓ 通道 5 rootInActiveWindow 遍历捕获到输入框: class=${found.className}")
                    return found
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun bindAsrWorker() {
        if (!isBoundWorker) {
            val intent = Intent(this, AsrWorkerService::class.java)
            isBoundWorker = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindAsrWorker() {
        if (isBoundWorker) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBoundWorker = false
            asrService = null
        }
    }

    private val knownSystemWindowIds = mutableSetOf<Int>()
    private var isSystemWindowsInitialized = false
    private var lastLeftKeyTriggerTime = 0L
    private var lastAuraTriggerTime = 0L
    private var activeSeedlingWindowId = -1

    private fun onOnePlusLeftKeyDetected(sourceDesc: String) {
        if (!isScreenInteractiveAndUnlocked()) {
            Log.d(TAG, "忽略熄屏/锁屏状态下的窗口特征触发 [$sourceDesc]")
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastLeftKeyTriggerTime < 380L) {
            return
        }
        lastLeftKeyTriggerTime = now

        Log.i(TAG, "⚡ 捕获一加左侧独立键单击 [$sourceDesc] -> 触发落笔开关")
        mainHandler.post {
            handleTriggerAction()
        }
    }

    private fun checkOnePlusLeftButtonWindows() {
        try {
            if (!isScreenInteractiveAndUnlocked()) {
                isSystemWindowsInitialized = false
                knownSystemWindowIds.clear()
                activeSeedlingWindowId = -1
                return
            }

            val currentWindows = windows ?: return
            val currentSystemIds = mutableSetOf<Int>()
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            var hasFullScreenAuraWindow = false
            val now = SystemClock.uptimeMillis()
            val inWakeGuardWindow = (now - lastScreenWakeTimestamp) < 1200L

            for (win in currentWindows) {
                if (win.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                val winId = win.id
                currentSystemIds.add(winId)

                val root = win.root
                val rootPkg = root?.packageName?.toString() ?: ""
                if (rootPkg == "com.oplus.gesture") {
                    hasFullScreenAuraWindow = true
                }

                if (!isSystemWindowsInitialized || inWakeGuardWindow) continue
                if (winId in knownSystemWindowIds) continue

                val bounds = android.graphics.Rect()
                win.getBoundsInScreen(bounds)
                val w = bounds.width()
                val h = bounds.height()
                val centerX = (bounds.left + bounds.right) / 2
                val rootCls = root?.className?.toString() ?: ""
                val winTitle = win.title?.toString() ?: ""

                // 过滤无关系统浮窗（音量条、电源菜单、生物识别、锁屏、截屏等）
                val isUnrelatedSystemUi = winTitle.contains("Volume", ignoreCase = true) ||
                    winTitle.contains("Power", ignoreCase = true) ||
                    winTitle.contains("Keyguard", ignoreCase = true) ||
                    winTitle.contains("Biometric", ignoreCase = true) ||
                    winTitle.contains("Screenshot", ignoreCase = true) ||
                    winTitle.contains("音量") ||
                    winTitle.contains("电源") ||
                    winTitle.contains("指纹") ||
                    winTitle.contains("面部") ||
                    winTitle.contains("锁屏")
                if (isUnrelatedSystemUi) continue

                val isAuraWindow = rootPkg == "com.oplus.gesture" ||
                    rootCls.contains("ActionKeyAnimView", ignoreCase = true)

                if (isAuraWindow) {
                    hasFullScreenAuraWindow = true
                    lastAuraTriggerTime = now
                    onOnePlusLeftKeyDetected("AuraView(id=$winId)")
                    continue
                }

                val isTopCenteredCapsule = (rootPkg == "com.android.systemui" || rootPkg.isEmpty()) &&
                    bounds.top in 5..(screenH / 14) &&
                    w in (screenW / 6)..(screenW * 2 / 3) &&
                    h in 50..(screenH / 12) &&
                    kotlin.math.abs(centerX - (screenW / 2)) <= (screenW / 8)

                if (isTopCenteredCapsule) {
                    activeSeedlingWindowId = winId
                    if (now - lastAuraTriggerTime > 950L) {
                        onOnePlusLeftKeyDetected("SeedlingCapsule(id=$winId, bounds=$bounds)")
                    }
                }
            }

            if (!hasFullScreenAuraWindow) {
                if (activeSeedlingWindowId != -1 && activeSeedlingWindowId !in currentSystemIds) {
                    activeSeedlingWindowId = -1
                }
                knownSystemWindowIds.clear()
            }
            knownSystemWindowIds.addAll(currentSystemIds)
            isSystemWindowsInitialized = true
        } catch (e: Exception) {
            Log.w(TAG, "检测一加独立键窗口异常: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            checkOnePlusLeftButtonWindows()
        }

        // 5 秒焦点智能延迟补射 (Delayed Auto-Inject)
        val pendingText = pendingDelayedInjectText
        if (pendingText != null) {
            val now = System.currentTimeMillis()
            if (now - pendingDelayedInjectTimestamp <= 5000L) {
                if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                ) {
                    val injected = injectViaInputMethodConnection(pendingText) || run {
                        val node = event.source ?: findTargetEditableNode()
                        node != null && isRealEditableNode(node) && injectTextToNode(node, pendingText)
                    }
                    if (injected) {
                        Log.i(TAG, "✓ 检测到用户点击输入框，延迟自动补射成功 (text=$pendingText)")
                        VibrationHelper.performClickVibration(this)
                        pendingDelayedInjectText = null
                        pendingDelayedInjectTimestamp = 0L
                        overlayManager.dismiss(delayMs = 0)
                    }
                }
            } else {
                pendingDelayedInjectText = null
                pendingDelayedInjectTimestamp = 0L
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}
        unbindAsrWorker()
        overlayManager.dismiss()
    }
}
