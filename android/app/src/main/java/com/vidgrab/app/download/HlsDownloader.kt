package com.vidgrab.app.download

import android.content.Context
import android.os.SystemClock
import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS : choix de la qualité, téléchargement parallèle des segments (tampon 128 Ko + watchdog), AES-128,
 * fusion sans ré-encodage vers .mp4 (Media3 Transformer) avec repli propre en .ts.
 */
object HlsDownloader {
    private const val WORKERS = 8
    private const val BUF = 128 * 1024
    private const val STALL_IDLE_MS = 8_000L
    private const val STALL_SLOW_WINDOW_MS = 5_000L
    private const val MIN_GLOBAL_SPEED_FOR_SLOW_CHECK = 300_000L
    private const val SLOW_WORKER_THRESHOLD_BPS = 20_000L

    private class WorkerState {
        @Volatile var activeCall: Call? = null
        @Volatile var stalledByWatchdog: Boolean = false
        val lastByteAt = AtomicLong(0L)
        val windowStartAt = AtomicLong(0L)
        val windowBytes = AtomicLong(0L)

        fun startAttempt(call: Call) {
            val now = SystemClock.elapsedRealtime()
            stalledByWatchdog = false
            lastByteAt.set(now)
            windowStartAt.set(now)
            windowBytes.set(0L)
            activeCall = call
        }

        fun onBytes(n: Long) {
            lastByteAt.set(SystemClock.elapsedRealtime())
            windowBytes.addAndGet(n)
        }

        fun clear() {
            activeCall = null
        }
    }

    /** @return fichier final (.mp4 ou .ts) situé dans [dir]. */
    suspend fun download(
        ctx: Context, taskId: String, video: DetectedVideo, dir: File,
        onProgress: (Long, Long) -> Unit, onMerging: () -> Unit
    ): File {
        dir.mkdirs()
        var url = video.url
        var text: String = withContext(Dispatchers.IO) { HlsParser.fetchText(url, video.headers, taskId) }
            ?: throw IOException("Playlist HLS inaccessible")

        if (HlsParser.isMaster(text)) {
            val variants = HlsParser.parseMaster(text, url)
            if (variants.isEmpty()) throw IOException("Aucune qualité HLS")
            val chosen = variants.firstOrNull { it.label == video.resolution } ?: variants.first()
            url = chosen.url
            text = withContext(Dispatchers.IO) { HlsParser.fetchText(url, video.headers, taskId) }
                ?: throw IOException("Playlist média inaccessible")
        }
        val playlist = HlsParser.parseMedia(text, url)
        if (playlist.segments.isEmpty()) throw IOException("Playlist vide")

        val keys = ConcurrentHashMap<String, ByteArray>()
        val count: Int = playlist.segments.size
        val downloaded = AtomicLong(0L)
        val doneCount = AtomicInteger(0)

        // Segment d'initialisation fMP4
        var initFile: File? = null
        playlist.mapUri?.let { mapUri ->
            val f = File(dir, "init.mp4")
            if (!f.exists()) withContext(Dispatchers.IO) {
                fetchToFile(taskId, video, mapUri, f, null, keys, AtomicLong(0L), null) { true }
            }
            initFile = f
        }

        fun segFile(i: Int) = File(dir, "seg_%05d.bin".format(i))
        val queue = ConcurrentLinkedQueue<Int>()
        for (i in 0 until count) {
            val f = segFile(i)
            if (f.exists() && f.length() > 0L) {
                downloaded.addAndGet(f.length())
                doneCount.incrementAndGet()
            } else queue.add(i)
        }

        coroutineScope {
            val n: Int = minOf(WORKERS, maxOf(1, queue.size))
            val workers = Array(n) { WorkerState() }

            val reporter = launch {
                var speedAt = SystemClock.elapsedRealtime()
                var speedBytes = downloaded.get()
                var globalSpeed = 0L
                while (isActive) {
                    val d: Long = downloaded.get()
                    val done: Int = doneCount.get()
                    val est: Long = if (done > 0) (d / done.toLong()) * count.toLong() else video.sizeBytes
                    onProgress(d, maxOf(est, d))

                    val now = SystemClock.elapsedRealtime()
                    val dt = now - speedAt
                    if (dt >= 2_000L) {
                        globalSpeed = ((d - speedBytes).coerceAtLeast(0L) * 1000L) / dt
                        speedAt = now
                        speedBytes = d
                    }

                    for (ws in workers) {
                        val call = ws.activeCall ?: continue
                        val idle = now - ws.lastByteAt.get()
                        if (idle >= STALL_IDLE_MS) {
                            ws.stalledByWatchdog = true
                            ws.clear()
                            runCatching { call.cancel() }
                            continue
                        }
                        val winDt = now - ws.windowStartAt.get()
                        if (winDt >= STALL_SLOW_WINDOW_MS) {
                            val wBytes = ws.windowBytes.getAndSet(0L)
                            ws.windowStartAt.set(now)
                            if (globalSpeed >= MIN_GLOBAL_SPEED_FOR_SLOW_CHECK) {
                                val wSpeed = (wBytes * 1000L) / winDt
                                if (wSpeed < SLOW_WORKER_THRESHOLD_BPS) {
                                    ws.stalledByWatchdog = true
                                    ws.clear()
                                    runCatching { call.cancel() }
                                }
                            }
                        }
                    }
                    delay(500L)
                }
            }
            try {
                (0 until n).map { workerIdx ->
                    val ws = workers[workerIdx]
                    async(Dispatchers.IO) {
                        while (true) {
                            val idx: Int = queue.poll() ?: break
                            ensureActive()
                            var attempt = 0
                            var stallCount = 0
                            while (true) {
                                ensureActive()
                                try {
                                    fetchToFile(
                                        taskId, video, playlist.segments[idx].url,
                                        segFile(idx), playlist.segments[idx], keys, downloaded, ws
                                    ) { isActive }
                                    break
                                } catch (e: IOException) {
                                    if (!isActive || !currentCoroutineContext().isActive) {
                                        throw CancellationException("Annulé", e)
                                    }
                                    if (ws.stalledByWatchdog) {
                                        ws.stalledByWatchdog = false
                                        stallCount++
                                        if (stallCount > 3) throw e
                                        delay(100L)
                                    } else {
                                        attempt++
                                        if (attempt >= 3) throw e
                                        delay(400L * attempt)
                                    }
                                }
                            }
                            doneCount.incrementAndGet()
                        }
                    }
                }.awaitAll()
            } finally {
                reporter.cancel()
                Http.cancelAll(taskId)
            }
        }
        val finalTotal: Long = downloaded.get()
        onProgress(finalTotal, finalTotal)
        onMerging()

        // ---- Fusion ----
        val hasInit = initFile != null
        val merged = File(dir, if (hasInit) "merged.mp4" else "merged.ts")
        withContext(Dispatchers.IO) {
            FileOutputStream(merged).use { out ->
                val buf = ByteArray(BUF)
                fun append(f: File) = BufferedInputStream(f.inputStream(), BUF).use { ins ->
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                }
                initFile?.let { append(it) }
                for (i in 0 until count) {
                    ensureActive()
                    append(segFile(i))
                }
            }
            for (i in 0 until count) segFile(i).delete()
        }
        if (hasInit) return merged

        val mp4 = File(dir, "merged_out.mp4")
        val ok: Boolean = try {
            Remuxer.toMp4(ctx, merged, mp4)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return if (ok) {
            merged.delete()
            mp4
        } else {
            mp4.delete()
            merged // repli propre en .ts
        }
    }

    private fun fetchToFile(
        taskId: String, video: DetectedVideo, url: String, target: File, seg: HlsParser.Segment?,
        keys: ConcurrentHashMap<String, ByteArray>, counter: AtomicLong, ws: WorkerState?, active: () -> Boolean
    ) {
        val tmp = File(target.path + ".tmp")
        var added = 0L
        val call = Http.client.newCall(Http.request(url, video.headers))
        Http.register(taskId, call)
        ws?.startAttempt(call)
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("Segment vide")
                var ins: InputStream = BufferedInputStream(body.byteStream(), BUF) // 128 Ko, jamais 512 octets
                val key = seg?.key
                if (seg != null && key != null) {
                    val keyBytes: ByteArray = keys.getOrPut(key.uri) { fetchKey(taskId, key.uri, video) }
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(HlsParser.ivFor(seg)))
                    ins = CipherInputStream(ins, cipher)
                }
                ins.use { input ->
                    FileOutputStream(tmp).use { fos ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            if (!active()) throw IOException("Annulé")
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            added += n.toLong()
                            counter.addAndGet(n.toLong())
                            ws?.onBytes(n.toLong())
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Renommage impossible")
        } catch (e: Exception) {
            counter.addAndGet(-added)
            tmp.delete()
            if (e is IOException) throw e else throw IOException(e)
        } finally {
            ws?.clear()
            Http.unregister(taskId, call)
        }
    }

    private fun fetchKey(taskId: String, uri: String, video: DetectedVideo): ByteArray {
        val call = Http.client.newCall(Http.request(uri, video.headers))
        Http.register(taskId, call)
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw IOException("Clé AES : HTTP ${r.code}")
                return r.body?.bytes() ?: throw IOException("Clé AES vide")
            }
        } finally {
            Http.unregister(taskId, call)
        }
    }
}
