package com.rama.riya

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.AlarmClock
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.view.KeyEvent
import android.os.Vibrator
import android.os.BatteryManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import android.provider.Settings
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiyaService : Service() {

    data class Contact(val name: String, val number: String)

    companion object {
        // Shown live on the main screen: stopped | listening | thinking | speaking
        @Volatile var state = "stopped"
        @Volatile var lastHeard = ""
        @Volatile var lastReply = ""
        @Volatile var lastError = ""
        @Volatile var lastHint = ""
        @Volatile var engineInfo = ""
        @Volatile var listener: (() -> Unit)? = null
        @Volatile var level = 0f          // microphone loudness 0..1 for the animated bars
        @Volatile var typing = false      // type-by-voice mode is on
        @Volatile var instance: RiyaService? = null   // lets the call-screening service hand over the caller instantly
    }

    private fun setState(st: String) {
        state = st
        notifyUi()
        handler.removeCallbacks(thinkGuard)
        if (st == "thinking") handler.postDelayed(thinkGuard, 30000L)
        if ((st == "thinking" || st == "speaking") && !typing) showVoiceUi()
    }

    // ---------- Google-style voice panel (overlay on an unlocked phone, full sheet on the lock screen) ----------
    private var voiceOverlay: VoiceOverlay? = null
    private val thinkGuard = Runnable {
        if (thinking && !speaking) {
            aiSeq++
            thinking = false
            setState("listening")
            say("Sorry, that took too long. Please ask me again.", true)
        }
    }
    private val vib: Vibrator? by lazy { try { getSystemService(Vibrator::class.java) } catch (e: Exception) { null } }

    @Volatile private var wakeTickAt = 0L
    @Volatile private var lockLaunchAt = 0L

    private fun tick(ms: Long = 30) {
        try { vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (e: Exception) { }
    }

    private fun showVoiceUi() {
        if (!running || unlocking || !prefs().getBoolean("voice_ui", true)) return
        if (isPhoneLocked()) launchLockVoice() else voiceOverlay?.show(false)
    }

    private fun launchLockVoice() {
        if (LockVoiceActivity.alive) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lockLaunchAt < 1500L) return
        lockLaunchAt = nowMs
        val i = Intent(this, LockVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try { startActivity(i) } catch (e: Exception) { }
        handler.postDelayed({
            if (running && !LockVoiceActivity.alive && isPhoneLocked()) {
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(NotificationChannel("riya_voice", "Riya voice screen", NotificationManager.IMPORTANCE_HIGH))
                    val pi = android.app.PendingIntent.getActivity(this, 7, i,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    val n = Notification.Builder(this, "riya_voice")
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle(assistantName)
                        .setCategory(Notification.CATEGORY_CALL).setFullScreenIntent(pi, true).setAutoCancel(true).build()
                    nm.notify(77, n)
                    handler.postDelayed({ try { nm.cancel(77) } catch (e: Exception) { } }, 3000L)
                } catch (e: Exception) { }
            }
        }, 450L)
    }

    private fun notifyUi() { handler.post { listener?.invoke() } }
    private fun prefs() = getSharedPreferences("riya", Context.MODE_PRIVATE)

    // Used when a contact number has no country code (change if you are not in India)
    private val defaultCountryCode = "91"

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchOn = false
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    // When Riya asks "whom?", the next sentence is the answer (no wake word needed)
    private var pendingContacts: List<Contact>? = null
    private var pendingAction: ((Contact) -> Unit)? = null
    private val clearPending = Runnable { pendingContacts = null; pendingAction = null; pendingMisses = 0 }
    private var pendingMisses = 0

    // Speech bookkeeping: only the latest utterance may end "speaking", plus a watchdog
    // so a lost TTS callback can never leave Riya deaf.
    private var utteranceCounter = 0
    private var currentUtterance: String? = null
    private val speakWatchdog = Runnable { endGuard(); speaking = false; setState("listening"); resumeAfterSpeech() }

    // ---------- Stop while speaking / language ----------
    private var stopGuard: StopGuard? = null
    private var guardOn = false
    @Volatile private var aiSeq = 0            // bumps when the user says stop, so a late online answer is dropped
    private var curTtsLang = "en"

    @Volatile private var lastChunkAt = 0L
    private var sessionChars = 0
    // a whole short utterance that is just "stop" (optionally with her name / "please"): the only thing that may cut her off
    private val stopPhraseRe = Regex("^(?:\\w+[ ,]+)?(stop|enough|cancel|quiet|silence)(?: it| talking| speaking| please| now)?(?:[ ,]+\\w+)?[ .!]*$")
    private fun isStopPhrase(list: List<String>) = list.any { stopPhraseRe.matches(it.lowercase().trim()) || nameOnly(it) }
    /** The whole sentence is just her name: she is being called. */
    private fun nameOnly(t: String): Boolean {
        val s = t.lowercase().trim()
        val m = wakeRegex.find(s) ?: return false
        return s.removeRange(m.range).trim(' ', '.', ',', '!', '?').isEmpty()
    }
    /** She is in the middle of an answer (speaking, or between the sentences of a streamed answer). */
    private fun answeringNow() = speaking || (thinking && System.currentTimeMillis() - lastChunkAt < 15000L)

    private fun beginGuard() {
        if (guardOn) return
        guardOn = true
        val e = ears
        if (useVosk && e != null) e.guardStart() else stopGuard?.begin()
    }

    private fun endGuard() {
        if (!guardOn) return
        guardOn = false
        ears?.guardEnd()
        stopGuard?.end()
    }

    /** The user said "stop": be quiet right now and be ready for the next task. */
    private fun interruptSpeech() {
        aiSeq++
        payAbort = true
        if (!speaking && !thinking) return
        endGuard()
        handler.removeCallbacks(speakWatchdog)
        currentUtterance = ""
        try { tts?.stop() } catch (e: Exception) { }
        thinking = false
        speaking = false
        afterSpeech = null
        pendingFreeText = null
        conversationUntil = System.currentTimeMillis() + 8000   // next sentence works even without my name
        setState("listening")
        notifyUi()
        startListening()
    }

    /** Switch the voice to the language of the answer. False = that voice is not installed on the phone. */
    private fun setSpeakLang(tag: String): Boolean {
        val t = tts ?: return true
        val loc = Locale.forLanguageTag(tag.ifBlank { "en-IN" })
        val key = loc.language
        if (key == curTtsLang) return true
        var r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) r = t.setLanguage(Locale(key))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale("en", "IN"))
            applyVoice()
            curTtsLang = "en"
            return false
        }
        curTtsLang = key
        if (key == "en") applyVoice()
        return true
    }

    private fun allowedLangs(): List<String> {
        val extra = (prefs().getString("langs", "te-IN,hi-IN") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return (listOf("en-IN") + extra).distinct()
    }

    /** Next time, listen in the language the user just used. */
    private fun noteLanguage(tag: String) {
        val lang = Locale.forLanguageTag(tag).language
        val match = allowedLangs().firstOrNull { Locale.forLanguageTag(it).language == lang } ?: return
        if (match != recLang) {
            recLang = match
            recognizerIntent = buildRecognizerIntent()
            resetRecognizer()
        }
    }

    private fun hasNonLatin(s: String) =
        s.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }

    private fun geminiKey() = prefs().getString("gemini_key", "").orEmpty().trim()
    private fun geminiReady() = geminiKey().isNotEmpty() && isOnline()

    private var overlayView: View? = null

    private var afterSpeech: (() -> Unit)? = null    // runs when the current sentence finishes

    private var useVosk = false
    private var ears: VoskEars? = null

    private var listening = false
    private var thinking = false
    private var conversationUntil = 0L          // after Riya talks, you can answer without the wake word
    private var currentFollowUp = false
    private var pendingFreeText: ((String) -> Unit)? = null
    private var freeUntil = 0L
    private val io = Executors.newSingleThreadExecutor()
    private val aiHistory = ArrayList<Pair<String, String>>()
    private var appCache: List<AppEntry> = emptyList()
    private var appCacheAt = 0L

    private lateinit var wakeRegex: Regex
    private var assistantName = "riya"

    private fun loadWakeName() {
        assistantName = getSharedPreferences("riya", Context.MODE_PRIVATE)
            .getString("name", "riya")!!.trim().lowercase().ifEmpty { "riya" }
        val variants = when (assistantName) {
            "riya" -> listOf("riya", "ria", "reya", "rhea", "riyah", "riyaa", "reeya", "rea", "reah", "riyan", "raya", "rya", "riyo", "reiya", "riaa")
            "janu" -> listOf("janu", "jaanu", "januu", "jhanu", "janoo", "zanu")
            "ram" -> listOf("ram", "raam", "rum", "rom", "ramm", "rahm")
            "maya" -> listOf("maya", "maia", "mya", "mia", "myah", "mayah", "maiya", "miya", "maaya", "mayaa")
            else -> listOf(assistantName)
        }
        StopWords.names = variants.take(4)
        MediaWords.names = (variants.take(6) + (if (assistantName == "riya") listOf("area", "aria", "arya") else emptyList())).distinct()
        wakeRegex = Regex("\\b(" + variants.joinToString("|") { Regex.escape(it) } + ")\\b")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadWakeName()
        val channelId = "riya_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Riya", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("$assistantName is listening")
            .setContentText("Say \"$assistantName\" followed by a command")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        // Keep the CPU awake so Riya keeps listening when the screen is off / locked
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "riya:listen").apply { acquire() }

        addOverlay()
        voiceOverlay = VoiceOverlay(this)
        muteBeeps()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lr = tts?.setLanguage(Locale("en", "IN"))
                if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.US)
                applyVoice()
                tts?.setSpeechRate(1.1f)    // calm, clear, Siri-like pace
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { startedId = utteranceId }
                    override fun onError(utteranceId: String?) = finishSpeaking(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finishSpeaking(utteranceId)
                    override fun onDone(utteranceId: String?) = finishSpeaking(utteranceId)
                })
                ttsReady = true
                stopGuard = StopGuard(this, prefs().getString("vosk_model", "en-in") ?: "en-in") { handler.post { interruptSpeech() } }
                say("Hi, I am $assistantName. I am listening.")
            }
        }
        if (prefs().getBoolean("offline_ears", false).not() && !SpeechRecognizer.isRecognitionAvailable(this)) {
            lastError = "No speech recognition on this phone. Install or update the Google app."
            notifyUi()
        }
        io.execute { installedApps() }   // warm the app list so "open ..." is instant

        instance = this
        ContactCache.start(this)
        registerCallWatcher()
        registerNetWatcher()
        registerUnlockWatcher()
        registerScreenWatcher()
        handler.postDelayed(mediaWatch, 3000L)
        handler.postDelayed(earsHeartbeat, 6000L)
        if (!prefs().contains("ears_v17")) prefs().edit().putBoolean("offline_ears", false).putBoolean("ears_v17", true).apply()
        VoskModels.preload(this, prefs().getString("vosk_model", "en-in") ?: "en-in", voiceLockOn() || prefs().getBoolean("voice_enroll", false))
        useVosk = wantVosk() || prefs().getBoolean("voice_enroll", false)
        engineInfo = if (useVosk) "Hearing: offline (Vosk " + (prefs().getString("vosk_model", "en-in") ?: "en-in") + ")" else "Hearing: Google speech"
        if (useVosk) startOfflineEars() else startListening()
    }

    // ---------- Weak signal: switch hearing between Google (good signal) and on-phone Vosk (weak / none) ----------
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastNetPoor: Boolean? = null
    private val netSwitch = Runnable { reevaluateNet() }

    private fun registerNetWatcher() {
        lastNetPoor = NetQuality.level(this) != NetQuality.Level.GOOD
        netCb = NetQuality.watch(this) {
            handler.post { handler.removeCallbacks(netSwitch); handler.postDelayed(netSwitch, 2500L) }
        }
        Thread { NetQuality.prewarm() }.start()
    }

    private fun reevaluateNet() {
        if (!running) return
        val poor = NetQuality.level(this) != NetQuality.Level.GOOD
        if (poor == lastNetPoor) return
        if (switchedForScreen || switchedForMedia || speaking || thinking || enrolling || typing || inCall) {
            handler.postDelayed(netSwitch, 3000L)       // busy right now: try again in a moment
            return
        }
        lastNetPoor = poor
        if (!poor) Thread { NetQuality.prewarm() }.start()
        val want = wantVosk()
        if (want == useVosk) return
        handler.removeCallbacks(listenWatchdog)
        try { recognizer?.cancel() } catch (e: Exception) { }
        resetRecognizer()
        ears?.stop()
        ears = null
        useVosk = want
        engineInfo = if (useVosk) "Hearing: on-phone (weak signal, no waiting)" else "Hearing: Google speech"
        notifyUi()
        if (useVosk) startOfflineEars() else startListening()
    }

    // ---------- Incoming calls: read out who is calling (works offline) ----------

    private var callReceiver: BroadcastReceiver? = null
    @Volatile private var inCall = false
    private var ringing = false
    private var ringingNumber: String? = null
    private var ringRepeats = 0
    private var announced = false
    private var announcedHadNumber = false
    private val ringTimeout = Runnable { endRing() }

    private fun registerCallWatcher() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val state = i?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                    @Suppress("DEPRECATION")
                    val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    handler.post { onCallState(state, num) }
                }
            }
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            callReceiver = r
        } catch (e: Exception) { /* call announcing is optional */ }
    }

    /** The call-screening service delivers the number before the phone even rings: this is the fastest path. */
    fun onScreenedNumber(number: String?) {
        handler.post { startRing(number) }
    }

    private fun endRing() {
        ringing = false
        announced = false
        announcedHadNumber = false
        ringingNumber = null
        handler.removeCallbacks(announceRunnable)
        handler.removeCallbacks(ringTimeout)
    }

    private fun startRing(number: String?) {
        if (!running || inCall) return
        if (!number.isNullOrBlank()) ringingNumber = number
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, 60000L)
        if (!ringing) {
            ringing = true
            ringRepeats = 0
            announced = false
            announcedHadNumber = false
        }
        val have = !ringingNumber.isNullOrBlank()
        if (!announced) {
            handler.removeCallbacks(announceRunnable)
            // number known: speak NOW. Number not known yet: give the second broadcast a very short moment.
            if (have) announceCaller() else handler.postDelayed(announceRunnable, 250L)
        } else if (!announcedHadNumber && have) {
            ringRepeats = 0
            announceCaller()          // first said "unknown number", now we know who it is
        }
    }

    private fun onCallState(state: String, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> startRing(number)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                endRing()
                try { tts?.stop() } catch (e: Exception) { }      // never talk over a connected call
                speaking = false
                enterCallMode(false)          // silent: only "<name> cut the call" etc. is heard
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                endRing()
                exitCallMode()
            }
        }
    }

    // ---------- During a call: Riya is completely silent and only reacts to "<name> + a call command" ----------
    @Volatile private var voipCall = false
    private var switchedForCall = false

    private fun enterCallMode(voip: Boolean) {
        if (voip) voipCall = true
        if (inCall) return
        inCall = true
        aiSeq++
        thinking = false
        pendingFreeText = null
        conversationUntil = 0L
        handler.removeCallbacks(listenWatchdog)
        // Google speech would take the microphone away from the call, so only the on-phone ears may run now
        try { recognizer?.cancel() } catch (e: Exception) { }
        resetRecognizer()
        voiceOverlay?.hide()
        if (!useVosk) {
            switchedForCall = true
            useVosk = true
            engineInfo = "Hearing: on-phone (in a call, silent)"
            ears?.stop()
            ears = null
            startOfflineEars()
        } else {
            ears?.resume()
        }
        setState("listening")
    }

    private fun exitCallMode() {
        if (!inCall) return
        inCall = false
        voipCall = false
        if (switchedForCall) {
            switchedForCall = false
            ears?.stop()
            ears = null
            useVosk = wantVosk() || switchedForScreen
            handler.postDelayed({
                if (!running) return@postDelayed
                if (useVosk) startOfflineEars() else { engineInfo = "Hearing: Google speech"; startListening() }
            }, 800L)
        } else handler.postDelayed({ startListening() }, 800L)
    }

    /** "riya cut the call" -> "cut the call". Null = the name was not said, so in a call Riya stays silent and does nothing. */
    private fun nameCommand(heard: List<String>): String? {
        for (text in heard) {
            val m = wakeRegex.find(text) ?: continue
            val after = text.substring(m.range.last + 1).trim()
            val before = text.substring(0, m.range.first).trim()
            return if (after.isNotEmpty()) after else before
        }
        for (text in heard) {
            val w = text.trim().split(" ").filter { it.isNotEmpty() }
            if (w.size >= 2 && isCallWord(w[0])) return w.drop(1).joinToString(" ")
            if (w.size == 1 && isCallWord(w[0])) return ""
        }
        // Speech engines write "Maya" as "my a", "mia", "mai" ...: accept such a word ONLY when the rest is a plain media command
        for (text in heard) {
            val w = text.trim(' ', '.', ',').split(" ").filter { it.isNotEmpty() }
            if (w.size < 2 || w.size > 4) continue
            val rests = ArrayList<String>()
            if (soundsLikeName(w[0])) rests.add(w.drop(1).joinToString(" "))
            if (w.size >= 3 && soundsLikeName(w[0] + w[1])) rests.add(w.drop(2).joinToString(" "))
            if (soundsLikeName(w.last())) rests.add(w.dropLast(1).joinToString(" "))
            for (r in rests) if (mediaRe.matches(r.trim())) return r.trim()
        }
        return null
    }

    /** Vowels and h dropped, y = i: "maya", "maia", "mia", "mya" all become "ma". */
    private fun looseKey(s: String): String {
        val sb = StringBuilder()
        for (c0 in s.lowercase()) {
            if (!c0.isLetter() || c0 == 'h') continue
            val c = if (c0 == 'y') 'i' else c0
            val out = if (c in "aeiou") 'a' else c
            if (sb.isNotEmpty() && sb.last() == out) continue
            sb.append(out)
        }
        return sb.toString()
    }

    private fun soundsLikeName(w: String): Boolean {
        if (isCallWord(w)) return true
        if (assistantName == "maya" && w in setOf("my", "mai", "ma", "maa")) return true
        val k = looseKey(w)
        return w.length >= 3 && k.length >= 2 && k == looseKey(assistantName)
    }

    /** Small buzz instead of a spoken answer (nothing is said into the call): one buzz = done, two = could not. */
    private fun callFeedback(ok: Boolean, msg: String) {
        if (ok) { lastHint = ""; lastReply = msg; tick(35) }
        else { lastHint = msg; tick(70); handler.postDelayed({ tick(70) }, 160L) }
        notifyUi()
    }

    private fun handleCallSpeech(heard: List<String>) {
        val raw = nameCommand(heard) ?: return
        val c = raw.lowercase().replace(Regex("^(please|can you|could you|just|now|hey|ok|okay)\\b[ ,]*"), "").trim(' ', '.', ',')
        lastHeard = heard[0]
        notifyUi()
        when {
            c.isEmpty() -> tick(25)
            Regex("^(cut|end|hang ?up|disconnect|drop|finish|reject)( the| this| my)?( call| phone)?$").containsMatchIn(c) -> endCallNow()
            Regex("\\b(speaker|speaker ?phone|loud ?speaker)\\b").containsMatchIn(c) ->
                callSpeaker(!Regex("\\b(off|normal|earpiece|stop|disable|remove)\\b").containsMatchIn(c))
            Regex("\\b(video|camera)\\b").containsMatchIn(c) && !Regex("\\b(off|stop|end)\\b").containsMatchIn(c) -> callVideo()
            radioParse(c) != null -> radioParse(c)!!.let { setRadio(it.first, it.second) }
            else -> { lastHint = "In a call I only do: cut the call, speaker, video, Wi-Fi on/off, Bluetooth on/off."; notifyUi() }
        }
    }

    /** Brings the phone's in-call screen to the front so its buttons can be pressed. Call from a background thread. */
    private fun showInCallUi() {
        try { getSystemService(TelecomManager::class.java).showInCallScreen(false) } catch (e: Exception) { }
        try { Thread.sleep(700) } catch (e: InterruptedException) { }
    }

    private fun endCallNow() {
        typeExec.execute {
            var ok = false
            if (!voipCall) {
                try {
                    if (Build.VERSION.SDK_INT >= 28 && checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED)
                        ok = getSystemService(TelecomManager::class.java).endCall()
                } catch (e: Exception) { }
            }
            if (!ok) {     // WhatsApp / Meet calls (or no phone permission): press the red button on screen
                val acc = RiyaAccessibility.instance
                if (acc != null) {
                    if (!voipCall) showInCallUi()
                    ok = acc.pressInCallButton(listOf("end call", "hang up", "end", "leave", "disconnect", "decline"), null, true) != 0
                }
            }
            handler.post { callFeedback(ok, "I could not end the call. Please allow phone permission in the Riya app, or tap the red button.") }
        }
    }

    private fun callSpeaker(on: Boolean) {
        typeExec.execute {
            val am = getSystemService(AudioManager::class.java)
            try { am.isSpeakerphoneOn = on } catch (e: Exception) { }
            try { Thread.sleep(500) } catch (e: InterruptedException) { }
            var ok = am.isSpeakerphoneOn == on
            if (!ok) {     // this phone ignored it: press the Speaker button on the call screen
                val acc = RiyaAccessibility.instance
                if (acc != null) { showInCallUi(); ok = acc.pressInCallButton(listOf("speaker"), on) != 0 }
            }
            handler.post { callFeedback(ok, "I could not switch the speaker. Please tap it once.") }
        }
    }

    private fun callVideo() {
        val acc = RiyaAccessibility.instance
        if (acc == null) { callFeedback(false, "Turn on Riya in Accessibility settings to use video."); return }
        typeExec.execute {
            showInCallUi()
            val r = acc.pressInCallButton(listOf("switch to video call", "start video call", "video call", "turn on video",
                "start video", "turn on camera", "switch to video", "video", "camera"), null)
            if (r != 0) {
                try { Thread.sleep(1300) } catch (e: InterruptedException) { }
                acc.clickLabel(listOf("switch", "request", "ask"), true)      // "switch to video?" confirmation, if the phone asks
            }
            handler.post { callFeedback(r != 0, "No video button on this call. Your phone, SIM or network may not support video calling.") }
        }
    }

    // ---------- Wi-Fi / Bluetooth by voice (through the Quick Settings panel) ----------
    private val radioRe = Regex("\\b(wi ?fi|wi-fi|bluetooth|blue tooth)\\b")

    /** "turn on wifi" -> ("wifi", true), "bluetooth off" -> ("bluetooth", false). Null if it is not an on/off request. */
    private fun radioParse(c: String): Pair<String, Boolean>? {
        if ("setting" in c || c.startsWith("play")) return null
        val m = radioRe.find(c) ?: return null
        val off = Regex("\\b(off|disable|disconnect|stop)\\b").containsMatchIn(c)
        val on = Regex("\\b(on|enable|start|connect)\\b").containsMatchIn(c)
        if (!on && !off) return null
        return (if (m.value.startsWith("blue")) "bluetooth" else "wifi") to !off
    }

    private fun setRadio(kind: String, on: Boolean) {
        val label = if (kind == "bluetooth") "Bluetooth" else "Wi-Fi"
        val state = if (on) "on" else "off"
        fun report(ok: Boolean, msg: String) { if (inCall) callFeedback(ok, msg) else say(msg, true) }
        val acc = RiyaAccessibility.instance
        if (acc == null) { report(false, "Please turn on Riya in Accessibility settings first, so I can switch $label $state."); return }
        if (isPhoneLocked()) { report(false, "Please unlock your phone to switch $label $state."); return }
        typeExec.execute {
            val r = try { acc.toggleRadio(kind, on) } catch (e: Exception) { 0 }
            handler.post {
                when (r) {
                    0 -> report(false, "I could not switch $label $state. Please tap it in quick settings.")
                    2 -> report(true, "$label is already $state.")
                    else -> report(true, "$label $state.")
                }
            }
        }
    }

    /** Video / music is playing and quiet mode is on: Riya answers only when her name is said. */
    private fun quietMedia() = prefs().getBoolean("quiet_media", true) && isMediaPlaying()

    private val announceRunnable = Runnable { announceCaller() }

    private fun callText(name: String?, n: String?): String = when {
        name != null -> "$name is calling"
        !n.isNullOrBlank() -> spellNumber(n) + " is calling"
        else -> "Unknown number is calling"
    }

    private fun announceCaller() {
        if (!ringing || !running) return
        handler.removeCallbacks(announceRunnable)
        announced = true
        val n = ringingNumber
        val hasNum = !n.isNullOrBlank()
        announcedHadNumber = hasNum
        val quick = if (hasNum) ContactCache.quickName(n!!) else null
        if (quick != null || !hasNum) {
            sayCall(callText(quick, n))           // memory lookup: speaks with no waiting at all
            scheduleRepeat()
        } else {
            val num = n!!
            // not in the memory list: ask the contacts / call log, on its own thread so nothing queues behind it
            Thread {
                val name = ContactCache.slowName(this, num)
                handler.post { if (ringing && running) { sayCall(callText(name, num)); scheduleRepeat() } }
            }.start()
        }
    }

    private fun scheduleRepeat() {
        ringRepeats++
        if (ringRepeats < 3) handler.postDelayed(announceRunnable, 8000L)
    }

    /** Lean speech path for calls: voice starts first, microphone housekeeping happens after. */
    private fun sayCall(text: String) {
        lastReply = text
        val t = tts
        if (!ttsReady || t == null) { notifyUi(); return }
        val id = "riya_" + (++utteranceCounter)
        currentUtterance = id
        currentFollowUp = true          // "answer" / "reject" work without the name right after
        handler.removeCallbacks(speakWatchdog)
        val lang = langTagOf(text)
        if (lang != "en-IN") setSpeakLang(lang) else { setSpeakLang("en-IN"); forceOfflineVoice() }   // on-phone voice: starts instantly, even with no signal
        speaking = true
        setState("speaking")
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            setState("listening")
            restart()
            return
        }
        handler.postDelayed(speakWatchdog, 3000L + text.length * 90L)
        ears?.pause()
        recognizer?.cancel()
        listening = false
        handler.removeCallbacks(listenWatchdog)
    }

    private fun lookupContactName(number: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    /** "+91 98765 43210" -> "9 8 7 6 5, 4 3 2 1 0" so the voice reads digit by digit. */
    private fun spellNumber(n: String): String {
        val digits = n.filter { it.isDigit() }
        val national = if (digits.length > 10) digits.takeLast(10) else digits
        return national.chunked(5).joinToString(", ") { it.toCharArray().joinToString(" ") }
    }

    private fun answerCall() {
        if (!ringing) { say("No call is ringing right now.", true); return }
        try {
            if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                getSystemService(TelecomManager::class.java).acceptRingingCall()
                return
            }
        } catch (e: Exception) { }
        say("Please allow phone permission in the Riya app so I can answer calls.", true)
    }

    private fun rejectCall() {
        if (!ringing && !inCall) { say("There is no call right now.", true); return }
        try {
            if (Build.VERSION.SDK_INT >= 28 &&
                checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                getSystemService(TelecomManager::class.java).endCall()
                return
            }
        } catch (e: Exception) { }
        say("I could not end the call. Please allow phone permission in the Riya app.", true)
    }

    // ---------- Offline hearing (Vosk) ----------

    private var voskRetries = 0

    // ---------- Voice Match: answer only to the enrolled voice (like Google Voice Match / Siri) ----------
    @Volatile private var enrolling = false
    private val enrollVecs = ArrayList<FloatArray>()
    private val enrollPrompts: List<String> get() = listOf(
        "Hello, it's me.",
        "Open the camera.",
        "Play some music.",
        "What's the time.",
        "Call my friend.",
        "$assistantName, next one.",
        "$assistantName, pause it.",
        "$assistantName, previous one.",
        "Set an alarm.",
        "Stop the music."
    )

    private fun voicePrint(): FloatArray? {
        val s = prefs().getString("voice_print", "").orEmpty()
        if (s.isBlank()) return null
        return try { s.split(",").map { it.toFloat() }.toFloatArray() } catch (e: Exception) { null }
    }

    private fun voiceLockOn() = prefs().getBoolean("voice_lock", false) && voicePrint() != null
    private fun wantVosk() = prefs().getBoolean("offline_ears", false) || voiceLockOn() ||
        NetQuality.level(this) != NetQuality.Level.GOOD     // weak or no signal: hear on the phone, never wait for the network

    private fun cosDist(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until minOf(a.size, b.size)) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 1.0
        return 1.0 - d / (Math.sqrt(na) * Math.sqrt(nb))
    }

    private fun voiceSamples(): List<FloatArray> {
        val raw = prefs().getString("voice_prints", "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return try { raw.split(";").filter { it.isNotBlank() }.map { l -> l.split(",").map { it.toFloat() }.toFloatArray() } } catch (e: Exception) { emptyList() }
    }

    private fun isMyVoice(sp: FloatArray?, frames: Int, nameSaid: Boolean = false): Boolean {
        val print = voicePrint() ?: return true
        if (sp == null) {
            if (nameSaid) return true          // very short command with her name: no voice print could be made, do not ignore you
            lastHint = "Voice not clear enough, ignored. Say her name first, or speak a little longer or closer."
            notifyUi(); tick(60); handler.postDelayed({ tick(60) }, 150L)
            return false
        }
        val base = prefs().getFloat("voice_thresh", 0.5f).toDouble()
        val sens = when (prefs().getInt("voice_sens", 1)) { 0 -> -0.05; 2 -> 0.20; else -> 0.10 }   // strict / normal / relaxed
        val thr = (base + sens + (if (frames < 60) 0.15 else 0.0)).coerceAtMost(0.9)
        var d = cosDist(sp, print)
        for (v in voiceSamples()) d = minOf(d, cosDist(sp, v))     // close to the average OR to any one training sentence
        if (d <= thr || (nameSaid && d <= thr + 0.12)) { lastHint = ""; return true }   // she is called by name: be a little more forgiving
        tick(60); handler.postDelayed({ tick(60) }, 150L)         // two buzzes: heard you, but the voice did not match
        lastHint = "Not your voice, ignored (match " + String.format(Locale.US, "%.2f", d) + ", needs " + String.format(Locale.US, "%.2f", thr) + " or less). Set Match strictness to Relaxed or train again."
        notifyUi()
        return false
    }

    private fun startEnroll() {
        if (ears?.speakerReady != true) {
            prefs().edit().putBoolean("voice_enroll", false).apply()
            say("Voice training is not available in this build. The voice model is missing.")
            return
        }
        enrolling = true
        enrollVecs.clear()
        say("Voice training. Say " + enrollPrompts.size + " short sentences, three words each, in your normal voice. First: " + enrollPrompts[0])
    }

    private fun enrollSample(text: String, sp: FloatArray?, frames: Int) {
        lastHeard = text
        notifyUi()
        if (sp == null || frames < 40 || text.trim().split(" ").size < 2) {
            say("I did not catch it. Please say it again: " + enrollPrompts[enrollVecs.size])
            return
        }
        enrollVecs.add(sp)
        if (enrollVecs.size < enrollPrompts.size) {
            say("Good. Next: " + enrollPrompts[enrollVecs.size])
            return
        }
        val n = sp.size
        val mean = FloatArray(n)
        for (v in enrollVecs) {
            var len = 0.0
            for (x in v) len += x * x
            val l = Math.sqrt(len).toFloat().coerceAtLeast(1e-6f)
            for (i in 0 until minOf(n, v.size)) { mean[i] = mean[i] + v[i] / l }
        }
        val count = enrollVecs.size.toFloat()
        for (i in 0 until n) { mean[i] = mean[i] / count }
        var worst = 0.0
        for (v in enrollVecs) worst = maxOf(worst, cosDist(v, mean))
        val thr = (worst + 0.30).coerceIn(0.50, 0.75)      // short sentences vary more, so a little wider
        prefs().edit().putString("voice_prints", enrollVecs.joinToString(";") { v -> v.joinToString(",") }).putString("voice_print", mean.joinToString(",")).putFloat("voice_thresh", thr.toFloat())
            .putBoolean("voice_lock", true).putBoolean("voice_enroll", false).apply()
        enrolling = false
        say("Done. From now on I will answer only to your voice.", true)
    }

    private fun startOfflineEars() {
        if (liveOn) return
        val model = prefs().getString("vosk_model", "en-in") ?: "en-in"
        ears?.stop()
        ears = null
        lastError = ""
        notifyUi()
        val e = VoskEars(
            this, model,
            onReady = {
                handler.post {
                    if (!running) return@post
                    voskRetries = 0
                    lastError = ""
                    notifyUi()
                    startListening()
                    if (prefs().getBoolean("voice_enroll", false)) handler.postDelayed({ if (running) startEnroll() }, 4000L)
                    else if (voiceLockOn() && ears?.speakerReady != true) {
                        lastError = "Voice Match model is missing in this build, so voice lock is not active."
                        notifyUi()
                    }
                }
            },
            onPartial = { p ->
                handler.post {
                    if (running && !speaking && !thinking) {
                        lastHeard = p
                        notifyUi()
                        if (!typing && !enrolling && !inCall && !isMediaPlaying() && wakeRegex.containsMatchIn(p)) {
                            val t = System.currentTimeMillis()
                            if (t - wakeTickAt > 2500L) { wakeTickAt = t; tick(20) }      // instant: she heard her name
                            showVoiceUi()   // panel appears as soon as you say my name
                        }
                        partialMediaHit(p)
                    }
                }
            },
            onText = { t ->
                val sp = ears?.lastSpk
                val fr = ears?.lastSpkFrames ?: 0
                handler.post {
                    if (running && thinking && !speaking && isStopPhrase(t) && System.currentTimeMillis() - lastChunkAt < 15000L) { interruptSpeech(); return@post }
                    if (running && thinking && !speaking && System.currentTimeMillis() - lastChunkAt > 15000L && !enrolling && t.any { wakeRegex.containsMatchIn(it) || mediaRe.matches(fixMediaWord(it.trim(' ', '.', ','))) }) {
                        aiSeq++                                  // she was busy: drop that answer, listen to the new call
                        thinking = false
                        setState("listening")
                    }
                    if (running && !speaking && !thinking) {
                        if (enrolling) { enrollSample(t[0], sp, fr); return@post }
                        if (voiceLockOn() && ears?.speakerReady == true && !isMyVoice(sp, fr, t.any { wakeRegex.containsMatchIn(it) })) return@post
                        lastHeard = t[0]
                        notifyUi()
                        handleSpeech(t)
                    }
                }
            },
            onFail = { msg ->
                handler.post {
                    if (!running) return@post
                    // Locked / screen off: Google speech is silent there, so NEVER give up on the offline ears; keep reopening the mic
                    val screenDark = isPhoneLocked() || !(try { getSystemService(PowerManager::class.java).isInteractive } catch (e: Exception) { true })
                    if (screenDark || voiceLockOn()) {
                        voskRetries++
                        ears?.stop()
                        ears = null
                        handler.postDelayed({ if (running && useVosk) startOfflineEars() }, minOf(400L * voskRetries, 3000L))
                        return@post
                    }
                    lastError = "Offline hearing failed ($msg). Using the Google recogniser instead."
                    useVosk = false
                    engineInfo = "Hearing: Google speech"
                    ears?.stop()
                    ears = null
                    notifyUi()
                    startListening()
                }
            }
        )
        e.looksComplete = { p -> val m = wakeRegex.find(p); m != null && p.substring(m.range.last + 1).trim().isNotEmpty() }
        // A pause in the middle of a sentence must not cut it ("open paytm ... send 500 ... to vinod"):
        // wait long when the sentence is clearly half said, short when it is a finished command like "next one".
        e.pauseFor = { p ->
            val m = wakeRegex.find(p)
            val tail = (if (m != null) p.substring(m.range.last + 1) else p).trim(' ', '.', ',')
            when {
                tail.isEmpty() -> 380L
                SpeechFixer.dangling(SpeechFixer.fix(tail)) -> 1500L
                mediaRe.matches(fixMediaWord(tail)) -> 220L
                m != null -> 420L
                else -> 650L
            }
        }
        e.onStopWord = { handler.post { tick(25); interruptSpeech() } }
        e.onMediaWord = { hit -> handler.post { mediaGuardHit(hit) } }
        e.onLevel = { v -> level = maxOf(level, v) }
        e.wantSpeaker = voiceLockOn() || prefs().getBoolean("voice_enroll", false)
        ears = e
        e.start()
    }

    // The speech recogniser plays a "ding" each time it starts listening. Mute the system and
    // notification streams while Riya runs (restored when Riya stops).
    private val mutedStreams = ArrayList<Int>()

    private fun muteBeeps() {
        val am = getSystemService(AudioManager::class.java)
        for (st in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!am.isStreamMute(st)) {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(st)
                }
            } catch (e: Exception) { /* needs Do Not Disturb access on some phones */ }
        }
    }

    private fun unmuteBeeps() {
        val am = getSystemService(AudioManager::class.java)
        for (st in mutedStreams) {
            try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        mutedStreams.clear()
    }

    // A 1px invisible overlay lets Android allow starting apps/calls from this background service.
    private fun addOverlay() {
        try {
            if (!Settings.canDrawOverlays(this) || overlayView != null) return
            val wm = getSystemService(WindowManager::class.java)
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(v, lp)
            overlayView = v
        } catch (e: Exception) { /* optional helper */ }
    }

    // ---------- Listening (one reused recognizer = much faster restarts) ----------

    private var recLang = "en-IN"
    // built later (needs the app context, which does not exist yet while the service object is being created)
    private lateinit var recognizerIntent: Intent

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
            val langs = ArrayList(allowedLangs())
            if (langs.size > 1) {   // Android 13+ Google speech: pick the spoken language automatically
                putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", langs)
                putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "balanced")
                putStringArrayListExtra("android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES", langs)
            }
            val names = contactNames()
            if (names.isNotEmpty()) putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", names)  // Android 13+
        }

    // Use the voice you picked in the app; otherwise the most natural one on the phone
    private fun applyVoice() {
        val t = tts ?: return
        try {
            val saved = prefs().getString("tts_voice", "") ?: ""
            val v = (if (saved.isNotEmpty()) VoiceTools.byName(t, saved) else null) ?: VoiceTools.best(t)
            if (v != null) t.voice = v
            voiceIsOffline = false
        } catch (e: Throwable) { }
    }

    // ---------- Voice that never waits for the network ----------
    @Volatile private var voiceIsOffline = false
    @Volatile private var startedId: String? = null

    /** Natural network voices stall on a weak signal; switch to the on-phone voice then, and back when the signal is good. */
    private fun ensureVoiceForNet() {
        if (curTtsLang != "en") return
        val t = tts ?: return
        val poor = NetQuality.level(this) != NetQuality.Level.GOOD
        if (poor == voiceIsOffline) return
        try {
            if (poor) { VoiceTools.bestOffline(t)?.let { t.voice = it; voiceIsOffline = true } }
            else applyVoice()
        } catch (e: Throwable) { }
    }

    private fun forceOfflineVoice() {
        if (curTtsLang != "en" || voiceIsOffline) return
        val t = tts ?: return
        try { VoiceTools.bestOffline(t)?.let { t.voice = it; voiceIsOffline = true } } catch (e: Throwable) { }
    }

    private fun errorName(e: Int) = when (e) {
        1 -> "network timeout"; 2 -> "network error"; 3 -> "audio problem"; 4 -> "server error"
        5 -> "client error"; 8 -> "recogniser busy"; 9 -> "microphone permission missing"
        10 -> "too many requests"; 11 -> "server disconnected"
        12 -> "language not supported"; 13 -> "language not available"
        else -> "error $e"
    }

    // She must never stay deaf: every 4 seconds, if nobody is speaking and the ears are not running, start them again.
    private var deadBeats = 0
    private val earsHeartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val idle = !speaking && !enrolling && !(thinking && answeringNow())
                if (idle && !(inCall && !useVosk)) {
                    if (useVosk) {
                        val e = ears
                        if (e == null || !e.loopActive) {
                            if (++deadBeats >= 2) { deadBeats = 0; startOfflineEars() }
                        } else { deadBeats = 0; e.resume() }
                    } else {
                        deadBeats = 0
                        if (!listening) startListening()
                    }
                } else deadBeats = 0
            } catch (e: Throwable) { }
            handler.postDelayed(this, 4000L)
        }
    }

    private val listenWatchdog = Runnable { listening = false; resetRecognizer(); startListening() }

    private val recListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening = false
            handler.removeCallbacks(listenWatchdog)
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.lowercase().trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (heard.isNotEmpty()) {
                pendingMisses = 0
                lastHeard = heard[0]
                notifyUi()
                handleSpeech(heard)
            }
            restart()
        }

        override fun onError(error: Int) {
            listening = false
            handler.removeCallbacks(listenWatchdog)
            val soft = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!soft) {
                lastError = "Mic problem: " + errorName(error)
                notifyUi()
                if ((error == 12 || error == 13) && recLang != "en-US") {
                    recLang = "en-US"
                    recognizerIntent = buildRecognizerIntent()
                }
                resetRecognizer()
            }
            if (pendingContacts != null && soft) {
                pendingMisses++
                if (pendingMisses >= 3) {
                    pendingMisses = 0
                    say("I did not hear you. Please say the number, like one, two or three.")
                }
            }
            restart(if (soft) 60L else 300L)
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (lastError.isNotEmpty()) { lastError = ""; notifyUi() }
            if (!thinking && !speaking) setState("listening")
        }

        // Fastest path: while choosing a contact, react the moment a clear number is heard
        override fun onPartialResults(partialResults: Bundle?) {
            val pending = pendingContacts ?: return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.lowercase().trim() }?.filter { it.isNotEmpty() } ?: return
            if (p.any { parseChoice(it, pending.size, strictOnly = true) != null }) {
                listening = false
                handler.removeCallbacks(listenWatchdog)
                recognizer?.cancel()
                lastHeard = p[0]; notifyUi()
                handleChoice(p, pending)
                restart()
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { level = maxOf(level, ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun startListening() {
        if (!running || speaking || liveOn) return
        if (inCall && !useVosk) return     // in a call only the on-phone ears may run
        if (useVosk) {
            ears?.resume()
            if (!thinking) setState("listening")
            return
        }
        if (listening) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(recListener) }
        }
        listening = true
        handler.removeCallbacks(listenWatchdog)
        handler.postDelayed(listenWatchdog, 20000)
        recognizerIntent = buildRecognizerIntent()   // refresh contact-name hints
        recognizer?.startListening(recognizerIntent)
    }

    private fun restart(delay: Long = 60) {
        if (running && !speaking) handler.postDelayed({ startListening() }, if (pendingContacts != null) 0L else delay)
    }

    // ---------- Speaking ----------

    private fun say(msg: String, followUp: Boolean = false, lang: String = "en-IN") {
        lastReply = msg
        if (inCall) { afterSpeech = null; notifyUi(); return }      // silent during a call
        if (liveOn) { notifyUi(); return }                          // Gemini Live is speaking by itself
        if (!ttsReady) {
            if (followUp) conversationUntil = System.currentTimeMillis() + 8000
            notifyUi()
            val h = afterSpeech; afterSpeech = null
            h?.invoke()
            return
        }
        val busy = speaking            // she is still talking: the new sentence is added after it, never on top of it
        speaking = true
        setState("speaking")
        ears?.pause()
        recognizer?.cancel()
        listening = false
        handler.removeCallbacks(listenWatchdog)
        val id = "riya_" + (++utteranceCounter)
        currentUtterance = id
        currentFollowUp = followUp
        handler.removeCallbacks(speakWatchdog)
        var spoken = msg
        if (!setSpeakLang(lang)) {
            spoken = "I do not have a " + Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH) +
                " voice on this phone. Please install it in the text to speech settings."
        }
        if (!busy) ensureVoiceForNet()
        val r = tts?.speak(spoken, if (busy) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            setState("listening")
            val h = afterSpeech; afterSpeech = null
            if (h != null) h() else restart()
        } else {
            beginGuard()
            if (!busy) sessionChars = 0
            sessionChars += spoken.length
            handler.postDelayed(speakWatchdog, 4000L + sessionChars * 90L)
            // a network voice that has not started after 1.3 s (signal dropped): say it again with the on-phone voice
            if (!busy && !voiceIsOffline && curTtsLang == "en") {
                val txt = spoken
                handler.postDelayed({
                    if (running && speaking && currentUtterance == id && startedId != id) {
                        forceOfflineVoice()
                        val nid = "riya_" + (++utteranceCounter)
                        currentUtterance = nid
                        try { tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, nid) } catch (e: Exception) { }
                    }
                }, 1300L)
            }
        }
    }

    private fun finishSpeaking(id: String?) {
        handler.post {
            if (id != currentUtterance) return@post
            handler.removeCallbacks(speakWatchdog)
            endGuard()
            speaking = false
            conversationUntil = if (currentFollowUp) System.currentTimeMillis() + 8000 else 0L
            if (pendingContacts != null) armPending()
            setState("listening")
            resumeAfterSpeech()
        }
    }

    private fun resumeAfterSpeech() {
        val h = afterSpeech
        afterSpeech = null
        if (h != null) h() else startListening()
    }

    private fun armPending() {
        handler.removeCallbacks(clearPending)
        handler.postDelayed(clearPending, 30000)
    }

    // ---------- Command handling ----------

    @Volatile private var heardAlts: List<String> = emptyList()

    // A sentence cut in the middle ("riya open paytm send 500" | "to vinod") is held for a moment and joined with the next words.
    @Volatile private var fragText: String? = null
    @Volatile private var fragAt = 0L
    private val fragFlush = Runnable {
        val f = fragText
        fragText = null
        if (f != null && running && !speaking && !thinking) handleSpeech(listOf(f), true)
    }

    private fun handleSpeech(heard0: List<String>, noHold: Boolean = false) {
        var heard = heard0
        val fr = fragText
        if (fr != null) {
            handler.removeCallbacks(fragFlush)
            fragText = null
            if (System.currentTimeMillis() - fragAt < 3500L && heard.none { wakeRegex.containsMatchIn(it) })
                heard = heard.map { "$fr $it".trim() }
        }
        heardAlts = heard
        if (inCall) { handleCallSpeech(heard); return }
        if (answeringNow()) {                      // she is talking: finish the answer; only "stop" cuts it
            if (isStopPhrase(heard)) interruptSpeech()
            return
        }
        // the same words twice within 1.5 s (two engines, a re-sent result) = one call: answer once
        run {
            val key = heard[0].trim(' ', '.', ',').lowercase()
            val tNow = System.currentTimeMillis()
            if (key.isNotEmpty() && key == lastSpeechKey && tNow - lastSpeechAt < 1500L) return
            lastSpeechKey = key
            lastSpeechAt = tNow
        }
        val pending = pendingContacts
        if (pending != null) {
            handleChoice(heard, pending)
            return
        }
        if (dictating) { handleDictation(heard); return }
        val now = System.currentTimeMillis()
        val free = pendingFreeText
        if (free != null) {
            pendingFreeText = null
            if (now < freeUntil) { free(heard[0]); return }
        }
        if (!noHold && heard[0].isNotBlank()) {
            val named = nameCommand(heard)
            val best = (bestAfterName(heard) ?: named ?: heard[0]).trim(' ', '.', ',')
            val addressed = named != null || now < conversationUntil || !prefs().getBoolean("wake_required", false)
            if (addressed && best.isNotEmpty() && SpeechFixer.dangling(SpeechFixer.fix(best))) {
                fragText = heard[0]
                fragAt = now
                handler.removeCallbacks(fragFlush)
                handler.postDelayed(fragFlush, 2300L)
                lastHint = "Waiting for the rest of what you were saying \u2026 (\u201c$best\u201d)"
                notifyUi()
                return
            }
        }
        // Video / movie / music is playing: stay muted. Only her name + a command is accepted (or the sentence right after she answered).
        if (quietMedia() && !(hasNonLatin(heard[0]) && indicNamedText(heard) != null)) {
            val cmd = (bestAfterName(heard) ?: nameCommand(heard))?.let { trimMediaTail(it) }
            // the live words already did this command a moment ago: do not press next twice
            if (cmd != null && now - partialHitAt < 2500L) {
                val fc = fixMediaWord(cmd.trim(' ', '.', ','))
                if (mediaRe.matches(fc) || musicParse(SpeechFixer.fix(fc)) != null) return
            }
            if (cmd != null) { conversationUntil = 0; handle(cmd) }
            else if (now < conversationUntil) { conversationUntil = 0; handle(trimToCommand(heard[0])) }
            else {
                // no name: a lone "next" / "previous" / "play next song" is still obeyed (reels in front, music playing, or just after a command)
                val bareCmd = heard.map { fixMediaWord(it.trim(' ', '.', ',')) }.firstOrNull {
                    ((nextRe.matches(it) || prevRe.matches(it)) && mediaRe.matches(it)) || musicNextRe.matches(it) || musicPrevRe.matches(it) }
                if (bareCmd != null) {
                    if (now - partialHitAt < 2500L) return
                    val song = musicNextRe.matches(bareCmd) || musicPrevRe.matches(bareCmd)
                    typeExec.execute {
                        if (bareMediaAllowed(song)) handler.post { partialHitAt = System.currentTimeMillis(); handle(bareCmd, false) }
                    }
                    return
                }
                lastHint = "Music or a video is playing, so I only react to \u201c$assistantName, ...\u201d. Heard \u201c${heard[0]}\u201d."
                notifyUi()
            }
            return
        }
        // Telugu / Hindi script: the usual phone commands are understood on the phone itself (instant, no internet)
        indicLocal(heard)?.let { conversationUntil = 0; handle(it); return }
        // Telugu, Hindi, Tamil ... (non-English script): Gemini understands it, runs the command or answers in that language
        val indicText = if (hasNonLatin(heard[0])) indicNamedText(heard) else null   // her name said in Telugu / Hindi script
        if (hasNonLatin(heard[0]) && geminiReady() && (!isMediaPlaying() || voiceLockOn() || indicText != null) &&
            (!prefs().getBoolean("wake_required", false) || now < conversationUntil || indicText != null)) {
            conversationUntil = 0
            askGemini(indicText ?: heard[0], false, true)
            return
        }
        if (indicText != null) {
            conversationUntil = 0
            say("I heard you, but for Telugu or Hindi questions I need internet and the Gemini key. English phone commands still work offline.", true)
            return
        }
        // "Riya call Vinod" and "call Vinod Riya"; of the engine's guesses the one that is a real command wins
        val named = bestAfterName(heard)
        if (named != null) { conversationUntil = 0; handle(named); return }
        // Bare "next" / "previous" / "pause": only for your voice (Voice Match) or right after a video command
        val bare = heard.map { it.trim(' ', '.', ',') }.firstOrNull { mediaRe.matches(it) }
        if (bare != null && (voiceLockOn() || now < mediaUntil || !isMediaPlaying())) { handle(bare, false); return }
        // No wake word, but Riya just spoke to you: treat this as the reply
        if (now < conversationUntil) { conversationUntil = 0; handle(trimToCommand(heard[0])); return }
        val t = heard[0].trim()
        val words = t.split(" ").filter { it.isNotEmpty() }
        val firstWords = t0First(heard[0])
        if (firstWords != null) { conversationUntil = 0; handle(firstWords); return }
        // The speech engine often writes the name wrongly ("ria", "area"). A short call like that = "I am listening"
        if (words.size <= 2 && words.any { isCallWord(it) }) {
            conversationUntil = 0
            handle("")
            return
        }
        // "area open instagram": drop 1-2 mis-heard name words in front of a real command
        val trimmed = trimToCommand(t)
        if (trimmed != t) { handle(trimmed); return }
        // Wake word turned off in the app: every sentence is a command
        if (isMediaPlaying() && !voiceLockOn()) {          // a video is playing: its voices are not commands
            lastHint = "Sound is playing, so I ignored \u201c${heard[0]}\u201d. Say \u201c$assistantName, ...\u201d first."
            notifyUi()
            return
        }
        if (!prefs().getBoolean("wake_required", false)) {
            val noise = setOf("the", "a", "uh", "huh", "hmm", "um", "oh", "ah", "eh", "and", "but")
            if (t.length >= 3 && t !in noise) handle(t, false)
            return
        }
        // Heard you, but there was no name: tell the user instead of staying silent
        lastHint = "Heard \u201c${heard[0]}\u201d. Start with \u201c$assistantName\u201d, e.g. \u201c$assistantName, open Instagram\u201d."
        notifyUi()
    }

    /** The guess written in Telugu / Hindi script that contains my name ("రియా" = riya), or null. */
    private fun indicNamedText(heard: List<String>): String? {
        for (h in heard) {
            if (!hasNonLatin(h)) continue
            val r = IndicRoman.toLatin(h)
            if (wakeRegex.containsMatchIn(r)) return h
            val w = r.split(" ").filter { it.isNotEmpty() }
            if (w.size >= 2 && isCallWord(w[0])) return h
        }
        return null
    }

    /** "రియా, కాల్ టు వెంకటేష్" / "వెంకటేష్ కి కాల్ చేయి" / "ఇన్‌స్టాగ్రామ్ ఓపెన్" ... -> "call venkatesh" etc. */
    private fun indicLocal(heard: List<String>): String? {
        val now = System.currentTimeMillis()
        for (h in heard) {
            if (!hasNonLatin(h)) continue
            var r = IndicRoman.toLatin(h)
            val m = wakeRegex.find(r)
            if (m != null) r = (r.substring(0, m.range.first) + " " + r.substring(m.range.last + 1)).trim()
            if (m == null && prefs().getBoolean("wake_required", false) && now >= conversationUntil) continue   // name required and not said
            val cmd = IndicCommands.parse(r) ?: continue
            lastHint = "Heard \u201c$h\u201d \u2192 $cmd"
            notifyUi()
            return cmd
        }
        return null
    }

    /** Of all guesses that contain my name, the command that is a real next / pause / music / payment command; else the first guess. */
    private fun bestAfterName(heard: List<String>): String? {
        var first: String? = null
        for (text in heard) {
            val m = wakeRegex.find(text) ?: continue
            val after = text.substring(m.range.last + 1).trim()
            val before = text.substring(0, m.range.first).trim()
            val cmd = if (after.isNotEmpty()) after else before
            if (first == null) first = cmd
            val fx = SpeechFixer.fix(fixMediaWord(cmd.trim(' ', '.', ',')))
            if (mediaRe.matches(fx) || musicParse(fx) != null || payParse(fx) != null) return cmd
        }
        return first
    }

    /** A video keeps talking and can end up in the same sentence: "next one and then she said ..." -> "next one". */
    private fun trimMediaTail(cmd: String): String {
        val c = cmd.trim(' ', '.', ',')
        val whole = fixMediaWord(c)
        if (mediaRe.matches(whole) || musicParse(whole) != null || payParse(whole) != null) return cmd
        val w = c.split(" ").filter { it.isNotEmpty() }
        for (k in minOf(3, w.size) downTo 1) {
            val head = fixMediaWord(w.take(k).joinToString(" "))
            if (mediaRe.matches(head) && head.split(" ")[0] in setOf("next", "previous", "pause", "mute", "unmute", "skip")) return head
        }
        return cmd
    }

    @Volatile private var partialHitAt = 0L
    private var lastSpeechKey = ""
    private var lastSpeechAt = 0L
    private var lastAckAt = 0L

    /** A video keeps talking, so the sentence may never end: act as soon as "<name> next / previous / pause / mute" is heard. */
    private fun partialMediaHit(p: String) {
        if (typing || enrolling || inCall || voiceLockOn() || !quietMedia()) return
        val now = System.currentTimeMillis()
        if (now - partialHitAt < 2000L) return
        val m = wakeRegex.find(p) ?: return
        val tail = p.substring(m.range.last + 1).trim().split(" ").filter { it.isNotEmpty() }
        if (tail.isEmpty()) return
        var cmd: String? = null
        for (k in minOf(2, tail.size) downTo 1) {
            val fx = fixMediaWord(tail.take(k).joinToString(" "))
            if (mediaRe.matches(fx) && fx.split(" ")[0] in setOf("next", "previous", "pause", "mute", "unmute", "skip")) { cmd = fx; break }
        }
        val c = cmd ?: return
        partialHitAt = now
        ears?.flushNow = true
        conversationUntil = 0
        handle(c)
    }

    private val verbStart = Regex(
        "^(open|launch|start|run|call|phone|dial|play|message|send|close|clear|kill|remove|clean|go|press|show|take|set|turn|switch|" +
        "volume|mute|unmute|increase|decrease|flashlight|torch|lock|stop|exit|wake|what|whats|tell|how|who|why|when|where)\\b"
    )

    /** "area open instagram" -> "open instagram" (only when 1-2 stray words sit in front of a real command). */
    private fun trimToCommand(t: String): String {
        if (verbStart.containsMatchIn(t)) return t
        val words = t.split(" ").filter { it.isNotEmpty() }
        for (k in 1..minOf(2, words.size - 1)) {
            val rest = words.drop(k).joinToString(" ")
            if (verbStart.containsMatchIn(rest)) return rest
        }
        return t
    }

    /** "ria what is the time": first word sounds like my name -> the rest is the command. */
    private fun t0First(text: String): String? {
        val w = text.trim().split(" ").filter { it.isNotEmpty() }
        if (w.size >= 2 && isCallWord(w[0])) return w.drop(1).joinToString(" ")
        return null
    }

    private fun isCallWord(w: String): Boolean {
        val extra = if (assistantName == "riya")
            setOf("ria", "reya", "rhea", "riyah", "riyaa", "rear", "area", "aria", "arya", "ariya", "rio", "reah", "real") else emptySet()
        return w == assistantName || w in extra || (assistantName.length >= 4 && editDistance(w, assistantName) <= 1)
    }

    private fun handle(raw: String, addressed: Boolean = true) {
        lastHint = ""
        var c = raw.trim()
        // "WhatsApp Business" / "WA business": remember it, then treat it as plain "whatsapp"
        val bizRe = Regex("\\b(whats ?app business|what'?s ?app business|what'?s ?up business|whatsapp biz|business whats ?app|business what'?s ?app|wa business|wa biz|w a business)\\b", RegexOption.IGNORE_CASE)
        waBiz = bizRe.containsMatchIn(c)
        if (waBiz) c = c.replace(bizRe, "whatsapp")
        // speech engines write the app name oddly: "open what's up" -> whatsapp
        c = c.replace(Regex("\\b(open|launch|start|on|to) (what'?s ?up|what sap|whats ?app|watsapp|what's ?app)\\b"), "$1 whatsapp")
        val filler = Regex("^(please|can you|could you|would you|will you|hey|ok|okay|just|now)\\b[ ,]*")
        var prev: String
        do { prev = c; c = c.replace(filler, "").trim() } while (c != prev)
        c = SpeechFixer.fix(c)       // "cal vinod" -> call vinod, "sent 500" -> send 500, "opan" -> open ...
        c = fixMediaWord(c)          // "nest" -> next, "paws" -> pause ... (only when the result is a real next / pause / mute command)
        if (c.isEmpty()) {
            val nowAck = System.currentTimeMillis()
            if (nowAck - lastAckAt < 4000L) return          // she already said "tell me" for this call
            lastAckAt = nowAck
            val o = prefs().getString("owner", "").orEmpty().trim()
            say(if (o.isEmpty()) "Haa, tell me. I am listening." else "Haa $o, tell me. I am listening.", followUp = true)
            return
        }

        val openRe = Regex("^(open|launch|start|run)\\s+(?:the\\s+)?(.+)$")
        if (isPhoneLocked()) {
            val need = lockedTaskPhrase(c)
            if (need != null) {
                if (autoUnlockThen(c)) return          // saved PIN: unlock by myself, then do the task
                afterUnlockCmd = c
                afterUnlockUntil = System.currentTimeMillis() + 60000
                val why = if (PinVault.has(this)) unlockBlocker()
                    else "I can unlock by myself if you save your phone PIN in my settings."
                say((if (why != null) "$why " else "") + "For now, please unlock your phone $need. I will do it as soon as you unlock.", true)
                return
            }
        }

        when {
            liveStartRe.containsMatchIn(c) -> startLive(null)
            Regex("^test (the )?(lock|unlock|locking|unlocking)( (and|then) (lock|unlock|locking|unlocking))?( test)?$|^(lock|unlock) test$").containsMatchIn(c) -> selfTestLockUnlock()
            unlockRe.containsMatchIn(c) -> {
                if (!isPhoneLocked()) say("The phone is already unlocked.", true)
                else if (!autoUnlockThen(null)) {
                    val why = unlockBlocker()
                    say(why ?: "I could not start the unlock. Please try again.", true)
                }
            }
            c == "exit" || c == "quit" || c.startsWith("stop listening") || (c.startsWith("turn off") && musicParse(c) == null && radioParse(c) == null) ||
                c.startsWith("shut down") || c.startsWith("exit $assistantName") -> stopSelf()
            musicParse(c) != null -> {
                val mc = musicParse(c)!!
                when (mc.first) {
                    "stop" -> pauseMusic()
                    "next" -> musicSkip(true)
                    "previous" -> musicSkip(false)
                    else -> startMusic(mc.second)
                }
            }
            Regex("^(stop|wait|enough|cancel|quiet|silence|shut up)( it| talking| speaking| please)?( $assistantName)?$").containsMatchIn(c) ->
                if (!speaking && !thinking && c.startsWith("stop") && isMediaPlaying()) pauseMusic() else interruptSpeech()
            Regex("^(start |begin |turn on )?(typing|dictation|dictate)( mode)?$").containsMatchIn(c) -> startDictation(true)
            Regex("^(type|dictate)\\s+.+$").containsMatchIn(c) -> typeSpeech(c.replace(Regex("^(type|dictate)\\s+"), ""))
            payParse(c) != null -> startPay(payParse(c)!!)
            mediaRe.matches(c) -> mediaControl(c)
            waCallParse(c) != null -> { val wc = waCallParse(c)!!; openWaCall(wc.first, wc.second) }
            waChatName(c) != null -> openWaChat(waChatName(c)!!)
            Regex("^(call|phone|dial)\\b").containsMatchIn(c) -> {
                val name = c.replace(Regex("^(call|phone|dial)( to)?"), "").trim()
                if (name.isEmpty()) {
                    pendingFreeText = { n ->
                        val nm = n.replace(Regex("^(call|phone|dial)( to)?"), "").trim()
                        if (nm.isNotEmpty()) pickContact(nm, "call") { dial(it) }
                    }
                    freeUntil = System.currentTimeMillis() + 15000
                    say("Whom should I call?")
                } else pickContact(name, "call") { dial(it) }
            }
            Regex("^(answer|pick up|pickup|accept)( the| this)?( call| phone)?$").containsMatchIn(c) -> answerCall()
            Regex("^(reject|decline|cut|end|hang up|disconnect)( the| this)?( call| phone)?$").containsMatchIn(c) -> rejectCall()
            Regex("^(close|clear|kill|remove|clean)\\b.*\\b(apps|applications|recents|recent|background|tasks)\\b").containsMatchIn(c) ||
                Regex("^(close|clear) all$").containsMatchIn(c) -> closeAllApps()
            Regex("^(go )?(to )?(the )?home( screen)?$").containsMatchIn(c) || c == "go home" ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "Home")
            Regex("^(go |press )?back$").containsMatchIn(c) ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "Back")
            Regex("\\b(recent apps|recents|app switcher|recent screen)\\b").containsMatchIn(c) ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps")
            Regex("\\b(notifications?|notification panel|notification bar)\\b").containsMatchIn(c) ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
            "quick settings" in c ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
            Regex("\\b(screenshot|screen shot)\\b").containsMatchIn(c) ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Taking a screenshot")
            Regex("\\b(alarm|wake me)\\b").containsMatchIn(c) -> setAlarm(c)
            Regex("\\btimer\\b").containsMatchIn(c) -> setTimer(c)
            radioParse(c) != null && RiyaAccessibility.instance != null -> radioParse(c)!!.let { setRadio(it.first, it.second) }
            Regex("\\b(wi ?fi|wi-fi|bluetooth|brightness|airplane mode|flight mode|hotspot|location settings|gps)\\b").containsMatchIn(c) &&
                !c.startsWith("play") -> openSettingsFor(c)
            openRe.containsMatchIn(c) -> {
                val target = openRe.find(c)!!.groupValues[2].replace(Regex("\\s+app$"), "").trim()
                if ("camera" in target) openCamera() else openApp(target)
            }
            c.startsWith("play") -> {
                val m = Regex("^play (.+?)(?: on (youtube|spotify))?$").find(c)
                val song = m?.groupValues?.get(1) ?: c.replace("play", "").trim()
                val platform = m?.groupValues?.get(2).orEmpty()
                if (!isOnline()) playLocal(song)
                else if (platform == "youtube") youtube(song) else playSpotify(song)
            }
            "whatsapp" in c || c.startsWith("message") || c.startsWith("send message") -> whatsapp(c)
            (Regex("\\block\\b").containsMatchIn(c) && "unlock" !in c) ||
                Regex("^(luck|lok|log|look)( the| my)?( phone| screen| mobile| it| now| please)+$").containsMatchIn(c) -> lockScreen()
            "flashlight" in c || "torch" in c || "flash light" in c -> flashlight(c)
            "volume" in c || "mute" in c || "louder" in c -> volume(c)
            Regex("^(what('?s| is) )?(the )?(current )?time( now| please)?$|\\bwhat time\\b|\\btime is it\\b|\\bthe time\\b").containsMatchIn(c) ->
                say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), true)
            Regex("\\b(what('?s| is) (the )?(date|day)|today'?s date|what day|which day|the date)\\b").containsMatchIn(c) ->
                say("Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), true)
            "camera" in c -> openCamera()
            else -> {
                val talk = smallTalk(c)
                if (talk != null) say(talk, true)
                else if (addressed && c.split(" ").size <= 3 && openApp(c, quiet = true)) { /* opened */ }
                else answerFreeText(c, addressed)
            }
        }
    }

    // ---------- Chat: offline small talk + optional AI brain ----------

    private val jokes = listOf(
        "Why did the phone go to school? To get smarter!",
        "Why was the computer cold? It left its Windows open.",
        "I told my phone a joke, but it did not laugh. It just buffered.",
        "Why do programmers prefer dark mode? Because light attracts bugs."
    )

    // ---------- Type by voice (cursor in any app) + WhatsApp / WhatsApp Business chats ----------

    private var dictating: Boolean
        get() = typing
        set(v) {
            typing = v
            if (v) voiceOverlay?.show(true) else voiceOverlay?.hide()
            notifyUi()
        }
    @Volatile private var waBiz = false
    private val typeExec = Executors.newSingleThreadExecutor()
    private var lastNoFieldHint = 0L
    private val dictIdle = Runnable { if (dictating) { dictating = false; tick(20) } }

    private fun pkgInstalled(p: String) = try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }

    private fun waPkg(biz: Boolean): String? {
        if (biz && pkgInstalled("com.whatsapp.w4b")) return "com.whatsapp.w4b"
        if (pkgInstalled("com.whatsapp")) return "com.whatsapp"
        if (pkgInstalled("com.whatsapp.w4b")) return "com.whatsapp.w4b"
        return null
    }

    /** "open whatsapp ramu", "whatsapp business chat with ramu", "open ramu chat on whatsapp" -> the person's name. */
    private fun waChatName(c: String): String? {
        if (" saying " in c || c.startsWith("send ") || c.startsWith("message ")) return null
        val a = Regex("^(?:open |launch |start |go to )?(?:the )?whatsapp(?: app)?(?: chat)?(?: with| of| to| for| and)?\\s+(.+)$").find(c)
        val b = Regex("^(?:open|start)\\s+(.+?)\\s+(?:chat\\s+)?(?:on|in)\\s+whatsapp$").find(c)
        val n = (a?.groupValues?.get(1) ?: b?.groupValues?.get(1))?.trim() ?: return null
        val words = setOf("status", "settings", "web", "call", "calls", "app", "business", "chats", "chat")
        return if (n.isEmpty() || n in words) null else n
    }

    /** "whatsapp call ramu", "open whatsapp ramu video call", "video call ramu", "call ramu on whatsapp" -> (name, isVideo) */
    private fun waCallParse(c: String): Pair<String, Boolean>? {
        val w = "(?:with |of |to |for |and )?"
        val tail = "(?: (?:on|in|using|through|via) whatsapp)?"
        val pats = listOf(
            Regex("^(?:open |launch |start |make |do )?(?:a )?whatsapp(?: app)?\\s+(video call|voice call|call|video)\\s+$w(.+)$") to 0,
            Regex("^(?:open |launch |start )?whatsapp(?: app)?\\s+(?:chat\\s+)?$w(.+?)\\s+(video call|voice call|call|video)$") to 1,
            Regex("^(video call|voice call)\\s+$w(.+?)$tail$") to 0,
            Regex("^(call|video call|voice call)\\s+(.+?)\\s+(?:on|in|using|through|via)\\s+whatsapp$") to 0
        )
        for ((re, order) in pats) {
            val m = re.find(c) ?: continue
            val kind = if (order == 0) m.groupValues[1] else m.groupValues[2]
            val name = (if (order == 0) m.groupValues[2] else m.groupValues[1]).trim()
            if (name.isEmpty() || name in setOf("call", "video", "chat", "status")) continue
            return Pair(name, kind.contains("video"))
        }
        return null
    }

    private fun waCallDataId(mime: String, c: Contact): Long? {
        val tail = c.number.filter { it.isDigit() }.takeLast(10)
        var byName: Long? = null
        try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1, ContactsContract.Data.DISPLAY_NAME),
                ContactsContract.Data.MIMETYPE + "=?", arrayOf(mime), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val jid = (cur.getString(1) ?: "").substringBefore("@").filter { it.isDigit() }
                    if (tail.length >= 7 && jid.endsWith(tail)) return id
                    if (byName == null && (cur.getString(2) ?: "").equals(c.name, ignoreCase = true)) byName = id
                }
            }
        } catch (e: Exception) { }
        return byName
    }

    private fun openWaCall(name: String, video: Boolean) {
        val biz = waBiz
        pickContact(name, if (video) "start the video call" else "start the call") { contact ->
            val pkg = waPkg(biz)
            if (pkg == null) { say("WhatsApp is not installed."); return@pickContact }
            val b = pkg == "com.whatsapp.w4b"
            val mime = "vnd.android.cursor.item/vnd.com.whatsapp." + (if (b) "w4b." else "") + (if (video) "video.call" else "voip.call")
            say("Starting a " + (if (video) "video call" else "call") + " with " + contact.name + " on " + (if (b) "WhatsApp Business" else "WhatsApp") + ".")
            val id = waCallDataId(mime, contact)
            if (id != null) {
                val di = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, id.toString()), mime)
                    .setPackage(pkg)
                if (launch(di)) return@pickContact
            }
            // no call shortcut for this contact: open the chat and press the call button
            var digits = contact.number.filter { it.isDigit() }
            if (digits.length == 10) digits = defaultCountryCode + digits
            val ci = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(pkg)
            if (!launch(ci)) { say("I could not open WhatsApp."); return@pickContact }
            typeExec.execute {
                Thread.sleep(2500)
                var ok = false
                for (t in 0 until 4) {
                    ok = RiyaAccessibility.instance?.pressCallButton(video) == true
                    if (ok) break
                    Thread.sleep(700)
                }
                if (!ok) handler.post { say("I opened the chat, but could not find the call button. Please tap it.") }
            }
        }
    }

    private fun openWaChat(name: String) {
        val biz = waBiz
        pickContact(name, "open the chat") { contact ->
            var digits = contact.number.filter { it.isDigit() }
            if (digits.length == 10) digits = defaultCountryCode + digits
            val pkg = waPkg(biz)
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))
            if (pkg != null) i.setPackage(pkg)
            if (!launch(i)) { say("I could not open WhatsApp."); return@pickContact }
            startDictation(false)
            say("Opening " + contact.name + "'s chat on " + (if (pkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp") +
                ". Say your message, then say send.")
        }
    }

    private fun startDictation(announce: Boolean) {
        if (RiyaAccessibility.instance == null) {
            say("Please turn on Riya in Accessibility settings first, so I can type for you.")
            return
        }
        RiyaAccessibility.instance?.pinTarget()      // type exactly where the cursor is right now
        lastReply = ""
        dictating = true
        handler.removeCallbacks(dictIdle)
        handler.postDelayed(dictIdle, 180000L)
        tick(40)                                       // a small buzz instead of a spoken message
    }

    private fun handleDictation(heard: List<String>) {
        val raw = heard[0].trim()
        val c = raw.lowercase().trim(' ', '.', ',')
        handler.removeCallbacks(dictIdle)
        handler.postDelayed(dictIdle, 180000L)
        when {
            Regex("^(stop|end|finish|exit|quit|close|done|cancel)( the)?( typing| dictation| dictating)( mode)?$").containsMatchIn(c) ||
                c == "stop typing" || c == "done typing" -> { dictating = false; tick(20) }
            Regex("^(send|send it|send message|send this|send now|press send)$").containsMatchIn(c) -> typeExec.execute {
                val ok = RiyaAccessibility.instance?.pressSend() == true
                handler.post { if (ok) tick(30) else { lastHint = "Could not find the Send button"; tick(80) }; notifyUi() }
            }
            Regex("^(delete that|undo that|undo|scratch that|remove that)$").containsMatchIn(c) -> typeExec.execute {
                val ok = RiyaAccessibility.instance?.deleteLastInserted() == true
                handler.post { tick(if (ok) 30L else 80L); notifyUi() }
            }
            Regex("^(clear (all )?(the )?text|delete everything|clear everything|clear all)$").containsMatchIn(c) -> typeExec.execute {
                val ok = RiyaAccessibility.instance?.clearField() == true
                handler.post { tick(if (ok) 30L else 80L); notifyUi() }
            }
            else -> typeSpeech(raw)
        }
    }

    /** Auto-correct with the online AI when it is available (3 second limit), otherwise the offline fixer runs. */
    private fun fixOnline(text: String): String? {
        if (!prefs().getBoolean("smart_fix", true) || !isOnline() || text.length < 3) return null
        val out: String? = try {
            val g = geminiKey()
            val k = prefs().getString("ai_key", "").orEmpty().trim()
            if (g.isNotEmpty()) GeminiBrain.fix(g, text) else if (k.isNotEmpty()) fixClaude(k, text) else null
        } catch (e: Exception) { null }
        if (out.isNullOrBlank() || out.length > text.length * 2 + 20 || out.length < text.length / 2) return null
        if (Regex("^(here|sure|okay|ok|corrected|the corrected|i'm sorry|i am sorry|sorry|as an ai)\\b", RegexOption.IGNORE_CASE).containsMatchIn(out)) return null
        return out.trim().trim('"', '\u201c', '\u201d')
    }

    private fun fixClaude(key: String, text: String): String? {
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 300)
            .put("system", "You fix dictated text. Correct spelling, grammar and obvious speech-recognition mistakes. " +
                "Keep the same language, meaning and wording as far as possible. Do not add, remove or explain anything. " +
                "Do not answer questions in the text, only fix them. Output ONLY the corrected text.")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
        } finally {
            conn.disconnect()
        }
    }

    /** Types what was said into the text box that has the cursor. */
    private fun typeSpeech(raw: String) {
        typeExec.execute {
            val acc = RiyaAccessibility.instance
            if (acc == null) {
                handler.post { dictating = false; say("Please turn on Riya in Accessibility settings first.") }
                return@execute
            }
            val fixed = fixOnline(raw) ?: raw
            var before: String? = null
            for (i in 0 until 6) {                      // the app may still be opening
                before = acc.textBeforeCursor()
                if (before != null) break
                Thread.sleep(500)
            }
            if (before == null) {
                handler.post {
                    val now = System.currentTimeMillis()
                    if (now - lastNoFieldHint > 8000) { lastNoFieldHint = now; lastHint = "Tap inside a text box, then speak."; tick(60); notifyUi() }
                }
                return@execute
            }
            val out = Dictation.polish(fixed, before)
            val ok = out.isNotEmpty() && acc.insertAtCursor(out)
            handler.post { if (!ok) { lastHint = "Could not type here"; tick(80) }; notifyUi() }
        }
    }

    /** Locked phone: returns what to say after "Please unlock your phone ..." for tasks, or null when it can be done / answered right now. */
    @Volatile private var afterUnlockCmd: String? = null
    @Volatile private var afterUnlockUntil = 0L
    @Volatile private var unlocking = false

    /** PIN saved + switched on + (unless turned off) Voice Match active, so only the owner's voice can make Riya type the PIN. */
    // "unlock", "un lock", "unlocked", "unlock my phone", and the usual mis-hearing "on lock ..." at the start
    private val unlockRe = Regex("\\bun ?(lock|luck|look|lok)\\w*\\b|^(on|an|and|a|in) (lock|luck|look)\\b|\\bopen (the |my )?lock\\b")

    /** null = unlocking is possible; otherwise the exact reason (spoken and shown on the main screen). */
    private fun unlockBlocker(): String? {
        if (!PinVault.has(this)) return "No phone PIN is saved. Open my settings, Phone unlock, type your PIN and tap save."
        if (!prefs().getBoolean("auto_unlock", true)) return "Automatic unlock is switched off in my settings."
        if (PinVault.load(this) == null) return "I could not read the saved PIN. Please save it again in my settings."
        if (RiyaAccessibility.instance == null) return "Accessibility is off for me. Please switch Riya on in the phone's Accessibility settings."
        if (prefs().getBoolean("unlock_voice_only", true) && voiceLockOn()) {
            // Voice Match is trained, so only the owner's voice may unlock. (Not trained = nothing to check, so unlock works.)
            if (ears?.speakerReady != true) return "Voice Match is still starting. Please say it again in a moment."
        }
        return null
    }

    private fun unlockAllowed(): Boolean = unlockBlocker() == null

    /** Locked phone: unlock with the saved PIN, then run [cmd] (if any). true = Riya is handling it. */
    private fun autoUnlockThen(cmd: String?): Boolean {
        val why = unlockBlocker()
        if (why != null) {
            // only complain when the user really set up auto-unlock (a PIN is saved)
            if (PinVault.has(this)) { lastHint = why; notifyUi() }
            return false
        }
        val acc = RiyaAccessibility.instance ?: return false
        val pin = PinVault.load(this) ?: return false
        if (unlocking) return true
        unlocking = true
        lastHint = ""
        handler.removeCallbacks(unlockReset)
        handler.postDelayed(unlockReset, 25000L)          // never stay "busy unlocking" forever
        handler.post { LockVoiceActivity.dismiss() }       // the voice sheet would cover the PIN pad
        handler.postDelayed({ LockVoiceActivity.dismiss() }, 400L)
        typeExec.execute {
            var ok = try { acc.unlockWithPin(pin) } catch (e: Exception) { false }
            // one more try when the pad was not found / a key could not be pressed (never after a wrong PIN: that would count twice)
            if (!ok && (acc.unlockFail == "nopad" || acc.unlockFail == "tap")) {
                handler.post { LockVoiceActivity.dismiss() }
                ok = try { acc.unlockWithPin(pin) } catch (e: Exception) { false }
            }
            val fail = acc.unlockFail
            handler.post {
                unlocking = false
                handler.removeCallbacks(unlockReset)
                if (!running) return@post
                if (ok) {
                    tick(35)
                    if (cmd != null) handler.postDelayed({ if (running) handle(cmd) }, 500L)
                    else if (testingLock) { testingLock = false; say("Lock and unlock test passed. Both work.", true) }
                    else { lastReply = "Phone unlocked."; notifyUi() }
                } else {
                    testingLock = false
                    val msg = when (fail) {
                        "pin" -> "I can only unlock a number PIN of four or more digits. Please save a number PIN in my settings."
                        "nopad" -> "I could not find the number pad on your lock screen. Riya unlocks only number PIN screens, not pattern or password."
                        "wrong" -> "I typed the PIN but the phone did not unlock. Please check the saved PIN in my settings."
                        else -> "I could not press the keys on the lock screen. Please unlock it yourself."
                    }
                    lastHint = msg
                    notifyUi()
                    if (cmd != null) { afterUnlockCmd = cmd; afterUnlockUntil = System.currentTimeMillis() + 60000 }
                    say(msg, true)
                }
            }
        }
        return true
    }

    private val unlockReset = Runnable { unlocking = false }

    private fun lockedTaskPhrase(c: String): String? {
        fun has(r: String) = Regex(r).containsMatchIn(c)
        return when {
            has("^(stop|wait|enough|cancel|quiet|silence|shut up)\\b") || c == "exit" || c == "quit" ||
                c.startsWith("turn off") || c.startsWith("shut down") -> null
            unlockRe.containsMatchIn(c) -> null
            liveStartRe.containsMatchIn(c) || liveChatLike(c) -> null
            payParse(c) != null -> "to make that payment"
            mediaRe.matches(c) -> null
            has("^(answer|pick up|pickup|accept|reject|decline|cut|end|hang up|disconnect)\\b") -> null
            has("^(start |begin |turn on )?(typing|dictation|dictate)( mode)?$") || has("^(type|dictate)\\s+") -> "to type"
            has("^(call|phone|dial)\\b") -> {
                val n = c.replace(Regex("^(call|phone|dial)( to)?"), "").trim()
                if (n.isEmpty()) "to make a call" else "to call $n"
            }
            has("^(open|launch|start|run)\\s+") -> {
                val n = c.replace(Regex("^(open|launch|start|run)\\s+(the\\s+)?"), "").replace(Regex("\\s+app$"), "").trim()
                if (n.isEmpty()) "to open that" else "to open $n"
            }
            c.startsWith("play") -> {
                val n = c.removePrefix("play").trim()
                if (n.isEmpty()) "to play music" else "to play $n"
            }
            waCallParse(c) != null -> "to make that call"
            "whatsapp" in c || c.startsWith("message") || c.startsWith("send message") -> "to send that message"
            "camera" in c -> "to open the camera"
            has("^(close|clear|kill|remove|clean)\\b.*\\b(apps|applications|recents|recent|background|tasks)\\b") || has("^(close|clear) all$") -> "to close your apps"
            has("^(go )?(to )?(the )?home( screen)?$") || c == "go home" || has("^(go |press )?back$") ||
                has("\\b(recent apps|recents|app switcher|recent screen)\\b") ||
                has("\\b(notifications?|notification panel|notification bar)\\b") || "quick settings" in c ||
                has("\\b(screenshot|screen shot)\\b") -> "to do that"
            has("\\b(wi ?fi|wi-fi|bluetooth|brightness|airplane mode|flight mode|hotspot|location settings|gps)\\b") -> "to change that setting"
            radioParse(c) != null -> "to change that setting"
            has("\\b(alarm|wake me|timer)\\b") -> "to set that"
            prefs().getBoolean("unlock_all", true) && !Regex("^(what|whats|who|why|how|when|where|tell|hi|hello|hey|thanks|thank)\\b").containsMatchIn(c) -> "to do that"
            else -> null      // time, date, volume, flashlight, chat and every question: answered right on the lock screen
        }
    }

    private fun smallTalk(c: String): String? {
        fun has(vararg w: String) = w.any { Regex("\\b" + it + "\\b").containsMatchIn(c) }
        val short = c.split(" ").size <= 3
        return when {
            short && has("hi", "hii", "hello", "hey", "namaste") -> "Hello! How can I help you?"
            "how are you" in c -> "I am doing great, thanks for asking! What can I do for you?"
            "your name" in c || "who are you" in c -> "I am $assistantName, your voice assistant."
            has("thanks", "thank you") -> "You are welcome!"
            "good morning" in c -> "Good morning! Have a wonderful day."
            "good night" in c -> "Good night! Sleep well."
            "joke" in c -> jokes.random()
            "i love you" in c -> "Aww, that is sweet! I am always here to help."
            has("bye", "goodbye") -> "Bye! Call me anytime."
            "battery" in c -> {
                val bm = getSystemService(BatteryManager::class.java)
                "Battery is at " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent"
            }
            "what can you do" in c || c == "help" ->
                "I can open any app, call your contacts, send WhatsApp messages, play songs, control the flashlight and volume, lock your screen, and chat with you."
            else -> null
        }
    }

    private fun needsLiveData(c: String) = Regex(
        "\\b(weather|temperature|forecast|news|headlines|score|stock|share price|price of|exchange rate|latest|trending|who won|live)\\b"
    ).containsMatchIn(c)

    /** Every answer comes from Google (Gemini, with Google Search for live facts). No on-phone model. */
    private fun answerFreeText(c: String, addressed: Boolean) {
        if (addressed && liveWanted()) { startLive(c); return }          // locked: talk with Gemini Live instead of typing/unlocking
        val live = needsLiveData(c)
        when {
            geminiReady() -> askGeminiStream(c, live || isPhoneLocked(), false)     // Gemini (+ Google Search), spoken sentence by sentence
            isOnline() -> askWeb(c, false)                                           // no key yet: keyless web answer
            else -> say("I need the internet to answer that. Please connect and ask again.", true)
        }
    }

    private fun langTagOf(s: String): String {
        for (ch in s) {
            if (!ch.isLetter()) continue
            return when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.TELUGU -> "te-IN"
                Character.UnicodeScript.DEVANAGARI -> "hi-IN"
                Character.UnicodeScript.TAMIL -> "ta-IN"
                Character.UnicodeScript.KANNADA -> "kn-IN"
                Character.UnicodeScript.MALAYALAM -> "ml-IN"
                Character.UnicodeScript.BENGALI -> "bn-IN"
                Character.UnicodeScript.GUJARATI -> "gu-IN"
                Character.UnicodeScript.GURMUKHI -> "pa-IN"
                Character.UnicodeScript.ARABIC -> "ur-IN"
                Character.UnicodeScript.LATIN -> "en-IN"
                else -> continue
            }
        }
        return "en-IN"
    }

    private fun finishOnline(q: String, txt: String) {
        aiHistory.add("user" to q)
        aiHistory.add("assistant" to txt)
        while (aiHistory.size > 20) aiHistory.removeAt(0)
        say(txt, true, langTagOf(txt))
    }

    /** Gemini with live Google Search. */
    private fun askGeminiSearch(q: String, fallbackLocal: Boolean) {
        val key = geminiKey()
        thinking = true
        setState("thinking")
        val my = ++aiSeq
        io.execute {
            val txt = try { GeminiBrain.askSearch(key, assistantName, q) } catch (e: Exception) { null }
            handler.post {
                if (my != aiSeq || !running) return@post
                thinking = false
                if (!txt.isNullOrBlank()) finishOnline(q, txt) else askGemini(q, fallbackLocal, false)   // plain Gemini next, then web answer
            }
        }
    }

    /** No key needed: weather, Wikipedia and DuckDuckGo answers. */
    private fun askWeb(q: String, fallbackLocal: Boolean) {
        thinking = true
        setState("thinking")
        val my = ++aiSeq
        io.execute {
            val txt = try { WebAnswer.answer(q) } catch (e: Exception) { null }
            handler.post {
                if (my != aiSeq || !running) return@post
                thinking = false
                if (!txt.isNullOrBlank()) finishOnline(q, txt)
                else say("Sorry, I could not find that online. Try asking in other words, or add a Gemini key in the app for full Google answers.", true)
            }
        }
    }

    private val streamBuf = StringBuilder()
    private var streamSpoken = 0
    private var streamAny = false
    private var streamQuestion = ""
    private val sentenceEnd = Regex("[.!?]\\s|\\n")

    private fun speakChunk(raw: String, lang: String = "en-IN") {
        val msg = raw.replace("*", "").replace("#", "").replace("_", " ").trim()
        if (msg.count { it.isLetter() } < 2 || inCall) return
        val first = !streamAny
        streamAny = true
        lastReply = if (first) msg else lastReply + " " + msg
        notifyUi()
        if (!ttsReady) return
        if (first && !speaking) setSpeakLang(lang)
        val busyNow = speaking
        lastChunkAt = System.currentTimeMillis()
        speaking = true
        setState("speaking")
        ears?.pause()
        recognizer?.cancel()
        listening = false
        handler.removeCallbacks(speakWatchdog)
        val id = "riya_" + (++utteranceCounter)
        currentUtterance = id
        currentFollowUp = true
        val r = tts?.speak(msg, if (first && !busyNow) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            setState("listening")
            startListening()
        } else {
            beginGuard()
            handler.postDelayed(speakWatchdog, 6000L + msg.length * 90L)
        }
    }

    /** Fast path: Gemini streams its answer and Riya speaks each sentence as it arrives. Web answer if Google fails. */
    private fun askGeminiStream(q: String, search: Boolean, fallbackLocal: Boolean) {
        val key = geminiKey()
        thinking = true
        setState("thinking")
        val my = ++aiSeq
        streamAny = false
        lastReply = ""
        val past = aiHistory.takeLast(8).toList()
        val said = StringBuilder()
        io.execute {
            val ok = try {
                GeminiBrain.askStream(key, assistantName, past, q, search) { sentence ->
                    handler.post {
                        if (my != aiSeq || !running) return@post
                        said.append(sentence).append(' ')
                        val lang = langTagOf(sentence)
                        noteLanguage(lang)
                        speakChunk(sentence, lang)
                    }
                }
            } catch (e: Exception) { false }
            handler.post {
                if (my != aiSeq || !running) return@post
                thinking = false
                if (streamAny) {
                    aiHistory.add("user" to q)
                    aiHistory.add("assistant" to said.toString().trim())
                    while (aiHistory.size > 20) aiHistory.removeAt(0)
                } else if (isOnline()) askWeb(q, false)                   // Google was too slow: keyless web answer
                else say("Sorry, I could not reach Google. Please check your internet.", true)
            }
        }
    }

    private fun askGemini(q: String, fallbackLocal: Boolean, allowCommand: Boolean) {
        val key = geminiKey()
        thinking = true
        setState("thinking")
        val my = ++aiSeq
        val past = aiHistory.takeLast(8).toList()
        io.execute {
            val reply = try { GeminiBrain.ask(key, assistantName, past, q) } catch (e: Exception) { null }
            handler.post {
                if (my != aiSeq) return@post        // the user said stop meanwhile
                thinking = false
                if (!running) return@post
                if (reply == null || (reply.text.isBlank() && reply.command.isBlank())) {
                    if (isOnline()) askWeb(q, false)
                    else say("Sorry, I could not reach Google. Please check your internet.", true)
                    return@post
                }
                if (reply.lang.isNotBlank()) noteLanguage(reply.lang)
                if (allowCommand && reply.command.isNotBlank()) {
                    handle(reply.command)           // \"call dwaraka\" said in Telugu
                    return@post
                }
                aiHistory.add("user" to q)
                aiHistory.add("assistant" to reply.text)
                while (aiHistory.size > 20) aiHistory.removeAt(0)
                say(reply.text, true, reply.lang)
            }
        }
    }

    private fun askAi(q: String, fallbackLocal: Boolean = false) {
        val key = prefs().getString("ai_key", "").orEmpty().trim()
        thinking = true
        setState("thinking")
        val past = aiHistory.takeLast(8).toList()
        io.execute {
            val reply = try { callClaude(key, past, q) } catch (e: Exception) { null }
            handler.post {
                thinking = false
                if (!running) return@post
                if (reply.isNullOrBlank()) {
                    say("Sorry, I could not reach my brain. Please check your internet and key.", true)
                } else {
                    aiHistory.add("user" to q)
                    aiHistory.add("assistant" to reply)
                    while (aiHistory.size > 20) aiHistory.removeAt(0)
                    say(reply, true)
                }
            }
        }
    }

    private fun callClaude(key: String, past: List<Pair<String, String>>, q: String): String? {
        val msgs = JSONArray()
        for ((role, text) in past) msgs.put(JSONObject().put("role", role).put("content", text))
        msgs.put(JSONObject().put("role", "user").put("content", q))
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 150)
            .put("system", "You are $assistantName, a friendly voice assistant on the user's phone. " +
                "Reply in one or two short spoken sentences. No markdown, no lists, no emojis.")
            .put("messages", msgs)
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Open ANY app by name ----------

    private data class AppEntry(val label: String, val norm: String, val pkg: String)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val appAliases = mapOf(
        "insta" to "instagram", "fb" to "facebook", "gpay" to "googlepay", "googlepay" to "gpay",
        "yt" to "youtube", "maps" to "maps", "gmail" to "gmail", "mail" to "gmail",
        "settings" to "settings", "setting" to "settings", "whatsapp" to "whatsapp",
        "chrome" to "chrome", "browser" to "chrome", "play store" to "playstore",
        "playstore" to "playstore", "photos" to "photos", "gallery" to "gallery",
        "calculator" to "calculator", "phonepe" to "phonepe", "paytm" to "paytm",
        "telegram" to "telegram", "snapchat" to "snapchat", "snap" to "snapchat",
        "twitter" to "x", "insta gram" to "instagram", "what's app" to "whatsapp"
    )

    @Synchronized
    private fun installedApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        if (appCache.isNotEmpty() && now - appCacheAt < 60000) return appCache
        appCache = loadInstalledApps()
        appCacheAt = now
        return appCache
    }

    private fun loadInstalledApps(): List<AppEntry> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map {
                val label = it.loadLabel(packageManager).toString()
                AppEntry(label, norm(label), it.activityInfo.packageName)
            }
            .filter { it.pkg != packageName && it.norm.isNotEmpty() }
            .distinctBy { it.pkg }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun findApp(spoken: String): AppEntry? {
        val apps = installedApps()
        val rawTarget = norm(spoken)
        if (rawTarget.isEmpty()) return null
        val targets = listOfNotNull(rawTarget, appAliases[spoken.trim()], appAliases[rawTarget]).distinct()

        for (t in targets) {
            apps.firstOrNull { it.norm == t }?.let { return it }
        }
        for (t in targets) {
            apps.filter { it.norm.startsWith(t) }.minByOrNull { it.norm.length }?.let { return it }
        }
        for (t in targets) {
            if (t.length >= 3) apps.filter { it.norm.contains(t) }.minByOrNull { it.norm.length }?.let { return it }
        }
        // speech-recognition slips ("whats up" / "spotifi"): closest spelling wins
        val t = rawTarget
        val best = apps.map { it to editDistance(t, it.norm) }.minByOrNull { it.second }
        if (best != null) {
            val limit = maxOf(1, maxOf(t.length, best.first.norm.length) / 4)
            if (best.second <= limit) return best.first
        }
        // sounds like: "instagrum", "yutube", "whats up" -> the app whose name sounds the same (only if clearly the best)
        if (rawTarget.length >= 4) {
            val ranked = apps.map { it to ContactMatcher.score(spoken, it.label) }.filter { it.second >= 0.85 }.sortedByDescending { it.second }
            if (ranked.isNotEmpty() && (ranked.size == 1 || ranked[0].second - ranked[1].second >= 0.05)) return ranked[0].first
        }
        return null
    }

    private fun openApp(spoken: String, quiet: Boolean = false): Boolean {
        val app = findApp(spoken)
        if (app == null) {
            if (!quiet) say("I could not find an app named $spoken")
            return false
        }
        val intent = packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent != null && launch(intent)) {
            say("Opening ${app.label}")
            return true
        }
        if (!quiet) say("I could not open ${app.label}")
        return false
    }

    // ---------- Contacts: search + ask whom ----------

    private fun queryContacts(like: String): List<Contact> {
        val result = LinkedHashMap<String, Contact>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$like%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                val key = num.filter { it.isDigit() }.takeLast(10)
                if (key.isNotEmpty() && !result.containsKey(key)) result[key] = Contact(n, num)
            }
        }
        return result.values.toList()
    }

    private fun findContacts(name: String): List<Contact> {
        var r = queryContacts(name)
        if (r.isNotEmpty()) {
            // "rama" also means Ramya Krishna: add contacts whose name sounds the same, so every candidate is offered
            val seen = r.map { it.number.filter { d -> d.isDigit() }.takeLast(10) }.toMutableSet()
            val extra = try { fuzzyContacts(name) } catch (e: Exception) { emptyList() }
            r = r + extra.filter { seen.add(it.number.filter { d -> d.isDigit() }.takeLast(10)) }
            return r
        }
        if (r.isEmpty()) {
            // "vinod kumar" not saved that way? try the individual words
            for (w in name.split(" ").filter { it.length >= 3 }.sortedByDescending { it.length }) {
                r = queryContacts(w)
                if (r.isNotEmpty()) break
            }
        }
        if (r.isEmpty()) r = fuzzyContacts(name)   // misheard name: match by sound
        return r
    }

    // Compare how names SOUND ("the worker" / "dwarka" -> Dwaraka)
    private fun fuzzyContacts(spoken: String): List<Contact> {
        val all = queryContacts("")
        if (all.isEmpty()) return all
        val names = all.map { it.name }
        // the phrase as heard + every other guess of the engine, with "call to" stripped off
        val callWord = Regex("^.*?\\b(call|phone|dial)( to)?\\b")
        val guesses = (listOf(spoken) + heardAlts.map { it.replace(callWord, "").trim() })
            .filter { it.isNotBlank() }.distinct()
        var bestSet: Set<String> = emptySet()
        var bestScore = 0.0
        for (g in guesses) {
            val r = ContactMatcher.rank(g, names)
            if (r.isNotEmpty() && r[0].second > bestScore) { bestScore = r[0].second; bestSet = r.map { it.first }.toSet() }
        }
        return all.filter { it.name in bestSet }
    }

    // Contact names, cached, used to teach the Google recogniser the names you say
    @Volatile private var nameCache: List<String> = emptyList()
    @Volatile private var nameCacheAt = 0L
    private fun contactNames(): ArrayList<String> {
        try {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ArrayList()
        } catch (e: Throwable) { return ArrayList() }
        if (System.currentTimeMillis() - nameCacheAt > 300_000 || nameCache.isEmpty()) {
            try {
                val set = LinkedHashSet<String>()
                contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use {
                    while (it.moveToNext() && set.size < 1000) it.getString(0)?.let { n -> set.add(n) }
                }
                nameCache = set.toList(); nameCacheAt = System.currentTimeMillis()
            } catch (e: Exception) { }
        }
        return ArrayList(nameCache)
    }

    private fun pickContact(name: String, what: String = "", action: (Contact) -> Unit) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("Please allow contacts permission in the Riya app"); return
        }
        val list = findContacts(name)
        when {
            list.isEmpty() -> say("I could not find $name in your contacts")
            list.size == 1 -> action(list[0])
            else -> {
                val shown = list.take(6)
                pendingContacts = shown
                pendingAction = action
                pendingMisses = 0
                armPending()
                val options = shown.mapIndexed { i, ct -> "${i + 1}, ${labelFor(ct, shown)}" }.joinToString(". ")
                val ask = if (what.isEmpty()) "Say the number." else "Say the number to $what."
                say("I found ${list.size} contacts. $options. $ask")
            }
        }
    }

    private fun labelFor(c: Contact, all: List<Contact>): String =
        if (all.count { it.name == c.name } > 1)
            "${c.name}, number ending ${c.number.filter { it.isDigit() }.takeLast(2)}"
        else c.name

    // Clear number words first; sound-alikes only if nothing clear was heard
    private val strictNumbers = mapOf(
        "one" to 1, "first" to 1, "two" to 2, "second" to 2, "three" to 3, "third" to 3,
        "four" to 4, "fourth" to 4, "five" to 5, "fifth" to 5, "six" to 6, "sixth" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9
    )
    private val soundAlikes = mapOf(
        "won" to 1, "wan" to 1, "to" to 2, "too" to 2, "tu" to 2, "tree" to 3, "free" to 3, "tri" to 3, "thri" to 3,
        "for" to 4, "fore" to 4, "far" to 4, "fife" to 5, "hive" to 5, "sex" to 6, "ate" to 8
    )

    private fun parseChoice(text: String, size: Int, strictOnly: Boolean = false): Int? {
        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if ("last" in tokens) return size
        for (t in tokens) {
            val digits = Regex("^(\\d+)(st|nd|rd|th)?$").find(t)?.groupValues?.get(1)?.toIntOrNull()
            val n = digits ?: strictNumbers[t]
            if (n != null && n in 1..size) return n
        }
        if (strictOnly) return null
        for (t in tokens) {
            val n = soundAlikes[t]
            if (n != null && n in 1..size) return n
        }
        return null
    }

    private val choiceStopWords = setOf("call", "the", "please", "number", "want", "i", "to", "for", "a", "and", "of", "that", "this", "one", "ok", "okay")

    private fun handleChoice(heard: List<String>, list: List<Contact>) {
        if (heard.any { Regex("\\b(cancel|nobody|none|never mind|stop)\\b").containsMatchIn(it) }) {
            clearPending.run(); handler.removeCallbacks(clearPending)
            say("Okay, cancelled")
            return
        }
        var chosen: Contact? = null
        for (text in heard) {
            val idx = parseChoice(text, list.size)
            if (idx != null) { chosen = list[idx - 1]; break }
        }
        if (chosen == null) {
            for (text in heard) {
                val words = text.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in choiceStopWords }
                if (words.isEmpty()) continue
                val matches = list.filter { c -> words.all { c.name.lowercase().contains(it) } }
                if (matches.size == 1) { chosen = matches[0]; break }
            }
        }
        if (chosen != null) {
            val action = pendingAction
            clearPending.run(); handler.removeCallbacks(clearPending)
            action?.invoke(chosen)
        } else {
            armPending()
            say("Sorry, say the number, like one, two or three")
        }
    }

    // ---------- Call ----------

    private fun dial(c: Contact) {
        say("Calling ${c.name}")
        val uri = Uri.fromParts("tel", c.number, null)
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                // TelecomManager places the call even when the phone is locked
                getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                return
            } catch (e: Exception) { /* fall through */ }
            if (launch(Intent(Intent.ACTION_CALL, uri))) return
        }
        launch(Intent(Intent.ACTION_DIAL, uri))
    }

    // ---------- WhatsApp ----------

    private fun whatsapp(c: String) {
        val cleaned = c.replace(Regex("^(send )?(a )?(whatsapp )?(message )?(on whatsapp )?(to )?"), "").trim()
        val m = Regex("^(.+?) (?:saying|that|message|as) (.+)$").find(cleaned)
        if (m == null) {
            say("Say it like: Riya send WhatsApp to Vinod saying hello")
            return
        }
        val name = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        val biz = waBiz
        pickContact(name, "send the message") { contact ->
            var digits = contact.number.filter { it.isDigit() }
            if (digits.length == 10) digits = defaultCountryCode + digits
            say("Sending WhatsApp to ${contact.name}")
            RiyaAccessibility.arm()
            val url = "https://wa.me/$digits?text=" + Uri.encode(text)
            val wi = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            waPkg(biz)?.let { wi.setPackage(it) }
            launch(wi)
        }
    }

    // ---------- Flashlight ----------

    private fun flashlight(c: String) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) { say("No flashlight found"); return }
        torchOn = if ("off" in c) false else if ("on" in c) true else !torchOn
        try {
            cm.setTorchMode(id, torchOn)
            say(if (torchOn) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) { say("Could not control the flashlight") }
    }

    // ---------- Offline phone controls ----------

    private val numWords = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val tensWords = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    /** Pulls numbers out of speech, both "7 30" and "seven thirty" / "twenty five". */
    private fun parseNums(text: String): List<Int> {
        val out = ArrayList<Int>()
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val digits = t.toIntOrNull()
            val tens = tensWords[t]
            val unit = numWords[t]
            if (digits != null) {
                out.add(digits)
            } else if (tens != null) {
                var v: Int = tens
                if (i + 1 < tokens.size) {
                    val u = numWords[tokens[i + 1]]
                    if (u != null && u in 1..9) { v += u; i++ }
                }
                out.add(v)
            } else if (unit != null && t != "oh") {
                out.add(unit)
            } else if (t == "oh") {
                out.add(0)
            }
            i++
        }
        return out
    }

    private fun isOnline(): Boolean = NetQuality.level(this) != NetQuality.Level.NONE

    private fun sysAction(action: Int, doneMsg: String) {
        val acc = RiyaAccessibility.instance
        if (acc == null) {
            say("Please turn on Riya in Accessibility settings first.", true)
            return
        }
        acc.performGlobalAction(action)
        say(doneMsg)
    }

    // ---------- Pay by voice: PhonePe / Google Pay / Paytm / BHIM (stops at the UPI PIN, you type the PIN) ----------
    private class UpiApp(val pkg: String, val label: String)
    private val upiApps = mapOf(
        "paytm" to UpiApp("net.one97.paytm", "Paytm"),
        "phonepe" to UpiApp("com.phonepe.app", "PhonePe"),
        "gpay" to UpiApp("com.google.android.apps.nbu.paisa.user", "Google Pay"),
        "bhim" to UpiApp("in.org.npci.upiapp", "BHIM")
    )
    private val payExec = Executors.newSingleThreadExecutor()
    @Volatile private var payAbort = false

    private fun upiKey(s: String): String? {
        val n = s.lowercase().replace(Regex("[^a-z]"), "")
        return when {
            n.contains("paytm") || n.contains("paytem") || n.contains("paytn") ||
                Regex("^pay(t|te|tea|tee|tem|tim|tm|tame|team|teem)m?$").matches(n) -> "paytm"      // "pay team", "pay t m" ...
            n.contains("phonepe") || n.contains("phonepay") || n.contains("fonepe") || n.contains("phonepea") ||
                n.contains("fonepay") || n.contains("phonepi") || n == "phonep" -> "phonepe"
            n.contains("gpay") || n.contains("googlepay") || n.contains("googlepe") || n.contains("gepay") ||
                n.contains("jeepay") || n.contains("geepay") || n.contains("googlepai") || n == "gpe" -> "gpay"
            n.contains("bhim") || n.contains("bheem") || n == "beam" || n == "bim" -> "bhim"
            else -> null
        }
    }

    private val amtUnits = setOf("hundred", "thousand", "lakh", "lakhs", "k")
    private fun isAmtToken(t: String) =
        t.matches(Regex("\\d[\\d,.]*k?")) || numWords.containsKey(t) || tensWords.containsKey(t) || t in amtUnits

    /** "5k", "5000", "five thousand", "two thousand five hundred" -> number. */
    private fun parseAmount(text: String): Int? {
        val t = text.lowercase().replace(",", "")
        Regex("(\\d+(?:\\.\\d+)?)\\s*(thousand|hundred|lakhs?|k)\\b").find(t)?.let {
            val mul = when (it.groupValues[2]) { "hundred" -> 100.0; "thousand", "k" -> 1000.0; else -> 100000.0 }
            return (it.groupValues[1].toDouble() * mul).toInt()
        }
        Regex("\\d+").find(t)?.let { return it.value.toInt() }
        var total = 0
        var cur = 0
        var any = false
        var prevUnit = false         // "two fifty" is said for 250, "seven fifty" for 750
        for (w in t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }) {
            val u = numWords[w]
            val tn = tensWords[w]
            val unitNow = u != null && w != "oh" && u in 1..9
            when {
                tn != null -> { cur = if (prevUnit && cur in 1..9) cur * 100 + tn else cur + tn; any = true }
                u != null && w != "oh" -> { cur += u; any = true }
                w == "hundred" -> { cur = maxOf(cur, 1) * 100; any = true }
                w == "thousand" -> { total += maxOf(cur, 1) * 1000; cur = 0; any = true }
                w == "lakh" || w == "lakhs" -> { total += maxOf(cur, 1) * 100000; cur = 0; any = true }
            }
            prevUnit = unitNow
        }
        return if (any) total + cur else null
    }

    private class PayCmd(val app: String, val name: String, val amount: Int)

    /** "open paytm send vinod 5000" / "send 5000 to vinod on phonepe" / "gpay pay vinod 2000 rupees". */
    private fun payParse(c0: String): PayCmd? {
        // "\u20b9500", "500 rs", "rs. 500", "500/-" -> "500"
        val c = c0.replace("\u20b9", " ")
            .replace(Regex("(\\d)\\s*(?:/-|rs\\.?|rupees?|inr)(?=\\s|$)"), "$1")
            .replace(Regex("\\b(?:rs\\.?|inr)\\s*(?=\\d)"), "")
            .replace(Regex("\\s+"), " ").trim()
        var appKey: String? = null
        var rest: String? = null
        // try every "send / pay / transfer" word as the split, so "open Google Pay send 500 to Vinod" and "Phone Pay" work too
        val verbRe = Regex("\\s+(?:and |then )?(?:send|pay|transfer)\\s+")
        var from = 0
        while (appKey == null) {
            val vm = verbRe.find(c, from) ?: break
            val before = c.substring(0, vm.range.first).replace(Regex("^(?:open |launch |start )"), "")
            val k = upiKey(before)
            if (k != null) { appKey = k; rest = c.substring(vm.range.last + 1) }
            from = vm.range.first + 1
        }
        if (appKey == null) {
            Regex("^(?:send|pay|transfer)\\s+(.+?)\\s+(?:on|in|using|via|through|with)\\s+(.+)$").find(c)?.let {
                val k = upiKey(it.groupValues[2])
                if (k != null) { appKey = k; rest = it.groupValues[1] }
            }
        }
        val a = appKey ?: return null
        val tokens = (rest ?: return null).split(" ").filter { it.isNotEmpty() && it !in setOf("to", "rupees", "rupee", "rs", "for", "rs.", "and") || it == "and" }
            .filter { it !in setOf("to", "rupees", "rupee", "rs", "for", "rs.", "please", "now", "only", "amount", "of") }
        // the amount is the run of number words at the end (or at the start) of what remains
        var s0 = tokens.size
        while (s0 > 0 && isAmtToken(tokens[s0 - 1])) s0--
        val name: String
        val amtText: String
        if (s0 < tokens.size) {
            name = tokens.subList(0, s0).joinToString(" ")
            amtText = tokens.subList(s0, tokens.size).joinToString(" ")
        } else {
            var e0 = 0
            while (e0 < tokens.size && isAmtToken(tokens[e0])) e0++
            if (e0 == 0) return null
            amtText = tokens.subList(0, e0).joinToString(" ")
            name = tokens.subList(e0, tokens.size).joinToString(" ")
        }
        val amount = parseAmount(amtText) ?: return null
        if (name.isBlank()) return null
        return PayCmd(a, name.trim(), amount)
    }

    private fun startPay(p: PayCmd) {
        val app = upiApps[p.app] ?: return
        if (!pkgInstalled(app.pkg)) { say("${app.label} is not installed on this phone."); return }
        if (RiyaAccessibility.instance == null) { say("Please turn on Riya in Accessibility settings first, so I can fill the payment."); return }
        if (p.amount <= 0 || p.amount > 100000) { say("I heard ${p.amount} rupees. That is not a valid amount. Please say it again."); return }
        pickContact(p.name, "pay") { ct -> confirmPay(app, ct, p.amount) }
    }

    private val payYesRe = Regex("^(yes|yeah|yep|yup|ya|haa|haan|han|ha|ok|okay|confirm|proceed|do it|go ahead|correct|sure|right|avunu|sare)\\b")
    private val payNoRe = Regex("\\b(no|nope|cancel|stop|dont|don'?t|wait|never ?mind)\\b")

    private fun confirmPay(app: UpiApp, c: Contact, amount: Int, tries: Int = 0) {
        pendingFreeText = { ans ->
            val alts = (listOf(ans) + heardAlts).map { it.lowercase().trim(' ', '.', ',', '!') }
            when {
                alts.any { payYesRe.containsMatchIn(it) } -> runPay(app, c, amount)
                alts.any { payNoRe.containsMatchIn(it) } || tries >= 2 -> say("Payment cancelled.")
                else -> confirmPay(app, c, amount, tries + 1)      // noise or something else: ask again, at most twice
            }
        }
        freeUntil = System.currentTimeMillis() + 25000L
        say("Pay $amount rupees to ${c.name} using ${app.label}. Say yes to confirm.")
    }

    private val pinWords = listOf("enter upi pin", "enter your upi pin", "enter 4-digit upi pin", "enter 6-digit upi pin", "enter 4 digit upi pin", "enter 6 digit upi pin")
    private val homeKeys = listOf("pay contacts", "pay contact", "to contact", "pay to contact", "to mobile number", "pay phone number",
        "pay anyone", "new payment", "send money", "pay by name", "mobile number or contact", "contact or number",
        "select contact", "pay mobile", "to upi", "search")

    private fun runPay(app: UpiApp, c: Contact, amount: Int) {
        payAbort = false
        lastHint = ""
        lastReply = ""
        handler.post {
            val i = packageManager.getLaunchIntentForPackage(app.pkg)
            if (i != null) launch(i)
        }
        payExec.execute {
            val acc = RiyaAccessibility.instance ?: return@execute
            val digits = c.number.filter { it.isDigit() }.takeLast(10)
            val first = c.name.lowercase().trim().split(" ").first().filter { it.isLetterOrDigit() }
            val amt = amount.toString()
            var searched = false
            var picked = false
            var amountSet = false
            var payClicks = 0
            var blank = 0
            var searchTicks = 0
            var finished = false
            var aborted = false
            var appPinTries = 0
            val appPin: String? = if (unlockAllowed()) PinVault.load(this) else null     // for the payment app's own lock only
            val end = System.currentTimeMillis() + 70000L
            try { Thread.sleep(2500) } catch (e: InterruptedException) { return@execute }
            while (System.currentTimeMillis() < end && running && !payAbort) {
                try { Thread.sleep(800) } catch (e: InterruptedException) { return@execute }
                if (acc.screenHas(pinWords)) { finished = true; break }          // PIN screen: stop here, never touch it
                // the payment app asks to be unlocked (app PIN / screen lock): type the saved phone PIN, at most twice.
                // A screen that mentions UPI, a bank or an amount is never treated as a lock screen.
                if (appPin != null && appPinTries < 2 && acc.onAppLockScreen()) {
                    appPinTries++
                    acc.typeAppPin(appPin)
                    continue
                }
                val nodes = acc.allNodes().size
                if (payClicks > 0 && nodes < 4) { if (++blank >= 3) { finished = true; break } } else blank = 0
                if (!amountSet) {
                    val ed = acc.editableNode(listOf("amount"))
                    val onAmount = ed != null || (picked && acc.screenHas(listOf("\u20b9")) && acc.editableNode(emptyList()) != null)
                    if (onAmount) {
                        val target = ed ?: acc.editableNode(emptyList())
                        val whoOk = acc.screenHas(listOf(first)) || (digits.length >= 4 && acc.screenHas(listOf(digits.takeLast(4))))
                        if (!whoOk) { aborted = true; break }                    // not sure who is being paid: stop
                        if (target != null && acc.setText(target, amt)) { amountSet = true; picked = true }
                        continue
                    }
                }
                if (amountSet) {
                    if (payClicks < 4) {
                        val ok = acc.clickLabel(listOf("proceed to pay", "pay \u20b9$amt", "pay rs $amt", "send \u20b9$amt", "proceed", "pay", "send", "next", "continue"), true) ||
                            acc.clickLabel(listOf("pay \u20b9", "proceed to pay"), false)
                        if (ok) payClicks++
                    }
                    continue
                }
                if (!picked) {
                    val ed = acc.editableNode(listOf("search", "name", "number", "contact", "phone", "mobile", "upi"))
                    if (ed != null) {
                        if (!searched) {
                            if (acc.setText(ed, digits)) searched = true
                        } else {
                            searchTicks++
                            if (acc.clickLabel(listOf(first), false) || (digits.length >= 4 && acc.clickLabel(listOf(digits.takeLast(4)), false))) picked = true
                            else if (searchTicks >= 4) acc.clickLabel(listOf("proceed", "next", "continue"), true)
                        }
                        continue
                    }
                    acc.clickLabel(homeKeys, false)
                }
            }
            handler.post {
                when {
                    payAbort -> { }
                    finished -> { tick(60); say("Please enter your UPI PIN.") }
                    aborted -> say("I could not confirm who the payment is for on screen, so I stopped. Please check and continue yourself.")
                    else -> {
                        lastHint = "Payment steps stopped. Screen: " + acc.screenLabels(14).joinToString(" | ")
                        notifyUi()
                        say("I could not complete the payment steps. Please finish it on screen.")
                    }
                }
            }
        }
    }

    // ---------- Music by voice: stop / start in ANY music app (Spotify, JioSaavn, PLAYit, YouTube Music ...) ----------
    private val musicStopRe = Regex(
        "^(?:stop|pause|turn off|switch off|shut off|kill|quiet)(?: playing)?(?: the| this| that| my)? ?(?:music|songs?|playing|playback|audio|spotify|saavn|playit)(?: please| now)?$" +
        "|^(?:music|songs?) (?:stop|off|pause)$"
    )
    private val musicStartRe = Regex(
        "^(?:please )?(?:start|begin|resume|continue|play|turn on|put on)(?: playing)?(?: some| my| the| a| any)?(?: music| songs?| playlist)?" +
        "(?: please| now)?(?: (?:on|in|using|with) (.+))?$"
    )
    private val musicLabels = listOf("spotify", "jiosaavn", "youtubemusic", "gaana", "wynk", "amazonmusic", "playit", "resso", "applemusic", "hungama", "samsungmusic", "musicplayer", "music")
    private val musicNextRe = Regex(
        "^(?:please )?(?:play |go to |skip to |switch to |change to )?(?:the |a )?next (?:song|songs|track)(?: please| now)?$" +
        "|^skip (?:the |this )?(?:song|track)(?: please| now)?$"
    )
    private val musicPrevRe = Regex(
        "^(?:please )?(?:play |go to |go back to |skip to )?(?:the )?(?:previous|last|earlier) (?:song|track)(?: please| now)?$" +
        "|^(?:go )?back (?:one )?(?:song|track)$"
    )
    @Volatile private var musicHitAt = 0L

    /** "stop" or "start" plus the app named in the sentence (blank = automatic). */
    private fun musicParse(c: String): Pair<String, String>? {
        if (musicStopRe.matches(c)) return "stop" to ""
        if (musicNextRe.matches(c)) return "next" to ""
        if (musicPrevRe.matches(c)) return "previous" to ""
        val m = musicStartRe.matchEntire(c) ?: return null
        val app = m.groupValues[1].trim()
        if (app.isEmpty() && !Regex("\\b(music|songs?|playlist)\\b").containsMatchIn(c)) return null   // bare "play" stays a video command
        return "start" to app
    }

    /** The music app to use: the one named, else the one chosen in the app, else Spotify, then the other known players. */
    private fun musicApp(named: String): AppEntry? {
        val apps = installedApps()
        if (named.isNotBlank()) {
            var n = norm(named).replace("savan", "saavn").replace("saavan", "saavn")
            if (n == "saavn" || n == "jiosaavn" || n == "jio") n = "jiosaavn"
            if (n == "ytmusic" || n == "youtubemusic") n = "youtubemusic"
            apps.firstOrNull { it.norm == n }?.let { return it }
            apps.firstOrNull { n.length >= 4 && it.norm.length >= 4 && (it.norm.contains(n) || n.contains(it.norm)) }?.let { return it }
            return null
        }
        val pref = prefs().getString("music_app", "").orEmpty()
        if (pref.isNotEmpty()) apps.firstOrNull { it.pkg == pref }?.let { return it }
        for (l in musicLabels) apps.firstOrNull { it.norm.contains(l) }?.let { return it }
        return null
    }

    /** Silent (small buzz): pauses whatever is playing, in any app. */
    private fun pauseMusic() {
        lastHint = ""
        lastReply = ""
        val acc = RiyaAccessibility.instance
        val locked = isPhoneLocked()
        typeExec.execute {
            val am = getSystemService(AudioManager::class.java)
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            Thread.sleep(450)
            if (am.isMusicActive) { mediaKey(KeyEvent.KEYCODE_MEDIA_STOP); Thread.sleep(450) }
            if (am.isMusicActive) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); Thread.sleep(450) }
            if (am.isMusicActive && acc != null && !locked) acc.clickLabel(listOf("pause"), true)
            handler.post { tick(40); lastReply = if (am.isMusicActive) "" else "Music paused"; notifyUi() }
        }
    }

    /** Next / previous song in any music app. If music was paused it starts playing; if no app is open it opens one. */
    private fun musicSkip(next: Boolean) {
        lastHint = ""
        lastReply = ""
        typeExec.execute {
            val am = getSystemService(AudioManager::class.java)
            if (next) mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            else {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)      // first press restarts the song if it already played a few seconds,
                Thread.sleep(350)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)      // second press goes to the real previous song
            }
            Thread.sleep(700)
            if (!am.isMusicActive) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); Thread.sleep(800) }
            handler.post {
                if (am.isMusicActive) tick(30) else startMusic("")      // nothing was open: open the music app and play
            }
        }
    }

    /** Resumes music, or opens the music app (Spotify by default) and starts playing by itself. */
    private fun startMusic(named: String) {
        lastHint = ""
        lastReply = ""
        val acc = RiyaAccessibility.instance
        typeExec.execute {
            val am = getSystemService(AudioManager::class.java)
            if (named.isBlank()) {
                if (!am.isMusicActive) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); Thread.sleep(800) }   // something was paused: resume it
                if (am.isMusicActive) { handler.post { tick(30) }; return@execute }
            } else if (am.isMusicActive) { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); Thread.sleep(300) }
            val app = musicApp(named)
            if (app == null) {
                handler.post { say(if (named.isBlank()) "I could not find a music app on this phone." else "I could not find $named on this phone.", true) }
                return@execute
            }
            // 1) empty search = "just play something": most players start straight away; 2) plain open
            val s = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(app.pkg)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, "")
            }
            var opened = launch(s)
            if (!opened) { val i = packageManager.getLaunchIntentForPackage(app.pkg); opened = i != null && launch(i) }
            if (!opened) { handler.post { say("I could not open " + app.label, true) }; return@execute }
            Thread.sleep(2500)
            for (attempt in 0..2) {
                if (am.isMusicActive) break
                when (attempt) {
                    1 -> if (acc != null) acc.clickLabel(listOf("play", "shuffle play", "play all", "resume"), true)
                    else -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
                Thread.sleep(1500)
            }
            handler.post {
                if (am.isMusicActive) tick(30)
                else say("I opened " + app.label + ". If music did not start, tap play once.", true)
            }
        }
    }

    /** Music guard heard "stop music" / "next song". With Voice Match on, only your voice is accepted. */
    @Volatile private var mediaNameAt = 0L
    @Volatile private var duckedFrom = -1
    private val unduckRun = object : Runnable {
        override fun run() {
            if (speaking || thinking) { handler.postDelayed(this, 1500L); return }
            restoreDuck()
        }
    }

    /** Lowers the video / music volume so you can be heard and understood; restoreDuck() brings it back. */
    private fun duckMedia() {
        try {
            val am = getSystemService(AudioManager::class.java)
            if (duckedFrom < 0) {
                val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (v <= 2) return
                duckedFrom = v
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxOf(1, v / 5), 0)
            }
            handler.removeCallbacks(unduckRun)
            handler.postDelayed(unduckRun, 7000L)
        } catch (e: Exception) { }
    }

    private fun restoreDuck() {
        handler.removeCallbacks(unduckRun)
        val v = duckedFrom
        if (v < 0) return
        duckedFrom = -1
        try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (e: Exception) { }
    }

    /** Bare "next" / "previous" (no name) may act when: a song word was said, a reels app is in front, or a media command was just given. Call off the main thread. */
    private fun bareMediaAllowed(song: Boolean): Boolean {
        if (song) return true
        if (System.currentTimeMillis() < mediaUntil) return true
        if (!prefs().getBoolean("bare_media", true)) return false
        return try { RiyaAccessibility.instance?.looksLikeReels() == true } catch (e: Exception) { false }
    }

    /** The tiny media-word recogniser heard her name or "next / previous / stop music / play next song" - works even when a video is loud. */
    private fun mediaGuardHit(h: MediaWords.Hit) {
        if (!running || speaking || typing || enrolling || inCall || !prefs().getBoolean("music_guard", true)) return
        val now = System.currentTimeMillis()
        if (h.kind == "name") {
            // only her name so far: wait a moment; if no command follows, lower the sound so she can hear the rest and answer
            if (now - mediaNameAt < 4000L) return
            mediaNameAt = now
            tick(20)
            handler.postDelayed({ if (running && !speaking && partialHitAt < now) duckMedia() }, 650L)
            return
        }
        if (now - partialHitAt < 1800L) return
        val run = {
            partialHitAt = System.currentTimeMillis()
            musicHitAt = partialHitAt
            ears?.flushNow = true
            conversationUntil = 0
            when (h.kind) {
                "stop" -> pauseMusic()
                "next" -> if (h.song) musicSkip(true) else mediaControl("next")
                "previous" -> if (h.song) musicSkip(false) else mediaControl("previous")
                "pause" -> mediaControl("pause")
                "play" -> mediaControl("play")
                else -> { }
            }
        }
        val e = ears
        typeExec.execute {
            val ok = h.named || h.kind == "stop" || bareMediaAllowed(h.song)
            if (!ok) return@execute
            handler.post {
                if (voiceLockOn() && e?.speakerReady == true) {
                    handler.postDelayed({ if (running && isMyVoice(e.lastSpk, e.lastSpkFrames, true)) run() }, 900L)
                } else run()
            }
        }
    }

    // ---------- Videos / reels playing: never interrupt them, never listen to them ----------
    private val mediaRe = Regex(
        "^(next|next (reel|video|short|shorts|one|song)|skip( this| it)?|swipe up|next (one )?please|" +
        "previous|previous (reel|video|short|shorts|one|song)|last (one|reel|video)|go to previous|swipe down|" +
        "pause|pause (it|video|reel|short|song|music|playing)|hold on|freeze|" +
        "play|resume|continue|unpause|play (it|video|reel|short)|start playing|" +
        "mute|unmute|mute (it|video|reel)|unmute (it|video|reel))$"
    )
    private val nextRe = Regex("^(next|next .*|skip.*|swipe up)$")
    private val prevRe = Regex("^(previous.*|last .*|go to previous|swipe down)$")
    private val pauseRe = Regex("^(pause.*|hold on|freeze)$")
    private val playRe = Regex("^(play|resume|continue|unpause|play (it|video|reel|short)|start playing)$")
    @Volatile private var mediaUntil = 0L        // after one video command, bare "next" / "previous" work without my name for a while
    @Volatile private var volBeforeMute = 0
    private var switchedForMedia = false

    /** Speech engines mishear the short words: "nest" -> next, "paws" -> pause, "mood" -> mute ... Used only if the result is a real media command. */
    private fun fixMediaWord(c: String): String {
        if (mediaRe.matches(c)) return c
        val map = mapOf("nest" to "next", "necks" to "next", "text" to "next", "nex" to "next", "nexed" to "next", "nexts" to "next",
            "nextone" to "next one", "next1" to "next one", "mex" to "next",
            "previews" to "previous", "previus" to "previous", "privious" to "previous", "pervious" to "previous", "previou" to "previous",
            "paws" to "pause", "pores" to "pause", "pass" to "pause", "paused" to "pause", "pose" to "pause", "paus" to "pause",
            "mood" to "mute", "moot" to "mute", "mutes" to "mute", "muted" to "mute", "meet" to "mute",
            "won" to "one", "wan" to "one", "on" to "one", "when" to "one", "1" to "one",
            "reels" to "reel", "videos" to "video", "shorts" to "short")
        var w = c.trim().split(" ").filter { it.isNotEmpty() }.map { it.trim('.', ',', '!', '?', ';', ':') }
            .flatMap { (map[it] ?: it).split(" ") }
        w = w.filter { it.isNotEmpty() && it != "please" && it != "now" }
        val joined = w.joinToString(" ").replace("un mute", "unmute").replace("go back", "previous").replace("back one", "previous one")
        return if (mediaRe.matches(joined)) joined else c
    }
    private var mediaQuietSince = 0L

    private fun isMediaPlaying(): Boolean = try {
        !speaking && getSystemService(AudioManager::class.java).isMusicActive
    } catch (e: Exception) { false }

    private fun mediaKey(code: Int) {
        try {
            val am = getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) { }
    }

    /** Silent (a small buzz): next / previous reel, pause, play, mute. */
    private fun mediaControl(c0: String) {
        restoreDuck()
        val c = fixMediaWord(c0)
        mediaUntil = System.currentTimeMillis() + 25000L
        lastHint = ""
        lastReply = ""
        val acc = RiyaAccessibility.instance
        val locked = isPhoneLocked()
        if (acc == null && !locked) { lastHint = "To press next / pause inside Reels and Shorts, switch Riya on in Accessibility."; notifyUi() }
        typeExec.execute {
            val am = getSystemService(AudioManager::class.java)
            var how = ""
            when {
                c.startsWith("unmute") -> try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, if (volBeforeMute > 0) volBeforeMute else max / 2, 0)
                    }
                } catch (e: Exception) { }
                c.startsWith("mute") -> try {
                    val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (v > 0) volBeforeMute = v
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                    if (!am.isStreamMute(AudioManager.STREAM_MUSIC) && am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)       // phones that ignore ADJUST_MUTE
                } catch (e: Exception) { }
                nextRe.matches(c) || prevRe.matches(c) -> {
                    val up = nextRe.matches(c)
                    val word = if (up) "Next" else "Previous"
                    if (acc != null && !locked && acc.looksLikeReels()) { acc.swipe(up); how = "$word: swiped the reel" }
                    else if (acc != null && !locked && acc.foregroundPkg() == "com.google.android.youtube") {
                        if (acc.clickLabel(listOf(if (up) "next video" else "previous video"), false)) how = "$word: pressed the player button"
                        else { acc.swipe(up); how = "$word: swiped (YouTube Shorts)" }
                    }
                    else {
                        mediaKey(if (up) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        how = "$word: sent the media key" + (if (acc == null) " (Accessibility is OFF, so reels cannot be swiped)" else "")
                    }
                }
                pauseRe.matches(c) -> {
                    mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    Thread.sleep(450)
                    if (am.isMusicActive && acc != null && !locked) acc.tapCenter()      // Instagram reels ignore media keys
                }
                playRe.matches(c) -> {
                    mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    Thread.sleep(450)
                    if (!am.isMusicActive && acc != null && !locked) acc.tapCenter()
                }
            }
            handler.post { tick(25); if (how.isNotEmpty()) { lastHint = how; notifyUi() } }
        }
    }

    private val mediaWatch = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val am0 = getSystemService(AudioManager::class.java)
                if (am0.mode == AudioManager.MODE_IN_COMMUNICATION) { if (!inCall) enterCallMode(true) }     // WhatsApp / Meet call
                else if (inCall && voipCall) exitCallMode()
                if (inCall) { ears?.musicMode = false; handler.postDelayed(this, 1500L); return }
                val playing = isMediaPlaying()
                ears?.musicMode = playing && prefs().getBoolean("music_guard", true)
                if (playing) mediaQuietSince = 0L
                if (playing && !useVosk && !speaking && !thinking && !switchedForScreen) {
                    // Google's recogniser takes audio focus and pauses videos; the offline mic does not
                    handler.removeCallbacks(listenWatchdog)
                    try { recognizer?.cancel() } catch (e: Exception) { }
                    resetRecognizer()
                    switchedForMedia = true
                    useVosk = true
                    engineInfo = "Hearing: offline (video playing, so it is not interrupted)"
                    startOfflineEars()
                } else if (!playing && switchedForMedia && !speaking) {
                    if (mediaQuietSince == 0L) mediaQuietSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - mediaQuietSince > 6000L && !isPhoneLocked()) {
                        switchedForMedia = false
                        ears?.stop()
                        ears = null
                        useVosk = wantVosk()
                        if (useVosk) startOfflineEars() else { engineInfo = "Hearing: Google speech"; startListening() }
                    }
                }
            } catch (e: Exception) { }
            handler.postDelayed(this, 2000L)
        }
    }

    // ---------- Screen off / locked: Google speech goes quiet, so use the offline mic then ----------
    private var screenReceiver: BroadcastReceiver? = null
    private var switchedForScreen = false

    private fun registerScreenWatcher() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_SCREEN_OFF -> handler.post { onScreenOff() }
                        Intent.ACTION_USER_PRESENT -> handler.post { onScreenOn() }
                        Intent.ACTION_SCREEN_ON -> handler.post { if (isPhoneLocked()) onScreenOff() }
                    }
                }
            }
            val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            screenReceiver = r
        } catch (e: Exception) { }
    }

    private fun onScreenOff() {
        if (!running || useVosk) return
        handler.removeCallbacks(listenWatchdog)
        try { recognizer?.cancel() } catch (e: Exception) { }
        resetRecognizer()
        switchedForScreen = true
        useVosk = true
        engineInfo = "Hearing: offline (screen off)"
        startOfflineEars()
    }

    private fun onScreenOn() {
        if (!running || !switchedForScreen) return
        switchedForScreen = false
        if (inCall) return
        ears?.stop()
        ears = null
        useVosk = wantVosk()
        handler.postDelayed({
            if (!running) return@postDelayed
            if (useVosk) startOfflineEars()
            else { engineInfo = "Hearing: Google speech"; startListening() }
        }, 120L)
    }

    @Volatile private var clearOnUnlock = false
    private var unlockReceiver: BroadcastReceiver? = null

    private fun isPhoneLocked(): Boolean = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
    } catch (e: Exception) { false }

    private fun killBackgroundApps() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (a in installedApps()) {
                if (a.pkg != packageName) {
                    try { am.killBackgroundProcesses(a.pkg) } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }
    }

    // When the phone is unlocked after a "close all" asked on the lock screen, finish clearing the recent list
    private fun registerUnlockWatcher() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val pend = afterUnlockCmd
                    afterUnlockCmd = null
                    if (pend != null && System.currentTimeMillis() < afterUnlockUntil) {
                        handler.postDelayed({ if (running) handle(pend) }, 450L)
                    }
                    if (!clearOnUnlock) return
                    clearOnUnlock = false
                    io.execute {
                        Thread.sleep(1200)
                        val ok = RiyaAccessibility.instance?.clearRecents() ?: false
                        handler.post { if (running) say(if (ok) "Recent apps cleared." else "I could not clear the recent list.", true) }
                    }
                }
            }
            val f = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            unlockReceiver = r
        } catch (e: Exception) { }
    }

    private fun closeAllApps() {
        val acc = RiyaAccessibility.instance
        if (isPhoneLocked()) {
            // Android does not let any app touch the recent-apps screen while locked, so stop the apps now
            // and clear the list the moment the phone is unlocked.
            io.execute { killBackgroundApps() }
            if (acc != null) clearOnUnlock = true
            say(if (acc != null) "The phone is locked, so I stopped the background apps. I will clear the recent list when you unlock."
                else "The phone is locked, so I stopped the background apps. Turn on Riya in Accessibility to clear the recent list too.", true)
            return
        }
        say("Closing all apps.")
        io.execute {
            killBackgroundApps()
            val cleared = if (acc != null) acc.clearRecents() else false
            handler.post {
                if (!running) return@post
                val msg = when {
                    cleared -> "All apps are closed."
                    acc == null -> "I stopped the background apps. Turn on Riya in Accessibility to clear the recent list too."
                    else -> "I stopped the background apps, but I could not clear the recent list on this phone."
                }
                say(msg, true)
            }
        }
    }

    private fun setAlarm(c: String) {
        val nums = parseNums(c)
        if (nums.isEmpty()) { say("Tell me the time, for example set alarm for seven thirty a m", true); return }
        var h = nums[0]
        var m = 0
        if (nums.size > 2 && nums[1] == 0) m = nums[2]
        else if (nums.size > 1) m = nums[1]
        val pm = Regex("\\b(p m|pm|evening|night|afternoon)\\b").containsMatchIn(c)
        val am = Regex("\\b(a m|am|morning)\\b").containsMatchIn(c)
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        if (h > 23 || m > 59) { say("I did not get that time. Please say it again.", true); return }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, assistantName)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (launch(i)) {
            val shown = SimpleDateFormat("h:mm a", Locale.getDefault()).format(
                java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m) }.time)
            say("Alarm set for $shown", true)
        } else say("I could not find a clock app", true)
    }

    private fun setTimer(c: String) {
        val nums = parseNums(c)
        var secs = -1
        if ("half an hour" in c || "half hour" in c) secs = 1800
        else {
            val n = nums.firstOrNull() ?: if (Regex("\\b(an|a|one) (hour|minute)\\b").containsMatchIn(c)) 1 else null
            if (n != null) {
                val unit = when {
                    "hour" in c -> 3600
                    "second" in c -> 1
                    else -> 60
                }
                secs = n * unit
            }
        }
        if (secs <= 0) { say("For how long? Say for example set a timer for five minutes", true); return }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, assistantName)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (launch(i)) {
            val spoken = if (secs % 3600 == 0) "${secs / 3600} hour" else if (secs % 60 == 0) "${secs / 60} minute" else "$secs second"
            say("Timer set for $spoken", true)
        } else say("I could not find a clock app", true)
    }

    private fun openSettingsFor(c: String) {
        val i = when {
            "bluetooth" in c -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "brightness" in c -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "airplane" in c || "flight" in c -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "location" in c || "gps" in c -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "hotspot" in c -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            else -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    else Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        if (launch(i)) say("Opening it. Android needs you to tap the switch yourself.", true)
        else say("I could not open that setting", true)
    }

    private fun playLocal(song: String) {
        say("No internet, so I will play music stored on your phone.")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, song)
        }
        if (!launch(i)) {
            val j = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
            if (!launch(j)) say("I could not find a music player on this phone", true)
        }
    }

    // ---------- Volume ----------

    private fun volume(c: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val stream = AudioManager.STREAM_MUSIC
        val percent = parseNums(c).firstOrNull()
        when {
            "unmute" in c -> am.setStreamVolume(stream, max / 2, AudioManager.FLAG_SHOW_UI)
            "mute" in c -> am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
            percent != null -> am.setStreamVolume(stream, (percent.coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI)
            "max" in c || "full" in c -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            "up" in c || "increase" in c || "raise" in c || "louder" in c || "high" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            }
            "down" in c || "decrease" in c || "lower" in c || "low" in c || "reduce" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            }
            else -> { say("Say volume up, down, mute, or a number"); return }
        }
        say("Volume " + (am.getStreamVolume(stream) * 100 / max) + " percent")
    }

    // ---------- Other actions ----------

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    // ---------- Gemini Live: real-time voice chat, also on the lock screen ----------
    @Volatile private var liveOn = false
    private var live: GeminiLive? = null
    private val liveBuf = StringBuilder()
    private val liveReply = StringBuilder()
    private val liveStartRe = Regex("^(start |open |go |switch to )?(gemini )?live( mode| chat| talk| conversation| voice)?$|^(let'?s |lets )?(talk|chat|speak)( to me| with me| live| to gemini)?$|\\bgemini live\\b|^talk to gemini$")
    private val liveStopRe = Regex("\\b(bye|goodbye|good bye|stop (the )?(live|chat|talking|conversation)|end (the )?(live|chat|conversation)|exit live|quit live|that'?s all)\\b")

    /** A longer sentence that is not a phone command: it is chat, so a locked phone must not be unlocked for it. */
    private fun liveChatLike(c: String): Boolean =
        prefs().getBoolean("live_when_locked", true) && c.trim().split(" ").size >= 4 &&
            !Regex("^(please )?(open|launch|start|call|dial|phone|play|send|message|whatsapp|close|turn|switch|set|take|record|volume|increase|decrease|flashlight|torch|brightness|alarm|timer|remind|next|previous|pause|resume|mute|unmute|lock|read|type|dictate|search|youtube|spotify|camera|capture|go|show|enable|disable|raise|lower|answer|reject|decline|cut|end|hang)\\b").containsMatchIn(c)

    private fun liveWanted(): Boolean =
        isPhoneLocked() && prefs().getBoolean("live_when_locked", true) && geminiReady() && !inCall && !liveOn

    private fun startLive(first: String?) {
        if (liveOn || inCall) return
        val key = geminiKey()
        if (key.isEmpty() || !isOnline()) { say("Gemini Live needs internet and the Gemini key in my settings.", true); return }
        liveOn = true
        aiSeq++
        thinking = false
        speaking = false
        try { tts?.stop() } catch (e: Exception) { }
        handler.removeCallbacks(listenWatchdog)
        try { recognizer?.cancel() } catch (e: Exception) { }
        resetRecognizer()
        ears?.stop()
        ears = null
        liveBuf.setLength(0)
        liveReply.setLength(0)
        lastHint = ""
        lastReply = "Gemini Live is on. Just talk. Say bye to stop."
        setState("listening")
        notifyUi()
        tick(40)
        val defaults = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025", "gemini-2.5-flash-native-audio-preview-09-2025")
        val custom = prefs().getString("live_model", "").orEmpty().trim()
        val models = if (custom.isNotEmpty()) listOf(custom) + defaults else defaults
        val g = GeminiLive(key, assistantName, prefs().getString("owner", "").orEmpty().trim(), first, models,
            { user, text -> handler.post { onLiveText(user, text) } },
            { why -> handler.post { endLive(why) } })
        live = g
        handler.postDelayed({ if (liveOn && live === g) g.start() }, 450L)       // let the offline mic close first
    }

    private fun onLiveText(user: Boolean, text: String) {
        if (!liveOn) return
        if (user) {
            liveReply.setLength(0)
            liveBuf.append(text)
            if (liveBuf.length > 120) liveBuf.delete(0, liveBuf.length - 120)
            lastHeard = liveBuf.toString().trim()
            if (liveStopRe.containsMatchIn(liveBuf.toString().lowercase())) {
                liveBuf.setLength(0)
                handler.postDelayed({ endLive("Gemini Live ended.") }, 2500L)      // let her say goodbye first
            }
        } else {
            liveBuf.setLength(0)
            liveReply.append(text)
            lastReply = liveReply.toString().trim()
        }
        notifyUi()
    }

    private fun endLive(why: String) {
        if (!liveOn) return
        liveOn = false
        try { live?.stop() } catch (e: Exception) { }
        live = null
        lastHint = why
        notifyUi()
        tick(40)
        if (!running) return
        if (isPhoneLocked() && !useVosk) { switchedForScreen = true; useVosk = true }
        handler.postDelayed({
            if (!running || liveOn) return@postDelayed
            if (useVosk) startOfflineEars() else { engineInfo = "Hearing: Google speech"; startListening() }
        }, 700L)
    }

    @Volatile private var testingLock = false

    /** "Riya, test unlock": locks the phone, waits, unlocks it with the saved PIN and says exactly what failed, if anything. */
    private fun selfTestLockUnlock() {
        val why = unlockBlocker()
        if (why != null) { say("The unlock test cannot run. $why", true); return }
        testingLock = true
        if (isPhoneLocked()) { autoUnlockThen(null); return }
        say("Testing. I will lock the phone now, wait a few seconds, then unlock it.", true)
        handler.postDelayed({ if (running) lockScreen() }, 3500L)
        handler.postDelayed({
            if (!running || !testingLock) return@postDelayed
            if (isPhoneLocked()) autoUnlockThen(null)
            else {
                testingLock = false
                say("The screen did not lock. Please turn on Riya in Accessibility settings or Device admin in my app. If the screen did turn off, your phone waits a few seconds before it really locks, so open Settings, Lock screen, and set Automatically lock to immediately.", true)
            }
        }, 13000L)
    }

    private fun lockScreen() {
        if (isPhoneLocked()) return
        val dpm = getSystemService(DevicePolicyManager::class.java)
        var ok = false
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
            try { dpm.lockNow(); ok = true } catch (e: Exception) { }
        }
        if (!ok) {
            val acc = RiyaAccessibility.instance
            if (acc != null && Build.VERSION.SDK_INT >= 28)
                ok = acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
        if (ok) tick(40)
        else say("I could not lock the phone. Please turn on Riya in Accessibility settings, or turn on Device admin in my app.", true)
    }

    private fun openCamera() {
        if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))) {
            if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) say("Camera not found")
        }
    }

    private fun youtube(query: String?) {
        val url = if (query.isNullOrBlank()) "https://www.youtube.com"
        else "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
        if (!launch(i)) launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openSpotify() {
        val i = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (i == null || !launch(i)) say("Spotify is not installed")
    }

    private fun playSpotify(song: String) {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, song)
        }
        if (!launch(i)) {
            if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song))))) {
                say("Spotify is not installed")
            }
        }
    }

    override fun onDestroy() {
        running = false
        try { restoreDuck() } catch (e: Exception) { }
        try { live?.stop() } catch (e: Exception) { }
        state = "stopped"
        handler.removeCallbacksAndMessages(null)
        listener?.invoke()
        io.shutdownNow()
        voiceOverlay?.destroy()
        typing = false
        try { overlayView?.let { getSystemService(WindowManager::class.java).removeView(it) } } catch (e: Exception) {}
        instance = null
        NetQuality.unwatch(this, netCb)
        ContactCache.stop(this)
        try { callReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        try { unlockReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        ears?.stop()
        recognizer?.destroy()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        unmuteBeeps()
        VoskModels.release()
        super.onDestroy()
    }
}
