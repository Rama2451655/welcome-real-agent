package com.rama.riya

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gemini Live: a real-time, two-way voice chat over one WebSocket.
 * Your voice goes up (16 kHz mic), Gemini's voice comes down (24 kHz) and is played at once.
 * Runs inside Riya's foreground service, so it keeps working with the screen off and the phone locked.
 */
class GeminiLive(
    private val key: String,
    private val name: String,
    private val owner: String,
    private val first: String?,
    private val models: List<String>,
    private val onText: (Boolean, String) -> Unit,      // (true = you said, false = Gemini said), text piece
    private val onEnd: (String) -> Unit                  // the session ended by itself (reason)
) {
    @Volatile private var alive = false
    @Volatile private var ready = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var modelIdx = 0
    @Volatile private var playUntil = 0L
    @Volatile private var lastActive = System.currentTimeMillis()
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec = false
    private val q = LinkedBlockingQueue<ByteArray>()
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private var idleThread: Thread? = null
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun start() {
        alive = true
        lastActive = System.currentTimeMillis()
        connect()
        idleThread = Thread {
            try {
                while (alive) {
                    Thread.sleep(3000)
                    if (alive && System.currentTimeMillis() - lastActive > 120000L) { end("Gemini Live stopped: quiet for 2 minutes."); break }
                }
            } catch (e: InterruptedException) { }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        alive = false
        ready = false
        try { ws?.close(1000, "bye") } catch (e: Exception) { }
        ws = null
        try { rec?.stop() } catch (e: Exception) { }
        try { rec?.release() } catch (e: Exception) { }
        rec = null
        try { track?.stop() } catch (e: Exception) { }
        try { track?.release() } catch (e: Exception) { }
        track = null
        q.clear()
        try { micThread?.interrupt() } catch (e: Exception) { }
        try { playThread?.interrupt() } catch (e: Exception) { }
        try { idleThread?.interrupt() } catch (e: Exception) { }
    }

    private fun end(why: String) {
        if (!alive) return
        stop()
        onEnd(why)
    }

    private fun connect() {
        if (!alive) return
        ready = false
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun setup(w: WebSocket) {
        val sys = "You are $name, a warm, quick voice assistant on the user's phone. " +
            (if (owner.isNotBlank()) "The user's name is $owner. " else "") +
            "Reply in the language the user speaks, in short natural sentences (one to three), like a friend on a call. " +
            "Never read out symbols or long lists. Answer the question properly and correctly. " +
            "You cannot press buttons on the phone during this chat. If the user asks you to open an app, call someone or unlock the phone, " +
            "say they can say it as a normal command after this chat ends (say bye or stop live)."
        val voice = JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore"))
        val cfg = JSONObject()
            .put("model", "models/" + models[modelIdx])
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", voice)))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        w.send(JSONObject().put("setup", cfg).toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { if (webSocket === ws) setup(webSocket) }
        override fun onMessage(webSocket: WebSocket, text: String) { handle(webSocket, text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(webSocket, bytes.utf8()) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            try { webSocket.close(1000, null) } catch (e: Exception) { }
            failed(webSocket, reason.ifBlank { "closed ($code)" })
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failed(webSocket, t.message ?: "connection failed")
        }
    }

    private fun failed(w: WebSocket, reason: String) {
        if (w !== ws || !alive) return
        val keyProblem = reason.contains("API key", true) || reason.contains("permission", true) || reason.contains("403") || reason.contains("401")
        if (!ready && !keyProblem && modelIdx + 1 < models.size) {      // this model name is not available for the key: try the next one
            modelIdx++
            try { ws?.cancel() } catch (e: Exception) { }
            connect()
            return
        }
        if (keyProblem) end("Gemini Live could not start: please check the Gemini key in my settings.")
        else if (!ready) end("Gemini Live could not start ($reason). Please check the internet and the Gemini key.")
        else end("Gemini Live ended ($reason).")
    }

    private fun handle(w: WebSocket, msg: String) {
        if (w !== ws || !alive) return
        try {
            val j = JSONObject(msg)
            if (j.has("setupComplete")) {
                ready = true
                startAudio()
                if (!first.isNullOrBlank()) sendText(first)
                return
            }
            if (j.has("goAway")) { end("Gemini Live session ran out of time. Say live to start again."); return }
            val sc = j.optJSONObject("serverContent") ?: return
            if (sc.optBoolean("interrupted")) { q.clear(); flushTrack() }
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (d.isNotEmpty()) {
                        val b = Base64.decode(d, Base64.DEFAULT)
                        q.add(b)
                        val now = System.currentTimeMillis()
                        playUntil = maxOf(now, playUntil) + b.size * 1000L / (24000 * 2)
                        lastActive = now
                    }
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.let {
                if (it.isNotBlank()) { lastActive = System.currentTimeMillis(); onText(true, it) }
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotBlank()) onText(false, it) }
        } catch (e: Exception) { }
    }

    private fun sendText(t: String) {
        try { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) } catch (e: Exception) { }
    }

    private fun flushTrack() {
        try { track?.pause(); track?.flush(); track?.play() } catch (e: Exception) { }
        playUntil = 0L
    }

    private fun startAudio() {
        if (rec != null) return
        try {
            val minT = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minT, 24000 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            playThread = Thread {
                try {
                    while (alive) {
                        val b = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        t.write(b, 0, b.size)
                    }
                } catch (e: Exception) { }
            }.apply { isDaemon = true; start() }

            val minR = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minR, 3200 * 4))
            if (r.state != AudioRecord.STATE_INITIALIZED) { end("Gemini Live could not open the microphone."); return }
            aec = try {
                if (AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true; true } else false
            } catch (e: Throwable) { false }
            r.startRecording()
            rec = r
            micThread = Thread {
                val buf = ShortArray(1600)          // 100 ms
                try {
                    while (alive) {
                        val n = r.read(buf, 0, buf.size)
                        if (n <= 0) { Thread.sleep(20); continue }
                        var peak = 0
                        for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); if (v > peak) peak = v }
                        // while Gemini is speaking, only send the mic if the phone can cancel its own echo and you are clearly talking
                        val playing = System.currentTimeMillis() < playUntil + 350L
                        if (playing && (!aec || peak < 6000)) continue
                        val bytes = ByteArray(n * 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, n)
                        val audio = JSONObject().put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000")
                        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
                    }
                } catch (e: Exception) { }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            end("Gemini Live could not start the audio.")
        }
    }
}
