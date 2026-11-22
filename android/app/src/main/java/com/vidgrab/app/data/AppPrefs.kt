package com.vidgrab.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/** Préférences persistantes : réglages, favoris, historique, PIN (SHA-256 salé), vidéos vues. */
object AppPrefs {
    private lateinit var sp: SharedPreferences

    val wifiOnly = MutableStateFlow(false)
    /** "dark" (défaut), "light" ou "system" */
    val themeMode = MutableStateFlow("dark")
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val viewed = MutableStateFlow<Set<String>>(emptySet())

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("vidgrab_prefs", Context.MODE_PRIVATE)
        wifiOnly.value = sp.getBoolean("wifi_only", false)
        themeMode.value = sp.getString("theme_mode", "dark") ?: "dark"
        bookmarks.value = readBookmarks()
        history.value = readHistory()
        viewed.value = sp.getStringSet("viewed", emptySet())?.toSet() ?: emptySet()
        if (bookmarks.value.isEmpty() && !sp.getBoolean("seeded", false)) {
            sp.edit().putBoolean("seeded", true).apply()
            bookmarks.value = listOf(
                Bookmark("Vimeo Staff Picks", "https://vimeo.com/channels/staffpicks"),
                Bookmark("Dailymotion", "https://www.dailymotion.com")
            )
            writeBookmarks()
        }
    }

    // ---- Réglages -------------------------------------------------------------------------
    fun setWifiOnly(v: Boolean) {
        sp.edit().putBoolean("wifi_only", v).apply()
        wifiOnly.value = v
    }

    fun setThemeMode(mode: String) {
        sp.edit().putString("theme_mode", mode).apply()
        themeMode.value = mode
    }

    // ---- Favoris --------------------------------------------------------------------------
    private fun readBookmarks(): List<Bookmark> = runCatching {
        val arr = JSONArray(sp.getString("bookmarks", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Bookmark(o.getString("t"), o.getString("u"))
        }
    }.getOrDefault(emptyList())

    private fun writeBookmarks() {
        val arr = JSONArray()
        bookmarks.value.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url)) }
        sp.edit().putString("bookmarks", arr.toString()).apply()
    }

    fun isBookmarked(url: String): Boolean = bookmarks.value.any { it.url == url }

    fun toggleBookmark(title: String, url: String): Boolean {
        val exists = isBookmarked(url)
        bookmarks.value = if (exists) bookmarks.value.filterNot { it.url == url }
        else bookmarks.value + Bookmark(title.ifBlank { hostOf(url) }, url)
        writeBookmarks()
        return !exists
    }

    fun removeBookmark(url: String) {
        bookmarks.value = bookmarks.value.filterNot { it.url == url }
        writeBookmarks()
    }

    fun renameBookmark(url: String, newTitle: String) {
        bookmarks.value = bookmarks.value.map { if (it.url == url) it.copy(title = newTitle) else it }
        writeBookmarks()
    }

    fun clearBookmarks() {
        bookmarks.value = emptyList()
        writeBookmarks()
    }

    // ---- Historique -----------------------------------------------------------------------
    private fun readHistory(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(sp.getString("history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(o.getString("t"), o.getString("u"), o.getLong("ts"))
        }
    }.getOrDefault(emptyList())

    private fun writeHistory() {
        val arr = JSONArray()
        history.value.forEach {
            arr.put(JSONObject().put("t", it.title).put("u", it.url).put("ts", it.timestamp))
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun addHistory(title: String, url: String) {
        if (!url.startsWith("http")) return
        val list = history.value.filterNot { it.url == url }.toMutableList()
        list.add(0, HistoryEntry(title.ifBlank { hostOf(url) }, url, System.currentTimeMillis()))
        history.value = list.take(100)
        writeHistory()
    }

    fun clearHistory() {
        history.value = emptyList()
        writeHistory()
    }

    fun hostOf(url: String): String = runCatching { Uri.parse(url).host ?: url }.getOrDefault(url).removePrefix("www.")

    // ---- Vidéos vues ----------------------------------------------------------------------
    fun markViewed(key: String) {
        val next = viewed.value + key
        viewed.value = next
        sp.edit().putStringSet("viewed", next).apply()
    }

    // ---- Persistance des téléchargements ----------------------------------------------------
    fun saveTasks(json: String) = sp.edit().putString("tasks", json).apply()
    fun loadTasks(): String = sp.getString("tasks", "[]") ?: "[]"

    // ---- Code PIN (SHA-256 salé) ------------------------------------------------------------
    fun hasPin(): Boolean = sp.contains("pin_hash") && sp.contains("pin_salt")

    fun setPin(pin: String) {
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        sp.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = sp.getString("pin_salt", null) ?: return false
        val expected = sp.getString("pin_hash", null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(salt, pin).toByteArray())
    }

    fun clearPin() = sp.edit().remove("pin_hash").remove("pin_salt").apply()

    private fun hash(salt: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
