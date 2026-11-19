package com.rama.riya

/**
 * Telugu / Hindi (Devanagari) script -> simple Latin letters, so that
 *  - a name written in Telugu ("వెంకటేష్") can be matched with the saved contact ("Venkatesh"),
 *  - the assistant's name written in Telugu ("రియా" = riya) is recognised,
 *  - common phone commands said in Telugu / Hindi are understood on the phone itself (no internet, instant).
 * The result is approximate on purpose: contact matching is by sound, and command words are matched loosely.
 */
object IndicRoman {
    private class Script(
        val cons: Map<Char, String>, val vowels: Map<Char, String>, val matras: Map<Char, String>,
        val virama: Char, val anusvara: Char, val candra: Char, val visarga: Char, val dropFinalA: Boolean
    )

    private val telugu = Script(
        cons = mapOf('క' to "k", 'ఖ' to "kh", 'గ' to "g", 'ఘ' to "gh", 'ఙ' to "ng", 'చ' to "ch", 'ఛ' to "chh", 'జ' to "j", 'ఝ' to "jh",
            'ఞ' to "ny", 'ట' to "t", 'ఠ' to "th", 'డ' to "d", 'ఢ' to "dh", 'ణ' to "n", 'త' to "t", 'థ' to "th", 'ద' to "d", 'ధ' to "dh",
            'న' to "n", 'ప' to "p", 'ఫ' to "ph", 'బ' to "b", 'భ' to "bh", 'మ' to "m", 'య' to "y", 'ర' to "r", 'ఱ' to "r", 'ల' to "l",
            'ళ' to "l", 'వ' to "v", 'శ' to "sh", 'ష' to "sh", 'స' to "s", 'హ' to "h"),
        vowels = mapOf('అ' to "a", 'ఆ' to "a", 'ఇ' to "i", 'ఈ' to "i", 'ఉ' to "u", 'ఊ' to "u", 'ఋ' to "ru", 'ఎ' to "e", 'ఏ' to "e",
            'ఐ' to "ai", 'ఒ' to "o", 'ఓ' to "o", 'ఔ' to "au"),
        matras = mapOf('ా' to "a", 'ి' to "i", 'ీ' to "i", 'ు' to "u", 'ూ' to "u", 'ృ' to "ru", 'ె' to "e", 'ే' to "e", 'ై' to "ai",
            'ొ' to "o", 'ో' to "o", 'ౌ' to "au"),
        virama = '్', anusvara = 'ం', candra = 'ఁ', visarga = 'ః', dropFinalA = false
    )

    private val hindi = Script(
        cons = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
            'ञ' to "ny", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh",
            'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l",
            'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
            '\u0958' to "k", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "j", '\u095C' to "d", '\u095D' to "dh", '\u095E' to "f", '\u095F' to "y"),
        vowels = mapOf('अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai",
            'ओ' to "o", 'औ' to "au", 'ऑ' to "o"),
        matras = mapOf('ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri", 'े' to "e", 'ै' to "ai", 'ो' to "o",
            'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e"),
        virama = '्', anusvara = 'ं', candra = 'ँ', visarga = 'ः', dropFinalA = true
    )

    private fun scriptOf(ch: Char): Script? = when (Character.UnicodeScript.of(ch.code)) {
        Character.UnicodeScript.TELUGU -> telugu
        Character.UnicodeScript.DEVANAGARI -> hindi
        else -> null
    }

    private fun isJoiner(ch: Char) = ch == '\u200C' || ch == '\u200D'

    fun toLatin(text: String): String {
        val sb = StringBuilder()
        val n = text.length
        var i = 0
        while (i < n) {
            val ch = text[i]
            if (isJoiner(ch) || ch == '\u093C') { i++; continue }
            val sc = scriptOf(ch)
            if (sc == null) {
                if (Character.isDigit(ch)) sb.append(Character.digit(ch, 10).coerceAtLeast(0)) else sb.append(ch.lowercaseChar())
                i++; continue
            }
            val c = sc.cons[ch]
            when {
                c != null -> {
                    var j = i + 1
                    while (j < n && (isJoiner(text[j]) || text[j] == '\u093C')) j++
                    val nx = if (j < n) text[j] else ' '
                    when {
                        nx == sc.virama -> {                       // consonant cluster: no vowel
                            sb.append(c)
                            j++
                            while (j < n && isJoiner(text[j])) j++
                        }
                        sc.matras.containsKey(nx) -> { sb.append(c).append(sc.matras[nx]); j++ }
                        else -> {
                            sb.append(c)
                            val endOfWord = j >= n || scriptOf(text[j]) == null
                            if (!(sc.dropFinalA && endOfWord)) sb.append('a')
                        }
                    }
                    i = j
                }
                sc.vowels.containsKey(ch) -> { sb.append(sc.vowels[ch]); i++ }
                ch == sc.anusvara -> {
                    val nx = if (i + 1 < n) text[i + 1] else ' '
                    val nr = sc.cons[nx]
                    sb.append(if (nr == null || nr.startsWith("p") || nr.startsWith("b") || nr.startsWith("m")) "m" else "n")
                    i++
                }
                ch == sc.candra -> { sb.append('n'); i++ }
                ch == sc.visarga -> { sb.append('h'); i++ }
                Character.isDigit(ch) -> { sb.append(Character.digit(ch, 10).coerceAtLeast(0)); i++ }
                else -> i++
            }
        }
        return sb.toString().lowercase().replace(Regex("([aeiou])\\1+"), "$1").replace(Regex("\\s+"), " ").trim()
    }
}

/** Common phone commands said in Telugu / Hindi, turned into the English command the app already understands. */
object IndicCommands {
    private const val CALL = "(?:call|kal|kol|kaal|phone|phon|fon|fone|dial|dayal|dyal)"
    private const val DO = "(?:chey\\w*|cey\\w*|kar\\w*|kij\\w*|lagao|laga\\w*)"
    private const val TO = "(?:to|tu|ki|ku|ko|ke)"
    private const val OPEN = "(?:open|opan|opn|khol\\w*|launch|chalu)"
    private const val PLAY = "(?:play|ple|pley|plae|baja\\w*)"
    private val filler = Regex("\\b(dayachesi|dayachesy|please|plis|pliz|jara|zara|jaldi|ippudu|abhi|ikkada)\\b")
    private val alias = mapOf(
        "yutyub" to "youtube", "yutub" to "youtube", "yutyubu" to "youtube", "vatsap" to "whatsapp", "vatsapp" to "whatsapp",
        "vhatsap" to "whatsapp", "vatsaap" to "whatsapp", "gugul" to "google", "kemera" to "camera", "kyamera" to "camera",
        "kamera" to "camera", "phonepe" to "phonepe", "instagram" to "instagram", "telegram" to "telegram"
    )

    private fun clean(s: String): String {
        var t = s.trim().replace(Regex("\\b(garu|gaaru)\\b"), "").replace(Regex("\\s+"), " ").trim()
        t = t.split(" ").joinToString(" ") { alias[it] ?: it }
        return t
    }

    /** [roman] = the sentence already written in Latin letters (see IndicRoman); the assistant's name is already removed. */
    fun parse(roman: String): String? {
        val r = roman.lowercase().replace(Regex("[.,!?]"), " ").replace(filler, " ").replace(Regex("\\s+"), " ").trim()
        if (r.isEmpty()) return null
        Regex("^(?:an|un|on)\\s?lo?a?c?k\\b.*$|^(?:anlak|unlak|anlok|unlok)\\b.*$").find(r)?.let { return "unlock phone" }
        Regex("^(?:lak|lok|lock)(?:\\s+(?:screen|skrin|phone|fon|fone))?(?:\\s+$DO)?$").find(r)?.let { return "lock screen" }
        Regex("^$CALL\\s+(?:$TO\\s+)?(.+?)(?:\\s+$DO)?$").find(r)?.let { m ->
            val n = clean(m.groupValues[1]); if (n.length >= 2) return "call $n"
        }
        Regex("^(.+?)\\s+(?:$TO\\s+)?$CALL(?:\\s+$DO)?$").find(r)?.let { m ->
            val hasParticle = Regex("\\s(?:$TO)\\s+$CALL").containsMatchIn(r)
            val hasDo = Regex("\\s$DO$").containsMatchIn(r)
            val n = clean(m.groupValues[1]); if (n.length >= 2 && (hasParticle || hasDo)) return "call $n"
        }
        Regex("^$OPEN\\s+(.+?)(?:\\s+$DO)?$").find(r)?.let { m ->
            val n = clean(m.groupValues[1]); if (n.length >= 2) return if ("camera" in n) "open camera" else "open $n"
        }
        Regex("^(.+?)\\s+(?:$OPEN|ter\\w+)(?:\\s+$DO)?$").find(r)?.let { m ->
            val n = clean(m.groupValues[1]); if (n.length >= 2) return if ("camera" in n) "open camera" else "open $n"
        }
        Regex("^$PLAY\\s+(.+)$").find(r)?.let { m -> val n = clean(m.groupValues[1]); if (n.length >= 2) return "play $n" }
        Regex("^(.+?)\\s+$PLAY(?:\\s+$DO)?$").find(r)?.let { m -> val n = clean(m.groupValues[1]); if (n.length >= 2) return "play $n" }
        return null
    }
}
