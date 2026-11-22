package com.vidgrab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Suggestions de recherche Google en direct (timeout 3 s, échec silencieux hors ligne). */
object SuggestApi {
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    }

    suspend fun fetch(query: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://suggestqueries.google.com/complete/search?client=firefox&q=$q")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList<String>()
                val body: String = r.body?.string() ?: return@withContext emptyList<String>()
                val arr = JSONArray(body).getJSONArray(1)
                (0 until arr.length()).map { arr.getString(it) }.take(5)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
