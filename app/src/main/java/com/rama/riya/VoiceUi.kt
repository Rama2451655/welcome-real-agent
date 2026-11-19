package com.rama.riya

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The four Google-coloured bars. Idle = four dots, listening = bars that follow your voice,
 * thinking = bouncing dots, speaking = a gentle wave.
 */
class GoogleBarsView(ctx: Context) : View(ctx) {
    @Volatile var mode = "idle"

    private val colors = intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cur = FloatArray(4)
    private val yOff = FloatArray(4)
    private var t = 0f
    private var lvl = 0f
    private var running = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            t += 0.05f
            val target = RiyaService.level
            lvl += (target - lvl) * 0.4f
            RiyaService.level = target * 0.82f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val barW = minOf(h * 0.34f, w / 9f)
        val gap = barW * 0.85f
        val total = 4 * barW + 3 * gap
        val x0 = (w - total) / 2f
        val cy = h / 2f
        val maxH = h * 0.92f
        for (i in 0..3) {
            var targetH = barW
            var targetY = 0f
            when (mode) {
                "listening" -> {
                    val wob = 0.55f + 0.45f * abs(sin(t * 3f + i * 0.9f))
                    targetH = barW + (maxH - barW) * (0.10f + 0.90f * lvl) * wob
                }
                "speaking" -> {
                    targetH = barW + (maxH - barW) * (0.25f + 0.55f * abs(sin(t * 4f + i * 1.3f)))
                }
                "thinking" -> {
                    targetY = -(sin(t * 5f - i * 0.8f).coerceAtLeast(0f)) * h * 0.28f
                }
            }
            cur[i] += (targetH - cur[i]) * 0.3f
            yOff[i] += (targetY - yOff[i]) * 0.3f
            if (cur[i] < barW) cur[i] = barW
            val x = x0 + i * (barW + gap)
            rect.set(x, cy - cur[i] / 2f + yOff[i], x + barW, cy + cur[i] / 2f + yOff[i])
            paint.color = colors[i]
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }
}

/** Slowly drifting soft colour glow used behind the main screen header. */
class AuroraView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cols = intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFF34A853.toInt(), 0xFFFBBC05.toInt())
    private var t = 0f
    private var running = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            t += 0.03f
            invalidate()
            postDelayed(this, 40)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; post(frame) }

    override fun onDetachedFromWindow() { running = false; removeCallbacks(frame); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = max(w, h) * 0.42f
        for (i in 0..3) {
            val cx = w * (0.5f + 0.38f * sin(t * 0.6f + i * 1.7f))
            val cy = h * (0.5f + 0.30f * cos(t * 0.5f + i * 2.1f))
            val c = (cols[i] and 0x00FFFFFF) or (0x46 shl 24)
            paint.shader = RadialGradient(cx, cy, r, intArrayOf(c, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}

/** Notices when nothing has happened for a while, so the voice panel can close itself. */
class IdleTracker {
    private var key = ""
    private var at = System.currentTimeMillis()

    fun touch() { at = System.currentTimeMillis() }

    fun idle(ms: Long): Boolean {
        val k = RiyaService.state + "|" + RiyaService.lastHeard + "|" + RiyaService.lastReply + "|" + (RiyaService.level > 0.3f)
        val now = System.currentTimeMillis()
        if (k != key || RiyaService.state == "thinking" || RiyaService.state == "speaking") { key = k; at = now }
        return now - at > ms
    }
}

/**
 * The Google-Assistant-like panel. Big panel (bottom sheet) for conversations,
 * small pill at the top while typing by voice (so it never covers the keyboard).
 */
class VoicePanel(ctx: Context, private val pill: Boolean, private val onClose: () -> Unit) : LinearLayout(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val bars = GoogleBarsView(ctx)
    private val status = TextView(ctx)
    private val heard = TextView(ctx)
    private val reply = TextView(ctx)
    private val hint = TextView(ctx)

    init {
        if (pill) buildPill() else buildSheet()
    }

    private fun buildSheet() {
        orientation = VERTICAL
        val r = dp(28).toFloat()
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2202030.toInt(), 0xF20B0B13.toInt())).apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(dp(1), 0x33FFFFFF)
        }
        setPadding(dp(24), dp(12), dp(24), dp(30))

        val handle = View(context).apply {
            background = GradientDrawable().apply { setColor(0x55FFFFFF); cornerRadius = dp(2).toFloat() }
        }
        addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status.apply { textSize = 14f; setTextColor(0xFF9AA3C0.toInt()); letterSpacing = 0.04f }
        head.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(context).apply {
            text = "\u2715"; textSize = 20f; setTextColor(0xFFB9B3E6.toInt())
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener { onClose() }
        }
        head.addView(close)
        addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        heard.apply {
            textSize = 26f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END
        }
        addView(heard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        reply.apply {
            textSize = 17f; setTextColor(0xFFB7F0DD.toInt())
            maxLines = 6; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        }
        addView(reply, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        hint.apply { textSize = 12f; setTextColor(0xFFFFC04D.toInt()) }
        addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(18) })
    }

    private fun buildPill() {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(0xEE16161E.toInt()); cornerRadius = dp(28).toFloat(); setStroke(dp(1), 0x33FFFFFF)
        }
        setPadding(dp(16), dp(10), dp(20), dp(10))
        addView(bars, LinearLayout.LayoutParams(dp(56), dp(30)))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        status.apply { textSize = 12f; setTextColor(0xFF9AA3C0.toInt()) }
        heard.apply {
            textSize = 15f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(240)
        }
        col.addView(status)
        col.addView(heard)
        addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        setOnClickListener { onClose() }
    }

    fun refresh() {
        val st = RiyaService.state
        val typing = RiyaService.typing
        bars.mode = when {
            typing -> "listening"
            st == "listening" -> "listening"
            st == "thinking" -> "thinking"
            st == "speaking" -> "speaking"
            else -> "idle"
        }
        status.text = when {
            typing -> "Typing \u2022 say \u201cstop typing\u201d to finish"
            st == "listening" -> "Listening\u2026"
            st == "thinking" -> "Thinking\u2026"
            st == "speaking" -> "Speaking\u2026"
            else -> ""
        }
        val h = RiyaService.lastHeard
        heard.text = if (h.isEmpty()) (if (pill) "" else "Say something\u2026") else h
        if (!pill) {
            reply.text = RiyaService.lastReply
            reply.visibility = if (RiyaService.lastReply.isEmpty()) View.GONE else View.VISIBLE
            val hn = RiyaService.lastHint
            hint.text = hn
            hint.visibility = if (hn.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}

/** Shows [VoicePanel] over any app (unlocked phone). Never takes focus, so typing and other apps are not disturbed. */
class VoiceOverlay(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var view: VoicePanel? = null
    private var pillMode = false
    private val tracker = IdleTracker()
    private val density = ctx.resources.displayMetrics.density

    private val ticker = object : Runnable {
        override fun run() {
            val v = view ?: return
            v.refresh()
            if (!RiyaService.typing && tracker.idle(5500L)) { hideNow(); return }
            main.postDelayed(this, 120)
        }
    }

    fun show(pill: Boolean) { main.post { showNow(pill) } }

    fun hide() { main.post { hideNow() } }

    fun destroy() { main.post { removeNow() } }

    private fun showNow(pill: Boolean) {
        if (!Settings.canDrawOverlays(ctx)) return
        if (view != null && pillMode == pill) { tracker.touch(); return }
        removeNow()
        try {
            val wm = ctx.getSystemService(WindowManager::class.java)
            val p = VoicePanel(ctx, pill) { hideNow() }
            val lp = WindowManager.LayoutParams(
                if (pill) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = if (pill) (Gravity.TOP or Gravity.CENTER_HORIZONTAL) else Gravity.BOTTOM
                if (pill) y = (48 * density).toInt()
            }
            p.alpha = 0f
            p.translationY = (if (pill) -40 else 120) * density
            wm.addView(p, lp)
            p.animate().alpha(1f).translationY(0f).setDuration(220).start()
            view = p
            pillMode = pill
            tracker.touch()
            p.refresh()
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, 120)
        } catch (e: Exception) { view = null }
    }

    private fun hideNow() {
        val v = view ?: return
        main.removeCallbacks(ticker)
        view = null
        v.animate().alpha(0f).translationY((if (pillMode) -40 else 120) * density).setDuration(180).withEndAction {
            try { ctx.getSystemService(WindowManager::class.java).removeView(v) } catch (e: Exception) { }
        }.start()
    }

    private fun removeNow() {
        val v = view ?: return
        main.removeCallbacks(ticker)
        view = null
        try { ctx.getSystemService(WindowManager::class.java).removeView(v) } catch (e: Exception) { }
    }
}

/** The same panel on the lock screen: wakes the screen and shows over the keyguard without unlocking. */
class LockVoiceActivity : Activity() {
    companion object {
        @Volatile var alive = false
        @Volatile var instance: LockVoiceActivity? = null
        /** Close the panel (it covers the PIN pad while the phone is being unlocked). Call on the main thread. */
        fun dismiss() { try { instance?.finish() } catch (e: Exception) { } }
    }

    private val h = Handler(Looper.getMainLooper())
    private val tracker = IdleTracker()
    private var panel: VoicePanel? = null

    private val tick = object : Runnable {
        override fun run() {
            panel?.refresh()
            val kg = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (RiyaService.state == "stopped" || !kg.isKeyguardLocked || (!RiyaService.typing && tracker.idle(6500L))) {
                finish()
                return
            }
            h.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        instance = this
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(0xB0000000.toInt()))

        val root = FrameLayout(this)
        root.setOnClickListener { finish() }
        val p = VoicePanel(this, false) { finish() }
        p.isClickable = true
        root.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        panel = p
        tracker.touch()
        p.refresh()
        h.postDelayed(tick, 120)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        tracker.touch()
    }

    override fun onDestroy() {
        alive = false
        if (instance === this) instance = null
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
