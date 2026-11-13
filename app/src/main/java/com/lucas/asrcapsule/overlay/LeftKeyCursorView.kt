package com.lucas.asrcapsule.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.lucas.asrcapsule.model.CapsuleState
import kotlin.math.sin

/**
 * 「侧键小药丸」悬浮提示视图
 * 紧贴一加左侧物理按键边缘：深色半透明圆角小胶囊 (26 × 60dp)，
 * 呈现 录音中(呼吸红点+3根声波) / 识别中(3点跳动) / 已填字(绿勾✓) / 异常(琥珀点)。
 */
class LeftKeyCursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density

    private enum class Status {
        RECORDING,
        THINKING,
        SUCCESS,
        WARN
    }

    private var status = Status.RECORDING
    private var smoothedLevel = 0f
    private var targetLevel = 0f
    private var animPhase = 0f

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEA161618.toInt()
    }
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        color = 0x33FFFFFF
    }
    private val recGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 255, 69, 58)
    }
    private val recDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AuraTokens.COLOR_REC
    }
    private val waveBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val thinkDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val successPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = AuraTokens.COLOR_OK
    }
    private val warnDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AuraTokens.COLOR_AMBER
    }

    private val pillRect = RectF()
    private val barRect = RectF()
    private val checkPath = Path()

    private val loopAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1400L
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { anim ->
            animPhase = anim.animatedValue as Float
            smoothedLevel += (targetLevel - smoothedLevel) * 0.30f
            invalidate()
        }
    }

    fun startRecording() {
        status = Status.RECORDING
        smoothedLevel = 0f
        targetLevel = 0f

        animate().cancel()
        translationX = -34f * dp
        alpha = 0f
        animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (!loopAnimator.isRunning) {
            loopAnimator.start()
        }
        invalidate()
    }

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
        if (status == Status.RECORDING) {
            smoothedLevel += (targetLevel - smoothedLevel) * 0.38f
            invalidate()
        }
    }

    fun setPhase(state: CapsuleState) {
        status = when (state) {
            CapsuleState.THINKING -> Status.THINKING
            CapsuleState.NO_SPEECH, CapsuleState.ERROR, CapsuleState.CANCELLED -> Status.WARN
            else -> return
        }
        invalidate()
    }

    fun showSuccess() {
        status = Status.SUCCESS
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((36f * dp).toInt(), (68f * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 4.5f * dp
        val top = 4f * dp
        val pillW = 26f * dp
        val pillH = 60f * dp
        val radius = 13f * dp

        pillRect.set(left, top, left + pillW, top + pillH)
        canvas.drawRoundRect(pillRect, radius, radius, pillBgPaint)
        canvas.drawRoundRect(pillRect, radius, radius, pillStrokePaint)

        val cx = left + pillW / 2f
        val cy = top + pillH / 2f

        when (status) {
            Status.RECORDING -> {
                // 顶部呼吸红点
                val breathNorm = (sin(animPhase.toDouble()).toFloat() + 1f) * 0.5f // 0..1
                val scale = 0.90f + breathNorm * 0.22f
                val dotRadius = 3.8f * dp * scale
                val dotY = top + 17f * dp
                recGlowPaint.alpha = (55 + (breathNorm * 65)).toInt()
                canvas.drawCircle(cx, dotY, dotRadius + 2.6f * dp, recGlowPaint)
                canvas.drawCircle(cx, dotY, dotRadius, recDotPaint)

                // 下方 3 根随音量跳动的细声波柱
                val barW = 2.5f * dp
                val gap = 3f * dp
                val barCenterY = top + 40f * dp
                val energy = smoothedLevel.coerceIn(0f, 1f)
                val idleRipple = (sin(animPhase * 2.0) * 0.5 + 0.5).toFloat()

                val heights = floatArrayOf(
                    (4.5f + energy * 8.5f + idleRipple * 1.5f) * dp,
                    (6.0f + energy * 9.0f + (1f - idleRipple) * 2.0f) * dp,
                    (4.5f + energy * 7.5f + idleRipple * 1.8f) * dp
                )
                val startX = cx - (barW * 3f + gap * 2f) / 2f
                for (i in 0..2) {
                    val bh = heights[i].coerceIn(4f * dp, 15f * dp)
                    val bx = startX + i * (barW + gap)
                    barRect.set(bx, barCenterY - bh / 2f, bx + barW, barCenterY + bh / 2f)
                    canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, waveBarPaint)
                }
            }

            Status.THINKING -> {
                // 居中纵向排列 3 个白色小圆点依次闪烁跳动
                val dotR = 2.25f * dp
                val spacing = 8f * dp
                for (i in 0..2) {
                    val phase = animPhase * 2f - i * 0.85f
                    val pulse = (sin(phase.toDouble()).toFloat() + 1f) * 0.5f
                    thinkDotPaint.alpha = (90 + pulse * 165).toInt()
                    val y = cy + (i - 1) * spacing - pulse * 1.2f * dp
                    canvas.drawCircle(cx, y, dotR, thinkDotPaint)
                }
            }

            Status.SUCCESS -> {
                // 居中绿色对勾 ✓
                checkPath.reset()
                checkPath.moveTo(cx - 5.5f * dp, cy + 0.5f * dp)
                checkPath.lineTo(cx - 1.5f * dp, cy + 4.5f * dp)
                checkPath.lineTo(cx + 6.0f * dp, cy - 4.0f * dp)
                canvas.drawPath(checkPath, successPaint)
            }

            Status.WARN -> {
                // 居中琥珀色提示点
                canvas.drawCircle(cx, cy, 3.5f * dp, warnDotPaint)
            }
        }
    }

    fun dismissCursor(onEnd: (() -> Unit)? = null) {
        loopAnimator.cancel()
        animate().cancel()
        animate()
            .translationX(-34f * dp)
            .alpha(0f)
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                onEnd?.invoke()
            }
            .start()
    }
}
