package com.rama.riya

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileOutputStream

/**
 * Media guard: while a video / reel / song plays, a tiny word-list recogniser listens ONLY for her name and a few
 * command words. Everything else (the video's own voices, music, traffic) becomes [unk], so "next", "previous",
 * "play next song", "stop music" and her name are heard even when the sound is loud and never "end a sentence".
 */
object MediaWords {
    /** Her name and its spellings, as the recogniser should know them. */
    @Volatile var names: List<String> = emptyList()
    private val cmdWords = listOf("stop", "pause", "music", "song", "songs", "the", "next", "previous", "play", "skip", "last", "track",
        "please", "turn", "off", "one", "reel", "video", "short", "go", "back", "swipe", "up", "down", "resume", "this", "it")

    fun grammar(): String = org.json.JSONArray((names + cmdWords + listOf("[unk]")).distinct()).toString()

    /** kind = next | previous | stop | pause | play | name.  song = the words song / music / track were said. */
    data class Hit(val kind: String, val named: Boolean, val song: Boolean)

    fun parse(json: String): Hit? {
        val o = try { JSONObject(json) } catch (e: Exception) { return null }
        val t = (o.optString("text").ifEmpty { o.optString("partial") }).lowercase().trim()
        if (t.isEmpty()) return null
        val toks = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nm = names
        val named = toks.any { it in nm }
        val w = toks.filter { it != "[unk]" && it !in nm }
        val song = w.any { it == "music" || it == "song" || it == "songs" || it == "track" }
        val kind = when {
            w.contains("previous") || (w.contains("last") && (song || w.contains("one") || w.contains("reel") || w.contains("video") || w.contains("short"))) ||
                (w.contains("swipe") && w.contains("down")) || (w.contains("go") && w.contains("back")) -> "previous"
            w.contains("next") || w.contains("skip") || (w.contains("swipe") && w.contains("up")) -> "next"
            (w.contains("stop") || w.contains("pause") || (w.contains("turn") && w.contains("off"))) && song -> "stop"
            named && (w.contains("pause") || w.contains("stop")) -> "pause"
            named && (w.contains("play") || w.contains("resume")) -> "play"
            named -> "name"
            else -> null
        }
        return if (kind == null) null else Hit(kind, named, song)
    }
}

/**
 * Offline speech-to-text using Vosk. Owns the microphone while running.
 * Audio is thrown away while [pause]d (so Riya never hears her own voice).
 */
class VoskEars(
    private val ctx: Context,
    private val modelName: String,
    private val onReady: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onText: (List<String>) -> Unit,
    private val onFail: (String) -> Unit
) {
    /** Voice Match: x-vector of the last finished sentence (null if the model is missing or the sentence was too short). */
    @Volatile var lastSpk: FloatArray? = null
    @Volatile var lastSpkFrames = 0
    @Volatile var speakerReady = false
    @Volatile var wantSpeaker = false
    /** Microphone loudness 0..1 (drives the Google-style bars). */
    @Volatile var onLevel: ((Float) -> Unit)? = null

    // The speaker recogniser ends its own sentences; keep that voice print instead of losing it.
    private var cachedSpk: FloatArray? = null
    private var cachedFrames = 0
    private var cachedAt = 0L

    private fun readSpk(json: String): Pair<FloatArray, Int>? {
        return try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("spk") ?: return null
            Pair(FloatArray(arr.length()) { arr.getDouble(it).toFloat() }, o.optInt("spk_frames", 0))
        } catch (e: Throwable) { null }
    }

    @Volatile private var alive = true
    /** true while the listening thread is really running (the service checks this to never stay deaf). */
    @Volatile var loopActive = false
        private set
    @Volatile private var paused = true
    @Volatile private var resumeAt = 0L

    fun start() {
        val t = Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (e: Throwable) { }
            runLoop()
        }
        t.isDaemon = true
        t.start()
    }

    /** True when the live words already look like "name + command": then finish the sentence sooner. */
    @Volatile var looksComplete: ((String) -> Boolean)? = null
    /** How long (ms) a pause may last before the sentence counts as finished; depends on what was said so far. */
    @Volatile var pauseFor: ((String) -> Long)? = null
    @Volatile var onStopWord: (() -> Unit)? = null
    /** Music guard: while music plays, a tiny word-list recogniser reacts to "stop music" even in noise. */
    @Volatile var musicMode = false
    @Volatile var onMediaWord: ((MediaWords.Hit) -> Unit)? = null
    @Volatile private var guarding = false
    /** Start a fresh sentence right now (used after a command was acted on from the live words). */
    @Volatile var flushNow = false
    /** While Riya speaks (mic paused), keep listening for just \"stop\". */
    fun guardStart() { guarding = true }
    fun guardEnd() { guarding = false }

    fun pause() { paused = true }

    fun resume() {
        if (paused) {
            resumeAt = System.currentTimeMillis() + 150   // skip the tail of Riya's own voice
            paused = false
        }
    }

    fun stop() { alive = false }

    // With alternatives on, Vosk returns {"alternatives":[{"text":..},..]}; otherwise {"text":..}
    private fun parseTexts(json: String): List<String> {
        val o = JSONObject(json)
        val out = ArrayList<String>()
        val alts = o.optJSONArray("alternatives")
        if (alts != null) {
            for (i in 0 until alts.length()) {
                val t = alts.getJSONObject(i).optString("text").trim()
                if (t.isNotEmpty() && t !in out) out.add(t)
            }
        } else {
            val t = o.optString("text").trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    private fun grabSpk(sr: Recognizer?) {
        var best: FloatArray? = null
        var frames = 0
        if (sr != null) {
            val r = readSpk(sr.finalResult)
            if (r != null) { best = r.first; frames = r.second }
        }
        val c = cachedSpk
        if (c != null && System.currentTimeMillis() - cachedAt < 4000 && cachedFrames > frames) { best = c; frames = cachedFrames }
        cachedSpk = null
        cachedFrames = 0
        lastSpk = best
        lastSpkFrames = frames
    }

    @Volatile private var usedComm = false

    /**
     * Earbuds: a wired / USB headset mic is used automatically. A Bluetooth earbud mic is used only when
     * "Use earbuds microphone" is ON (Bluetooth then switches to call quality). Otherwise the phone mic hears you
     * and the music keeps its quality. Returns true when a Bluetooth communication device was switched on.
     */
    private fun pickMic(a: AudioRecord): Boolean {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val wired = ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            if (wired != null) { a.setPreferredDevice(wired); return false }
            if (!ctx.getSharedPreferences("riya", Context.MODE_PRIVATE).getBoolean("earbud_mic", false)) return false
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                if (dev != null && am.setCommunicationDevice(dev)) {
                    Thread.sleep(400)
                    ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                        ?.let { a.setPreferredDevice(it) }
                    return true
                }
            } else {
                val bt = ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (bt != null) {
                    @Suppress("DEPRECATION") am.startBluetoothSco()
                    Thread.sleep(900)
                    a.setPreferredDevice(bt)
                    return true
                }
            }
        } catch (e: Throwable) { }
        return false
    }

    private fun runLoop() {
        loopActive = true
        var rec: Recognizer? = null
        var guardRec: Recognizer? = null
        var musicRec: Recognizer? = null
        var spkRec: Recognizer? = null
        var mic: AudioRecord? = null
        try {
            val m = VoskModels.model(ctx, modelName)     // shared + preloaded: no disk load on lock / unlock
            val r = Recognizer(m, 16000.0f)
            rec = r
            guardRec = Recognizer(m, 16000.0f, StopWords.grammar())
            musicRec = try { Recognizer(m, 16000.0f, MediaWords.grammar()) } catch (e: Throwable) { null }
            if (wantSpeaker) {
                try {
                    val sm = VoskModels.speaker(ctx)
                    val sr = Recognizer(m, 16000.0f)
                    sr.setSpeakerModel(sm)
                    spkRec = sr
                    speakerReady = true
                } catch (e: Throwable) { speakerReady = false }
            }
            r.setMaxAlternatives(3)   // keep the 3 best guesses (cheaper than 5) so a misheard name can still be matched

            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 16000 * 2)
            )
            mic = a
            usedComm = pickMic(a)
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(a.audioSessionId)?.enabled = true } catch (e: Throwable) { }
            // Hardware AGC: most phones' chip-level gain control, so a far-away voice is boosted before it even reaches us.
            try { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(a.audioSessionId)?.enabled = true } catch (e: Throwable) { }
            if (a.state != AudioRecord.STATE_INITIALIZED) {
                if (alive) onFail("microphone could not be opened")
                return
            }
            a.startRecording()
            onReady()

            val buf = ShortArray(1200)      // 75 ms chunks: fast enough, and the CPU wakes less often (battery)
            var lastPartial = ""
            var lastChange = 0L
            var needReset = true
            var wasGuarding = false
            var wasMusic = false
            var mediaResetAt = 0L
            var lastNameHitAt = 0L
            var dead = 0
            var zeroMs = 0L
            var noiseFloor = 0.004
            var lastLoudAt = 0L
            var idle = true
            var env = 0.02                          // slow-moving estimate of how loud your voice has been recently
            val farMode = ctx.getSharedPreferences("riya", Context.MODE_PRIVATE).getBoolean("far_mic", true)
            val targetLevel = 0.09                  // where a close, clear voice normally sits (0-1 scale)
            val maxGain = 7.0
            val pre = java.util.ArrayDeque<ShortArray>()
            // one chunk of audio through the recognisers
            fun feed(b: ShortArray, len: Int) {
                if (musicMode) {
                    val mr = musicRec
                    if (mr != null) {
                        val nowM = System.currentTimeMillis()
                        if (!wasMusic) { mr.reset(); wasMusic = true; mediaResetAt = nowM }
                        else if (nowM - mediaResetAt > 8000L) { mr.reset(); mediaResetAt = nowM }      // video talk never "ends": start fresh now and then
                        val fullM = mr.acceptWaveForm(b, len)
                        val hit = MediaWords.parse(if (fullM) mr.result else mr.partialResult)
                        if (hit != null) {
                            if (hit.kind == "name") {
                                if (nowM - lastNameHitAt > 1500L) { lastNameHitAt = nowM; onMediaWord?.invoke(hit) }
                            } else { mr.reset(); mediaResetAt = nowM; onMediaWord?.invoke(hit) }
                        }
                    }
                } else wasMusic = false
                val sp = spkRec
                if (sp != null && sp.acceptWaveForm(b, len)) {
                    val got = readSpk(sp.result)
                    if (got != null && got.second >= cachedFrames) { cachedSpk = got.first; cachedFrames = got.second; cachedAt = System.currentTimeMillis() }
                }
                if (r.acceptWaveForm(b, len)) {
                    val texts = parseTexts(r.result)
                    grabSpk(spkRec)
                    lastPartial = ""
                    if (texts.isNotEmpty()) onText(texts)
                } else {
                    val p = JSONObject(r.partialResult).optString("partial").trim()
                    val now = System.currentTimeMillis()
                    if (p.isNotEmpty() && p != lastPartial) {
                        lastPartial = p
                        lastChange = now
                        onPartial(p)
                    } else if (p.isNotEmpty() && now - lastChange > (pauseFor?.invoke(p) ?: (if (looksComplete?.invoke(p) == true) 190L else 270L))) {
                        // You stopped talking: don't wait for Vosk's slow silence detector, finish right now.
                        val texts = parseTexts(r.finalResult)
                        r.reset()
                        grabSpk(spkRec)
                        lastPartial = ""
                        if (texts.isNotEmpty()) onText(texts)
                    }
                }
            }
            while (alive) {
                val n = a.read(buf, 0, buf.size)
                if (n <= 0) {
                    // the system silenced the mic (screen locked, other app took it): fail so the service restarts the ears
                    if (++dead > 150) throw IllegalStateException("microphone stopped")
                    Thread.sleep(20); continue
                }
                dead = 0
                // SOFTWARE GAIN: a distant voice arrives quiet; boost it towards a normal speaking level before
                // anything else looks at it, so both the wake-up threshold below and the recogniser get a clearer
                // signal. Pure silence is left alone (gain only grows once there is some real signal), and the
                // boost eases in and out over about a second so it never "pumps" mid-word.
                if (farMode) {
                    var peak = 0
                    for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); if (v > peak) peak = v }
                    val peakN = peak / 32768.0
                    env += (peakN - env) * (if (peakN > env) 0.35 else 0.08)
                    if (peakN > 0.001 && env > 0.0015) {     // this block really has some signal, not just a lingering average
                        val g = (targetLevel / env).coerceIn(1.0, maxGain)
                        if (g > 1.03) for (i in 0 until n) {
                            val v = (buf[i] * g).toInt()
                            buf[i] = v.coerceIn(-32768, 32767).toShort()
                        }
                    }
                }
                // Some phones keep the mic "open" after the screen locks but feed it pure silence (all zeros).
                // A real room never gives exact zeros for 8 seconds, so reopen the microphone.
                run {
                    var mx = 0
                    for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); if (v > mx) mx = v }
                    if (mx == 0) { zeroMs += n * 1000L / 16000L; if (zeroMs > 8000L) throw IllegalStateException("microphone silenced by the system") }
                    else zeroMs = 0L
                }
                if (paused || System.currentTimeMillis() < resumeAt) {
                    needReset = true
                    val gr = guardRec
                    if (paused && guarding && gr != null) {
                        if (!wasGuarding) { gr.reset(); wasGuarding = true }
                        val full = gr.acceptWaveForm(buf, n)
                        if (StopWords.isStop(if (full) gr.result else gr.partialResult)) {
                            guarding = false
                            gr.reset()
                            onStopWord?.invoke()
                        }
                    } else wasGuarding = false
                    continue
                }
                wasGuarding = false
                if (flushNow) { flushNow = false; needReset = true }
                if (needReset) { r.reset(); spkRec?.reset(); cachedSpk = null; cachedFrames = 0; lastPartial = ""; needReset = false }
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                val rms = Math.sqrt(sum / n) / 32768.0
                onLevel?.invoke((rms * 9.0).toFloat().coerceIn(0f, 1f))
                // ---- BATTERY: while the room is quiet the recognisers are not run at all (they are the heavy part) ----
                val nowV = System.currentTimeMillis()
                noiseFloor += (rms - noiseFloor) * (if (rms < noiseFloor) 0.25 else 0.003)
                if (rms > maxOf(0.0045, noiseFloor * 2.2)) lastLoudAt = nowV
                val awake = nowV - lastLoudAt < 2500L || lastPartial.isNotEmpty() || guarding || musicMode
                if (!awake) {
                    idle = true
                    pre.addLast(buf.copyOf(n))            // keep the last ~0.3 s so the first word is not cut off
                    while (pre.size > 4) pre.removeFirst()
                    continue
                }
                if (idle) {
                    idle = false
                    r.reset(); spkRec?.reset(); musicRec?.reset(); cachedSpk = null; cachedFrames = 0; lastPartial = ""
                    while (pre.isNotEmpty()) { val c = pre.removeFirst(); feed(c, c.size) }
                }
                feed(buf, n)
            }
        } catch (e: Throwable) {
            if (alive) onFail(e.message ?: e.javaClass.simpleName)
        } finally {
            loopActive = false
            if (usedComm) try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).let { am ->
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") am.stopBluetoothSco() } } catch (e: Throwable) { }
            try { mic?.stop() } catch (e: Throwable) { }
            try { mic?.release() } catch (e: Throwable) { }
            try { rec?.close() } catch (e: Throwable) { }
            try { guardRec?.close() } catch (e: Throwable) { }
            try { musicRec?.close() } catch (e: Throwable) { }
            try { spkRec?.close() } catch (e: Throwable) { }
        }
    }
}
