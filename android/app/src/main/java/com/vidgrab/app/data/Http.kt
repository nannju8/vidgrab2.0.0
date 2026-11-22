package com.vidgrab.app.data

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** OkHttpClient singleton optimisé + registre des Call actifs par tâche (annulation instantanée). */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()

    fun register(taskId: String, call: Call) {
        activeCalls.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }.add(call)
    }

    fun unregister(taskId: String, call: Call) {
        activeCalls[taskId]?.remove(call)
    }

    /** Ferme immédiatement tous les sockets d'une tâche (Pause / Annuler). */
    fun cancelAll(taskId: String) {
        activeCalls.remove(taskId)?.forEach { runCatching { it.cancel() } }
    }

    private val forbidden = setOf("range", "host", "content-length", "accept-encoding", "connection")

    /** Construit une requête GET en réinjectant les en-têtes capturés (Referer, Origin, UA, Cookie...). */
    fun request(url: String, headers: Map<String, String>, range: String? = null): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (k.lowercase() !in forbidden && v.isNotBlank()) runCatching { b.header(k, v) }
        }
        b.header("Accept-Encoding", "identity")
        if (range != null) b.header("Range", range)
        return b.build()
    }
}
