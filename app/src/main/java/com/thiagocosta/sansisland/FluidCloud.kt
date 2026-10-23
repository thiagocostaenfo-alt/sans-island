package com.thiagocosta.sansisland

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.max

/**
 * ColorOS "Fluid Cloud" look. The capsule around the camera is solid black so it melts into the cutout;
 * as it opens into a card ([expansion] 0 -> 1) it turns into translucent dark glass with a thin light edge.
 * Touches leave a soft glow where the finger lands, and [ripple] sends a ring of light out from the center
 * (play/pause, track change).
 */
class FluidCloudDrawable(private val density: Float) : Drawable() {

    var radius = 0f
    /** 0 = compact capsule, 1 = expanded card */
    var expansion = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private var cachedH = -1
    private var cachedExp = -1f

    private var touchX = 0f
    private var touchY = 0f
    private var touchT = 1f // 0 = just touched, 1 = faded
    private var touchAnim: ValueAnimator? = null
    private var ringT = 1f
    private var ringAnim: ValueAnimator? = null

    fun touch(x: Float, y: Float) {
        touchX = x; touchY = y
        touchAnim?.cancel()
        touchAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener { touchT = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    fun ripple() {
        ringAnim?.cancel()
        ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { ringT = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    fun stop() {
        touchAnim?.cancel(); ringAnim?.cancel()
        touchT = 1f; ringT = 1f
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val e = expansion.coerceIn(0f, 1f)
        if (b.height() != cachedH || e != cachedExp) build(b.top.toFloat(), b.height().toFloat(), e)

        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(rect, fill)
        if (e > 0.01f) canvas.drawRect(rect, sheen)

        if (touchT < 1f) {
            val r = (22f + 60f * touchT) * density
            touchPaint.shader = RadialGradient(touchX, touchY, r,
                intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, (70 * (1 - touchT)).toInt()), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(touchX, touchY, r, touchPaint)
        }
        if (ringT < 1f) {
            val maxR = hypot(rect.width(), rect.height()) / 2f
            ringPaint.strokeWidth = (2.5f + 4f * (1 - ringT)) * density
            ringPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, (90 * (1 - ringT)).toInt())
            ringPaint.maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(rect.centerX(), rect.centerY(), maxR * (0.15f + 0.85f * ringT), ringPaint)
        }
        canvas.restore()

        if (e > 0.01f) {
            val half = edge.strokeWidth / 2f
            rect.inset(half, half)
            canvas.drawRoundRect(rect, radius - half, radius - half, edge)
        }
    }

    private fun build(top: Float, h: Float, e: Float) {
        cachedH = h.toInt(); cachedExp = e
        // capsule: pure black; card: translucent dark glass (ColorOS 17 lets a little of the screen through)
        val aTop = (255 - 40 * e).toInt()
        val aBottom = (255 - 58 * e).toInt()
        fill.shader = LinearGradient(0f, top, 0f, top + h,
            ColorUtils.setAlphaComponent(0xFF16161A.toInt(), aTop), ColorUtils.setAlphaComponent(0xFF0B0B0D.toInt(), aBottom), Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(0f, top, 0f, top + max(24f * density, h * 0.35f),
            ColorUtils.setAlphaComponent(Color.WHITE, (16 * e).toInt()), 0x00FFFFFF, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(0f, top, 0f, top + h,
            ColorUtils.setAlphaComponent(Color.WHITE, (90 * e).toInt()), ColorUtils.setAlphaComponent(Color.WHITE, (22 * e).toInt()), Shader.TileMode.CLAMP)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Progress bar with ColorOS' "chasing light": a bright spot runs along the filled part and glows
 * at its head. Used for music and live progress (downloads, deliveries).
 */
class ChasingProgressView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chase = Paint(Paint.ANTI_ALIAS_FLAG)
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL) }
    private val rect = RectF()
    private var t = 0f
    private val runner = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    var color = Color.WHITE
        set(v) { field = v; invalidate() }

    /** 0..1, or null for an indeterminate bar */
    var progress: Float? = 0f
        set(v) { field = v?.coerceIn(0f, 1f); invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); runner.start() }
    override fun onDetachedFromWindow() { runner.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val barH = 4f * density
        val top = (h - barH) / 2f
        rect.set(0f, top, w, top + barH)
        canvas.drawRoundRect(rect, barH, barH, track)
        val p = progress
        val end = if (p == null) w else w * p
        if (end <= 0f) return
        rect.set(0f, top, end, top + barH)
        fillPaint.color = color
        fillPaint.alpha = if (p == null) 90 else 255
        canvas.drawRoundRect(rect, barH, barH, fillPaint)
        // the chasing light: a short bright streak that runs from the start to the head
        val x = end * t
        val len = max(24f * density, end * 0.25f)
        chase.shader = LinearGradient(x - len, 0f, x, 0f,
            intArrayOf(0x00FFFFFF, ColorUtils.setAlphaComponent(Color.WHITE, 200)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(max(0f, x - len), top, x, top + barH), barH, barH, chase)
        if (p != null) {
            head.color = ColorUtils.blendARGB(color, Color.WHITE, 0.5f)
            canvas.drawCircle(end, h / 2f, 4.5f * density, head)
        }
    }
}
