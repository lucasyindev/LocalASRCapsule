package com.lucas.asrcapsule.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.lucas.asrcapsule.model.CapsuleState

/**
 * 「侧键小药丸」悬浮控制器：紧贴左键展示四态呼吸药丸，触摸完全穿透不误触。
 */
class ArtifactOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "ArtifactOverlayManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dp = context.resources.displayMetrics.density

    private var cursorView: LeftKeyCursorView? = null
    private var isAttached = false
    private var isShowingSuccess = false
    private var dismissRunnable: Runnable? = null

    fun show(onDismissComplete: (() -> Unit)? = null) {
        mainHandler.post {
            isShowingSuccess = false
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            dismissRunnable = null

            val screenH = context.resources.displayMetrics.heightPixels
            val view = cursorView ?: LeftKeyCursorView(context).also { cursorView = it }

            view.startRecording()

            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = (36f * dp).toInt()
                height = (68f * dp).toInt()
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = (screenH * 0.22f).toInt()
            }

            if (!isAttached) {
                try {
                    windowManager.addView(view, params)
                    isAttached = true
                    Log.i(TAG, "侧键小药丸已挂载")
                } catch (e: Exception) {
                    Log.e(TAG, "挂载侧键小药丸异常: ${e.message}", e)
                }
            } else {
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
        }
    }

    fun showTranscriptionResult(text: String, isClipboard: Boolean) {
        mainHandler.post {
            isShowingSuccess = true
            cursorView?.showSuccess()
            dismiss(delayMs = 320L)
        }
    }

    fun notifySessionClosed() {
        mainHandler.post {
            if (!isShowingSuccess) {
                dismiss(delayMs = 0L)
            }
        }
    }

    fun setPhase(state: CapsuleState, detail: String? = null) {
        mainHandler.post {
            when (state) {
                CapsuleState.THINKING -> cursorView?.setPhase(state)
                CapsuleState.NO_SPEECH -> {
                    cursorView?.setPhase(state)
                    dismiss(delayMs = 450L)
                }
                CapsuleState.ERROR -> {
                    cursorView?.setPhase(state)
                    dismiss(delayMs = 550L)
                }
                CapsuleState.CANCELLED -> {
                    cursorView?.setPhase(state)
                    dismiss(delayMs = 200L)
                }
                else -> {}
            }
        }
    }

    fun setLevel(level: Float) {
        mainHandler.post {
            cursorView?.setLevel(level)
        }
    }

    fun dismiss(delayMs: Long = 0L, onComplete: (() -> Unit)? = null) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }

        val runnable = Runnable {
            val view = cursorView
            if (view != null && isAttached) {
                view.dismissCursor {
                    try {
                        if (isAttached) {
                            windowManager.removeViewImmediate(view)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "卸载侧键小药丸异常: ${e.message}")
                    } finally {
                        cursorView = null
                        isAttached = false
                        isShowingSuccess = false
                        dismissRunnable = null
                        onComplete?.invoke()
                    }
                }
            } else {
                cursorView = null
                isAttached = false
                isShowingSuccess = false
                dismissRunnable = null
                onComplete?.invoke()
            }
        }

        dismissRunnable = runnable
        if (delayMs > 0L) {
            mainHandler.postDelayed(runnable, delayMs)
        } else {
            mainHandler.post(runnable)
        }
    }

    fun isShowing(): Boolean = isAttached
}
