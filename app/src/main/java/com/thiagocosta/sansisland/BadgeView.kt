package com.thiagocosta.sansisland

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils

/** team badge: a disc in the team's two colors with its abbreviation (no official crest) */
class BadgeView(context: Context, private val team: TeamSide) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = 1.2f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f * density, 0f, 0f, 0x88000000.toInt())
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f; val r = s / 2f - ring.strokeWidth
        rect.set(cx - r, cy - r, cx + r, cy + r)
        // a near-identical second color would make one flat disc: then use a darker shade of the first
        val second = if (ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(team.altColor, 255),
                ColorUtils.setAlphaComponent(team.color, 255)) < 1.3) ColorUtils.blendARGB(team.color, Color.BLACK, 0.35f) else team.altColor
        fill.color = team.color
        canvas.drawArc(rect, 90f, 180f, true, fill)
        fill.color = second
        canvas.drawArc(rect, 270f, 180f, true, fill)
        canvas.drawOval(rect, ring)
        val label = team.abbr.take(3)
        text.textSize = r * (if (label.length >= 3) 0.62f else 0.8f)
        canvas.drawText(label, cx, cy - (text.ascent() + text.descent()) / 2f, text)
    }
}
