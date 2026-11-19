package com.rama.riya

/**
 * Sound-based contact matching. Speech engines write names wrongly ("the worker", "dwarka",
 * "dwaaraka" for Dwaraka). We compare how names SOUND, not how they are spelled.
 */
object ContactMatcher {

    /** Rough phonetic key tuned for Indian-English names. */
    fun key(input: String): String {
        var s = input.lowercase().replace(Regex("[^a-z ]"), " ").trim()
        s = s.replace("ph", "f").replace("kh", "k").replace("gh", "g").replace("th", "t")
            .replace("dh", "d").replace("bh", "b").replace("sh", "s").replace("ch", "c")
            .replace("ck", "k").replace("x", "ks").replace("ee", "i").replace("oo", "u")
        val sb = StringBuilder()
        for (ch in s) {
            if (ch == ' ') continue
            val m = when (ch) {
                'a', 'e', 'i', 'o', 'u', 'y', 'h' -> continue   // vowels carry little signal
                'p', 'b', 'f' -> 'P'
                't', 'd' -> 'T'
                'k', 'g', 'c', 'q' -> 'K'
                's', 'z', 'j' -> 'S'
                'v', 'w' -> 'V'
                else -> ch.uppercaseChar()
            }
            if (sb.isEmpty() || sb.last() != m) sb.append(m)
        }
        return sb.toString()
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** 0.0 .. 1.0 – how well the spoken name fits a saved contact name. */
    fun score(spoken: String, saved: String): Double {
        // names written in Telugu / Hindi script are compared by sound too (వెంకటేష్ = Venkatesh)
        fun lat(x: String) = x.lowercase().trim().let { if (it.any { ch -> ch.code > 127 }) IndicRoman.toLatin(it) else it }
        val s = lat(spoken)
        val c = lat(saved)
        if (s.isEmpty() || c.isEmpty()) return 0.0
        if (c.contains(s)) return 1.0
        var best = maxOf(sim(s, c) * 0.95, sim(key(s), key(c)))
        // whole spoken phrase vs each word of the saved name ("dwaraka" vs "Dwaraka Rao")
        for (w in c.split(Regex("\\s+")).filter { it.length >= 2 }) {
            best = maxOf(best, sim(key(s), key(w)), sim(s, w) * 0.95)
        }
        // each spoken word vs each saved word
        for (sw in s.split(Regex("\\s+")).filter { it.length >= 2 }) {
            for (w in c.split(Regex("\\s+")).filter { it.length >= 2 }) {
                best = maxOf(best, sim(key(sw), key(w)) * 0.9)
            }
        }
        return best
    }

    /** Best contacts for the spoken name (all within 0.08 of the top score), most likely first. */
    fun rank(spoken: String, names: List<String>, threshold: Double = 0.7): List<Pair<String, Double>> {
        val scored = names.distinct().map { it to score(spoken, it) }.filter { it.second >= threshold }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return scored
        val top = scored[0].second
        return scored.filter { it.second >= top - 0.08 }
    }
}
