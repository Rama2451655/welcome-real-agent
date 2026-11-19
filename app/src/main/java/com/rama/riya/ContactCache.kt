package com.rama.riya

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Every saved number is kept in memory, so the caller's name is found in microseconds instead of
 * waiting for a contacts database query while the phone is already ringing.
 *
 * Name order for an incoming call:
 *  1. saved contact (exact number, any format: +91..., 0..., spaces)
 *  2. likely name: same number with a different country code / prefix, or the name the phone's call log
 *     remembers for that number
 *  3. the number itself (read digit by digit by the caller)
 */
object ContactCache {
    private val byTail = ConcurrentHashMap<String, String>()   // last 10 digits -> contact name
    @Volatile private var started = false
    private var observer: ContentObserver? = null

    private fun digits(n: String) = n.filter { it.isDigit() }
    private fun tail(n: String, k: Int): String { val d = digits(n); return if (d.length > k) d.takeLast(k) else d }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        reload(app)
        try {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { reload(app) }
            }
            app.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, o)
            observer = o
        } catch (e: Exception) { }
    }

    fun stop(ctx: Context) {
        try { observer?.let { ctx.applicationContext.contentResolver.unregisterContentObserver(it) } } catch (e: Exception) { }
        observer = null
        started = false
    }

    @Volatile private var loading = false

    fun reload(ctx: Context) {
        if (loading) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        loading = true
        Thread {
            try {
                val fresh = HashMap<String, String>()
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val num = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val t = tail(num, 10)
                        if (t.length >= 6 && !fresh.containsKey(t)) fresh[t] = name
                    }
                }
                if (fresh.isNotEmpty()) { byTail.clear(); byTail.putAll(fresh) }
            } catch (e: Exception) { }
            loading = false
        }.start()
    }

    /** Instant, memory only. Safe on any thread. */
    fun quickName(number: String): String? {
        val t = tail(number, 10)
        if (t.length < 6) return null
        byTail[t]?.let { return it }
        // same person saved with another prefix (e.g. +91 vs 0 vs none): match on the last 8 digits
        if (t.length >= 8) {
            val k = t.takeLast(8)
            for ((key, name) in byTail) if (key.endsWith(k)) return name
        }
        return null
    }

    /** A few milliseconds of database work: call from a background thread, never from the UI thread. */
    fun slowName(ctx: Context, number: String): String? {
        quickName(number)?.let { return it }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.let { n -> if (n.isNotBlank()) return n }
                }
            } catch (e: Exception) { }
        }
        // likely name: the name the dialer remembered for this number in earlier calls
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            val t = tail(number, 9)
            if (t.length >= 7) {
                try {
                    ctx.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls.CACHED_NAME),
                        CallLog.Calls.CACHED_NAME + " IS NOT NULL AND " + CallLog.Calls.CACHED_NAME + " != '' AND " + CallLog.Calls.NUMBER + " LIKE ?",
                        arrayOf("%$t"),
                        CallLog.Calls.DATE + " DESC LIMIT 1"
                    )?.use {
                        if (it.moveToFirst()) it.getString(0)?.let { n -> if (n.isNotBlank()) return n }
                    }
                } catch (e: Exception) { }
            }
        }
        return null
    }
}
