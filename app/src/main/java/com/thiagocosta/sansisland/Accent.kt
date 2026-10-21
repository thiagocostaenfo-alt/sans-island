package com.thiagocosta.sansisland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap

/** accent colors taken from app icons and album art, same idea as the Sans Keyboard */
object Accent {
    const val DEFAULT = 0xFF3E8BF5.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()

    private const val NONE = 0
    private const val SIZE = 48
    private val cache = LruCache<String, Int>(100)

    fun forPackage(context: Context, pkg: String?): Int {
        if (pkg.isNullOrEmpty()) return DEFAULT
        val cached = cache.get(pkg)
        if (cached != null) return if (cached == NONE) DEFAULT else cached
        val color = try {
            fromDrawable(context.packageManager.getApplicationIcon(pkg))
        } catch (_: Exception) { null }
        cache.put(pkg, color ?: NONE)
        return color ?: DEFAULT
    }

    fun fromBitmap(bitmap: Bitmap?): Int? {
        if (bitmap == null) return null
        return try {
            dominant(Bitmap.createScaledBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, false), SIZE, SIZE, true))
        } catch (_: Exception) { null }
    }

    fun fromDrawable(drawable: Drawable?): Int? {
        if (drawable == null) return null
        return try { dominant(drawable.toBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)) } catch (_: Exception) { null }
    }

    /** most prominent saturated hue, made bright enough to read on the black island */
    private fun dominant(bitmap: Bitmap): Int? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buckets = 36
        val weight = FloatArray(buckets)
        val r = FloatArray(buckets)
        val g = FloatArray(buckets)
        val b = FloatArray(buckets)
        val hsv = FloatArray(3)
        var opaque = 0
        for (p in pixels) {
            if (Color.alpha(p) < 200) continue
            opaque++
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.22f) continue
            val wt = hsv[1] * hsv[2]
            val i = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            weight[i] += wt
            r[i] += Color.red(p) * wt
            g[i] += Color.green(p) * wt
            b[i] += Color.blue(p) * wt
        }
        if (opaque == 0) return null
        var best = -1
        var bestW = 0f
        for (i in 0 until buckets) {
            val wt = weight[i] + 0.5f * (weight[(i + 1) % buckets] + weight[(i + buckets - 1) % buckets])
            if (wt > bestW) { bestW = wt; best = i }
        }
        if (best < 0 || weight[best] < opaque * 0.04f) return null
        val wt = weight[best]
        val color = Color.rgb((r[best] / wt).toInt(), (g[best] / wt).toInt(), (b[best] / wt).toInt())
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = hsl[2].coerceIn(0.5f, 0.68f)
        return ColorUtils.HSLToColor(hsl)
    }
}
