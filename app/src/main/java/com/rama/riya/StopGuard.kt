package com.rama.riya

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/** The words that interrupt Riya while she is talking. */
object StopWords {
    // Only "stop" is listened for while she talks. The recogniser is forced to pick from this tiny list, so extra words
    // made ordinary talk, noise and her own voice turn into false stops; one word keeps that to a minimum.
    /** Her name (and its 3 commonest spellings) is also a stop: calling her always gets her attention, even while she talks. */
    @Volatile var names: List<String> = emptyList()

    fun grammar(): String = org.json.JSONArray(listOf("stop") + names + listOf("[unk]")).toString()

    /** true only when the whole utterance is "stop" or her name (other words / noise are [unk]). */
    fun isStop(json: String): Boolean {
        val o = try { JSONObject(json) } catch (e: Exception) { return false }
        val t = o.optString("text").ifEmpty { o.optString("partial") }.lowercase().trim()
        val toks = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nm = names
        return toks.isNotEmpty() && toks.size <= 3 && toks.any { it == "stop" || it in nm } &&
            toks.all { it == "stop" || it in nm || it == "[unk]" }
    }
}

/**
 * Listens for "stop" ONLY while Riya is speaking (works with Google hearing too).
 * Uses a tiny offline recogniser that knows just a few words, so it reacts instantly and never sends audio anywhere.
 */
class StopGuard(
    private val ctx: Context,
    private val modelName: String,
    private val onStop: () -> Unit
) {
    @Volatile private var gen = 0

    fun begin() {
        val my = ++gen
        val t = Thread { run(my) }
        t.isDaemon = true
        t.start()
    }

    fun end() { gen++ }

    private fun run(my: Int) {
        var mic: AudioRecord? = null
        var rec: Recognizer? = null
        try {
            val m = try { VoskModels.model(ctx, modelName) } catch (e: Throwable) { return }
            if (gen != my) return
            rec = Recognizer(m, 16000.0f, StopWords.grammar())
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16000 * 2)
            )
            mic = a
            if (a.state != AudioRecord.STATE_INITIALIZED) return
            a.startRecording()
            val buf = ShortArray(800)
            while (gen == my) {
                val n = a.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(20); continue }
                val full = rec.acceptWaveForm(buf, n)
                if (StopWords.isStop(if (full) rec.result else rec.partialResult)) {
                    if (gen == my) { gen++; onStop() }
                    break
                }
            }
        } catch (e: Throwable) {
            // stop-word listening is optional
        } finally {
            try { mic?.stop() } catch (e: Throwable) { }
            try { mic?.release() } catch (e: Throwable) { }
            try { rec?.close() } catch (e: Throwable) { }
        }
    }
}
