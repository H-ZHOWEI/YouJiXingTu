package com.youjixingtu.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** 复刻旧版网页的星空背景 */
class StarFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)

    private val stars = mutableListOf<Star>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var progress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stars.clear()
        repeat(110) {
            stars.add(
                Star(
                    x = Math.random().toFloat() * w,
                    y = Math.random().toFloat() * h,
                    radius = (0.6f + Math.random().toFloat() * 2f),
                    phase = (Math.random().toFloat() * 6.28f),
                    speed = (0.6f + Math.random().toFloat() * 1.5f)
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        stars.forEach { s ->
            val alpha = (0.25f + 0.7f * abs(sin(progress * Math.PI.toFloat() * s.speed + s.phase))) * 255f
            paint.alpha = alpha.toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.radius, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isRunning) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    /** D1：Activity 退到后台时暂停逐帧绘制，省电 */
    fun pauseAnimation() {
        if (animator.isRunning) animator.pause()
    }

    fun resumeAnimation() {
        if (!animator.isRunning) animator.resume()
    }
}
