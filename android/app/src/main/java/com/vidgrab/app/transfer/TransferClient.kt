package com.vidgrab.app.transfer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.vidgrab.app.data.LibraryRepository
import com.vidgrab.app.data.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Client transfert Wi-Fi : radar UDP + sonde passerelle Hotspot + téléchargement 4 flux directs sans double copie. */
object TransferClient {

    private const val BLOCK = 4L * 1024L * 1024L
    private const val WORKERS = 4
    private const val BUF = 256 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 16 })
            .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val pingClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(1100, TimeUnit.MILLISECONDS).build()
    }

    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun cancelAll() {
        activeCalls.forEach { runCatching { it.cancel() } }
        activeCalls.clear()
    }

    data class AuthResult(val token: String, val files: List<TransferFileState>)
    data class SavedResult(val uri: Uri, val key: String?)

    fun startDiscovery(ctx: Context, scope: CoroutineScope, onPeer: (PeerInfo) -> Unit): Job = scope.launch(Dispatchers.IO) {
        val udpJob = launch {
            val sock = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1200
                    bind(InetSocketAddress(TransferServer.UDP_PORT))
                }
            }.getOrNull() ?: return@launch
            val buf = ByteArray(2048)
            try {
                while (isActive) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val ok = runCatching { sock.receive(pkt) }.isSuccess
                    if (!ok) continue
                    val ip = pkt.address.hostAddress ?: continue
                    val json = runCatching { JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    if (json.optString("app") != "VidGrab") continue
                    onPeer(
                        PeerInfo(
                            ip = ip,
                            port = json.optInt("port", TransferServer.DEFAULT_PORT),
                            deviceName = json.optString("device", "VidGrab"),
                            fileCount = json.optInt("count", 0),
                            totalBytes = json.optLong("totalBytes", 0L)
                        )
                    )
                }
            } finally {
                runCatching { sock.close() }
            }
        }
        val gwJob = launch {
            while (isActive) {
                val gw = LocalNetHelper.inspect(ctx).gatewayIp
                if (!gw.isNullOrBlank()) {
                    ping(gw, TransferServer.DEFAULT_PORT)?.let(onPeer)
                }
                delay(1500L)
            }
        }
        try {
            udpJob.join()
        } finally {
            udpJob.cancel()
            gwJob.cancel()
        }
    }

    suspend fun ping(ip: String, port: Int): PeerInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("http://$ip:$port/ping").build()
            pingClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val j = JSONObject(r.body?.string().orEmpty())
                if (j.optString("app") != "VidGrab") return@withContext null
                PeerInfo(
                    ip = ip,
                    port = port,
                    deviceName = j.optString("device", "VidGrab"),
                    fileCount = j.optInt("count", 0),
                    totalBytes = j.optLong("totalBytes", 0L)
                )
            }
        }.getOrNull()
    }

    suspend fun authenticate(ip: String, port: Int, pin: String): AuthResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("pin", pin).put("device", Build.MODEL ?: "VidGrab").toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("http://$ip:$port/auth").post(body).build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(txt.ifBlank { "HTTP ${r.code}" })
            val j = JSONObject(txt)
            val token = j.getString("token")
            val arr = j.getJSONArray("files")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TransferFileState(
                    index = o.getInt("index"),
                    name = o.getString("name"),
                    ext = o.getString("ext"),
                    sizeBytes = o.getLong("size"),
                    isApk = o.optBoolean("isApk", false)
                )
            }
            AuthResult(token, list)
        }
    }

    fun sendProgress(ip: String, port: Int, token: String, doneBytes: Long, doneFiles: Int) {
        runCatching {
            val body = JSONObject().put("doneBytes", doneBytes).put("doneFiles", doneFiles).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("http://$ip:$port/progress").header("X-VG-Token", token).post(body).build()
            pingClient.newCall(req).execute().close()
        }
    }

    suspend fun downloadFile(
        ctx: Context,
        ip: String,
        port: Int,
        token: String,
        file: TransferFileState,
        savePrivate: Boolean,
        onProgress: (Long) -> Unit
    ): SavedResult {
        val url = "http://$ip:$port/file/${file.index}"
        if (file.isApk) {
            return downloadToDownloads(ctx, url, token, file, onProgress)
        }
        if (savePrivate) {
            val dir = LibraryRepository.privateDir(ctx)
            val target = uniqueFile(dir, Storage.safeName(file.name), file.ext)
            try {
                if (file.sizeBytes > 0L) {
                    RandomAccessFile(target, "rw").use { raf ->
                        raf.setLength(file.sizeBytes)
                        parallelWrite(url, token, file.sizeBytes, raf.channel, onProgress)
                    }
                } else {
                    singleWrite(url, token, target.outputStream(), onProgress)
                }
                return SavedResult(Uri.fromFile(target), "prv:${target.name}")
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
        return downloadToMoviesDirect(ctx, url, token, file, onProgress)
    }

    private suspend fun downloadToMoviesDirect(
        ctx: Context, url: String, token: String, file: TransferFileState, onProgress: (Long) -> Unit
    ): SavedResult = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${Storage.safeName(file.name)}.${file.ext}")
            put(MediaStore.Video.Media.MIME_TYPE, Storage.mimeFor(file.ext))
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VidGrab")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var uri = resolver.insert(collection, values) ?: throw IOException("MediaStore inaccessible")
        try {
            val usedParallel = if (file.sizeBytes > 0L) {
                runCatching {
                    val fd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("FD null")
                    ParcelFileDescriptor.AutoCloseOutputStream(fd).use { fos ->
                        parallelWrite(url, token, file.sizeBytes, fos.channel, onProgress)
                    }
                    true
                }.getOrElse { err ->
                    if (err is CancellationException) throw err
                    // Repart d'un fichier vierge pour éviter toute trace partielle avant le repli
                    runCatching { resolver.delete(uri, null, null) }
                    uri = resolver.insert(collection, values) ?: throw IOException("MediaStore inaccessible")
                    false
                }
            } else false

            if (!usedParallel) {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    singleWrite(url, token, out, onProgress)
                } ?: throw IOException("Flux écriture null")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            SavedResult(uri, "pub:${ContentUris.parseId(uri)}")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private suspend fun downloadToDownloads(
        ctx: Context, url: String, token: String, file: TransferFileState, onProgress: (Long) -> Unit
    ): SavedResult = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${Storage.safeName(file.name)}.${file.ext}")
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VidGrab")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Downloads inaccessible")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> singleWrite(url, token, out, onProgress) }
                ?: throw IOException("Flux Downloads null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            SavedResult(uri, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private suspend fun parallelWrite(
        url: String, token: String, total: Long, channel: FileChannel, onProgress: (Long) -> Unit
    ) = coroutineScope {
        val blocks = ((total + BLOCK - 1L) / BLOCK).toInt().coerceAtLeast(1)
        val queue = ConcurrentLinkedQueue((0 until blocks).toList())
        val done = AtomicLong(0L)
        val reporter = launch {
            while (isActive) {
                onProgress(done.get())
                delay(250L)
            }
        }
        try {
            val n = minOf(WORKERS, blocks)
            (0 until n).map {
                async(Dispatchers.IO) {
                    val buf = ByteArray(BUF)
                    while (true) {
                        val idx = queue.poll() ?: break
                        ensureActive()
                        val start = idx.toLong() * BLOCK
                        val end = minOf(start + BLOCK, total) - 1L
                        val req = Request.Builder().url(url).header("X-VG-Token", token)
                            .header("Range", "bytes=$start-$end").build()
                        val call = client.newCall(req)
                        activeCalls.add(call)
                        try {
                            call.execute().use { r ->
                                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                                val ins = BufferedInputStream(r.body?.byteStream() ?: throw IOException("Vide"), BUF)
                                var pos = start
                                while (pos <= end) {
                                    ensureActive()
                                    val want = minOf(BUF.toLong(), end - pos + 1L).toInt()
                                    val k = ins.read(buf, 0, want)
                                    if (k < 0) throw IOException("Fin prématurée")
                                    val bb = ByteBuffer.wrap(buf, 0, k)
                                    var w = 0L
                                    while (bb.hasRemaining()) w += channel.write(bb, pos + w).toLong()
                                    pos += k.toLong()
                                    done.addAndGet(k.toLong())
                                }
                            }
                        } finally {
                            activeCalls.remove(call)
                        }
                    }
                }
            }.awaitAll()
            onProgress(total)
        } finally {
            reporter.cancel()
        }
    }

    private suspend fun singleWrite(
        url: String, token: String, out: OutputStream, onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        out.use { os ->
            val req = Request.Builder().url(url).header("X-VG-Token", token).build()
            val call = client.newCall(req)
            activeCalls.add(call)
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val ins = BufferedInputStream(r.body?.byteStream() ?: throw IOException("Vide"), BUF)
                    val buf = ByteArray(BUF)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        ensureActive()
                        val k = ins.read(buf)
                        if (k < 0) break
                        os.write(buf, 0, k)
                        done += k.toLong()
                        val now = System.currentTimeMillis()
                        if (now - last > 250L) {
                            onProgress(done)
                            last = now
                        }
                    }
                    onProgress(done)
                }
            } finally {
                activeCalls.remove(call)
            }
        }
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 1
        while (f.exists()) f = File(dir, "${base}_$n.$ext").also { n++ }
        return f
    }
}
