package com.thiagocosta.sansisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The island: a black pill around the front camera that grows into a card.
 * COMPACT: icon left of the camera, timer / % / waveform right of it, optional second bubble.
 * EXPANDED: full width card with picture, texts, progress and buttons (long press, notifications, incoming calls).
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        /** place the overlay window; x/y are screen coordinates of its top-left corner */
        fun applyWindow(width: Int, height: Int, x: Int, y: Int, touchable: Boolean)
    }

    private enum class Mode { HIDDEN, COMPACT, EXPANDED }

    // geometry of the camera cutout, screen coordinates
    private var screenW = resources.displayMetrics.widthPixels
    private var cutoutCenterX = screenW / 2
    private var cutoutCenterY = dp(20)
    private var cutoutW = dp(14)
    private var cutoutH = dp(14)

    private val pillBg = GradientDrawable().apply { setColor(Color.BLACK) }
    private val pill = FrameLayout(context).apply { background = pillBg; clipToOutline = true }
    private val compactRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val expandedPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val bubble = FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
        visibility = GONE
    }
    private val bubbleIcon = ImageView(context)
    private val glow = GlowView(context, pill) { radius }
    private var glowStyle = GlowView.styleFromPref(Prefs.islandStyle)
    private val glass = SiriGlassDrawable(resources.displayMetrics.density)
    private val bubbleGlass = SiriGlassDrawable(resources.displayMetrics.density)
    private val bubblePlain = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }

    /** item (key@update) that already had its "Siri moment", so it is not replayed */
    private var momentToken: String? = null
    private val finishMoment = Runnable { render() }

    private var mode = Mode.HIDDEN
    private var shownItem: IslandItem? = null
    private var expandedKey: String? = null
    private var suppressed = false
    private var animator: ValueAnimator? = null
    private var pillW = 0
    private var pillH = 0
    private var radius = 0f
    private var pillLip = 0f

    // views updated by the media ticker
    private var mediaProgress: ProgressBar? = null
    private var mediaElapsed: TextView? = null
    private var mediaRemaining: TextView? = null
    private val mediaTicker = object : Runnable {
        override fun run() {
            updateMediaProgress()
            postDelayed(this, 500)
        }
    }
    private val autoCollapse = Runnable { collapse() }

    init {
        clipChildren = false
        pill.addView(compactRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pill.addView(expandedPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pill.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        glow.style = glowStyle
        applyBackgrounds()
        addView(pill, LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = glowPad() })
        bubble.addView(bubbleIcon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bubble, LayoutParams(0, 0, Gravity.TOP or Gravity.END).apply { topMargin = glowPad(); marginEnd = glowPad() })
        // on top of the pill so the light line sits right on its edge; it never consumes touches
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pill.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> glow.invalidate() }
        setupGestures()
        bubble.setOnClickListener { IslandHub.secondary()?.let { IslandHub.prefer(it.key) }; haptic(HapticFeedbackConstants.CONTEXT_CLICK) }
        visibility = INVISIBLE
    }

    // ------------------------------------------------------------ public api

    fun setGeometry(screenWidth: Int, cutoutCenterX: Int, cutoutCenterY: Int, cutoutWidth: Int, cutoutHeight: Int) {
        screenW = screenWidth
        this.cutoutCenterX = cutoutCenterX
        this.cutoutCenterY = cutoutCenterY
        cutoutW = cutoutWidth
        cutoutH = cutoutHeight
        render(animate = false)
    }

    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        render(animate = true)
    }

    fun render(animate: Boolean = true) {
        removeCallbacks(finishMoment)
        applyStyle()
        val item = if (suppressed) null else IslandHub.primary()
        val secondary = if (suppressed) null else IslandHub.secondary()
        if (expandedKey != null && item?.key != expandedKey) expandedKey = null
        val newMode = when {
            item == null -> Mode.HIDDEN
            item.kind == Kind.NOTIFICATION || item.kind == Kind.TEST || item.incomingCall || item.key == expandedKey -> Mode.EXPANDED
            else -> Mode.COMPACT
        }
        mode = newMode
        if (item != null && (item.key != shownItem?.key || item.updatedAt != shownItem?.updatedAt) && item.kind != Kind.MEDIA) {
            glow.pulse()
            if (isGlass()) glass.pulse()
        }
        if (item != null) glow.accent = item.accent
        glow.setRunning(newMode != Mode.HIDDEN)
        shownItem = item
        removeCallbacks(mediaTicker)

        when (newMode) {
            Mode.HIDDEN -> {
                bubble.visibility = GONE
                animateTo(compactHeight(), compactHeight(), compactHeight() / 2f, 0f, animate, null) {
                    if (mode == Mode.HIDDEN) {
                        visibility = INVISIBLE
                        glow.setRunning(false)
                        glass.stop()
                        host.applyWindow(1, 1, cutoutCenterX, 0, touchable = false)
                    }
                }
            }
            Mode.COMPACT -> {
                visibility = VISIBLE
                bindCompact(item!!)
                val h = compactHeight()
                val w = measureCompactWidth(h)
                bindBubble(secondary, h)
                // the glass hangs below the black part, so the black part stays centered on the camera
                val lip = compactLip()
                animateTo(w, h + lip, (h + lip) / 2f, lip.toFloat(), animate, compactRow)
            }
            Mode.EXPANDED -> {
                visibility = VISIBLE
                bubble.visibility = GONE
                // new Siri: something new arrives -> the island first swells into the glowing glass pebble
                val token = item!!.key + "@" + item.updatedAt
                val arrival = item.kind == Kind.NOTIFICATION || item.kind == Kind.TEST || item.incomingCall
                if (isGlass() && animate && arrival && momentToken != token) {
                    momentToken = token
                    glass.listen()
                    val ph = dp(104)
                    animateTo(dp(150), ph, ph * 0.43f, ph * 0.47f, true, null) { postDelayed(finishMoment, 260) }
                    return
                }
                bindExpanded(item)
                val w = screenW - dp(20)
                expandedPanel.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val h = max(expandedPanel.measuredHeight, compactHeight())
                val r = (if (isGlass()) dp(34) else dp(30)).toFloat().coerceAtMost(h / 2f)
                animateTo(w, h, r, expandedLip().toFloat(), animate, expandedPanel)
                if (item.media != null) post(mediaTicker)
            }
        }
    }

    fun collapse() {
        removeCallbacks(autoCollapse)
        if (expandedKey != null) { expandedKey = null; render() }
    }

    // ------------------------------------------------------------ sizing

    private fun compactHeight(): Int {
        val base = (cutoutH + dp(14)).coerceIn(dp(30), dp(40))
        return (base + dp(Prefs.sizeDp)).coerceAtLeast(dp(24))
    }

    private fun cameraGap(): Int = cutoutW + dp(14)

    private fun isGlass() = glowStyle == GlowView.Style.GLASS

    /** height of the glass part below the black part */
    private fun compactLip() = if (isGlass()) (compactHeight() * 0.36f).roundToInt() else 0
    private fun expandedLip() = if (isGlass()) dp(22) else 0

    private fun measureCompactWidth(h: Int): Int {
        val left = compactRow.getChildAt(0)
        val right = compactRow.getChildAt(2)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val exactH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        left.measure(unspecified, exactH)
        right.measure(unspecified, exactH)
        // symmetric around the camera, like the iPhone
        val slot = max(max(left.measuredWidth, right.measuredWidth), h - dp(6))
        (left.layoutParams as LinearLayout.LayoutParams).width = slot
        (right.layoutParams as LinearLayout.LayoutParams).width = slot
        return slot * 2 + cameraGap() + dp(16)
    }

    private fun animateTo(targetW: Int, targetH: Int, targetRadius: Float, targetLip: Float, animate: Boolean, content: View?, end: (() -> Unit)? = null) {
        animator?.cancel()
        val startW = if (pillW == 0) targetH else pillW
        val startH = if (pillH == 0) targetH else pillH
        val startR = if (pillW == 0) targetH / 2f else radius
        val startLip = if (pillW == 0) targetLip else pillLip
        val bubbleSpace = if (bubble.visibility == VISIBLE) 2 * (bubbleSize() + dp(6)) else 0
        // during the animation the window covers both sizes; it shrinks to the final size afterwards
        placeWindow(max(startW, targetW) + bubbleSpace, max(startH, targetH))
        for (v in listOf(compactRow, expandedPanel)) if (v !== content) v.visibility = GONE
        content?.visibility = if (mode == Mode.HIDDEN) GONE else VISIBLE
        if (!animate) {
            setPill(targetW, targetH, targetRadius, targetLip)
            content?.alpha = 1f
            placeWindow(targetW + bubbleSpace, targetH)
            end?.invoke()
            return
        }
        content?.alpha = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                val f = it.animatedValue as Float
                val c = f.coerceIn(0f, 1f)
                setPill(lerp(startW, targetW, f), lerp(startH, targetH, f), startR + (targetRadius - startR) * c, startLip + (targetLip - startLip) * c)
                content?.alpha = ((it.animatedFraction - 0.35f) / 0.65f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    setPill(targetW, targetH, targetRadius, targetLip)
                    content?.alpha = 1f
                    placeWindow(targetW + bubbleSpace, targetH)
                    end?.invoke()
                }
            })
            start()
        }
    }

    private fun setPill(w: Int, h: Int, r: Float, lip: Float) {
        pillW = w
        pillH = h
        radius = r
        pillLip = lip
        pillBg.cornerRadius = r
        glass.radius = r
        glass.lip = lip
        glass.invalidateSelf()
        glow.invalidate()
        val lp = pill.layoutParams
        lp.width = w
        lp.height = h
        pill.layoutParams = lp
        pill.invalidateOutline()
    }

    private fun placeWindow(contentWidth: Int, contentHeight: Int) {
        val pad = glowPad()
        val width = contentWidth + 2 * pad
        val height = contentHeight + 2 * pad
        val w = width.coerceAtMost(screenW)
        val centerX = cutoutCenterX + dp(Prefs.offsetXdp)
        val x = (centerX - w / 2).coerceIn(0, max(0, screenW - w))
        val top = cutoutCenterY - compactHeight() / 2 + dp(Prefs.offsetYdp) - pad
        host.applyWindow(w, height, x, top, touchable = mode != Mode.HIDDEN)
    }

    private fun bubbleSize() = compactHeight()

    /** room around the pill for the Siri glow */
    private fun glowPad() = when (glowStyle) {
        GlowView.Style.CLASSIC -> 0
        GlowView.Style.GLASS -> dp(18) // room for the shadow under the glass
        else -> dp(14)
    }

    private fun applyStyle() {
        val style = GlowView.styleFromPref(Prefs.islandStyle)
        if (style == glowStyle) return
        glowStyle = style
        glow.style = style
        if (style != GlowView.Style.GLASS) glass.stop()
        applyBackgrounds()
        (pill.layoutParams as LayoutParams).topMargin = glowPad()
        (bubble.layoutParams as LayoutParams).apply { topMargin = glowPad(); marginEnd = glowPad() }
        pill.requestLayout()
        bubble.requestLayout()
    }

    private fun applyBackgrounds() {
        pill.background = if (isGlass()) glass else pillBg
        bubble.background = if (isGlass()) bubbleGlass else bubblePlain
    }

    // ------------------------------------------------------------ compact content

    private fun bindCompact(item: IslandItem) {
        compactRow.removeAllViews()
        val h = compactHeight()
        val iconSize = (h * 0.62f).roundToInt()

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(4), 0, 0, 0)
        }
        left.addView(iconView(item, iconSize, rounded = iconSize / 2f), LinearLayout.LayoutParams(iconSize, iconSize))
        item.compactLabel?.let {
            left.addView(text(it, 13f, Color.WHITE, bold = true).apply { setPadding(dp(6), 0, 0, 0) })
        }

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, dp(4), 0)
        }
        when {
            item.chronoBase != null -> right.addView(chronometer(item, 13f))
            item.compactText != null -> right.addView(text(item.compactText, 13f, item.accent, bold = true))
            item.media != null -> right.addView(WaveformView(context).apply {
                color = item.accent
                setPlaying(item.media.playing)
            }, LinearLayout.LayoutParams(dp(20), (h * 0.45f).roundToInt()))
            else -> right.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(item.accent) }
            }, LinearLayout.LayoutParams(dp(8), dp(8)))
        }

        compactRow.setPadding(dp(8), 0, dp(8), compactLip())
        compactRow.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        compactRow.addView(View(context), LinearLayout.LayoutParams(cameraGap(), 1))
        compactRow.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun bindBubble(secondary: IslandItem?, size: Int) {
        if (secondary == null) { bubble.visibility = GONE; return }
        bubble.visibility = VISIBLE
        (bubble.layoutParams as LayoutParams).apply { width = size; height = size }
        bubble.requestLayout()
        bubbleGlass.radius = size / 2f
        bubbleGlass.lip = size * 0.34f
        bubbleGlass.invalidateSelf()
        val pad = (size * 0.2f).roundToInt()
        bubbleIcon.setPadding(pad, pad, pad, pad)
        applyIcon(bubbleIcon, secondary, rounded = (size - 2 * pad) / 2f)
    }

    // ------------------------------------------------------------ expanded content

    private fun bindExpanded(item: IslandItem) {
        expandedPanel.removeAllViews()
        mediaProgress = null; mediaElapsed = null; mediaRemaining = null
        expandedPanel.setPadding(dp(16), dp(14), dp(16), dp(14) + expandedLip())

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iconSize = dp(46)
        header.addView(iconView(item, iconSize, rounded = if (item.iconIsPicture) dp(12).toFloat() else iconSize / 2f),
            LinearLayout.LayoutParams(iconSize, iconSize))

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        texts.addView(text(item.title, 15f, Color.WHITE, bold = true, lines = 1))
        if (!item.text.isNullOrBlank()) texts.addView(text(item.text, 13f, 0xFFA1A1A6.toInt(), lines = if (item.kind == Kind.NOTIFICATION) 3 else 1))
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        when {
            item.chronoBase != null -> header.addView(chronometer(item, 22f))
            item.media != null -> header.addView(WaveformView(context).apply {
                color = item.accent
                setPlaying(item.media.playing)
            }, LinearLayout.LayoutParams(dp(26), dp(22)))
            item.compactText != null && item.progress == null -> header.addView(text(item.compactText, 20f, item.accent, bold = true))
        }
        expandedPanel.addView(header)

        if (item.media != null && item.media.durationMs > 0) {
            val bar = progressBar(item.accent).apply { max = 1000 }
            mediaProgress = bar
            expandedPanel.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(14) })
            val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            mediaElapsed = text("0:00", 11f, 0xFF8E8E93.toInt())
            mediaRemaining = text("", 11f, 0xFF8E8E93.toInt()).apply { gravity = Gravity.END }
            times.addView(mediaElapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            times.addView(mediaRemaining, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            expandedPanel.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            updateMediaProgress()
        } else if (item.progress != null || item.indeterminate) {
            val bar = progressBar(item.accent).apply {
                isIndeterminate = item.indeterminate && item.progress == null
                max = 1000
                progress = ((item.progress ?: 0f) * 1000).roundToInt()
            }
            expandedPanel.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(14) })
        }

        if (item.actions.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            if (item.media != null) {
                item.actions.forEachIndexed { i, action ->
                    val big = i == 1
                    val size = if (big) dp(52) else dp(44)
                    row.addView(ImageView(context).apply {
                        setImageResource(action.iconRes)
                        imageTintList = ColorStateList.valueOf(Color.WHITE)
                        val pad = if (big) dp(10) else dp(10)
                        setPadding(pad, pad, pad, pad)
                        background = rippleCircle()
                        setOnClickListener { haptic(HapticFeedbackConstants.CONTEXT_CLICK); action.run(); bumpAutoCollapse() }
                    }, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(14); marginEnd = dp(14) })
                }
            } else {
                item.actions.forEach { action ->
                    val bg = when (action.tone) {
                        Tone.POSITIVE -> Accent.GREEN
                        Tone.NEGATIVE -> Accent.RED
                        Tone.NEUTRAL -> 0xFF2C2C2E.toInt()
                    }
                    row.addView(text(action.label, 14f, Color.WHITE, bold = true, lines = 1).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(bg) }
                        setPadding(dp(10), 0, dp(10), 0)
                        setOnClickListener {
                            haptic(HapticFeedbackConstants.CONFIRM)
                            action.run()
                            if (item.kind == Kind.NOTIFICATION) IslandHub.dismissTransient() else collapse()
                        }
                    }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                }
            }
            expandedPanel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
    }

    private fun updateMediaProgress() {
        val media = shownItem?.media ?: return
        val bar = mediaProgress ?: return
        val elapsedSinceUpdate = if (media.playing) ((SystemClock.elapsedRealtime() - media.positionAt) * media.speed).toLong() else 0L
        val pos = (media.positionMs + elapsedSinceUpdate).coerceIn(0L, media.durationMs)
        bar.progress = if (media.durationMs > 0) (pos * 1000 / media.durationMs).toInt() else 0
        mediaElapsed?.text = formatTime(pos)
        mediaRemaining?.text = "-" + formatTime(media.durationMs - pos)
    }

    // ------------------------------------------------------------ gestures

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val item = shownItem ?: return true
                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                item.open?.invoke()
                if (item.kind.ongoing) collapse() else IslandHub.dismissTransient()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val item = shownItem ?: return
                if (mode == Mode.COMPACT) {
                    haptic(HapticFeedbackConstants.LONG_PRESS)
                    expandedKey = item.key
                    render()
                    bumpAutoCollapse()
                }
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val item = shownItem ?: return false
                if (abs(velocityY) < dp(400) && abs(velocityX) < dp(400)) return false
                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                when {
                    !item.kind.ongoing -> IslandHub.dismissTransient()
                    mode == Mode.EXPANDED && velocityY < 0 -> collapse()
                    mode == Mode.COMPACT && velocityY > 0 && abs(velocityY) > abs(velocityX) -> {
                        expandedKey = item.key; render(); bumpAutoCollapse()
                    }
                    else -> { expandedKey = null; IslandHub.dismissOngoing(item.key) }
                }
                return true
            }
        })
        pill.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) bumpAutoCollapse()
            detector.onTouchEvent(event)
        }
    }

    private fun bumpAutoCollapse() {
        removeCallbacks(autoCollapse)
        if (expandedKey != null) postDelayed(autoCollapse, 8000)
    }

    private fun haptic(type: Int) {
        if (Prefs.haptics) performHapticFeedback(type)
    }

    // ------------------------------------------------------------ view helpers

    private fun iconView(item: IslandItem, size: Int, rounded: Float): ImageView =
        ImageView(context).apply {
            applyIcon(this, item, rounded)
            if (!item.iconIsPicture && item.icon == null) {
                val pad = (size * 0.18f).roundToInt()
                setPadding(pad, pad, pad, pad)
            }
        }

    private fun applyIcon(view: ImageView, item: IslandItem, rounded: Float) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        if (item.icon != null) {
            view.setImageDrawable(item.icon)
            view.imageTintList = null
            view.background = null
        } else {
            view.setImageResource(item.iconRes)
            view.imageTintList = ColorStateList.valueOf(item.accent)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((item.accent and 0x00FFFFFF) or 0x33000000)
            }
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, rounded)
        }
        view.clipToOutline = true
    }

    private fun chronometer(item: IslandItem, sizeSp: Float) = Chronometer(context).apply {
        base = item.chronoBase!!
        isCountDown = item.countDown
        setTextColor(item.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
        start()
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun progressBar(accent: Int) = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(accent)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3A3C.toInt())
        indeterminateTintList = ColorStateList.valueOf(accent)
    }

    private fun rippleCircle() = android.graphics.drawable.RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF), null,
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    )

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).roundToInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
