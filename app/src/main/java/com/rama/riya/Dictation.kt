package com.rama.riya

/** Turns raw speech text into clean typed text: spoken punctuation, capital letters, contractions, common spelling slips. */
object Dictation {

    private val fixes = mapOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't", "doesnt" to "doesn't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't", "couldnt" to "couldn't",
        "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "im" to "I'm", "ive" to "I've", "thats" to "that's", "whats" to "what's", "youre" to "you're", "theyre" to "they're",
        "alot" to "a lot", "teh" to "the", "recieve" to "receive", "definately" to "definitely", "thier" to "their",
        "wich" to "which", "becuase" to "because", "tommorow" to "tomorrow", "tomorow" to "tomorrow", "seperate" to "separate",
        "occured" to "occurred", "untill" to "until", "wierd" to "weird", "freind" to "friend", "adress" to "address"
    )
    private val fixRegexes = fixes.map { Regex("\\b" + it.key + "\\b", RegexOption.IGNORE_CASE) to it.value }

    private val spokenPunct = listOf(
        Regex("\\s*\\b(full stop)\\b\\s*", RegexOption.IGNORE_CASE) to ". ",
        Regex("\\s+period$", RegexOption.IGNORE_CASE) to ".",
        Regex("\\s*\\bcomma\\b\\s*", RegexOption.IGNORE_CASE) to ", ",
        Regex("\\s*\\bquestion mark\\b\\s*", RegexOption.IGNORE_CASE) to "? ",
        Regex("\\s*\\b(exclamation mark|exclamation point)\\b\\s*", RegexOption.IGNORE_CASE) to "! ",
        Regex("\\s*\\bsemicolon\\b\\s*", RegexOption.IGNORE_CASE) to "; ",
        Regex("\\s*\\bcolon\\b\\s*", RegexOption.IGNORE_CASE) to ": ",
        Regex("\\s*\\b(at the rate|at sign)\\b\\s*", RegexOption.IGNORE_CASE) to "@",
        Regex("\\s*\\bdot com\\b", RegexOption.IGNORE_CASE) to ".com",
        Regex("\\s*\\b(new line|next line|new paragraph)\\b\\s*", RegexOption.IGNORE_CASE) to "\n"
    )

    /** @param before the text that already sits left of the cursor (decides capital letter and spacing). */
    fun polish(raw: String, before: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        for ((re, rep) in spokenPunct) t = t.replace(re, rep)
        for ((re, rep) in fixRegexes) t = t.replace(re, rep)
        t = t.replace(Regex("\\bi('(m|ll|d|ve))\\b"), "I$1").replace(Regex("\\bi\\b"), "I")
        t = t.replace(Regex("\\s+([,.;:?!])"), "$1")                 // no space before punctuation
        t = t.replace(Regex("([,;?!])(?=[A-Za-z])"), "$1 ")          // one space after it
        t = t.replace(Regex("[ \\t]{2,}"), " ").trim(' ')
        // capital letter at the start of every sentence
        t = t.replace(Regex("([.!?]\\s+|\\n)([a-z])")) { it.groupValues[1] + it.groupValues[2].uppercase() }
        val tb = before.trimEnd(' ', '\t')
        val atStart = tb.isEmpty() || tb.endsWith("\n") || tb.last() in ".!?"
        if (atStart && t.isNotEmpty() && t[0].isLowerCase()) t = t[0].uppercaseChar() + t.substring(1)
        val needSpace = before.isNotEmpty() && !before.last().isWhitespace()
        if (needSpace && t.isNotEmpty() && t[0] !in ",.;:?!)\n") t = " $t"
        return t
    }
}
