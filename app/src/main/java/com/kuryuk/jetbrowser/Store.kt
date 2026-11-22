package com.kuryuk.jetbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Site(val title: String, val url: String, val time: Long = 0L)

/** Basit kalıcı depo: hızlı erişim, yer imleri, geçmiş, ayarlar. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("jet", Context.MODE_PRIVATE)

    private fun load(key: String): MutableList<Site> {
        val out = mutableListOf<Site>()
        val raw = p.getString(key, null) ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Site(o.optString("t"), o.optString("u"), o.optLong("d")))
            }
        } catch (_: Exception) { }
        return out
    }

    private fun save(key: String, list: List<Site>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        p.edit().putString(key, arr.toString()).apply()
    }

    var speedDial: MutableList<Site>
        get() = if (p.contains("dial")) load("dial") else mutableListOf(
            Site("Google", "https://www.google.com"),
            Site("YouTube", "https://m.youtube.com")
        )
        set(v) = save("dial", v)

    var bookmarks: MutableList<Site>
        get() = load("bm")
        set(v) = save("bm", v)

    var history: MutableList<Site>
        get() = load("hist")
        set(v) = save("hist", v)

    fun addHistory(title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val h = history
        h.removeAll { it.url == url }
        h.add(0, Site(title.ifBlank { url }, url, System.currentTimeMillis()))
        history = h.take(500).toMutableList()
    }

    var searchEngine: Int
        get() = p.getInt("engine", 0)
        set(v) = p.edit().putInt("engine", v).apply()

    var desktopMode: Boolean
        get() = p.getBoolean("desktop", false)
        set(v) = p.edit().putBoolean("desktop", v).apply()

    var adBlock: Boolean
        get() = p.getBoolean("adblock", true)
        set(v) = p.edit().putBoolean("adblock", v).apply()

    var popupBlock: Boolean
        get() = p.getBoolean("popup", true)
        set(v) = p.edit().putBoolean("popup", v).apply()

    /** null = cihaza göre (TV'de açık, telefonda kapalı) */
    fun cursorDefault(isTv: Boolean): Boolean = p.getBoolean("cursor", isTv)
    fun setCursorDefault(v: Boolean) = p.edit().putBoolean("cursor", v).apply()

    var cursorSpeed: Int
        get() = p.getInt("cspeed", 2)
        set(v) = p.edit().putInt("cspeed", v).apply()

    var textZoom: Int
        get() = p.getInt("zoom", 100)
        set(v) = p.edit().putInt("zoom", v).apply()

    companion object {
        val ENGINES = arrayOf("Google", "Bing", "DuckDuckGo", "Yandex")
        val ENGINE_URLS = arrayOf(
            "https://www.google.com/search?q=",
            "https://www.bing.com/search?q=",
            "https://duckduckgo.com/?q=",
            "https://yandex.com.tr/search/?text="
        )
    }
}
