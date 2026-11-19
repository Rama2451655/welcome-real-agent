package com.rama.riya

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Answers from the internet with no key: weather, DuckDuckGo instant answers and Wikipedia.
 * Used when the phone is online (especially on the lock screen, where the offline model is slow or silent).
 */
object WebAnswer {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, timeout: Int = 3500): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            val t = if (NetQuality.lastLevel == NetQuality.Level.POOR) timeout * 2 else timeout   // weak signal: wait a bit longer, it is the only way
            c.connectTimeout = t
            c.readTimeout = t
            c.setRequestProperty("User-Agent", "RiyaAssistant/2.0 (Android)")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private val lead = Regex(
        "^(please |hey |ok |okay )*(search( google)?( for)?|google|tell me about|tell me|what is|what's|what are|who is|who was|who are|" +
            "where is|when is|when was|how many|how much|define|meaning of|explain)\\s+",
        RegexOption.IGNORE_CASE
    )

    fun topic(q: String): String {
        var t = q.trim().trim('?', '.', '!')
        var prev: String
        do {
            prev = t
            t = t.replace(lead, "").trim()
        } while (t != prev && t.isNotEmpty())
        t = t.replace(Regex("^(the|a|an) ", RegexOption.IGNORE_CASE), "")
        return t.ifEmpty { q }
    }

    private fun weather(low: String): String? {
        val m = Regex("(?:weather|temperature|forecast)(?: like)?(?: in| at| for)? ([a-z ]+?)(?: today| now| tomorrow| right now)?$").find(low.trim())
        var place = m?.groupValues?.get(1)?.trim().orEmpty()
        if (place == "the" || place == "today" || place == "now") place = ""
        val txt = get("https://wttr.in/" + enc(place) + "?format=j1", 4500) ?: return null
        return try {
            val o = JSONObject(txt)
            val cc = o.getJSONArray("current_condition").getJSONObject(0)
            val desc = cc.getJSONArray("weatherDesc").getJSONObject(0).optString("value").lowercase()
            val area = try {
                o.getJSONArray("nearest_area").getJSONObject(0).getJSONArray("areaName").getJSONObject(0).optString("value")
            } catch (e: Exception) { "" }
            val where = if (area.isNotEmpty()) "In $area it is" else "It is"
            "$where ${cc.optString("temp_C")} degrees Celsius with $desc, feels like ${cc.optString("FeelsLikeC")}, humidity ${cc.optString("humidity")} percent."
        } catch (e: Exception) { null }
    }

    private fun wiki(topic: String): String? {
        val u = "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=" + enc(topic) +
            "&gsrlimit=1&prop=extracts&exintro=1&explaintext=1&exsentences=2&redirects=1"
        val o = JSONObject(get(u) ?: return null)
        val pages = o.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        if (!keys.hasNext()) return null
        val ex = pages.getJSONObject(keys.next()).optString("extract").trim()
        return ex.ifEmpty { null }
    }

    private fun ddg(topic: String): String? {
        val o = JSONObject(get("https://api.duckduckgo.com/?q=" + enc(topic) + "&format=json&no_html=1&skip_disambig=1&t=riya") ?: return null)
        val a = o.optString("Answer").trim()
        if (a.isNotEmpty() && !a.startsWith("{")) return a
        val ab = o.optString("AbstractText").trim()
        return ab.ifEmpty { null }
    }

    private fun shorten(s: String?): String? {
        if (s == null) return null
        var t = s.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\[[0-9]+]"), "").replace(Regex("\\s+"), " ").trim()
        if (t.length > 280) {
            val cut = t.lastIndexOf(". ", 280)
            t = if (cut > 60) t.substring(0, cut + 1) else t.substring(0, 280)
        }
        return t.ifEmpty { null }
    }

    /** Blocking (call from a worker thread). Null when nothing useful was found. */
    fun answer(q: String): String? {
        val low = q.lowercase()
        if (Regex("\\b(weather|temperature|forecast)\\b").containsMatchIn(low)) {
            weather(low)?.let { return it }
        }
        val t = topic(q)
        val f1 = pool.submit(Callable<String?> { try { ddg(t) } catch (e: Exception) { null } })
        val f2 = pool.submit(Callable<String?> { try { wiki(t) } catch (e: Exception) { null } })
        val a = try { f1.get(4500, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
        val b = try { f2.get(4500, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
        return shorten(if (a != null && a.length > 12) a else b)
    }
}
