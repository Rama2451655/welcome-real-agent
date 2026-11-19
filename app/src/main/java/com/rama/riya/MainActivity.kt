package com.rama.riya

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.role.RoleManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.min
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private class Step(val title: String, val sub: String, val done: () -> Boolean, val action: () -> Unit)

    // Palette
    private val bgTop = Color.parseColor("#0F0C29")
    private val bgMid = Color.parseColor("#1B1646")
    private val bgBottom = Color.parseColor("#2E1A5E")
    private val purple = Color.parseColor("#8B6CFF")
    private val pink = Color.parseColor("#FF5C93")
    private val teal = Color.parseColor("#2DE2C0")
    private val amber = Color.parseColor("#FFC04D")
    private val red = Color.parseColor("#FF5470")
    private val white = Color.WHITE
    private val soft = Color.parseColor("#B9B3E6")

    private lateinit var prefs: SharedPreferences
    private lateinit var titleView: TextView
    private lateinit var nameBox: EditText
    private lateinit var keyBox: EditText
    private lateinit var geminiBox: android.widget.EditText
    private lateinit var ownerBox: EditText
    private lateinit var errorText: TextView
    private lateinit var bars: GoogleBarsView
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var replyText: TextView
    private lateinit var urlBox: EditText
    @Volatile private var downloading = false
    private val modelUrlDefault =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private val bigBrainUrl =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private val statusViews = ArrayList<Pair<TextView, Step>>()

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun gradOval(c1: Int, c2: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
        shape = GradientDrawable.OVAL
    }

    private fun ripple(bg: GradientDrawable) = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")), bg, null)

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#14FFFFFF"), 24, Color.parseColor("#22FFFFFF"))
        setPadding(dp(20), dp(16), dp(20), dp(16))
        elevation = dp(2).toFloat()
        layoutParams = params(14)
    }

    private fun input(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        textSize = 16f
        setTextColor(white)
        setHintTextColor(Color.parseColor("#80FFFFFF"))
        background = rounded(Color.parseColor("#22FFFFFF"), 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = params(8)
    }

    private fun chooseLanguages() {
        val tags = arrayOf("te-IN", "hi-IN", "ta-IN", "kn-IN", "ml-IN", "mr-IN", "bn-IN", "gu-IN", "pa-IN", "ur-IN")
        val names = arrayOf("Telugu", "Hindi", "Tamil", "Kannada", "Malayalam", "Marathi", "Bengali", "Gujarati", "Punjabi", "Urdu")
        val chosen = (prefs.getString("langs", "te-IN,hi-IN") ?: "").split(",").map { it.trim() }
        val checked = BooleanArray(tags.size) { tags[it] in chosen }
        android.app.AlertDialog.Builder(this)
            .setTitle("Languages I speak (English always on)")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                val sel = tags.filterIndexed { i, _ -> checked[i] }.joinToString(",")
                prefs.edit().putString("langs", sel).apply()
                Toast.makeText(this, "Saved. Restart Riya to use it.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseMusicApp() {
        val known = listOf("spotify", "jiosaavn", "youtubemusic", "gaana", "wynk", "amazonmusic", "playit", "resso", "applemusic", "hungama", "samsungmusic", "musicplayer", "music")
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { (l, _) -> val n = l.lowercase().filter { ch -> ch.isLetterOrDigit() }; known.any { k -> n.contains(k) } }
            .distinctBy { it.second }
        val labels = (listOf("Auto (Spotify first, then others)") + apps.map { it.first }).toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Music app to open")
            .setItems(labels) { _, pos ->
                if (pos == 0) prefs.edit().remove("music_app").apply()
                else prefs.edit().putString("music_app", apps[pos - 1].second).apply()
                Toast.makeText(this, "Saved: " + labels[pos], Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private var pickerTts: android.speech.tts.TextToSpeech? = null

    private fun chooseVoice() {
        pickerTts?.shutdown()
        pickerTts = android.speech.tts.TextToSpeech(this) { st ->
            val t = pickerTts
            if (st != android.speech.tts.TextToSpeech.SUCCESS || t == null) return@TextToSpeech
            runOnUiThread {
                val voices = VoiceTools.sorted(t)
                if (voices.isEmpty()) {
                    android.widget.Toast.makeText(this, "No English voices found. Install Google Text-to-speech voices in phone settings.", android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = (listOf("★ Auto: Siri-like voice (recommended)") +
                    voices.mapIndexed { i, v -> VoiceTools.star(i) + VoiceTools.label(v) }).toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Tap a voice to hear it")
                    .setItems(labels) { _, pos ->
                        if (pos == 0) {
                            prefs.edit().remove("tts_voice").apply()
                            VoiceTools.best(t)?.let { t.voice = it }
                            t.setSpeechRate(1.0f)
                            t.speak("Hi, I am Riya. This is my default voice.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "demo")
                            android.widget.Toast.makeText(this, "Auto voice saved. Restart Riya to use it.", android.widget.Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        val i = pos - 1
                        val v = voices[i]
                        prefs.edit().putString("tts_voice", v.name).apply()
                        t.voice = v
                        t.setSpeechRate(1.0f)
                        t.speak("Hi, I am Riya. How does this voice sound?", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "demo")
                        android.widget.Toast.makeText(this, "Saved. Restart Riya to use it.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    override fun onDestroy() { pickerTts?.shutdown(); super.onDestroy() }

    private fun bigButton(text: String, c1: Int, c2: Int, outlineOnly: Boolean, weight: Float, onClick: () -> Unit): TextView {
        val bg = if (outlineOnly) rounded(Color.parseColor("#14FFFFFF"), 16, c1)
        else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply { cornerRadius = dp(16).toFloat() }
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (outlineOnly) c1 else white)
            gravity = Gravity.CENTER
            background = ripple(bg)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(54), weight).apply { marginStart = dp(4); marginEnd = dp(4) }
        }
    }

    // ---------- permission / setup state ----------
    private fun granted(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(packageName)
    }

    private fun steps(): List<Step> = listOf(
        Step("Microphone, contacts, calls", "Hear you, call people, read out who is calling", {
            granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.READ_CONTACTS) &&
                granted(Manifest.permission.CALL_PHONE) && granted(Manifest.permission.READ_PHONE_STATE) &&
                granted(Manifest.permission.READ_CALL_LOG) && granted(Manifest.permission.ANSWER_PHONE_CALLS) &&
                (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        }) {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            requestPermissions(perms.toTypedArray(), 1)
        },
        Step("Lock screen", "Device admin, lets me lock your phone", {
            getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(this, AdminReceiver::class.java))
        }) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Needed so Riya can lock your screen.")
            })
        },
        Step("Display over other apps", "Lets me open apps and calls instantly", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        },
        Step("Run in background", "Keeps me listening with screen off", {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        },
        Step("Silence the listening beep", "Do Not Disturb access, lets me mute the ding", {
            getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
        }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        },
        Step("Phone controls (close all apps, WhatsApp send)", "Accessibility: Riya, On", { accessibilityOn() }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        Step("Instant caller name", "Choose Riya as call screening app: the name is spoken before the phone rings", { callScreeningOn() }) {
            requestCallScreening()
        }
    )

    private fun callScreeningOn(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return try { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING) } catch (e: Exception) { false }
    }

    private fun requestCallScreening() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 78)
            else Toast.makeText(this, "This phone does not offer call screening", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open the call screening request", Toast.LENGTH_LONG).show()
        }
    }

    private fun stepRow(index: Int, step: Step): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { step.action() }
        }
        val badge = TextView(this).apply {
            text = "$index"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            background = oval(purple)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            addView(label(step.title, 15f, white, true))
            addView(label(step.sub, 12f, soft))
        }
        val status = TextView(this).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        row.addView(badge)
        row.addView(col)
        row.addView(status)
        statusViews.add(status to step)
        return row
    }

    private fun refreshSetup() {
        var done = 0
        for ((tv, step) in statusViews) {
            val ok = try { step.done() } catch (e: Exception) { false }
            tv.text = if (ok) "✓ Done" else "Tap"
            tv.setTextColor(if (ok) teal else amber)
            if (ok) done++
        }
        if (::setupSummary.isInitialized) {
            val total = statusViews.size
            setupSummary.text = if (done == total) "All set, everything is ready" else "$done of $total steps done"
            setupFill.animate().scaleX(if (total == 0) 0f else done.toFloat() / total).setDuration(500).start()
            homeSetupChip.text = if (done == total) "✅  Setup complete" else "⚡  Finish setup: $done/$total  ›"
            homeSetupChip.setTextColor(if (done == total) teal else amber)
            refreshFeatures()
        }
    }

    // ---------- live assistant state (orb) ----------
    private fun applyState() {
        val st = RiyaService.state
        val mode = when (st) { "listening", "speaking", "thinking" -> st; else -> "idle" }
        bars.mode = mode
        if (::pulse.isInitialized) pulse.mode = mode
        val label = when {
            RiyaService.typing -> "Typing\u2026 say \u201cstop typing\u201d to finish"
            st == "listening" -> "Listening\u2026"
            st == "speaking" -> "Speaking\u2026"
            st == "thinking" -> "Thinking\u2026"
            else -> "Tap START to wake me up"
        }
        statusText.text = label
        if (::topSub.isInitialized) {
            topSub.text = when {
                st == "stopped" -> "not running"
                RiyaService.typing -> "typing for you"
                st == "listening" -> "listening"
                st == "speaking" -> "speaking"
                st == "thinking" -> "thinking"
                else -> "ready"
            }
            val dot = when (st) { "listening" -> teal; "speaking", "thinking" -> amber; "stopped" -> Color.parseColor("#6B6890"); else -> purple }
            (topDot.background as? GradientDrawable)?.setColor(dot)
            val q = NetQuality.level(this)
            netChip.text = when (q) {
                NetQuality.Level.GOOD -> "\uD83D\uDCF6 Online"
                NetQuality.Level.POOR -> "\uD83D\uDCF6 Weak \u00B7 fast mode"
                NetQuality.Level.NONE -> "\u2708 Offline mode"
            }
            netChip.setTextColor(when (q) { NetQuality.Level.GOOD -> teal; NetQuality.Level.POOR -> amber; NetQuality.Level.NONE -> red })
        }
        fun show(tv: TextView, text: String) {
            if (text.isBlank()) { tv.visibility = View.GONE; return }
            if (tv.text.toString() != text) tv.text = text
            if (tv.visibility != View.VISIBLE) {
                tv.visibility = View.VISIBLE
                tv.alpha = 0f
                tv.translationY = dp(12).toFloat()
                tv.animate().alpha(1f).translationY(0f).setDuration(220).start()
            }
        }
        show(heardText, RiyaService.lastHeard)
        show(replyText, RiyaService.lastReply)
        errorText.text = if (RiyaService.lastError.isNotEmpty()) RiyaService.lastError else if (RiyaService.lastHint.isNotEmpty()) RiyaService.lastHint else RiyaService.engineInfo
    }

    // ---------- start / stop ----------
    private fun startRiya() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Allow the microphone first (step 1)", Toast.LENGTH_LONG).show()
            return
        }
        val name = nameBox.text.toString().trim().ifEmpty { "Riya" }
        prefs.edit().putString("name", name).putString("owner", ownerBox.text.toString().trim())
            .putString("ai_key", keyBox.text.toString().trim())
            .putString("gemini_key", geminiBox.text.toString().trim()).apply()
        titleView.text = name
        val i = Intent(this, RiyaService::class.java)
        stopService(i)               // restart so the new name is used
        startForegroundService(i)
    }

    // =====================================================================================
    //  WhatsApp-style layout: top bar + 4 swipeable pages + bottom navigation
    //  Riya (live chat) | Features (stories + list) | Setup | Settings
    // =====================================================================================

    private class Feature(
        val emoji: String, val title: String, val sub: String,
        val c1: Int, val c2: Int, val tips: List<String>, val ready: (() -> Boolean)? = null
    )

    private val pages = ArrayList<View>()
    private val navCells = ArrayList<LinearLayout>()
    private val navIcons = ArrayList<TextView>()
    private val navLabels = ArrayList<TextView>()
    private var curTab = 0
    private lateinit var pageHost: FrameLayout
    private lateinit var topSub: TextView
    private lateinit var topDot: View
    private lateinit var netChip: TextView
    private lateinit var pulse: PulseView
    private lateinit var setupSummary: TextView
    private lateinit var setupFill: View
    private lateinit var homeSetupChip: TextView
    private lateinit var featuresScroll: ScrollView
    private lateinit var featuresList: LinearLayout
    private val featureRows = ArrayList<Triple<View, Feature, TextView>>()
    private val featureSearch = ArrayList<Pair<View, String>>()
    private val ringAnims = ArrayList<ObjectAnimator>()
    @Volatile private var blockSwipe = false

    private val teal2 = Color.parseColor("#1CA7EC")
    private val green = Color.parseColor("#34A853")
    private val blue = Color.parseColor("#4285F4")
    private val orange = Color.parseColor("#FF7A45")

    private fun styleSwitch(sw: android.widget.Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(teal, Color.parseColor("#B8B4D8")))
        sw.trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#662DE2C0"), Color.parseColor("#33FFFFFF")))
    }

    private fun toggle(text: String, key: String, def: Boolean, top: Int = 10, onChange: ((Boolean) -> Unit)? = null): android.widget.Switch =
        android.widget.Switch(this).apply {
            this.text = text
            setTextColor(white)
            textSize = 14f
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply(); onChange?.invoke(on) }
            layoutParams = params(top)
            styleSwitch(this)
        }

    private fun pageContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(24))
    }

    private fun pageScroll(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun sectionTitle(c: LinearLayout, title: String, sub: String) {
        c.addView(label(title, 24f, white, true), params(6))
        c.addView(label(sub, 13f, soft), params(2))
    }

    /** Press feedback: the card shrinks slightly under the finger. */
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    // ---------- top bar ----------
    private fun buildTopBar(name: String): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(14), dp(8))
        }
        val avatar = TextView(this).apply {
            text = "✦"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(white)
            background = gradOval(purple, pink)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        titleView = label(name, 24f, white, true)
        titleView.post {
            titleView.paint.shader = LinearGradient(0f, 0f, titleView.width.toFloat().coerceAtLeast(dp(60).toFloat()), 0f,
                intArrayOf(teal, purple, pink), null, Shader.TileMode.CLAMP)
            titleView.invalidate()
        }
        topDot = View(this).apply {
            background = oval(Color.parseColor("#6B6890"))
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        }
        topSub = label("", 12f, soft)
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(topDot)
            addView(topSub)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            addView(titleView)
            addView(subRow)
        }
        netChip = label("", 11f, teal, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(Color.parseColor("#1AFFFFFF"), 14, Color.parseColor("#22FFFFFF"))
        }
        bar.addView(avatar)
        bar.addView(col)
        bar.addView(netChip)
        return bar
    }

    // ---------- bottom navigation ----------
    private fun buildNav(): View {
        val wrap = FrameLayout(this).apply { setPadding(dp(12), dp(4), dp(12), dp(10)) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#26FFFFFF"), 30, Color.parseColor("#2AFFFFFF"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            elevation = dp(6).toFloat()
        }
        val tabs = listOf("🏠" to "Riya", "✨" to "Features", "🛠" to "Setup", "⚙️" to "Settings")
        tabs.forEachIndexed { i, (icon, title) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2); marginEnd = dp(2) }
                setOnClickListener { showTab(i) }
            }
            val ic = TextView(this).apply { text = icon; textSize = 20f; gravity = Gravity.CENTER }
            val lb = TextView(this).apply { text = title; textSize = 11f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }
            cell.addView(ic)
            cell.addView(lb)
            navCells.add(cell); navIcons.add(ic); navLabels.add(lb)
            bar.addView(cell)
        }
        wrap.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun updateNav() {
        navCells.forEachIndexed { i, cell ->
            val sel = i == curTab
            cell.background = if (sel) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#AA2DE2C0"), Color.parseColor("#AA8B6CFF")))
                .apply { cornerRadius = dp(24).toFloat() } else null
            navLabels[i].setTextColor(if (sel) white else soft)
            navIcons[i].animate().scaleX(if (sel) 1.18f else 1f).scaleY(if (sel) 1.18f else 1f)
                .setInterpolator(OvershootInterpolator(2.2f)).setDuration(280).start()
            cell.alpha = if (sel) 1f else 0.72f
        }
    }

    private fun staggerIn(page: View) {
        val content = (page as? ScrollView)?.getChildAt(0) as? ViewGroup ?: return
        for (k in 0 until minOf(content.childCount, 14)) {
            val v = content.getChildAt(k)
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp(26).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(k * 45L).setDuration(330)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun showTab(i: Int, fromUser: Boolean = true) {
        if (i == curTab || i !in pages.indices) return
        val dir = if (i > curTab) 1 else -1
        curTab = i
        for ((idx, p) in pages.withIndex()) if (idx != i) { p.animate().cancel(); p.visibility = View.GONE }
        val page = pages[i]
        page.animate().cancel()
        page.visibility = View.VISIBLE
        page.alpha = 0f
        page.translationX = dir * dp(60).toFloat()
        page.animate().alpha(1f).translationX(0f).setDuration(240).setStartDelay(0).setInterpolator(DecelerateInterpolator()).start()
        staggerIn(page)
        updateNav()
        if (fromUser) pageHost.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dp(90) && abs(dx) > abs(dy) * 1.6f && abs(velocityX) > 700f) {
                    if (dx < 0 && curTab < pages.size - 1) showTab(curTab + 1)
                    else if (dx > 0 && curTab > 0) showTab(curTab - 1)
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) blockSwipe = false
        val handled = super.dispatchTouchEvent(ev)
        if (!blockSwipe && pages.isNotEmpty()) swipe.onTouchEvent(ev)
        return handled
    }

    // ---------- page 1: Riya (live chat) ----------
    private fun bubble(isUser: Boolean): TextView {
        val r = dp(20).toFloat()
        val tail = dp(5).toFloat()
        val bg = if (isUser)
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(purple, pink)).apply { cornerRadii = floatArrayOf(r, r, r, r, tail, tail, r, r) }
        else
            GradientDrawable().apply { setColor(Color.parseColor("#22FFFFFF")); setStroke(dp(1), Color.parseColor("#33FFFFFF")); cornerRadii = floatArrayOf(r, r, r, r, r, r, tail, tail) }
        return TextView(this).apply {
            textSize = 16f
            setTextColor(if (isUser) white else Color.parseColor("#D9FFF6"))
            setLineSpacing(0f, 1.12f)
            background = bg
            setPadding(dp(16), dp(11), dp(16), dp(11))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (isUser) Gravity.END else Gravity.START
                topMargin = dp(10)
            }
        }
    }

    private fun buildHome(name: String): View {
        val c = pageContent()

        val hero = FrameLayout(this).apply {
            background = rounded(Color.parseColor("#12FFFFFF"), 30, Color.parseColor("#22FFFFFF"))
            clipToOutline = true
        }
        hero.addView(AuroraView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pulse = PulseView(this)
        hero.addView(pulse, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bars = GoogleBarsView(this)
        hero.addView(bars, FrameLayout.LayoutParams(dp(190), dp(84), Gravity.CENTER))
        c.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(6) })

        statusText = label("", 22f, white, true).apply { gravity = Gravity.CENTER }
        c.addView(statusText, params(14))

        heardText = bubble(true)
        replyText = bubble(false)
        c.addView(heardText)
        c.addView(replyText)
        errorText = label("", 12f, amber).apply { gravity = Gravity.CENTER }
        c.addView(errorText, params(10))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(bigButton("▶  START", teal, purple, false, 1.3f) { startRiya() })
        buttons.addView(bigButton("■  STOP", red, red, true, 1f) { stopService(Intent(this, RiyaService::class.java)) })
        c.addView(buttons, params(16))

        homeSetupChip = label("", 14f, amber, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = ripple(rounded(Color.parseColor("#14FFFFFF"), 18, Color.parseColor("#22FFFFFF")))
            isClickable = true
            setOnClickListener { showTab(2) }
            layoutParams = params(14)
        }
        pressable(homeSetupChip)
        c.addView(homeSetupChip)

        val say = card()
        say.addView(label("Try saying", 15f, white, true))
        listOf("“$name, call Vinod”", "“$name, play next song”", "“$name, why is the sky blue?”").forEach {
            say.addView(label(it, 14f, soft).apply { setPadding(0, dp(6), 0, 0) })
        }
        say.addView(label("See everything I can do in Features  ›", 13f, teal, true).apply {
            setPadding(0, dp(12), 0, 0)
            isClickable = true
            setOnClickListener { showTab(1) }
        })
        c.addView(say)
        return pageScroll(c)
    }

    // ---------- page 2: Features (like WhatsApp Status + chat list) ----------
    private fun features(name: String): List<Feature> = listOf(
        Feature("📞", "Instant caller name", "Says who is calling the moment the phone rings",
            teal, teal2, listOf(
                "I say the saved name first. If the number is saved in a different format, or only the call log knows the name, I use that likely name. If nobody knows it, I read the number digit by digit.",
                "Say “answer” or “reject” without touching the phone.",
                "Works with no internet. For the fastest start turn on Setup › Instant caller name."
            )) { callScreeningOn() && granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.READ_PHONE_STATE) },
        Feature("🧠", "Ask me anything", "Any question, any language, answers start in about a second",
            purple, pink, listOf(
                "“$name, why is the sky blue?”",
                "Speak Telugu, Hindi, English… I reply in the same language.",
                "Weather, news, scores and prices use live Google Search.",
                "Every answer comes from Google, spoken sentence by sentence."
            )) { prefs.getString("gemini_key", "").orEmpty().isNotBlank() },
        Feature("📶", "Weak-signal mode", "Stays fast and works properly even on a very slow connection",
            green, teal, listOf(
                "On a weak signal I hear you on the phone and speak with the on-phone voice, so only the Google answer waits for the network.",
                "When the signal is good again I switch back by myself. The chip at the top shows which mode is active.",
                "Answers always come from Google, so a good connection gives the fastest replies."
            ), null),
        Feature("📲", "Call people", "“$name, call Vinod”",
            blue, purple, listOf(
                "“$name, call Vinod”",
                "Several people with the same name: I read them numbered, you say the number.",
                "Names the speech engine mishears are matched to your contacts."
            )) { granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.CALL_PHONE) },
        Feature("💬", "WhatsApp", "Messages, voice calls and video calls",
            green, teal, listOf(
                "“$name, send WhatsApp to Amma saying hello”",
                "“$name, open WhatsApp Business Ramu call” / “… Ramu video call”",
                "“$name, open WhatsApp Ramu”, then speak your message and say send."
            )) { accessibilityOn() },
        Feature("⌨️", "Type by voice", "Speak, and I type at the cursor, anywhere",
            amber, orange, listOf(
                "Put the cursor in any text box and say “$name, start typing”.",
                "Say: send · new line · delete that · clear text · stop typing.",
                "One sentence only: “$name, type I will be late today”.",
                "Smart auto-correct fixes spelling online (Settings)."
            )) { accessibilityOn() },
        Feature("☎️", "During a call", "Completely silent. Only her name + a call command works",
            teal, purple, listOf(
                "While you talk on a call I never speak and never answer ordinary talk. I only react when you say my name first.",
                "“$name, cut the call”  ·  “$name, speaker on”  ·  “$name, video call” (turn on video)",
                "“$name, turn on Wi-Fi” / “turn off Bluetooth”: I press the switch in Quick Settings for you.",
                "You get a small buzz instead of a spoken answer: one buzz = done, two buzzes = could not.",
                "Needs Accessibility ON and the Phone permission. Video works only if your phone, SIM or app supports video calling."
            )) { accessibilityOn() && granted(Manifest.permission.ANSWER_PHONE_CALLS) },
        Feature("🎵", "Music control", "Play, pause, next and previous in any music app",
            pink, purple, listOf(
                "“$name, play Kesariya” · “start playing music” · “stop music”",
                "“$name, play next song” · “play previous song”",
                "“$name, play music on JioSaavn” (Spotify, PLAYit, YouTube Music…).",
                "While music plays only “stop music”, “next song” and “previous song” are listened for, so noise cannot trigger anything else."
            )),
        Feature("🎬", "Reels & Shorts", "Hands-free next, previous, pause and mute",
            red, pink, listOf(
                "Say “$name, next” once, then just “next”, “previous”, “pause”, “play”, “mute”.",
                "With Voice Match on, no name is needed at all."
            )) { accessibilityOn() },
        Feature("💸", "Pay by voice", "PhonePe, Paytm, Google Pay, BHIM",
            teal, green, listOf(
                "“$name, open PhonePe send Vinod 500”",
                "I confirm the name and amount, fill everything and stop at the UPI PIN. You type the PIN, I never see it."
            )) { accessibilityOn() },
        Feature("🚀", "Open any app", "“$name, open Instagram”",
            blue, teal2, listOf("“$name, open Instagram” · “open Settings” · “open PhonePe”", "Every app on the phone works.")),
        Feature("🔦", "Phone controls", "Flashlight, volume, lock, camera, screenshots",
            orange, amber, listOf(
                "“$name, flashlight on” · “volume 60” · “lock screen” · “open camera”",
                "“go home” · “go back” · “recent apps” · “take a screenshot” · “open notifications”",
                "“$name, close all apps”"
            )) { accessibilityOn() },
        Feature("⏰", "Alarms & timers", "“set alarm for seven thirty a m”",
            purple, blue, listOf("“$name, set alarm for seven thirty a m”", "“$name, set a timer for five minutes”")),
        Feature("🔒", "Voice Match", "Answers only to your voice",
            purple, pink, listOf(
                "Train once in Settings, like Google Voice Match.",
                "After that other people and crowd talk are ignored, even for “stop music”."
            )) { !prefs.getString("voice_print", "").isNullOrBlank() && prefs.getBoolean("voice_lock", false) },
        Feature("✋", "Say “stop” any time", "Cuts my reply instantly and I am ready again",
            red, orange, listOf("Say “stop” while I am talking and I go quiet at once.", "The next sentence works without my name for a few seconds.")),
        Feature("🎙", "Voice panel", "Google-style bars on the screen and the lock screen",
            blue, purple, listOf("Four coloured bars follow your voice with a live transcript.", "Switch it in Settings.")),
        Feature("😄", "Small talk", "Time, date, battery, jokes",
            amber, pink, listOf("“$name, what's the time?”", "“$name, tell me a joke”", "“$name, battery”"))
    )

    private fun featureRow(f: Feature): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(rounded(Color.parseColor("#14FFFFFF"), 22, Color.parseColor("#1FFFFFFF")))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = params(10)
            isClickable = true
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val av = TextView(this).apply {
            text = f.emoji
            textSize = 22f
            gravity = Gravity.CENTER
            background = gradOval(f.c1, f.c2)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12); marginEnd = dp(8) }
            addView(label(f.title, 16f, white, true))
            addView(label(f.sub, 13f, soft).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        val chip = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        head.addView(av); head.addView(col); head.addView(chip)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        f.tips.forEach { tip ->
            details.addView(label("•  $tip", 13.5f, Color.parseColor("#E6E2FF")).apply { setPadding(0, dp(5), 0, 0); setLineSpacing(0f, 1.1f) })
        }
        wrap.addView(head)
        wrap.addView(details)
        wrap.setOnClickListener {
            TransitionManager.beginDelayedTransition(featuresList)
            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        chip.setOnClickListener { if (f.ready != null && !(try { f.ready.invoke() } catch (e: Exception) { false })) showTab(2) else wrap.performClick() }
        pressable(wrap)
        featureRows.add(Triple(wrap, f, chip))
        featureSearch.add(wrap to (f.title + " " + f.sub + " " + f.tips.joinToString(" ")).lowercase())
        return wrap
    }

    private fun refreshFeatures() {
        for ((_, f, chip) in featureRows) {
            val r = f.ready
            if (r == null) {
                chip.text = "Auto"
                chip.setTextColor(purple)
                chip.background = rounded(Color.parseColor("#228B6CFF"), 12)
            } else {
                val ok = try { r() } catch (e: Exception) { false }
                chip.text = if (ok) "Ready" else "Set up ›"
                chip.setTextColor(if (ok) teal else amber)
                chip.background = rounded(if (ok) Color.parseColor("#222DE2C0") else Color.parseColor("#22FFC04D"), 12)
            }
        }
    }

    private fun storyView(f: Feature, onClick: () -> Unit): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val box = FrameLayout(this)
        val ring = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.SWEEP_GRADIENT
                setColors(intArrayOf(f.c1, f.c2, pink, f.c1))
            }
        }
        box.addView(ring, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
        box.addView(View(this).apply { background = oval(bgMid) }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        box.addView(TextView(this).apply { text = f.emoji; textSize = 26f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        ringAnims.add(ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
            duration = 5000L + ringAnims.size * 450L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        })
        cell.addView(box, LinearLayout.LayoutParams(dp(70), dp(70)))
        cell.addView(label(f.title, 11f, soft).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        })
        return cell
    }

    private fun buildFeatures(name: String): View {
        val c = pageContent()
        sectionTitle(c, "Everything $name can do", "Tap a feature to see how to use it")

        val search = input("Search features…", "").apply {
            background = rounded(Color.parseColor("#1AFFFFFF"), 26, Color.parseColor("#22FFFFFF"))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            layoutParams = params(14)
        }
        c.addView(search)

        val list = features(name)
        featuresList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) blockSwipe = true; false }
        }
        val stories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(4)) }
        hs.addView(stories)
        c.addView(hs, params())

        list.forEach { featuresList.addView(featureRow(it)) }
        list.forEachIndexed { i, f ->
            stories.addView(storyView(f) {
                val row = featureRows[i].first
                featuresScroll.post {
                    featuresScroll.smoothScrollTo(0, (featuresList.top + row.top - dp(8)).coerceAtLeast(0))
                    val details = (row as ViewGroup).getChildAt(1)
                    if (details.visibility != View.VISIBLE) row.performClick()
                }
            })
        }
        c.addView(featuresList)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().trim().lowercase()
                TransitionManager.beginDelayedTransition(featuresList)
                for ((v, hay) in featureSearch) v.visibility = if (q.isEmpty() || hay.contains(q)) View.VISIBLE else View.GONE
            }
        })
        featuresScroll = pageScroll(c)
        return featuresScroll
    }

    // ---------- page 3: Setup ----------
    private fun buildSetup(): View {
        val c = pageContent()
        sectionTitle(c, "Setup", "Tap each step once. Green means done.")

        val prog = card()
        setupSummary = label("", 16f, white, true)
        prog.addView(setupSummary)
        val track = FrameLayout(this).apply { background = rounded(Color.parseColor("#22FFFFFF"), 8) }
        setupFill = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(teal, purple)).apply { cornerRadius = dp(8).toFloat() }
            pivotX = 0f
            scaleX = 0f
        }
        track.addView(setupFill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        prog.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10) })
        c.addView(prog)

        val setup = card()
        statusViews.clear()
        steps().forEachIndexed { i, st -> setup.addView(stepRow(i + 1, st)) }
        c.addView(setup)

        return pageScroll(c)
    }

    // ---------- page 4: Settings ----------
    private fun buildSettings(name: String): View {
        val c = pageContent()
        sectionTitle(c, "Settings", "Make $name yours")

        val nameCard = card()
        nameCard.addView(label("Assistant name", 15f, white, true))
        nameCard.addView(label("Say this name before every command", 12f, soft))
        nameBox = input("e.g. Riya, Jarvis, Maya", name)
        nameCard.addView(nameBox)
        nameCard.addView(label("Your name (I call you this)", 12f, soft).apply { setPadding(0, dp(10), 0, 0) })
        ownerBox = input("e.g. Ram", prefs.getString("owner", "Ram") ?: "Ram")
        nameCard.addView(ownerBox)
        nameCard.addView(toggle("Say the name before commands", "wake_required", false, 12))
        nameCard.addView(label("Turn off to react to every sentence (noisy rooms may trigger it)", 12f, soft))
        nameCard.addView(toggle("Google-style voice panel (also on the lock screen)", "voice_ui", true, 12))
        c.addView(nameCard)

        val gemCard = card()
        gemCard.addView(label("Gemini AI (free, any language)", 17f, white, true))
        gemCard.addView(label("When the internet is on, Riya answers with Gemini in the language you speak. Every answer comes from Google, so she needs internet. Get a free key at aistudio.google.com/apikey and paste it here.", 12f, soft))
        geminiBox = input("Gemini API key", prefs.getString("gemini_key", "") ?: "", password = true)
        gemCard.addView(geminiBox)
        val lb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = params(10) }
        lb.addView(bigButton("🌐  LANGUAGES I SPEAK", teal, purple, false, 1f) { chooseLanguages() })
        gemCard.addView(lb)
        c.addView(gemCard)

        val aiCard = card()
        aiCard.addView(label("AI brain (optional)", 15f, white, true))
        aiCard.addView(label("Paste an Anthropic API key so I can chat and answer any question. Leave empty to use Gemini / offline chat.", 12f, soft))
        keyBox = input("API key", prefs.getString("ai_key", "") ?: "", password = true)
        aiCard.addView(keyBox)
        c.addView(aiCard)

        val typeCard = card()
        typeCard.addView(label("Type by voice", 17f, white, true))
        typeCard.addView(toggle("Smart auto-correct (online, uses your Gemini / AI key)", "smart_fix", true, 10))
        c.addView(typeCard)

        val vmCard = card()
        vmCard.addView(label("Voice Match (only my voice)", 17f, white, true))
        vmCard.addView(label("Train Riya with your voice, like Google Voice Match. After training she ignores other people. Needs the offline hearing, which is switched on automatically.", 12f, soft))
        val vmStatus = label("", 14f, teal).apply { setPadding(0, dp(10), 0, 0) }
        fun vmRefresh() {
            val has = !prefs.getString("voice_print", "").isNullOrBlank()
            vmStatus.text = if (has && prefs.getBoolean("voice_lock", false)) "✓ Voice locked: Riya answers only to you"
                else if (has) "Voice trained but lock is OFF" else "No voice trained yet"
            refreshFeatures()
        }
        vmRefresh()
        vmCard.addView(vmStatus)
        val vmSwitch = toggle("Only respond to my voice", "voice_lock", false, 10) {
            vmRefresh()
            Toast.makeText(this@MainActivity, "Tap START to apply", Toast.LENGTH_SHORT).show()
        }
        vmCard.addView(vmSwitch)
        vmCard.addView(label("Match strictness (if she says “not your voice”, choose Relaxed)", 12f, soft).apply { setPadding(0, dp(12), 0, 0) })
        val sensRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = params(6) }
        val sensNames = arrayOf("Strict", "Normal", "Relaxed")
        val sensViews = ArrayList<TextView>()
        fun sensRefresh() {
            val cur = prefs.getInt("voice_sens", 1)
            sensViews.forEachIndexed { i, v ->
                v.background = if (i == cur) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(teal, purple)).apply { cornerRadius = dp(14).toFloat() }
                    else rounded(Color.parseColor("#14FFFFFF"), 14, Color.parseColor("#33FFFFFF"))
                v.setTextColor(if (i == cur) white else soft)
            }
        }
        sensNames.forEachIndexed { i, n ->
            val v = TextView(this).apply {
                text = n; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
                setOnClickListener { prefs.edit().putInt("voice_sens", i).apply(); sensRefresh() }
            }
            sensViews.add(v); sensRow.addView(v)
        }
        sensRefresh()
        vmCard.addView(sensRow)
        val vmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = params(10) }
        vmRow.addView(bigButton("🎙  TRAIN MY VOICE", teal, purple, false, 1.5f) {
            prefs.edit().putBoolean("voice_enroll", true).apply()
            Toast.makeText(this, "Riya will start now. Say the 10 short sentences she asks, 3 words each.", Toast.LENGTH_LONG).show()
            startRiya()
        })
        vmRow.addView(bigButton("REMOVE", red, red, true, 1f) {
            prefs.edit().remove("voice_print").putBoolean("voice_lock", false).putBoolean("voice_enroll", false).apply()
            vmSwitch.isChecked = false
            vmRefresh()
        })
        vmCard.addView(vmRow)
        c.addView(vmCard)

        val unlockCard = card()
        unlockCard.addView(label("Phone unlock (PIN)", 17f, white, true))
        unlockCard.addView(label("When the phone is locked and a command needs it open (call, open an app, payment ...), Riya wakes the screen, types your PIN, then does the task. In a payment app she also types it if the app asks to be unlocked. She NEVER types your UPI PIN: that one is always yours. The PIN is stored encrypted on this phone only. Needs Accessibility ON and a number-PIN lock screen.", 12f, soft))
        val pinStatus = label("", 14f, teal).apply { setPadding(0, dp(10), 0, 0) }
        fun pinRefresh() { pinStatus.text = if (PinVault.has(this)) "✓ PIN saved (encrypted)" else "No PIN saved yet" }
        pinRefresh()
        unlockCard.addView(pinStatus)
        val pinBox = input("Phone lock PIN (numbers only)", "")
        pinBox.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        unlockCard.addView(pinBox)
        unlockCard.addView(toggle("Unlock automatically for voice commands", "auto_unlock", true, 10))
        unlockCard.addView(toggle("Only my voice can unlock (needs Voice Match)", "unlock_voice_only", true, 10))
        unlockCard.addView(toggle("Unlock for every command, even volume and flashlight", "unlock_all", true, 10))
        unlockCard.addView(toggle("Gemini Live voice chat when the phone is locked (say Riya, then talk)", "live_when_locked", true, 10))
        unlockCard.addView(label("Safety: with this ON, a stranger, a TV or a video saying the name cannot make Riya type your PIN. If you switch it OFF, anyone who says the name near the phone can unlock it.", 12f, soft))
        val pinRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = params(10) }
        pinRow.addView(bigButton("🔓  SAVE PIN", teal, purple, false, 1.5f) {
            val v = pinBox.text.toString().trim()
            if (v.length < 4 || !v.all { it.isDigit() }) Toast.makeText(this, "The PIN must be numbers only, at least 4 digits", Toast.LENGTH_LONG).show()
            else if (PinVault.save(this, v)) { pinBox.setText(""); pinRefresh(); Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(this, "Could not save the PIN on this phone", Toast.LENGTH_LONG).show()
        })
        pinRow.addView(bigButton("REMOVE", red, red, true, 1f) { PinVault.clear(this); pinRefresh() })
        unlockCard.addView(pinRow)
        c.addView(unlockCard)

        val payGuardCard = card()
        payGuardCard.addView(label("Payment app guard", 17f, white, true))
        payGuardCard.addView(label("Banks and payment apps block Riya on purpose: an app that can read the screen is exactly what banking fraud malware uses, so they refuse to run while any such app is switched on, including Riya. There is no safe way for Riya to hide from that check or to switch herself off and on \u2014 Android does not allow an app to flip its own Accessibility switch, so this cannot be automatic. What Riya CAN do: notice the moment a payment app opens, and show a notification that takes you straight to the Accessibility screen so turning Riya off, and back on afterwards, is one tap instead of a search through Settings.", 12f, soft))
        payGuardCard.addView(toggle("Notify me when a payment or banking app opens or closes", "pay_guard", true, 10))
        val payExtraBox = input("Add another app's package name (e.g. com.something.bank)", prefs.getString("pay_watch_extra", "") ?: "")
        payGuardCard.addView(payExtraBox)
        payGuardCard.addView(bigButton("➕  ADD TO WATCHED APPS", teal, purple, false, 1f) {
            val v = payExtraBox.text.toString().trim()
            if (v.isNotEmpty()) {
                val cur = prefs.getString("pay_watch_extra", "") ?: ""
                prefs.edit().putString("pay_watch_extra", if (cur.isEmpty()) v else "$cur,$v").apply()
                payExtraBox.setText("")
                Toast.makeText(this, "Added. Riya will watch for it too.", Toast.LENGTH_SHORT).show()
            }
        })
        payGuardCard.addView(label("Paytm, PhonePe, Google Pay, BHIM and the common bank apps are already watched by default. Find a package name under the app's page in the Play Store, in the link after \u201cid=\u201d.", 12f, soft))
        payGuardCard.addView(label("Quicker: swipe down twice from the top of the screen (Quick Settings), tap the pencil / edit icon, and drag the \u201cRiya\u201d tile up next to Wi-Fi and Flashlight. Then switching Riya off before a payment, and back on after, is a tap on that tile plus one real toggle, with no notification or search needed.", 12f, soft))
        c.addView(payGuardCard)

        val musicCard = card()
        musicCard.addView(label("Music by voice", 17f, white, true))
        musicCard.addView(label("Riya opens your music app (Spotify first) and controls it by voice. Turn on Voice Match so only YOUR voice can stop it in a crowd.", 12f, soft))
        musicCard.addView(toggle("Stay quiet while a video / movie / music plays (answer only after her name)", "quiet_media", true, 10))
        musicCard.addView(toggle("Hands-free guard while a video / reel / song plays (her name, next, previous, stop music, play next song)", "music_guard", true, 10))
        musicCard.addView(toggle("In Reels / Shorts, plain \u201cnext\u201d and \u201cprevious\u201d work without her name", "bare_media", true, 10))
        val mb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = params(10) }
        mb.addView(bigButton("🎵  CHOOSE MUSIC APP", teal, purple, false, 1f) { chooseMusicApp() })
        musicCard.addView(mb)
        c.addView(musicCard)

        val voiceCard = card()
        voiceCard.addView(label("Riya's voice", 17f, white, true))
        voiceCard.addView(label("Riya uses a Siri-like voice automatically (and the on-phone voice when the signal is weak). Pick a different one below (★ = closest to Siri). Restart Riya after changing.", 12f, soft))
        val vb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = params(10) }
        vb.addView(bigButton("🔊  CHOOSE VOICE", teal, purple, false, 1f) { chooseVoice() })
        voiceCard.addView(vb)
        c.addView(voiceCard)

        val ears = card()
        ears.addView(label("Hearing", 17f, white, true))
        ears.addView(label("Riya switches to on-phone hearing by herself when the signal is weak. You can also force it on.", 12f, soft))
        ears.addView(toggle("Offline hearing (always)", "offline_ears", false, 10))
        ears.addView(toggle("Hear from further away (boosts a quiet, distant voice automatically; turns on the phone's own automatic gain control too). Uses a little more battery and may react to background noise a bit more. Tap START again after changing.", "far_mic", true, 10))
        ears.addView(toggle("Use my earbuds microphone (Bluetooth music turns to call quality; wired earbuds are used automatically). Tap START again after changing.", "earbud_mic", false, 10))
        val indianSwitch = android.widget.Switch(this).apply {
            text = "Indian-English hearing model"
            setTextColor(white)
            textSize = 14f
            isChecked = prefs.getString("vosk_model", "en-in") == "en-in"
            setOnCheckedChangeListener { _, on -> prefs.edit().putString("vosk_model", if (on) "en-in" else "en-us").apply() }
            layoutParams = params(8)
            styleSwitch(this)
        }
        ears.addView(indianSwitch)
        c.addView(ears)
        return pageScroll(c)
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("riya", Context.MODE_PRIVATE)
        if (!prefs.contains("voice_v3")) prefs.edit().remove("tts_voice").putBoolean("voice_v3", true).apply()   // switch everyone to the Siri-like default once
        if (!prefs.contains("ears_v17")) prefs.edit().putBoolean("offline_ears", false).putBoolean("ears_v17", true).apply()
        window.statusBarColor = bgTop
        window.navigationBarColor = bgBottom
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val name = prefs.getString("name", "Riya") ?: "Riya"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgMid, bgBottom))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insetTop(insets), 0, insetBottom(insets))
            insets
        }
        root.addView(buildTopBar(name), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pageHost = FrameLayout(this)
        pages.clear()
        pages.add(buildHome(name))
        pages.add(buildFeatures(name))
        pages.add(buildSetup())
        pages.add(buildSettings(name))
        pages.forEachIndexed { i, p ->
            pageHost.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            p.visibility = if (i == 0) View.VISIBLE else View.GONE
        }
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildNav(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        root.requestApplyInsets()
        updateNav()
        staggerIn(pages[0])
    }

    @Suppress("DEPRECATION")
    private fun insetTop(i: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) i.getInsets(WindowInsets.Type.systemBars()).top else i.systemWindowInsetTop

    @Suppress("DEPRECATION")
    private fun insetBottom(i: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) maxOf(i.getInsets(WindowInsets.Type.systemBars()).bottom, i.getInsets(WindowInsets.Type.ime()).bottom)
        else i.systemWindowInsetBottom

    override fun onResume() {
        super.onResume()
        refreshSetup()
        applyState()
        ringAnims.forEach { try { if (!it.isStarted) it.start() } catch (e: Exception) { } }
        RiyaService.listener = { runOnUiThread { applyState() } }
    }

    override fun onPause() {
        RiyaService.listener = null
        ringAnims.forEach { try { it.cancel() } catch (e: Exception) { } }
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
    }
}

/** Soft rings that expand from the centre: slow when idle, faster and louder-reacting while listening. */
class PulseView(ctx: Context) : View(ctx) {
    @Volatile var mode = "idle"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private var t = 0f
    private var running = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val speed = when (mode) {
                "listening" -> 0.008f + RiyaService.level * 0.02f
                "speaking" -> 0.011f
                "thinking" -> 0.016f
                else -> 0.003f
            }
            t = (t + speed) % 1f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postOnAnimation(frame) }

    override fun onDetachedFromWindow() { running = false; removeCallbacks(frame); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        if (maxR <= 0f) return
        val strength = if (mode == "idle") 40 else 120
        for (i in 0..2) {
            val p = (t + i / 3f) % 1f
            val r = maxR * (0.35f + 0.65f * p)
            val a = ((1f - p) * strength).toInt()
            paint.color = (if (i % 2 == 0) 0x2DE2C0 else 0x8B6CFF) or (a shl 24)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}
