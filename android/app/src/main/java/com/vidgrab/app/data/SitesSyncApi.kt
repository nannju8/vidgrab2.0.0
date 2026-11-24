package com.vidgrab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** Récupère la liste des sites/liens à jour depuis un fichier JSON distant (GitHub). */
object SitesSyncApi {
    // Remplace par l'URL raw de ton fichier sites.json sur GitHub
    const val DEFAULT_SYNC_URL =
        "https://raw.githubusercontent.com/nannju8/vidgrab/main/sites.json"

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    }

    suspend fun fetchRemoteSites(url: String = DEFAULT_SYNC_URL): Result<List<Bookmark>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val body = r.body?.string().orEmpty()
                    val arr = JSONArray(body)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        val title = o.optString("title").trim()
                        val siteUrl = o.optString("url").trim()
                        if (title.isNotEmpty() && siteUrl.startsWith("http")) Bookmark(title, siteUrl) else null
                    }
                }
            }
        }
}
