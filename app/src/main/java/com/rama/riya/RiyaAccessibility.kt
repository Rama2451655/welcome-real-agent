package com.rama.riya

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Path
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityWindowInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 1) Presses WhatsApp's Send button, only for ~20 seconds after Riya opens a prefilled chat.
 * 2) Works offline: home / back / recents / notifications / screenshot, and "close all apps".
 */
class RiyaAccessibility : AccessibilityService() {

    companion object {
        @Volatile var armedUntil = 0L
        @Volatile var instance: RiyaAccessibility? = null
        fun arm() { armedUntil = System.currentTimeMillis() + 20000 }
    }

    private val worker = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
        makeChannel()
    }

    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    override fun onDestroy() { instance = null; worker.shutdownNow(); super.onDestroy() }

    // The text box you last tapped / placed the cursor in (so "start typing" types exactly there)
    @Volatile private var pinned: AccessibilityNodeInfo? = null
    @Volatile private var pinnedAt = 0L

    // ---------- payment / banking apps: tell the user to switch Riya off, then back on ----------
    // Riya cannot switch her own Accessibility on or off (Android blocks that, on purpose, for the same reason
    // banking apps block Riya: an app that could silently flip this would be indistinguishable from a banking
    // trojan doing the same thing). So this only ever points the user to the Settings switch, one tap away.

    private var watchedPkg: String? = null     // the payment app currently in front, or null

    private fun watchList(): Set<String> {
        val def = setOf(
            "net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
            "com.dreamplug.androidapp", "com.freecharge.android", "com.mobikwik_new", "com.amazon.mShop.android.shopping",
            "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.csam.icici.bank.imobile", "com.icicibank.pockets",
            "com.snapwork.hdfc", "com.axis.mobile", "com.axisbank.mobile", "com.msf.kbank.mobile", "com.fss.pnbone",
            "com.konylabs.pnb", "com.infrasoft.uboi", "com.obi.mobilebanking", "com.rblbank.mobank",
            "com.yesbank.mobile", "com.idbibank", "com.federal.frontier", "com.Bankofbaroda.mconnect"
        )
        val extra = getSharedPreferences("riya", Context.MODE_PRIVATE).getString("pay_watch_extra", "") ?: ""
        val removed = getSharedPreferences("riya", Context.MODE_PRIVATE).getStringSet("pay_watch_removed", emptySet()) ?: emptySet()
        return (def + extra.split(",").map { it.trim() }.filter { it.isNotEmpty() }) - removed
    }

    private fun makeChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("riya_pay_guard", "Payment app opened", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun accessibilitySettingsIntent(): PendingIntent {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 771, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notifyPayGuard(title: String, text: String) {
        if (!getSharedPreferences("riya", Context.MODE_PRIVATE).getBoolean("pay_guard", true)) return
        val n = Notification.Builder(this, "riya_pay_guard")
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(accessibilitySettingsIntent())
            .addAction(Notification.Action.Builder(null, "Open Accessibility settings", accessibilitySettingsIntent()).build())
            .setAutoCancel(true).setOnlyAlertOnce(false)
            .build()
        try { (getSystemService(NotificationManager::class.java)).notify(772, n) } catch (e: Exception) { }
    }

    private fun checkPayGuard(pkg: String) {
        val inList = pkg in watchList()
        if (inList && watchedPkg != pkg) {
            watchedPkg = pkg
            notifyPayGuard("Payment app opened",
                "If it blocks Riya for security: tap here or the Riya tile in Quick Settings, switch Riya off, then open the app again.")
        } else if (!inList && watchedPkg != null && pkg != packageName) {
            watchedPkg = null
            notifyPayGuard("Payment app closed", "Tap here or the Riya tile in Quick Settings, then switch Riya back on so your voice commands work again.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { checkPayGuard(it) }
        }
        if (event != null) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                    val src = event.source
                    if (src != null && src.isEditable && event.packageName?.toString() != packageName) {
                        pinned = src
                        pinnedAt = System.currentTimeMillis()
                    }
                }
            }
        }
        if (System.currentTimeMillis() > armedUntil) return
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val root = rootInActiveWindow ?: return
        val ids = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && click(nodes[0])) { armedUntil = 0; return }
        }
        val byText = root.findAccessibilityNodeInfosByText("Send")
        if (byText.isNotEmpty() && click(byText[0])) armedUntil = 0
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = n
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        return false
    }

    // ---------- type by voice: works in WhatsApp, mail, notes, browsers ... ----------

    @Volatile private var lastInserted = ""
    private var visitedE = 0

    private fun firstEditable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 30 || visitedE > 3000) return null
        visitedE++
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val r = firstEditable(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun appRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { if (it.packageName?.toString() != packageName) roots.add(it) }
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val r = w.root
                    if (r != null && r.packageName?.toString() != packageName) roots.add(r)
                }
            }
        } catch (e: Exception) { }
        return roots
    }

    /** The text box that has the cursor, else the one you last tapped. Never jumps to some other box. */
    private fun inputNode(): AccessibilityNodeInfo? {
        for (r in appRoots()) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        val p = pinned
        if (p != null && System.currentTimeMillis() - pinnedAt < 10 * 60_000L) {
            try { if (p.refresh() && p.isEditable && p.isVisibleToUser) return p } catch (e: Exception) { }
        }
        return null
    }

    /** Remember the box that has the cursor right now. */
    fun pinTarget(): Boolean {
        val n = inputNode() ?: return false
        pinned = n
        pinnedAt = System.currentTimeMillis()
        return true
    }

    private fun fieldText(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else (n.text?.toString() ?: "")

    /** Text left of the cursor, or null when no text box is available. */
    fun textBeforeCursor(): String? {
        val n = inputNode() ?: return null
        val t = fieldText(n)
        val sel = n.textSelectionStart
        return t.substring(0, if (sel in 0..t.length) sel else t.length)
    }

    /** Types [s] at the cursor. */
    fun insertAtCursor(s: String): Boolean {
        val n = inputNode() ?: return false
        n.refresh()
        val cur = fieldText(n)
        val sel = n.textSelectionStart.let { if (it in 0..cur.length) it else cur.length }
        val end = n.textSelectionEnd.let { if (it in sel..cur.length) it else sel }
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur.substring(0, sel) + s + cur.substring(end))
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) {
            val sb = Bundle()
            sb.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, sel + s.length)
            sb.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, sel + s.length)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sb)
            lastInserted = s
            return true
        }
        // some apps refuse set-text: paste instead
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("riya", s))
            val ok = n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (ok) lastInserted = s
            ok
        } catch (e: Exception) { false }
    }

    fun deleteLastInserted(): Boolean {
        val n = inputNode() ?: return false
        val cur = fieldText(n)
        val idx = if (lastInserted.isEmpty()) -1 else cur.lastIndexOf(lastInserted)
        if (idx < 0) return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur.removeRange(idx, idx + lastInserted.length))
        lastInserted = ""
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun clearField(): Boolean {
        val n = inputNode() ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun findSend(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 30 || visitedE > 3000) return null
        visitedE++
        val d = (n.contentDescription?.toString() ?: "").trim().lowercase()
        val t = (n.text?.toString() ?: "").trim().lowercase()
        val id = (n.viewIdResourceName ?: "").lowercase()
        if (d == "send" || t == "send" || d.startsWith("send ") || id.endsWith(":id/send") || id.endsWith("/send_button")) return n
        for (i in 0 until n.childCount) {
            val r = findSend(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    /** Presses the Send button of the app on screen (WhatsApp, Gmail, SMS ...). */
    fun pressSend(): Boolean {
        for (r in appRoots()) {
            visitedE = 0
            val s = findSend(r, 0)
            if (s != null && click(s)) return true
        }
        return try {
            val n = inputNode()
            n != null && android.os.Build.VERSION.SDK_INT >= 30 &&
                n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } catch (e: Exception) { false }
    }

    // ---------- generic screen helpers (used by the UPI payment flow; PIN screens are never read) ----------

    private fun collect(n: AccessibilityNodeInfo?, depth: Int, out: ArrayList<AccessibilityNodeInfo>) {
        if (n == null || depth > 30 || out.size > 700) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), depth + 1, out)
    }

    fun allNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (r in appRoots()) collect(r, 0, out)
        return out
    }

    /** Text + description of a node, lower case. Password fields are skipped. */
    fun labelOf(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return ""
        return ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim().lowercase()
    }

    fun screenLabels(max: Int): List<String> =
        allNodes().map { labelOf(it) }.filter { it.isNotEmpty() }.distinct().take(max)

    fun screenHas(keys: List<String>): Boolean {
        for (n in allNodes()) {
            val l = labelOf(n)
            if (l.isNotEmpty() && keys.any { l.contains(it) }) return true
        }
        return false
    }

    /** Clicks the first non-editable item matching a key; earlier keys win. */
    fun clickLabel(keys: List<String>, exact: Boolean): Boolean {
        val nodes = allNodes()
        for (k in keys) {
            for (n in nodes) {
                if (n.isEditable) continue
                val l = labelOf(n)
                if (l.isEmpty()) continue
                val hit = if (exact) l == k else l.contains(k)
                if (hit && click(n)) return true
            }
        }
        return false
    }

    fun editableNode(keys: List<String>): AccessibilityNodeInfo? {
        for (n in allNodes()) {
            if (!n.isEditable || !n.isVisibleToUser || n.isPassword) continue
            if (keys.isEmpty()) return n
            val l = ((n.hintText?.toString() ?: "") + " " + (n.viewIdResourceName ?: "") + " " + (n.text?.toString() ?: "") + " " +
                (n.contentDescription?.toString() ?: "")).lowercase()
            if (keys.any { l.contains(it) }) return n
        }
        return null
    }

    fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return true
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("riya", text))
            n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) { false }
    }

    // ---------- reels / shorts / video control ----------

    fun foregroundPkg(): String? = appRoots().firstOrNull()?.packageName?.toString()

    private fun hasId(n: AccessibilityNodeInfo?, depth: Int, keys: List<String>): Boolean {
        if (n == null || depth > 25 || visitedE > 2500) return false
        visitedE++
        val id = (n.viewIdResourceName ?: "").lowercase()
        if (keys.any { id.contains(it) }) return true
        for (i in 0 until n.childCount) if (hasId(n.getChild(i), depth + 1, keys)) return true
        return false
    }

    /** Instagram, Facebook, TikTok, Snapchat and YouTube Shorts: swiping moves to the next / previous video. */
    fun looksLikeReels(): Boolean {
        val pkg = foregroundPkg() ?: return false
        if (pkg.startsWith("com.instagram") || pkg == "com.facebook.katana" || pkg == "com.facebook.lite" || pkg.startsWith("com.zhiliaoapp") ||
            pkg.startsWith("com.ss.android.ugc") || pkg == "com.snapchat.android" || pkg == "in.mohalla.sharechat" || pkg == "in.mohalla.video" ||
            pkg == "com.eterno.shortvideos" || pkg == "com.roposo.android" || pkg == "com.twitter.android" || pkg == "com.pinterest") return true
        if (pkg == "com.google.android.youtube") {
            for (r in appRoots()) { visitedE = 0; if (hasId(r, 0, listOf("reel", "shorts", "short_"))) return true }
            if (screenHas(listOf("remix", "shorts player", "shorts video"))) return true      // view ids are hidden in some YouTube versions
        }
        return false
    }

    private fun gesture(p: Path, dur: Long): Boolean = try {
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(), null, null)
    } catch (e: Exception) { false }

    /** up = finger moves up = NEXT reel; down = PREVIOUS reel. */
    fun swipe(up: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val h = dm.heightPixels.toFloat()
        val p = Path()
        if (up) { p.moveTo(x, h * 0.80f); p.lineTo(x, h * 0.22f) } else { p.moveTo(x, h * 0.22f); p.lineTo(x, h * 0.80f) }
        return gesture(p, 150)          // a quick, long flick: Reels and Shorts ignore slow short swipes
    }

    /** A tap in the middle of the video pauses / resumes it in Reels, Shorts and most players. */
    fun tapCenter(): Boolean {
        val dm = resources.displayMetrics
        val p = Path()
        p.moveTo(dm.widthPixels * 0.5f, dm.heightPixels * 0.42f)
        return gesture(p, 50)
    }

    // ---------- WhatsApp call buttons (fallback when the direct call shortcut is not available) ----------

    private fun findCallBtn(n: AccessibilityNodeInfo?, depth: Int, video: Boolean): AccessibilityNodeInfo? {
        if (n == null || depth > 30 || visitedE > 3000) return null
        visitedE++
        val d = (n.contentDescription?.toString() ?: "").trim().lowercase()
        val hit = if (video) d == "video call" else (d == "voice call" || d == "audio call" || d == "call")
        if (hit) return n
        for (i in 0 until n.childCount) {
            val r = findCallBtn(n.getChild(i), depth + 1, video)
            if (r != null) return r
        }
        return null
    }

    private fun findConfirm(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 30 || visitedE > 3000) return null
        visitedE++
        val t = (n.text?.toString() ?: "").trim().lowercase()
        val cls = n.className?.toString() ?: ""
        if (cls.contains("Button") && n.isClickable && (t == "call" || t == "voice call" || t == "video call" || t == "start")) return n
        for (i in 0 until n.childCount) {
            val r = findConfirm(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    /** In an open WhatsApp chat: taps the voice / video call button (and the confirm button if WhatsApp asks). */
    fun pressCallButton(video: Boolean): Boolean {
        var pressed = false
        for (r in appRoots()) {
            visitedE = 0
            val b = findCallBtn(r, 0, video)
            if (b != null && click(b)) { pressed = true; break }
        }
        if (!pressed) return false
        Thread.sleep(1200)
        for (r in appRoots()) {
            visitedE = 0
            val c = findConfirm(r, 0)
            if (c != null) { click(c); break }
        }
        return true
    }

    // ---------- in-call buttons and Quick Settings tiles (v2.7) ----------

    /** Every window's root, system windows too (the Quick Settings panel belongs to System UI). */
    private fun allWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { if (it.packageName?.toString() != packageName) roots.add(it) }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() != packageName) roots.add(r)
            }
        } catch (e: Exception) { }
        return roots
    }

    private fun nodesOf(roots: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (r in roots) collect(r, 0, out)
        return out
    }

    /** true = on, false = off, null = the tile does not say. */
    private fun nodeState(n: AccessibilityNodeInfo): Boolean? {
        if (n.isCheckable) return n.isChecked
        var s = ""
        if (Build.VERSION.SDK_INT >= 30) s = (n.stateDescription?.toString() ?: "").lowercase()
        s += " " + labelOf(n)
        if (Regex("(^|[\\s,])(off|disabled)($|[\\s,.])").containsMatchIn(s)) return false
        if (Regex("(^|[\\s,])(on|enabled)($|[\\s,.])").containsMatchIn(s)) return true
        return null
    }

    /**
     * Switches Wi-Fi or Bluetooth through the Quick Settings panel (Android no longer lets apps do it directly).
     * Returns 1 = switched, 2 = it was already so, 0 = could not. Blocks, so call it from a background thread.
     */
    fun toggleRadio(kind: String, want: Boolean): Int {
        val keys = if (kind == "bluetooth") listOf("bluetooth") else listOf("wi-fi", "wifi", "internet")
        fun tileOf(): AccessibilityNodeInfo? {
            for (n in nodesOf(allWindowRoots())) {
                if (n.isEditable) continue
                val l = labelOf(n)
                if (l.isEmpty() || l.length > 80) continue
                if (!keys.any { l.contains(it) }) continue
                if ((n.className?.toString() ?: "").contains("Switch")) continue
                if (n.isClickable || n.isCheckable) return n
            }
            return null
        }
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        Thread.sleep(1100)
        var result = 0
        var dialog = false
        try {
            val tile = tileOf() ?: return 0
            if (nodeState(tile) == want) {
                result = 2
            } else {
                click(tile)
                Thread.sleep(1000)
                // Android 12+: the Wi-Fi tile opens a small "Internet" panel with the real switch inside
                val sw = nodesOf(allWindowRoots()).firstOrNull { n ->
                    val cls = n.className?.toString() ?: ""
                    val id = (n.viewIdResourceName ?: "").lowercase()
                    n.isCheckable && (cls.contains("Switch") || cls.contains("Toggle")) &&
                        (keys.any { id.contains(it.replace("-", "")) } || keys.any { labelOf(n).contains(it) })
                }
                if (sw != null) {
                    dialog = true
                    if (sw.isChecked != want) click(sw)
                    result = 1
                } else {
                    val after = tileOf()?.let { nodeState(it) }
                    result = if (after == null || after == want) 1 else 0
                }
            }
        } finally {
            try {
                if (dialog) { performGlobalAction(GLOBAL_ACTION_BACK); Thread.sleep(350) }
                if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                else performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (e: Exception) { }
        }
        return result
    }

    /**
     * On the in-call screen: presses the first button whose label matches (earlier keys win).
     * want = the state a toggle button should end up in (null = just press). 0 = not found, 1 = pressed, 2 = already so.
     */
    fun pressInCallButton(keys: List<String>, want: Boolean?, exact: Boolean = false): Int {
        val nodes = allNodes()
        for (k in keys) {
            for (n in nodes) {
                if (n.isEditable) continue
                val l = labelOf(n)
                if (l.isEmpty() || l.length > 40) continue
                if (!(if (exact) l == k else l.contains(k))) continue
                if (want != null && n.isCheckable && n.isChecked == want) return 2
                if (click(n)) return 1
            }
        }
        return 0
    }

    // ---------- offline phone controls ----------

    /** Opens the recent-apps screen and clears it. Blocks, so call it from a background thread. */
    fun clearRecents(): Boolean {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(500)
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        Thread.sleep(1600)
        var cleared = false
        // 1) find the "clear all" button (text, description or id), scrolling the carousel if needed
        for (attempt in 0 until 3) {
            if (pressClearAll()) { cleared = true; break }
            swipeRight()
            Thread.sleep(600)
        }
        // 2) works on every phone: if there is no button (Realme, Oppo, Samsung ...), swipe each card up
        if (!cleared) {
            var any = false
            for (i in 0 until 14) {
                if (swipeUpCard()) any = true
                Thread.sleep(380)
            }
            cleared = any
        }
        Thread.sleep(500)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return cleared
    }

    private val buttonWords = listOf(
        "clear all", "close all", "clear_all", "clearall", "close_all", "closeall", "delete all", "delete_all",
        "dismiss all", "dismiss_all", "remove all", "remove_all", "clear-all", "clear_recent", "clear all apps",
        "clean_all", "trash", "clear_button", "clear_btn"
    )

    private fun isClearButton(n: AccessibilityNodeInfo): Boolean {
        val id = (n.viewIdResourceName ?: "").lowercase()
        val txt = (n.text?.toString() ?: "").lowercase()
        val desc = (n.contentDescription?.toString() ?: "").lowercase()
        return buttonWords.any { id.contains(it) || txt.contains(it) || desc.contains(it) }
    }

    private var visited = 0

    private fun findClear(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 30 || visited > 3000) return null
        visited++
        if (isClearButton(n)) return n
        for (i in 0 until n.childCount) {
            val r = findClear(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun pressClearAll(): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try { for (w in windows) { w.root?.let { roots.add(it) } } } catch (e: Exception) { }
        for (root in roots) {
            visited = 0
            val hit = findClear(root, 0) ?: continue
            if (click(hit)) return true
            if (tapNode(hit)) return true      // button not "clickable" for accessibility: tap its position
        }
        return false
    }

    private fun tapNode(n: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val p = Path()
        p.moveTo(r.exactCenterX(), r.exactCenterY())
        return gesture(GestureDescription.StrokeDescription(p, 0, 60))
    }

    private fun gesture(stroke: GestureDescription.StrokeDescription): Boolean {
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var done = false
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (ok) latch.await(1500, TimeUnit.MILLISECONDS)
        return ok && done
    }

    private fun swipeRight() {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.45f
        val p = Path()
        p.moveTo(dm.widthPixels * 0.2f, y)
        p.lineTo(dm.widthPixels * 0.85f, y)
        gesture(GestureDescription.StrokeDescription(p, 0, 250))
    }

    /** Flicks the centre recent-app card upwards (that closes it). */
    private fun swipeUpCard(): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val p = Path()
        p.moveTo(x, dm.heightPixels * 0.62f)
        p.lineTo(x, dm.heightPixels * 0.10f)
        return gesture(GestureDescription.StrokeDescription(p, 0, 160))
    }

    // ---------- Unlock the phone with the saved PIN, and app-lock screens (never the UPI PIN) ----------

    private fun sleepMs(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) { } }

    private fun isLockWindowPkg(pkg: String?): Boolean {
        val p = (pkg ?: "").lowercase()
        return p.contains("systemui") || p.contains("keyguard") || p.contains("lockscreen")
    }

    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE, "riya:unlock")
            wl.acquire(6000L)
        } catch (e: Exception) { }
    }

    /** Finger moves up from the bottom of the lock screen: opens the PIN pad. */
    private fun swipeKeyguardUp(): Boolean {
        val dm = resources.displayMetrics
        val p = Path()
        p.moveTo(dm.widthPixels * 0.5f, dm.heightPixels * 0.88f)
        p.lineTo(dm.widthPixels * 0.5f, dm.heightPixels * 0.30f)
        return gesture(GestureDescription.StrokeDescription(p, 0, 260))
    }

    /** The digit keys 0-9 of a number pad (lock screen, or an app's own pad). */
    private fun digitKeys(nodes: List<AccessibilityNodeInfo>, lockWindowsOnly: Boolean): Map<Char, AccessibilityNodeInfo> {
        val out = HashMap<Char, AccessibilityNodeInfo>()
        val idRe = Regex(":id/(?:key|digit|num|number|numpad|keypad|btn|button|pin|pinpad)_?([0-9])$", RegexOption.IGNORE_CASE)
        val descRe = Regex("^([0-9])(?:[ ,.]+[A-Za-z ]{0,12})?$")
        for (n in nodes) {
            if (lockWindowsOnly && !isLockWindowPkg(n.packageName?.toString())) continue
            if (!n.isVisibleToUser) continue
            var d: Char? = idRe.find(n.viewIdResourceName ?: "")?.groupValues?.get(1)?.get(0)
            if (d == null) {
                val t = n.text?.toString()?.trim() ?: ""
                val cd = n.contentDescription?.toString()?.trim() ?: ""
                d = when {
                    t.length == 1 && t[0].isDigit() -> t[0]
                    cd.length == 1 && cd[0].isDigit() -> cd[0]
                    cd.isNotEmpty() && lockWindowsOnly && descRe.matches(cd) -> cd[0]      // "2 ABC" style keys
                    t.isNotEmpty() && lockWindowsOnly && descRe.matches(t) -> t[0]
                    else -> null
                }
            }
            if (d != null && !out.containsKey(d)) out[d] = n
        }
        return out
    }

    private fun padKeys(lockWindowsOnly: Boolean): Map<Char, AccessibilityNodeInfo> {
        val nodes = nodesOf(allWindowRoots())
        val k = digitKeys(nodes, lockWindowsOnly)
        if (k.size >= 8 || !lockWindowsOnly) return k
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return if (km.isKeyguardLocked) digitKeys(nodes, false) else k
    }

    private fun typePinOnPad(pin: String, lockWindowsOnly: Boolean): Boolean {
        for (ch in pin) {
            val keys = padKeys(lockWindowsOnly)    // looked up again each time: the pad redraws
            val n = keys[ch] ?: return false
            if (!(click(n) || tapNode(n))) return false
            sleepMs(100)
        }
        return true
    }

    private fun pressEnterKey(lockWindowsOnly: Boolean): Boolean {
        for (n in nodesOf(allWindowRoots())) {
            if (!n.isVisibleToUser) continue
            val id = (n.viewIdResourceName ?: "").lowercase()
            val l = labelOf(n)
            if ((id.endsWith("key_enter") || id.endsWith("enter_key") || l == "enter" || l == "ok" || l == "done") && (click(n) || tapNode(n))) return true
        }
        return false
    }

    /**
     * Wakes the phone, opens the PIN pad and taps the saved PIN. Needs a number-pad lock screen.
     * Blocks for up to about 12 seconds: call it from a background thread. true = the phone is unlocked now.
     */
    /** Why the last unlockWithPin failed: "pin" (not a number PIN) | "nopad" (number pad never appeared) | "tap" (a key could not be pressed) | "wrong" (typed, still locked). */
    @Volatile var unlockFail = ""

    fun unlockWithPin(pin: String): Boolean {
        unlockFail = ""
        if (pin.length < 4 || !pin.all { it.isDigit() }) { unlockFail = "pin"; return false }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!km.isKeyguardLocked) return true
        wakeScreen()
        // look for the number pad straight away; swipe up only when it is not there
        var padReady = false
        for (swipe in 0 until 4) {
            val waitSteps = if (swipe == 0) 5 else 7
            for (i in 0 until waitSteps) {
                if (!km.isKeyguardLocked) return true
                val keys = padKeys(true)
                if (keys.size >= 8 && pin.all { keys.containsKey(it) }) { padReady = true; break }
                sleepMs(150)
            }
            if (padReady) break
            swipeKeyguardUp()
        }
        if (!padReady) { unlockFail = "nopad"; return false }
        if (!typePinOnPad(pin, true)) { unlockFail = "tap"; return false }
        sleepMs(250)
        if (km.isKeyguardLocked) pressEnterKey(true)
        for (i in 0 until 14) {
            if (!km.isKeyguardLocked) return true
            sleepMs(200)
        }
        unlockFail = "wrong"
        return !km.isKeyguardLocked
    }

    /**
     * true when the app in front shows its OWN lock screen (app PIN / passcode / "confirm your screen lock").
     * Never true for a UPI PIN screen: anything that mentions UPI, a bank or an amount is refused.
     */
    fun onAppLockScreen(): Boolean {
        val nodes = nodesOf(allWindowRoots())
        val text = nodes.map { labelOf(it) }.filter { it.isNotEmpty() }
        if (text.any { it.contains("upi") || it.contains("\u20b9") || it.contains("bank") || it.contains("rs.") }) return false
        val lockWords = listOf("unlock", "app lock", "passcode", "screen lock", "device pin", "enter your pin", "enter pin",
            "app pin", "confirm your pin", "confirm your password", "verify it")
        if (text.none { t -> lockWords.any { t.contains(it) } }) return false
        return digitKeys(nodes, false).size >= 9
    }

    fun typeAppPin(pin: String): Boolean {
        if (!pin.all { it.isDigit() }) return false
        if (!typePinOnPad(pin, false)) return false
        sleepMs(300)
        pressEnterKey(false)
        return true
    }

    override fun onInterrupt() {}
}
