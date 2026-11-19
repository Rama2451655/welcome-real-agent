package com.rama.riya

import android.content.Context
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileOutputStream

/**
 * One shared copy of the Vosk speech models for the whole app.
 * Loading a model from disk takes seconds; before this, every lock / unlock / video / signal change
 * reloaded it and Riya was deaf meanwhile. Now it is loaded once (preloaded when the service starts)
 * and every recogniser is created from it in a few milliseconds.
 */
object VoskModels {
    private val models = HashMap<String, Model>()
    private var spk: SpeakerModel? = null

    private fun copyAssets(ctx: Context, path: String, dest: File) {
        val names = ctx.assets.list(path)
        if (names == null || names.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (n in names) copyAssets(ctx, "$path/$n", File(dest, n))
        }
    }

    private fun prepare(ctx: Context, name: String): File {
        val src = "models/$name"
        val list = ctx.assets.list(src)
        if (list == null || list.isEmpty()) throw IllegalStateException("speech model '$name' is not inside the app")
        val dest = File(ctx.filesDir, "vosk/$name")
        val ok = File(dest, ".copied")
        if (!ok.exists()) {
            dest.deleteRecursively()
            copyAssets(ctx, src, dest)
            ok.writeText("1")
        }
        return dest
    }

    @Synchronized
    fun model(ctx: Context, name: String): Model {
        models[name]?.let { return it }
        val m = Model(prepare(ctx.applicationContext, name).absolutePath)
        models[name] = m
        return m
    }

    @Synchronized
    fun speaker(ctx: Context): SpeakerModel {
        spk?.let { return it }
        val s = SpeakerModel(prepare(ctx.applicationContext, "spk").absolutePath)
        spk = s
        return s
    }

    /** Load in the background so the first (and every later) start of the ears is instant. */
    fun preload(ctx: Context, name: String, withSpeaker: Boolean) {
        val app = ctx.applicationContext
        Thread {
            try { model(app, name) } catch (e: Throwable) { }
            if (withSpeaker) try { speaker(app) } catch (e: Throwable) { }
        }.also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun release() {
        // Vosk models are reference counted: closing here is safe even if a recogniser is still shutting down.
        for (m in models.values) try { m.close() } catch (e: Throwable) { }
        models.clear()
        try { (spk as? java.io.Closeable)?.close() } catch (e: Throwable) { }
        spk = null
    }
}
