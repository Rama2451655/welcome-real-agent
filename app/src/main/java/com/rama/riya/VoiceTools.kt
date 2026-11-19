package com.rama.riya

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/**
 * Picks the most human-sounding voice installed on the phone (Google's neural voices when available).
 * Default is a Siri-like voice: natural US-English female. (Apple's real Siri voice cannot be installed on Android.)
 */
object VoiceTools {

    fun english(tts: TextToSpeech): List<Voice> =
        try { tts.voices?.filter { it.locale.language == "en" && !it.features.contains("notInstalled") } ?: emptyList() }
        catch (e: Throwable) { emptyList() }

    private fun score(v: Voice): Int {
        var s = v.quality                                   // QUALITY_VERY_HIGH = 500 ... VERY_LOW = 100
        val n = v.name.lowercase()
        if (v.isNetworkConnectionRequired) s += 300         // Google's network voices are the neural, natural ones
        if (n.contains("neural") || n.contains("wavenet") || n.contains("studio")) s += 200
        // Siri-like = smooth US English. UK is the next best, Indian English is still available in the picker.
        when (v.locale.country) {
            "US" -> s += 250
            "GB" -> s += 120
            "IN" -> s += 60
            "AU" -> s += 40
        }
        // Google US female voices (tpf is the closest to Siri's default tone)
        if (n.contains("-tpf-")) s += 120
        else if (n.contains("-iob-") || n.contains("-sfg-") || n.contains("-ena-") || n.contains("-ene-") || n.contains("female")) s += 60
        return s
    }

    fun best(tts: TextToSpeech): Voice? = english(tts).maxByOrNull { score(it) }

    /** Best voice that needs no internet: starts speaking instantly, works with no signal at all. */
    fun bestOffline(tts: TextToSpeech): Voice? =
        english(tts).filter { !it.isNetworkConnectionRequired }.maxByOrNull { score(it) }

    fun byName(tts: TextToSpeech, name: String): Voice? = english(tts).firstOrNull { it.name == name }

    /** Readable label for the picker. */
    fun label(v: Voice): String {
        val net = if (v.isNetworkConnectionRequired) " · natural (online)" else " · offline"
        return "${v.locale.displayName}  ${v.name.substringAfter("-x-", v.name).take(14)}$net"
    }

    fun sorted(tts: TextToSpeech): List<Voice> = english(tts).sortedByDescending { score(it) }

    /** First entries of the picker get a star so the Siri-like ones are easy to find. */
    fun star(i: Int) = if (i < 3) "★ " else ""
}
