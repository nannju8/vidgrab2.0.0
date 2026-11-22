package com.vidgrab.app.download

import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.data.Http
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
 * (Dispatchers.IO + Range + écriture positionnelle). Les derniers octets restent aussi rapides que les premiers.
 * Repli automatique en flux unique si le serveur ne supporte pas Range. Reprise via fichier .state.
 */
object DirectDownloader {
    private const val BLOCK: Long = 3L * 1024L * 1024L
    private const val WORKERS = 8
    private const val BUF = 128 * 1024

    private class Info(val total: Long, val ranges: Boolean)

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

            val scope: CoroutineScope = this
            val reporter = launch {
                while (isActive) {
                    onProgress(downloaded.get(), total)
                    delay(500L)
                }
            }
            try {
                val n: Int = minOf(WORKERS, maxOf(1, queue.size))
                (0 until n).map {
                    async(Dispatchers.IO) {
                        while (true) {
                            val idx: Int = queue.poll() ?: break
                            ensureActive()
                            fetchBlock(taskId, video, channel, idx, total, downloaded) { scope.isActive }
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

    private fun fetchBlock(
        taskId: String, video: DetectedVideo, channel: FileChannel, index: Int, total: Long,
        downloaded: AtomicLong, active: () -> Boolean
    ) {
        val start: Long = index.toLong() * BLOCK
        val end: Long = minOf(start + BLOCK, total) - 1L
        var attempt = 0
        while (true) {
            var added = 0L
            val call = Http.client.newCall(Http.request(video.url, video.headers, "bytes=$start-$end"))
            Http.register(taskId, call)
            try {
                call.execute().use { r ->
                    if (r.code != 206) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("Réponse vide")
                    BufferedInputStream(body.byteStream(), BUF).use { ins ->
                        val buf = ByteArray(BUF)
                        var pos: Long = start
                        while (pos <= end) {
                            val want: Int = minOf(BUF.toLong(), end - pos + 1L).toInt()
                            val n = ins.read(buf, 0, want)
                            if (n < 0) throw IOException("Fin de flux prématurée")
                            val bb = ByteBuffer.wrap(buf, 0, n)
                            var written = 0L
                            while (bb.hasRemaining()) written += channel.write(bb, pos + written).toLong()
                            pos += n.toLong()
                            added += n.toLong()
                            downloaded.addAndGet(n.toLong())
                        }
                    }
                }
                return
            } catch (e: IOException) {
                downloaded.addAndGet(-added)
                attempt++
                if (attempt >= 4 || !active()) throw e
                Thread.sleep(400L * attempt)
            } finally {
                Http.unregister(taskId, call)
            }
        }
    }
}
