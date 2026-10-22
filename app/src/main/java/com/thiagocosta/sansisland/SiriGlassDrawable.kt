package com.thiagocosta.sansisland

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.max

/**
 * The new-Siri island: black on top (around the camera), turning into silver glass at the bottom,
 * with the multicolor Siri light on the line where black meets glass and a bright glass rim below.
 *
 * [radius] and [lip] (height of the glass part, px) are set by the island while it morphs.
 * The light drifts and brightens for a few seconds after [pulse] or [listen], then rests,
 * so an island that stays on screen (music, timer) costs no constant redraws.
 */
class SiriGlassDrawable(private val density: Float) : Drawable() {

    var radius = 0f
    var lip = 0f

    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sides = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private val lineMatrix = Matrix()

    // cached shaders, rebuilt only when the size changes
    private var cachedW = -1
    private var cachedH = -1
    private var cachedLip = -1f
    private var colorShader: LinearGradient? = null
    private var envelope: LinearGradient? = null

    /** 1 = resting light; above 1 while something arrives */
    private var boost = 0f
    private var phase = 0f

    private var drift: ValueAnimator? = null
    private var boostAnim: ValueAnimator? = null

    /** short flash: an update to what the island shows */
    fun pulse() = animateBoost(0.7f, 1100)

    /** the bigger "Siri is listening" moment while the island grows */
    fun listen() = animateBoost(1.2f, 1600)

    fun stop() {
        drift?.cancel(); boostAnim?.cancel()
        boost = 0f
        invalidateSelf()
    }

    private fun animateBoost(peak: Float, duration: Long) {
        boostAnim?.cancel()
        boostAnim = ValueAnimator.ofFloat(peak, 0f).apply {
            this.duration = duration
            addUpdateListener { boost = it.animatedValue as Float; invalidateSelf() }
            start()
        }
        // let the colors drift while it is bright, then rest
        drift?.cancel()
        drift = ValueAnimator.ofFloat(phase, phase + 0.55f).apply {
            this.duration = duration + 1400
            interpolator = LinearInterpolator()
            addUpdateListener { phase = (it.animatedValue as Float) % 2f; invalidateSelf() }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(rect, black)

        val l = lip.coerceAtMost(h * 0.6f)
        if (l >= 2f * density) {
            if (w != cachedW || h != cachedH || l != cachedLip) buildShaders(b.left.toFloat(), b.top.toFloat(), w, h, l)
            val lipTop = b.bottom - l

            // silver glass rising out of the black, darker towards the sides like a lens
            canvas.drawRect(b.left.toFloat(), lipTop, b.right.toFloat(), b.bottom.toFloat(), glass)
            canvas.drawRect(b.left.toFloat(), lipTop, b.right.toFloat(), b.bottom.toFloat(), sides)

            // the Siri light on the boundary
            val y = lipTop + l * 0.13f
            val thick = max(1.6f * density, l * 0.085f) * (1f + boost * 0.35f)
            lineMatrix.setTranslate(w * phase, 0f)
            colorShader?.setLocalMatrix(lineMatrix)
            val shader = ComposeShader(colorShader!!, envelope!!, PorterDuff.Mode.DST_IN)
            lineGlow.shader = shader
            lineGlow.alpha = (165 * (0.75f + boost * 0.5f)).toInt().coerceIn(0, 255)
            lineGlow.maskFilter = BlurMaskFilter(thick * 2.4f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRect(b.left.toFloat(), y - thick * 1.6f, b.right.toFloat(), y + thick * 1.6f, lineGlow)
            lineCore.shader = shader
            lineCore.alpha = (235 * (0.9f + boost * 0.2f)).toInt().coerceIn(0, 255)
            lineCore.maskFilter = BlurMaskFilter(thick * 0.45f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRect(b.left.toFloat(), y - thick / 2f, b.right.toFloat(), y + thick / 2f, lineCore)

            // bright rim along the bottom of the glass
            val half = rim.strokeWidth / 2f
            rect.inset(half, half)
            canvas.drawRoundRect(rect, radius - half, radius - half, rim)
        }
        canvas.restore()
    }

    private fun buildShaders(left: Float, top: Float, w: Int, h: Int, l: Float) {
        cachedW = w; cachedH = h; cachedLip = l
        val bottom = top + h
        val lipTop = bottom - l
        glass.shader = LinearGradient(0f, lipTop, 0f, bottom,
            intArrayOf(Color.BLACK, 0xFF55575D.toInt(), 0xFFC3C5CB.toInt(), 0xFFDCDDE2.toInt(), 0xFFB9BBC1.toInt()),
            floatArrayOf(0f, 0.22f, 0.62f, 0.86f, 1f), Shader.TileMode.CLAMP)
        sides.shader = LinearGradient(left, 0f, left + w, 0f,
            intArrayOf(0xC8000000.toInt(), 0x40000000, 0x00000000, 0x40000000, 0xC8000000.toInt()),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP)
        // colors of the new Siri light; mirrored so the drift has no seam
        colorShader = LinearGradient(left, 0f, left + w, 0f, SIRI_LIGHT, null, Shader.TileMode.MIRROR)
        // the light fades out before the rounded ends
        envelope = LinearGradient(left, 0f, left + w, 0f,
            intArrayOf(0x00FFFFFF, 0x00FFFFFF, Color.WHITE, Color.WHITE, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.06f, 0.3f, 0.7f, 0.94f, 1f), Shader.TileMode.CLAMP)
        rim.strokeWidth = 1.3f * density
        rim.shader = LinearGradient(0f, lipTop, 0f, bottom,
            intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xB8FFFFFF.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private val SIRI_LIGHT = intArrayOf(
            0xFFB04CFF.toInt(), // violet
            0xFFFF4FAE.toInt(), // pink
            0xFFFF9E7A.toInt(), // warm peach
            0xFFFFF6EC.toInt(), // white core
            0xFFAFD4FF.toInt(), // light blue
            0xFF4D7CFF.toInt(), // blue
            0xFF55E3FF.toInt(), // cyan
        )
    }
}
