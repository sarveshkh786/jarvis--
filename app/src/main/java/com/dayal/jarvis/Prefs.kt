package com.dayal.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun geminiKey(c: Context): String = p(c).getString("gemini_key", "") ?: ""
    fun model(c: Context): String = p(c).getString("model", "gemini-flash-latest") ?: "gemini-flash-latest"
    fun lang(c: Context): String = p(c).getString("lang", "hi-IN") ?: "hi-IN"

    fun save(c: Context, key: String, model: String, lang: String) {
        p(c).edit()
            .putString("gemini_key", key.trim())
            .putString("model", model.trim().ifBlank { "gemini-flash-latest" })
            .putString("lang", lang)
            .apply()
    }
}

/** Small in-memory log shown on the app screen. */
object JarvisLog {
    private val lines = ArrayDeque<String>()
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun add(s: String) {
        synchronized(lines) {
            lines.addFirst(s)
            while (lines.size > 40) lines.removeLast()
        }
        main.post { listener?.invoke() }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}
