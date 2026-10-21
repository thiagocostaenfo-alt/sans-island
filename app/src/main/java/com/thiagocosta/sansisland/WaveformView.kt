package com.thiagocosta.sansisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** little animated equalizer bars in the accent color, like the iPhone's music indicator */
class WaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private var playing = false
    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    fun setPlaying(value: Boolean) {
        playing = value
        if (value && isAttachedToWindow) { if (!animator.isStarted) animator.start() } else animator.cancel()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val bars = 5
        val gap = width / (bars * 2f - 1)
        val barW = gap
        for (i in 0 until bars) {
            val level = if (playing) 0.3f + 0.7f * ((sin(phase * (1 + i * 0.37f) + i * 1.3f) + 1f) / 2f) else 0.25f
            val barH = height * level
            val left = i * 2 * gap
            rect.set(left, (height - barH) / 2f, left + barW, (height + barH) / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }
}
