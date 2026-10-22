package com.thiagocosta.sansisland

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** Google search bar in glass styles. The whole bar is drawn into a bitmap, so the rim can fade like on the icons. */
abstract class SearchWidget(private val style: Style) : AppWidgetProvider() {

    enum class Style { SMOKY, ICON }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, manager, id)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val options = manager.getAppWidgetOptions(id)
        val density = context.resources.displayMetrics.density
        // portrait uses min width and max height
        val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(180)
        val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 56).coerceIn(40, 64)
        val w = (wDp * density).roundToInt()
        val h = (hDp * density).roundToInt()

        val views = RemoteViews(context.packageName, R.layout.widget_search)
        views.setImageViewBitmap(R.id.widget_bg, render(context, w, h, density))
        views.setOnClickPendingIntent(R.id.widget_search, action(context, SearchLauncher.SEARCH, 1))
        views.setOnClickPendingIntent(R.id.widget_mic, action(context, SearchLauncher.VOICE, 2))
        views.setOnClickPendingIntent(R.id.widget_lens, action(context, SearchLauncher.LENS, 3))
        manager.updateAppWidget(id, views)
    }

    private fun action(context: Context, what: String, code: Int): PendingIntent {
        val intent = Intent(context, SearchLauncher::class.java)
            .setAction(what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun render(context: Context, w: Int, h: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val inset = 1.5f * density
        val rect = RectF(inset, inset, w - inset, h - inset)
        val r = rect.height() / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density }
        when (style) {
            Style.SMOKY -> {
                fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0x9E46464A.toInt(), 0xB51A1A1D.toInt(), Shader.TileMode.CLAMP)
                rim.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0x6BF0F0F0, 0x1FF0F0F0, Shader.TileMode.CLAMP)
            }
            Style.ICON -> {
                fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                    intArrayOf(0xF2000000.toInt(), 0xB0000000.toInt(), 0x2B000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                rim.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                    intArrayOf(0x14D7DCE2, 0x30D7DCE2, 0x99D7DCE2.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            }
        }
        c.drawRoundRect(rect, r, r, fill)
        val half = rim.strokeWidth / 2f
        c.drawRoundRect(RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half), r - half, r - half, rim)

        // left: the Google app's own "G" (from the installed app), or a magnifier if it is missing
        val iconSize = h * 0.46f
        val cy = h / 2f
        val gLeft = r * 0.7f
        val google = googleIcon(context)
        if (google != null) {
            google.setBounds(gLeft.roundToInt(), (cy - iconSize / 2).roundToInt(), (gLeft + iconSize).roundToInt(), (cy + iconSize / 2).roundToInt())
            google.draw(c)
        } else drawMagnifier(c, gLeft + iconSize / 2, cy, iconSize / 2, density)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCDD0D6.toInt()
            textSize = h * 0.30f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        c.drawText(context.getString(R.string.widget_search_hint), gLeft + iconSize + 12 * density, cy - (text.ascent() + text.descent()) / 2, text)

        val ink = 0xFFECEEF2.toInt()
        // must match the click zones in widget_search.xml: two 48dp slots, 8dp from the right edge
        val slot = 48 * density
        val end = w - 8 * density
        drawLens(c, end - slot * 0.5f, cy, h * 0.19f, ink, density)
        drawMic(c, end - slot * 1.5f, cy, h * 0.19f, ink, density)
        return bmp
    }

    private fun googleIcon(context: Context): Drawable? = try {
        val icon = context.packageManager.getApplicationIcon(GOOGLE_PKG)
        if (icon is AdaptiveIconDrawable) {
            // the foreground holds the logo inside a 108dp canvas, crop to the safe zone
            object : Drawable() {
                val fg = icon.foreground
                override fun draw(canvas: Canvas) {
                    val b = bounds
                    val extra = b.width() * 0.25f
                    fg.setBounds((b.left - extra).roundToInt(), (b.top - extra).roundToInt(), (b.right + extra).roundToInt(), (b.bottom + extra).roundToInt())
                    canvas.save(); canvas.clipRect(b); fg.draw(canvas); canvas.restore()
                }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        } else icon
    } catch (_: Exception) { null }

    private fun drawMagnifier(c: Canvas, cx: Float, cy: Float, s: Float, density: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFECEEF2.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeCap = Paint.Cap.ROUND }
        c.drawCircle(cx - s * 0.15f, cy - s * 0.15f, s * 0.55f, p)
        c.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s * 0.75f, cy + s * 0.75f, p)
    }

    private fun drawMic(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, density: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND }
        c.drawRoundRect(RectF(cx - s * 0.36f, cy - s, cx + s * 0.36f, cy + s * 0.25f), s * 0.36f, s * 0.36f, fill)
        c.drawArc(RectF(cx - s * 0.65f, cy - s * 0.6f, cx + s * 0.65f, cy + s * 0.55f), 0f, 180f, false, line)
        c.drawLine(cx, cy + s * 0.55f, cx, cy + s * 0.9f, line)
    }

    /** camera viewfinder for visual search */
    private fun drawLens(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, density: Float) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val r = s * 0.9f
        val k = s * 0.45f
        val path = Path()
        // four corners of a viewfinder
        path.moveTo(cx - r, cy - r + k); path.quadTo(cx - r, cy - r, cx - r + k, cy - r)
        path.moveTo(cx + r - k, cy - r); path.quadTo(cx + r, cy - r, cx + r, cy - r + k)
        path.moveTo(cx + r, cy + r - k); path.quadTo(cx + r, cy + r, cx + r - k, cy + r)
        path.moveTo(cx - r + k, cy + r); path.quadTo(cx - r, cy + r, cx - r, cy + r - k)
        c.drawPath(path, line)
        c.drawCircle(cx, cy, s * 0.32f, fill)
    }

    companion object {
        const val GOOGLE_PKG = "com.google.android.googlequicksearchbox"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            listOf(SearchWidgetSmoky::class.java, SearchWidgetIcon::class.java).forEach { cls ->
                val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isNotEmpty()) context.sendBroadcast(Intent(context, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
            }
        }
    }
}

class SearchWidgetSmoky : SearchWidget(Style.SMOKY)
class SearchWidgetIcon : SearchWidget(Style.ICON)

/** invisible activity: opens Google search, voice search or Lens, falling back to whatever is installed */
class SearchLauncher : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = SearchWidget.GOOGLE_PKG
        val candidates: List<Intent> = when (intent?.action) {
            VOICE -> listOf(
                Intent("android.speech.action.WEB_SEARCH").setPackage(g),
                Intent(Intent.ACTION_VOICE_COMMAND),
                Intent("android.speech.action.WEB_SEARCH"),
            )
            LENS -> listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).setPackage(g),
                Intent().setClassName(g, "com.google.android.apps.search.lens.LensExportedActivity"),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com")),
            )
            else -> listOf(
                Intent("android.search.action.GLOBAL_SEARCH").setPackage(g),
                Intent(Intent.ACTION_WEB_SEARCH).setPackage(g),
                Intent("android.search.action.GLOBAL_SEARCH"),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            )
        }
        for (i in candidates) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                break
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        const val SEARCH = "com.thiagocosta.sansisland.SEARCH"
        const val VOICE = "com.thiagocosta.sansisland.VOICE"
        const val LENS = "com.thiagocosta.sansisland.LENS"
    }
}
