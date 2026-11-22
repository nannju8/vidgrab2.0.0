package com.vidgrab.app.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.data.DlStatus
import com.vidgrab.app.data.DownloadTask
import com.vidgrab.app.data.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.vidgrab.app.data.Http
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cœur du système de téléchargement : file d'attente (MAX_PARALLEL = 2), pause/reprise/annulation,
 * politique « Wi-Fi uniquement » (ConnectivityManager.NetworkCallback) et persistance des tâches.
 */
object DownloadCenter {
    const val MAX_PARALLEL = 2

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    /** Messages ponctuels (toasts / snackbars). */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Émis quand un fichier a été ajouté à la bibliothèque. */
    val libraryChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val onWifi = MutableStateFlow(true)
    /** Vrai si des téléchargements sont suspendus à cause du mode « Wi-Fi uniquement ». */
    val wifiBlocked = MutableStateFlow(false)

    private val speedSamples = ConcurrentHashMap<String, LongArray>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        restore()
        registerNetworkCallback()
        scope.launch { AppPrefs.wifiOnly.collect { enforceNetworkPolicy() } }
    }

    // ---- API publique ---------------------------------------------------------------------
    fun isActive(): Boolean = _tasks.value.any {
        it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED || it.status == DlStatus.MERGING
    }

    fun enqueue(video: DetectedVideo): Boolean {
        if (_tasks.value.any { it.video.url == video.url }) {
            events.tryEmit("Déjà dans la file de téléchargement")
            return false
        }
        _tasks.update { it + DownloadTask(id = video.id, video = video) }
        persist()
        events.tryEmit("Téléchargement ajouté à la file")
        ensureService()
        pump()
        return true
    }

    fun pause(id: String) {
        val t = find(id) ?: return
        if (t.status == DlStatus.DONE) return
        setStatus(id, DlStatus.PAUSED, interrupted = false)
        Http.cancelAll(id)
        jobs[id]?.cancel()
        pump()
    }

    fun pauseAll(interrupted: Boolean = true) {
        _tasks.value.filter {
            it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED || it.status == DlStatus.WAITING_WIFI
        }.forEach {
            setStatus(it.id, DlStatus.PAUSED, interrupted = interrupted)
            Http.cancelAll(it.id)
            jobs[it.id]?.cancel()
        }
        persist()
    }

    fun resume(id: String) {
        val t = find(id) ?: return
        if (t.status != DlStatus.PAUSED && t.status != DlStatus.FAILED && t.status != DlStatus.WAITING_WIFI) return
        _tasks.update { list -> list.map { if (it.id == id) it.copy(status = DlStatus.QUEUED, error = null, interrupted = false) else it } }
        persist()
        ensureService()
        pump()
    }

    fun cancel(id: String) {
        val job = jobs[id]
        Http.cancelAll(id)
        job?.cancel()
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
        scope.launch(Dispatchers.IO) {
            job?.cancelAndJoin()
            cleanup(id)
            pump()
        }
    }

    /** Appelé par WorkManager : reprend les tâches interrompues, sans doublon si un téléchargement est déjà actif. */
    fun resumeInterruptedIfIdle(): Boolean {
        if (isActive()) return false
        val ids = _tasks.value.filter { it.interrupted }.map { it.id }
        if (ids.isEmpty()) return false
        _tasks.update { list -> list.map { if (it.interrupted) it.copy(status = DlStatus.QUEUED, interrupted = false) else it } }
        ensureService()
        pump()
        return true
    }

    // ---- File d'attente ---------------------------------------------------------------------
    private fun pump() {
        synchronized(lock) {
            if (AppPrefs.wifiOnly.value && !onWifi.value) {
                enforceNetworkPolicy()
                return
            }
            val busy = _tasks.value.count { it.status == DlStatus.RUNNING || it.status == DlStatus.MERGING }
            var slots = MAX_PARALLEL - busy
            if (slots <= 0) return
            for (t in _tasks.value.filter { it.status == DlStatus.QUEUED }) {
                if (slots <= 0) break
                if (jobs.containsKey(t.id)) continue // ancien job en cours d'arrêt : repris à sa terminaison
                start(t)
                slots--
            }
        }
    }

    private fun start(task: DownloadTask) {
        setStatus(task.id, DlStatus.RUNNING)
        speedSamples.remove(task.id)
        val job = scope.launch(Dispatchers.IO) { execute(task) }
        jobs[task.id] = job
        job.invokeOnCompletion {
            jobs.remove(task.id, job)
            pump()
        }
    }

    private suspend fun execute(task: DownloadTask) {
        val id = task.id
        val v = task.video
        val dir = File(app.filesDir, "dl").apply { mkdirs() }
        try {
            val result: File = if (v.format == "HLS") {
                HlsDownloader.download(
                    app, id, v, File(dir, id),
                    { d, t -> progress(id, d, t) },
                    { setStatus(id, DlStatus.MERGING) }
                )
            } else {
                val f = File(dir, "$id.${v.format.lowercase()}")
                DirectDownloader.download(id, v, f) { d, t -> progress(id, d, t) }
                f
            }
            setStatus(id, DlStatus.MERGING)
            val uri = Storage.saveToMovies(app, result, v.title)
            if (uri == null) throw IllegalStateException("Enregistrement MediaStore impossible")
            cleanup(id)
            _tasks.update { list -> list.filterNot { it.id == id } }
            persist()
            events.tryEmit("Terminé : ${v.title}")
            libraryChanged.tryEmit(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val st = find(id)?.status
            if (st == null || st == DlStatus.PAUSED || st == DlStatus.WAITING_WIFI || st == DlStatus.CANCELED) return
            _tasks.update { list ->
                list.map {
                    if (it.id == id) it.copy(status = DlStatus.FAILED, speedBps = 0L, etaSec = -1L, error = e.message ?: "Erreur réseau") else it
                }
            }
            persist()
        }
    }

    private fun cleanup(id: String) {
        val dir = File(app.filesDir, "dl")
        File(dir, id).deleteRecursively()
        dir.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
    }

    // ---- Progression & vitesse --------------------------------------------------------------
    private fun progress(id: String, downloaded: Long, total: Long) {
        val now: Long = SystemClock.elapsedRealtime()
        val s = speedSamples.getOrPut(id) { longArrayOf(now, downloaded, 0L) }
        val dt: Long = now - s[0]
        var speed: Long = s[2]
        if (dt >= 800L) {
            val inst: Long = ((downloaded - s[1]).coerceAtLeast(0L) * 1000L) / dt
            speed = if (s[2] == 0L) inst else (s[2] * 6L + inst * 4L) / 10L
            s[0] = now
            s[1] = downloaded
            s[2] = speed
        }
        val eta: Long = if (speed > 0L && total > downloaded) (total - downloaded) / speed else -1L
        _tasks.update { list ->
            list.map {
                if (it.id == id && it.status == DlStatus.RUNNING) it.copy(downloaded = downloaded, total = total, speedBps = speed, etaSec = eta)
                else it
            }
        }
    }

    // ---- Réseau (mode Wi-Fi uniquement) -----------------------------------------------------
    private fun registerNetworkCallback() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        onWifi.value = caps?.let { isUnmetered(it) } ?: true
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val wifi = isUnmetered(networkCapabilities)
                    if (onWifi.value != wifi) {
                        onWifi.value = wifi
                        enforceNetworkPolicy()
                    }
                }
            })
        }
    }

    private fun isUnmetered(c: NetworkCapabilities): Boolean =
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    fun enforceNetworkPolicy() {
        val block = AppPrefs.wifiOnly.value && !onWifi.value
        if (block) {
            var parked = false
            _tasks.value.filter { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }.forEach {
                setStatus(it.id, DlStatus.WAITING_WIFI, interrupted = false)
                Http.cancelAll(it.id)
                jobs[it.id]?.cancel()
                parked = true
            }
            wifiBlocked.value = parked || _tasks.value.any { it.status == DlStatus.WAITING_WIFI }
            persist()
        } else {
            wifiBlocked.value = false
            val waiting = _tasks.value.filter { it.status == DlStatus.WAITING_WIFI }
            if (waiting.isNotEmpty()) {
                _tasks.update { list -> list.map { if (it.status == DlStatus.WAITING_WIFI) it.copy(status = DlStatus.QUEUED) else it } }
                ensureService()
                pump()
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------
    private fun find(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    private fun setStatus(id: String, status: DlStatus, interrupted: Boolean? = null) {
        _tasks.update { list ->
            list.map {
                if (it.id == id) it.copy(
                    status = status,
                    speedBps = if (status == DlStatus.RUNNING) it.speedBps else 0L,
                    etaSec = if (status == DlStatus.RUNNING) it.etaSec else -1L,
                    interrupted = interrupted ?: it.interrupted
                ) else it
            }
        }
        persist()
    }

    private fun ensureService() {
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }
    }

    // ---- Persistance ------------------------------------------------------------------------
    private fun persist() {
        runCatching {
            val arr = JSONArray()
            _tasks.value.forEach { t ->
                val v = t.video
                val headers = JSONObject()
                v.headers.forEach { (k, value) -> headers.put(k, value) }
                arr.put(
                    JSONObject()
                        .put("status", t.status.name)
                        .put("downloaded", t.downloaded)
                        .put("total", t.total)
                        .put("id", v.id).put("url", v.url).put("title", v.title).put("format", v.format)
                        .put("size", v.sizeBytes).put("res", v.resolution).put("page", v.pageUrl)
                        .put("headers", headers)
                )
            }
            AppPrefs.saveTasks(arr.toString())
        }
    }

    private fun restore() {
        runCatching {
            val arr = JSONArray(AppPrefs.loadTasks())
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val h = o.getJSONObject("headers")
                val headers = HashMap<String, String>()
                h.keys().forEach { k -> headers[k] = h.getString(k) }
                val video = DetectedVideo(
                    o.getString("id"), o.getString("url"), o.getString("title"), o.getString("format"),
                    o.getLong("size"), o.getString("res"), o.getString("page"), headers
                )
                val wasPaused = o.getString("status") == DlStatus.PAUSED.name
                DownloadTask(
                    id = video.id, video = video, status = DlStatus.PAUSED,
                    downloaded = o.getLong("downloaded"), total = o.getLong("total"),
                    interrupted = !wasPaused
                )
            }
            _tasks.value = list
        }
    }
}
