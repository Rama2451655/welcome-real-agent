package com.rama.riya

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online brain: Google Gemini (free key from aistudio.google.com).
 * Understands any language and answers in the language the user spoke.
 * Model names change often, so several are tried in order and the one that works is remembered.
 */
object GeminiBrain {
    class Reply(val text: String, val lang: String, val command: String)

    private val models = listOf(
        "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite"
    )
    @Volatile private var good: String? = null

    private class Result(val code: Int, val reply: Reply?, val badKey: Boolean)

    /** Whole answer must arrive within this time, otherwise the caller tries a keyless web answer. Longer on a weak signal (there is no on-phone brain any more). */
    private fun deadlineMs() = if (NetQuality.lastLevel == NetQuality.Level.POOR) 18000L else 12000L
    private fun connectMs() = if (NetQuality.lastLevel == NetQuality.Level.POOR) 6000L else 3000L

    private fun thinkingOff(model: String, cfg: JSONObject) {
        // Gemini 2.5 models "think" before answering, which costs seconds. Voice answers do not need it.
        if (model.startsWith("gemini-2.5")) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
    }

    fun ask(key: String, name: String, past: List<Pair<String, String>>, q: String): Reply? {
        val order = (listOfNotNull(good) + models).distinct()
        val t0 = System.currentTimeMillis()
        val end = t0 + deadlineMs()
        for (m in order) {
            val left = end - System.currentTimeMillis()
            if (left < 1500) break                       // out of time: stop waiting
            val r = try { call(key, m, name, past, q, left) } catch (e: Exception) { Result(0, null, false) }
            if (r.reply != null) { good = m; NetQuality.record(System.currentTimeMillis() - t0, true); return r.reply }
            if (r.badKey) return null          // wrong key: other models will not help
        }
        NetQuality.record(System.currentTimeMillis() - t0, false)
        return null
    }

    private fun system(name: String) =
        "You are $name, a warm, friendly voice assistant on the user's phone. The user can speak ANY language. " +
        "Detect the language of the user's latest message and reply in that SAME language, written in its own native script " +
        "(Telugu in Telugu script, Hindi in Devanagari, Tamil in Tamil script, English in English, and so on). " +
        "If the message is a language typed in English letters (for example 'ela unnav'), still reply in that language's native script. " +
        "If the text is just English words written in another script, treat it as English. " +
        "Replies are spoken aloud: one to three short, natural, conversational sentences. No markdown, no lists, no emojis, no symbols. " +
        "Return JSON with three fields. " +
        "lang: BCP-47 code of the language you reply in, like en-IN, te-IN, hi-IN, ta-IN, kn-IN, ml-IN, mr-IN, bn-IN, gu-IN, pa-IN, ur-IN. " +
        "reply: what you say. " +
        "command: ONLY if the user asks you to DO something on the phone, an English command using exactly these forms: " +
        "'call NAME', 'open APP', 'play SONG', 'message NAME text', 'set alarm for 7:30 am', 'set timer for 5 minutes', " +
        "'flashlight on', 'flashlight off', 'volume up', 'volume down', 'lock phone', 'go home', 'go back', 'take screenshot'. " +
        "Write names and apps in English letters (for example ద్వారక becomes dwaraka). Otherwise leave command empty."

    private fun call(key: String, model: String, name: String, past: List<Pair<String, String>>, q: String, budgetMs: Long): Result {
        val contents = JSONArray()
        for ((role, text) in past) {
            contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", q))))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("lang", JSONObject().put("type", "STRING"))
            .put("reply", JSONObject().put("type", "STRING"))
            .put("command", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("lang").put("reply"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(name)))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("temperature", 0.5)
                .put("maxOutputTokens", 500)
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .also { thinkingOff(model, it) })

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = minOf(connectMs(), budgetMs).toInt()
            conn.readTimeout = budgetMs.toInt()
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
                val bad = code == 401 || code == 403 || (code == 400 && err.contains("API key", ignoreCase = true))
                return Result(code, null, bad)
            }
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            val raw = sb.toString().trim()
            if (raw.isEmpty()) return Result(code, null, false)
            return Result(code, parse(raw), false)
        } finally {
            conn.disconnect()
        }
    }

    private const val FIX_PROMPT =
        "You fix dictated text. Correct spelling, grammar and obvious speech-recognition mistakes. " +
        "Keep the same language, meaning and wording as far as possible. Do not add, remove or explain anything. " +
        "Do not answer questions that appear in the text, only fix them. Output ONLY the corrected text."

    /** Fast spelling / grammar fix for typing by voice. Returns null on any problem so the caller types the offline version. */
    fun fix(key: String, text: String): String? {
        for (m in (listOfNotNull(good) + models).distinct().take(2)) {
            try {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", FIX_PROMPT))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 300))
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 3000
                    conn.readTimeout = 4000
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode != 200) continue
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                    val out = sb.toString().trim()
                    if (out.isNotEmpty()) { good = m; return out }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) { }
        }
        return null
    }

    /** Answer grounded with live Google Search (news, weather, scores, anything current). Null on any problem. */
    fun askSearch(key: String, name: String, q: String): String? {
        val sys = "You are $name, a voice assistant. Use Google Search for current facts. Answer in one to three short spoken sentences, " +
            "in the same language the user spoke. No markdown, no lists, no emojis, no web addresses."
        for (m in (listOfNotNull(good) + models).distinct().take(2)) {
            try {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", q)))))
                    .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                    .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 400).also { thinkingOff(m, it) })
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = connectMs().toInt()
                    conn.readTimeout = if (NetQuality.lastLevel == NetQuality.Level.POOR) 13000 else 8000
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode != 200) continue
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                    val out = sb.toString().replace("*", "").trim()
                    if (out.isNotEmpty()) { good = m; return out }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) { }
        }
        return null
    }


    private fun systemPlain(name: String, search: Boolean) =
        "You are $name, a warm, friendly voice assistant on the user's phone. The user can speak ANY language. " +
        "Reply in the SAME language the user spoke, in its own native script (Telugu in Telugu script, Hindi in Devanagari, English in English). " +
        "If the message is a language typed in English letters, still reply in that language's native script. " +
        "Replies are spoken aloud: one to three short, natural sentences, the first sentence short and direct. " +
        "No markdown, no lists, no emojis, no symbols, no web addresses. Be accurate; if you are not sure, say so briefly." +
        (if (search) " Use Google Search for current facts." else "")

    /**
     * Streams the answer and hands over each finished sentence at once, so Riya can start speaking after about a second.
     * Returns true if at least one sentence was delivered. Any failure returns false so the caller can use the web answer.
     */
    fun askStream(key: String, name: String, past: List<Pair<String, String>>, q: String, search: Boolean,
                  onSentence: (String) -> Unit): Boolean {
        val order = (listOfNotNull(good) + models).distinct().take(3)
        val t0 = System.currentTimeMillis()
        var firstAt = 0L
        val deliver: (String) -> Unit = { sentence ->
            if (firstAt == 0L) { firstAt = System.currentTimeMillis(); NetQuality.record(firstAt - t0, true) }   // time to first spoken sentence
            onSentence(sentence)
        }
        val end = t0 + deadlineMs()
        for (m in order) {
            val left = end - System.currentTimeMillis()
            if (left < 1500) break
            var delivered = false
            try {
                val contents = JSONArray()
                for ((role, text) in past) {
                    contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text))))
                }
                contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", q))))
                val cfg = JSONObject().put("temperature", 0.4).put("maxOutputTokens", 400)
                thinkingOff(m, cfg)
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPlain(name, search)))))
                    .put("contents", contents)
                    .put("generationConfig", cfg)
                if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:streamGenerateContent?alt=sse")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = minOf(connectMs(), left).toInt()
                    conn.readTimeout = minOf(if (NetQuality.lastLevel == NetQuality.Level.POOR) 12000L else 8000L, left).toInt()
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code != 200) {
                        if (code == 401 || code == 403) return false      // wrong key: other models will not help
                        continue
                    }
                    val buf = StringBuilder()
                    var first = true
                    fun flush(final: Boolean) {
                        while (true) {
                            val t = buf.toString()
                            var cut = -1
                            for (i in t.indices) {
                                val ch = t[i]
                                if ((ch == '.' || ch == '!' || ch == '?' || ch == '\u0964' || ch == '\n') &&
                                    (i == t.length - 1 || t[i + 1] == ' ' || t[i + 1] == '\n')) { cut = i + 1; break }
                            }
                            // first sentence still long: speak up to the first comma so the answer starts sooner
                            if (cut < 0 && first && t.length >= 70) { val c = t.indexOf(", "); if (c in 25..t.length - 2) cut = c + 1 }
                            if (cut < 0) break
                            val sentence = t.substring(0, cut).trim()
                            buf.delete(0, cut)
                            if (sentence.isNotEmpty()) { deliver(sentence); delivered = true; first = false }
                        }
                        if (final) {
                            val rest = buf.toString().trim()
                            buf.setLength(0)
                            if (rest.isNotEmpty()) { deliver(rest); delivered = true }
                        }
                    }
                    conn.inputStream.bufferedReader().use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val js = line.removePrefix("data:").trim()
                            if (js.isEmpty()) continue
                            val parts = try {
                                JSONObject(js).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                            } catch (e: Exception) { null } ?: continue
                            for (i in 0 until parts.length()) buf.append(parts.getJSONObject(i).optString("text"))
                            flush(false)
                        }
                    }
                    flush(true)
                    if (delivered) { good = m; return true }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (delivered) return true      // already speaking: keep what was said
            }
        }
        if (firstAt == 0L) NetQuality.record(System.currentTimeMillis() - t0, false)
        return false
    }

    private fun parse(raw: String): Reply {
        return try {
            val o = JSONObject(raw.removePrefix("```json").removeSuffix("```").trim())
            Reply(o.optString("reply").trim(), o.optString("lang").trim().ifEmpty { "en-IN" }, o.optString("command").trim())
        } catch (e: Exception) {
            Reply(raw, "en-IN", "")          // model ignored the JSON format: just speak the text
        }
    }
}
