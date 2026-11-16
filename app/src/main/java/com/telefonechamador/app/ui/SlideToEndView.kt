package com.telefonechamador.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Interruptor deslizante "deslize para encerrar" — substitui o botão de toque
 * simples da tela de ligação: como a tela fica acesa durante a chamada (para
 * ler a transcrição), um toque acidental não pode mais desligar a ligação.
 */
class SlideToEndView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onConfirmed: (() -> Unit)? = null
    private var label = "Deslize para encerrar"

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7A2222.toInt() }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFC9C9.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2EFE6.toInt() }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7A2222.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2EFE6.toInt()
        textAlign = Paint.Align.CENTER
    }

    private var thumbX = 0f
    private var downX = 0f
    private var dragging = false
    private var animator: ValueAnimator? = null

    private val thumbR: Float get() = height / 2f - dp(4)
    private val maxTravel: Float get() = max(1f, width - height.toFloat())

    fun setLabel(text: String) {
        label = text
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        textPaint.textSize = h * 0.26f
        crossPaint.strokeWidth = h * 0.07f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = RectF(dp(2), dp(2), w - dp(2), h - dp(2))
        c.drawRoundRect(r, h / 2f, h / 2f, trackPaint)

        val t = thumbX / maxTravel
        // trilha de progresso atrás do polegar
        trailPaint.color = android.graphics.Color.argb((70 + 90 * t).toInt(), 255, 120, 120)
        c.drawRoundRect(
            RectF(dp(2), dp(2), dp(2) + thumbX + h - dp(4), h - dp(2)),
            h / 2f, h / 2f, trailPaint,
        )

        // rótulo (some conforme desliza)
        textPaint.alpha = (255 * (1f - t * 0.85f)).toInt()
        c.drawText(
            label, w / 2f,
            h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f,
            textPaint,
        )

        // polegar com ✖
        val cx = dp(2) + thumbR + thumbX
        c.drawCircle(cx, h / 2f, thumbR, thumbPaint)
        val s = thumbR * 0.42f
        c.drawLine(cx - s, h / 2f - s, cx + s, h / 2f + s, crossPaint)
        c.drawLine(cx - s, h / 2f + s, cx + s, h / 2f - s, crossPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downX = e.x - thumbX
                animator?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                thumbX = (e.x - downX).coerceIn(0f, maxTravel)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (thumbX >= maxTravel * 0.88f) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onConfirmed?.invoke()
                }
                snapTo(0f)
            }
        }
        return true
    }

    private fun snapTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(thumbX, target).apply {
            addUpdateListener {
                thumbX = it.animatedValue as Float
                invalidate()
            }
            duration = 220
            start()
        }
    }

    private fun dp(v: Int) = v * resources.displayMetrics.density
}
