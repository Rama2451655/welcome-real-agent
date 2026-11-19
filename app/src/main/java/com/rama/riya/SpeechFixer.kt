package com.rama.riya

/**
 * Speech engines mishear and mis-spell: "cal vinod", "opan instagram", "plae music", "sent 500 to vinod",
 * "turn on bluetoot". This fixes the COMMAND words (the verb and the system words) by sound / spelling, and tells
 * when a sentence is only half said ("send 500 to ...") so Riya waits for the rest instead of cutting it.
 * Names of people and apps are not touched here: contacts and apps are matched by sound elsewhere.
 * Pure Kotlin (no Android classes) so it can be tested on a computer.
 */
object SpeechFixer {

    /** Words that start a command. A first word close to one of these is replaced by it. */
    private val verbs = setOf(
        "open", "launch", "start", "run", "call", "phone", "dial", "play", "pause", "resume", "stop", "next", "previous",
        "mute", "unmute", "send", "pay", "transfer", "turn", "switch", "set", "increase", "decrease", "raise", "lower",
        "search", "close", "type", "read", "take", "record", "remind", "cancel", "answer", "reject", "unlock", "lock",
        "go", "show", "text", "message", "whatsapp", "volume", "brightness", "wifi", "bluetooth", "flashlight", "torch",
        "hotspot", "screenshot", "alarm", "timer", "skip", "swipe", "scroll", "tell", "find", "navigate", "directions",
        "share", "click", "tap", "press", "exit", "quit", "enable", "disable", "reply", "forward", "delete", "check",
        "dictate", "typing", "dictation", "decline", "accept", "hang", "disconnect", "increase", "reduce"
    )

    /** Ordinary sentence starters: never "fixed" (they are questions / talk for Gemini). */
    private val starters = setOf(
        "what", "whats", "who", "whom", "whose", "why", "how", "when", "where", "which", "is", "are", "am", "was", "can", "could",
        "do", "does", "did", "will", "would", "should", "shall", "the", "this", "that", "there", "here", "i", "my", "me", "we",
        "you", "he", "she", "it", "they", "hi", "hello", "hey", "good", "thanks", "thank", "yes", "no", "okay", "ok", "please",
        "also", "and", "then", "give", "get", "make", "let", "let's", "explain", "describe", "write", "list", "name", "define",
        "translate", "calculate", "count", "sing", "say", "speak", "talk", "wake", "sleep", "listen", "help", "stay", "wait",
        "time", "date", "news", "weather", "battery", "joke", "jokes", "song", "songs", "music", "cricket", "score", "movie", "movies",
        "recipe", "today", "tomorrow", "yesterday", "morning", "night", "evening", "bye", "sorry", "photos", "gallery", "camera",
        "clock", "calculator", "settings", "contacts", "chrome", "maps", "email", "gmail", "files", "store",
        "back", "home", "exit", "repeat", "again", "more", "less", "louder", "quieter", "faster", "slower", "up", "down", "on", "off"
    )

    /** Words that follow a system verb ("turn on bluetooth", "increase the volume"). */
    private val systemWords = setOf(
        "wifi", "bluetooth", "flashlight", "torch", "hotspot", "volume", "brightness", "airplane", "flight", "mode", "silent",
        "vibrate", "location", "data", "screen", "rotation", "battery", "saver", "music", "sound", "ringtone", "alarm", "timer"
    )
    private val systemVerbs = setOf("turn", "switch", "enable", "disable", "increase", "decrease", "raise", "lower", "reduce", "set", "toggle")

    private val numberWords = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty",
        "seventy", "eighty", "ninety", "hundred", "thousand", "lakh", "lakhs", "k", "and", "half"
    )
    private val moneyFiller = setOf("please", "rupees", "rupee", "rs", "inr", "only", "\u20b9")

    // ---------- spelling distance ----------

    /** Edit distance where swapping two neighbouring letters counts once. */
    fun dist(a: String, b: String): Int {
        if (a == b) return 0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** First letter + consonants, y = i, repeats collapsed: "opan" and "open" differ only by a vowel. */
    private fun skeleton(s: String): String {
        val sb = StringBuilder()
        for ((i, c0) in s.withIndex()) {
            val c = if (c0 == 'y') 'i' else c0
            if (i > 0 && c in "aeiouh") continue
            if (sb.isNotEmpty() && sb.last() == c) continue
            sb.append(c)
        }
        return sb.toString()
    }

    /** The one vocabulary word [t] was most likely meant to be, or null if none is close enough or two are equally close. */
    private fun nearest(t: String, vocab: Collection<String>): String? {
        if (t.length < 3 || !t.all { it.isLetter() }) return null
        if (t in vocab) return t
        val limit = when { t.length <= 3 -> 1; t.length <= 5 -> 1; else -> 2 }     // 3 letters: only one missing letter ("cal" -> call)
        var best: String? = null
        var bestD = Int.MAX_VALUE
        var tie = false
        val tSk = skeleton(t)
        for (v in vocab) {
            if (kotlin.math.abs(v.length - t.length) > 1) continue
            if (t.length == 3 && v.length != 4) continue
            if (t[0] != v[0]) continue                 // a slip rarely changes the first letter
            var d = dist(t, v)
            // same consonants (a vowel slip): "opan" -> open, "plae" -> play
            if (d > limit && t.length >= 4 && v.length == t.length && tSk.length >= 2 && tSk == skeleton(v) && d <= 2) d = limit
            if (d > limit) continue
            if (d < bestD) { best = v; bestD = d; tie = false }
            else if (d == bestD && v != best) tie = true
        }
        return if (tie) null else best
    }

    // ---------- fixing ----------

    private fun isAmountWord(w: String): Boolean =
        w in numberWords || w.firstOrNull()?.isDigit() == true || w.startsWith("\u20b9") || w.endsWith("/-") || w in moneyFiller

    /**
     * Returns [text] with the misheard command words corrected, or [text] unchanged.
     * [extraVerbs]: more words that may start a command (none needed today; kept for future use).
     */
    fun fix(text: String, extraVerbs: Collection<String> = emptyList()): String {
        val orig = text.trim()
        if (orig.isEmpty()) return text
        val tokens = orig.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.size > 9) return text                       // a long sentence is a question or dictation: leave it
        var changed = false
        val verbSet = verbs + extraVerbs
        val allFirst = verbSet + starters

        // 1. "sent / sand / cent 500 to vinod" -> send   (also "pay" heard as "pie", "bay")
        for (i in 0 until tokens.size - 1) {
            val t = tokens[i]
            val next = tokens[i + 1]
            val amountNext = isAmountWord(next) && next !in moneyFiller
            if (amountNext && t in setOf("sent", "sand", "cent", "sant", "scent", "sending", "sends", "sen", "sind", "sandy")) { tokens[i] = "send"; changed = true }
            else if (amountNext && t in setOf("pie", "bay", "pee", "pay\u0027s", "paid", "paying")) { tokens[i] = "pay"; changed = true }
        }

        // 2. the first word: a command verb
        val first = tokens[0]
        if (first !in allFirst && first.all { it.isLetter() }) {
            val fx = nearest(first, verbSet)
            if (fx != null) { tokens[0] = fx; changed = true }
        }

        // 3. "turn on bluetoot", "increas volume": the system words after a system verb
        if (tokens[0] in systemVerbs || tokens[0] in setOf("volume", "brightness")) {
            for (i in 1 until tokens.size) {
                val t = tokens[i]
                if (t in systemWords || t in starters || t.length < 4 || !t.all { it.isLetter() }) continue
                val fx = nearest(t, systemWords)
                if (fx != null) { tokens[i] = fx; changed = true }
            }
        }
        return if (changed) tokens.joinToString(" ") else text
    }

    // ---------- half-finished sentences ----------

    private val connectors = setOf(
        "to", "for", "and", "the", "a", "an", "of", "in", "at", "my", "by", "with", "from", "then", "is", "are", "as", "into",
        "about", "called", "named", "that", "your", "his", "her", "our", "or", "but", "if", "so", "on", "off"
    )
    private val needsObject = setOf(
        "open", "launch", "start", "call", "phone", "dial", "play", "send", "pay", "transfer", "message", "text", "search",
        "set", "remind", "type", "tell", "find", "show", "turn", "switch", "whatsapp", "navigate", "take", "record", "go"
    )

    /** true when the sentence stops where more is clearly coming ("open", "call", "send 500", "pay 500 to"). */
    fun dangling(textRaw: String): Boolean {
        val t = textRaw.lowercase().trim(' ', '.', ',', '?', '!').split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.isEmpty()) return false
        val last = t.last()
        if (last == "on" || last == "off") {
            // "wifi on" is complete; "turn on" / "switch off" is not
            if (t.size <= 2 && t[0] in setOf("turn", "switch")) return true
            if (t.size >= 3 && t[t.size - 2] in setOf("turn", "switch")) return true
            return false
        }
        if (last in connectors) return true
        if (t.size == 1 && t[0] in needsObject) return true
        // money: the amount without the person ("send 500", "open paytm send 500 rupees")
        val payAt = t.indexOfFirst { it == "send" || it == "pay" || it == "transfer" }
        val question = t[0] in setOf("how", "what", "why", "when", "where", "who", "which", "is", "can", "do", "does", "should", "will")
        if (!question && payAt in 0..3) {
            val tail = t.drop(payAt + 1).filter { it !in moneyFiller }
            if (tail.all { isAmountWord(it) || it == "to" }) return true
        }
        return false
    }
}
