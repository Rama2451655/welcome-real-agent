package com.rama.riya

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/**
 * Instant answer to "how good is the connection right now?".
 * NONE = no internet, POOR = weak / slow / not yet verified, GOOD = fine.
 * Besides what Android reports it learns from real requests: if recent answers were slow or failed it says POOR,
 * so Riya switches to the on-phone ears, voice and brain before the user has to wait.
 */
object NetQuality {
    enum class Level { NONE, POOR, GOOD }

    @Volatile var lastLevel = Level.GOOD
        private set
    @Volatile private var ewmaMs = 0.0
    @Volatile private var failStreak = 0
    @Volatile private var lastRecord = 0L

    /** Called by the online brains after every real request. */
    fun record(latencyMs: Long, ok: Boolean) {
        lastRecord = System.currentTimeMillis()
        if (!ok) { failStreak++; return }
        failStreak = 0
        ewmaMs = if (ewmaMs == 0.0) latencyMs.toDouble() else ewmaMs * 0.6 + latencyMs * 0.4
    }

    private fun measurementsFresh() = System.currentTimeMillis() - lastRecord < 90_000L

    fun level(ctx: Context): Level {
        val l = compute(ctx)
        lastLevel = l
        return l
    }

    private fun compute(ctx: Context): Level {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return Level.NONE
            val caps = cm.getNetworkCapabilities(net) ?: return Level.NONE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Level.NONE
            // connected but Android has not verified internet (captive portal, very weak signal): try, but treat as weak
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return Level.POOR
            val down = caps.linkDownstreamBandwidthKbps
            if (down in 1..1500) return Level.POOR                       // 2G / EDGE / very weak 3G
            if (Build.VERSION.SDK_INT >= 29 && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val s = caps.signalStrength
                if (s != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && s < -80) return Level.POOR   // weak Wi-Fi
            }
            if (measurementsFresh() && (ewmaMs > 3500 || failStreak >= 2)) return Level.POOR
            return Level.GOOD
        } catch (e: Exception) {
            return Level.GOOD
        }
    }

    /** Calls [onChange] whenever the connection appears, disappears or changes quality. */
    fun watch(ctx: Context, onChange: () -> Unit): ConnectivityManager.NetworkCallback? {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()
                override fun onLost(network: Network) = onChange()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
            }
            cm.registerDefaultNetworkCallback(cb)
            cb
        } catch (e: Exception) { null }
    }

    fun unwatch(ctx: Context, cb: ConnectivityManager.NetworkCallback?) {
        if (cb == null) return
        try { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (e: Exception) { }
    }

    /**
     * Warms up DNS and the radio path to Google ahead of time, so the first question after a quiet moment
     * does not pay for the slow lookup on a weak signal. It also measures the real round trip. Call from a background thread.
     */
    fun prewarm() {
        val t0 = System.currentTimeMillis()
        try {
            val c = URL("https://generativelanguage.googleapis.com/").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.requestMethod = "HEAD"
                c.responseCode          // any answer (even 404) means the handshake is done
            } finally { c.disconnect() }
            record(System.currentTimeMillis() - t0, true)
        } catch (e: Exception) {
            record(System.currentTimeMillis() - t0, false)
        }
    }
}
