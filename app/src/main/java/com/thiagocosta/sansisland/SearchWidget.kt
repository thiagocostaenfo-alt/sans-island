package com.thiagocosta.sansisland

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * Google search bar in glass styles. The bar itself is a nine-patch (only its middle stretches), so the
 * rounded ends and the vertical gradient keep their proportions at any width; icons and text are real views.
 */
abstract class SearchWidget(private val style: Style) : AppWidgetProvider() {

    enum class Style { SMOKY, ICON }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, manager, id)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val layout = if (style == Style.SMOKY) R.layout.widget_search_smoky else R.layout.widget_search_icon
        val views = RemoteViews(context.packageName, layout)
        googleIconBitmap(context)?.let { views.setImageViewBitmap(R.id.widget_g, it) }
        views.setOnClickPendingIntent(R.id.widget_search, action(context, SearchLauncher.SEARCH, 1))
        manager.updateAppWidget(id, views)
    }

    private fun action(context: Context, what: String, code: Int): PendingIntent {
        val intent = Intent(context, SearchLauncher::class.java)
            .setAction(what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** the Google app's own "G", square, without the white circle of its launcher icon */
    private fun googleIconBitmap(context: Context): Bitmap? = try {
        val icon = context.packageManager.getApplicationIcon(GOOGLE_PKG)
        val size = (16 * context.resources.displayMetrics.density * 2).roundToInt() // 2x for crisp scaling
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (icon is AdaptiveIconDrawable) {
            // the logo sits in the inner 2/3 of a 108dp canvas: enlarge so the logo fills the square
            val extra = (size * 0.25f).roundToInt()
            icon.foreground.setBounds(-extra, -extra, size + extra, size + extra)
            icon.foreground.draw(c)
        } else {
            icon.setBounds(0, 0, size, size)
            icon.draw(c)
        }
        bmp
    } catch (_: Exception) { null }

    companion object {
        const val GOOGLE_PKG = "com.google.android.googlequicksearchbox"
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
