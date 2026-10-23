package com.thiagocosta.sansisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.sin

/**
 * Siri-style edge light: a thin multicolor line that flows around the island,
 * plus a soft halo outside it that breathes. Drawn behind the pill, around its bounds.
 */
class GlowView(context: Context, private val target: View, private val radius: () -> Float) : View(context) {

    enum class Style { SIRI, ACCENT, CLASSIC, GLASS, FLUID }

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * density }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        maskFilter = BlurMaskFilter(9f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()
    private val pillPath = android.graphics.Path()
    /** soft shadow under the glass island, as if it floats above the screen */
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x70000000
        maskFilter = BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val shaderMatrix = Matrix()
    private var shader: SweepGradient? = null
    private var angle = 0f
    private var breath = 1f
    private var pulse = 0f

    var style = Style.SIRI
        set(value) { field = value; rebuildShader(); invalidate() }

    var accent = Accent.DEFAULT
        set(value) { if (field != value) { field = value; if (style == Style.ACCENT) rebuildShader() } }

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            breath = 0.78f + 0.22f * ((sin(Math.toRadians(angle * 2.0)) + 1f) / 2f).toFloat()
            invalidate()
        }
    }

    private var pulseAnimator: ValueAnimator? = null

    init {
        rebuildShader()
    }

    /** brief flash when something new arrives, like Siri lighting up */
    fun pulse() {
        if (style == Style.CLASSIC || style == Style.GLASS || style == Style.FLUID) return
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 900
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setRunning(running: Boolean) {
        if (running && (style == Style.SIRI || style == Style.ACCENT)) { if (!spin.isStarted) spin.start() } else spin.cancel()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun rebuildShader() {
        val colors = when (style) {
            Style.SIRI -> SIRI_COLORS
            Style.ACCENT -> {
                val light = ColorUtils.blendARGB(accent, Color.WHITE, 0.45f)
                val deep = ColorUtils.blendARGB(accent, Color.BLACK, 0.25f)
                intArrayOf(accent, light, accent, deep, accent)
            }
            Style.CLASSIC, Style.GLASS, Style.FLUID -> null
        }
        shader = colors?.let { SweepGradient(0f, 0f, it, null) }
        line.shader = shader
        halo.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        if (target.visibility != VISIBLE || target.width == 0 || target.height == 0) return
        if (style == Style.GLASS || style == Style.FLUID) {
            drawShadow(canvas)
            return
        }
        val s = shader ?: return
        rect.set(target.left.toFloat(), target.top.toFloat(), target.right.toFloat(), target.bottom.toFloat())
        shaderMatrix.reset()
        shaderMatrix.setRotate(angle)
        shaderMatrix.postTranslate(rect.centerX(), rect.centerY())
        s.setLocalMatrix(shaderMatrix)
        val r = radius()

        val intensity = (breath + pulse * 0.6f).coerceAtMost(1.4f)
        halo.alpha = (150 * intensity).toInt().coerceIn(0, 255)
        halo.strokeWidth = (7f + 5f * pulse) * density
        canvas.drawRoundRect(rect, r, r, halo)

        val inset = line.strokeWidth / 2f
        line.alpha = (235 * intensity).toInt().coerceIn(0, 255)
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, r - inset, r - inset, line)
    }

    private fun drawShadow(canvas: Canvas) {
        val r = radius()
        rect.set(target.left.toFloat(), target.top.toFloat(), target.right.toFloat(), target.bottom.toFloat())
        pillPath.reset()
        pillPath.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipOutPath(pillPath) // only around the island, never over it
        val inset = rect.width() * 0.05f
        rect.inset(inset, 0f)
        rect.offset(0f, 7f * density)
        canvas.drawRoundRect(rect, r, r, shadow)
        canvas.restore()
    }

    companion object {
        // the warm-to-cool sweep of Apple's Siri glow
        private val SIRI_COLORS = intArrayOf(
            0xFFFF8A3D.toInt(), // orange
            0xFFFF3D9A.toInt(), // pink
            0xFFA45CFF.toInt(), // purple
            0xFF3D8BFF.toInt(), // blue
            0xFF3DE0FF.toInt(), // cyan
            0xFFFF8A3D.toInt(),
        )

        fun styleFromPref(value: String) = when (value) {
            "accent" -> Style.ACCENT
            "classic" -> Style.CLASSIC
            "siri" -> Style.SIRI
            "glass" -> Style.GLASS
            else -> Style.FLUID
        }
    }
}
