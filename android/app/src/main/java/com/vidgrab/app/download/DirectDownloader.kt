package com.vidgrab.app.download

import android.os.SystemClock
import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Téléchargement direct ultra-rapide : file dynamique de micro-blocs de 3 Mo consommée par 8 workers
 * (Dispatchers.IO + Range + écriture positionnelle), avec chien de garde anti-worker lent.
 * Repli automatique en flux unique si le serveur ne supporte pas Range. Reprise via fichier .state.
 */
object DirectDownloader {
    private const val BLOCK: Long = 3L * 1024L * 1024L
    private const val WORKERS = 8
    private const val BUF = 128 * 1024
    private const val STALL_IDLE_MS = 8_000L
    private const val STALL_SLOW_WINDOW_MS = 5_000L
    private const val MIN_GLOBAL_SPEED_FOR_SLOW_CHECK = 300_000L // 300 Ko/s
    private const val SLOW_WORKER_THRESHOLD_BPS = 20_000L // 20 Ko/s

    private class Info(val total: Long, val ranges: Boolean)

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

    suspend fun download(taskId: String, video: DetectedVideo, out: File, onProgress: (Long, Long) -> Unit) {
        val info = withContext(Dispatchers.IO) { probeInfo(taskId, video) }
        if (info.ranges && info.total > 0L) parallel(taskId, video, out, info.total, onProgress)
        else single(taskId, video, out, info.total, onProgress)
    }

    private fun probeInfo(taskId: String, video: DetectedVideo): Info {
        val call = Http.client.newCall(Http.request(video.url, video.headers, "bytes=0-0"))
        Http.register(taskId, call)
        try {
            call.execute().use { r ->
                if (r.code == 206) {
                    val total: Long = r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                    return Info(total, total > 0L)
                }
                if (r.isSuccessful) {
                    val len: Long = r.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                    return Info(len, false)
                }
                throw IOException("HTTP ${r.code}")
            }
        } finally {
            Http.unregister(taskId, call)
        }
    }

    // ---- Flux unique ----------------------------------------------------------------------
    private suspend fun single(taskId: String, video: DetectedVideo, out: File, knownTotal: Long, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val call = Http.client.newCall(Http.request(video.url, video.headers))
            Http.register(taskId, call)
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("Réponse vide")
                    val len: Long = body.contentLength()
                    val total: Long = if (len > 0L) len else knownTotal
                    BufferedInputStream(body.byteStream(), BUF).use { ins ->
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(BUF)
                            var done = 0L
                            var last = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = ins.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                done += n.toLong()
                                val now = System.currentTimeMillis()
                                if (now - last > 400L) {
                                    onProgress(done, total)
                                    last = now
                                }
                            }
                            onProgress(done, done)
                        }
                    }
                }
            } finally {
                Http.unregister(taskId, call)
            }
        }

    // ---- Micro-blocs parallèles -------------------------------------------------------------
    private suspend fun parallel(taskId: String, video: DetectedVideo, out: File, total: Long, onProgress: (Long, Long) -> Unit) =
        coroutineScope {
            val stateFile = File(out.path + ".state")
            val blocks: Int = ((total + BLOCK - 1L) / BLOCK).toInt()
            val done: MutableSet<Int> = ConcurrentHashMap.newKeySet()

            if (out.exists() && out.length() == total && stateFile.exists()) {
                runCatching {
                    val parts = stateFile.readText().split(':', limit = 2)
                    if (parts[0].toLongOrNull() == total && parts.size == 2) {
                        parts[1].split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until blocks }.forEach { done.add(it) }
                    }
                }
            } else {
                out.delete()
            }

            fun blockLen(i: Int): Long = minOf(BLOCK, total - i.toLong() * BLOCK)

            val downloaded = AtomicLong(0L)
            done.forEach { downloaded.addAndGet(blockLen(it)) }
            val queue = ConcurrentLinkedQueue<Int>((0 until blocks).filter { it !in done })

            val raf = RandomAccessFile(out, "rw")
            if (raf.length() != total) raf.setLength(total)
            val channel: FileChannel = raf.channel

            val n: Int = minOf(WORKERS, maxOf(1, queue.size))
            val workers = Array(n) { WorkerState() }
            val scope: CoroutineScope = this

            val reporter = launch {
                var speedAt = SystemClock.elapsedRealtime()
                var speedBytes = downloaded.get()
                var globalSpeed = 0L
                while (isActive) {
                    val cur = downloaded.get()
                    onProgress(cur, total)

                    val now = SystemClock.elapsedRealtime()
                    val dt = now - speedAt
                    if (dt >= 2_000L) {
                        globalSpeed = ((cur - speedBytes).coerceAtLeast(0L) * 1000L) / dt
                        speedAt = now
                        speedBytes = cur
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
                            fetchBlock(taskId, video, channel, idx, total, downloaded, ws) { scope.isActive }
                            done.add(idx)
                        }
                    }
                }.awaitAll()
                onProgress(total, total)
            } finally {
                reporter.cancel()
                Http.cancelAll(taskId)
                runCatching { stateFile.writeText("$total:" + done.joinToString(",")) }
                runCatching { channel.close() }
                runCatching { raf.close() }
            }
        }

    private suspend fun fetchBlock(
        taskId: String, video: DetectedVideo, channel: FileChannel, index: Int, total: Long,
        downloaded: AtomicLong, ws: WorkerState, active: () -> Boolean
    ) {
        val start: Long = index.toLong() * BLOCK
        val end: Long = minOf(start + BLOCK, total) - 1L
        var pos: Long = start
        var attempt = 0
        var stallCount = 0

        while (pos <= end) {
            currentCoroutineContext().ensureActive()
            var attemptBytes = 0L
            val call = Http.client.newCall(Http.request(video.url, video.headers, "bytes=$pos-$end"))
            Http.register(taskId, call)
            ws.startAttempt(call)
            try {
                call.execute().use { r ->
                    if (r.code != 206) throw IOException("HTTP ${r.code}")
                    val crStart = r.header("Content-Range")
                        ?.removePrefix("bytes ")
                        ?.substringBefore('-')
                        ?.trim()
                        ?.toLongOrNull()
                    if (crStart != null && crStart != pos) {
                        if (crStart == 0L && pos > start) {
                            downloaded.addAndGet(-(pos - start))
                            pos = start
                        }
                        throw IOException("Plage Range inattendue ($crStart)")
                    }
                    val body = r.body ?: throw IOException("Réponse vide")
                    BufferedInputStream(body.byteStream(), BUF).use { ins ->
                        val buf = ByteArray(BUF)
                        while (pos <= end) {
                            currentCoroutineContext().ensureActive()
                            val want: Int = minOf(BUF.toLong(), end - pos + 1L).toInt()
                            val n = ins.read(buf, 0, want)
                            if (n < 0) throw IOException("Fin de flux prématurée")
                            val bb = ByteBuffer.wrap(buf, 0, n)
                            var written = 0L
                            while (bb.hasRemaining()) written += channel.write(bb, pos + written).toLong()
                            pos += n.toLong()
                            attemptBytes += n.toLong()
                            downloaded.addAndGet(n.toLong())
                            ws.onBytes(n.toLong())
                        }
                    }
                }
                return
            } catch (e: IOException) {
                if (!active() || !currentCoroutineContext().isActive) {
                    throw CancellationException("Annulé", e)
                }
                if (ws.stalledByWatchdog) {
                    ws.stalledByWatchdog = false
                    stallCount++
                    if (stallCount > 4) throw e
                    delay(100L)
                } else {
                    if (attemptBytes >= 128L * 1024L) attempt = 0
                    attempt++
                    if (attempt >= 4) throw e
                    delay(400L * attempt)
                }
            } finally {
                ws.clear()
                Http.unregister(taskId, call)
            }
        }
    }
}
