package com.vidgrab.app.transfer

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Serveur HTTP local zéro-copie (FileChannel.transferTo + DirectByteBuffer 512 Ko) + balise radar UDP. */
class TransferServer(
    private val ctx: Context,
    private val sources: List<TransferSource>,
    private val pin: String,
    private val onPeerConnected: (String) -> Unit,
    private val onProgressReport: (Long, Int) -> Unit,
    private val onRawBytesSent: (Int, Long) -> Unit
) {
    companion object {
        const val DEFAULT_PORT = 8765
        const val UDP_PORT = 48765
        private const val CHUNK = 2L * 1024L * 1024L
        private const val DIRECT_BUF = 512 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverChannel: ServerSocketChannel? = null
    private var udpSocket: DatagramSocket? = null
    private var acceptJob: Job? = null
    private var beaconJob: Job? = null

    private val tokens = ConcurrentHashMap<String, String>()
    private val badAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val rng = SecureRandom()

    var port: Int = DEFAULT_PORT
        private set

    fun start(): Int {
        val ssc = ServerSocketChannel.open()
        ssc.configureBlocking(true)
        runCatching { ssc.bind(InetSocketAddress(DEFAULT_PORT), 64) }
            .onFailure { ssc.bind(InetSocketAddress(0), 64) }
        serverChannel = ssc
        port = (ssc.localAddress as InetSocketAddress).port

        acceptJob = scope.launch {
            while (isActive) {
                val client = runCatching { ssc.accept() }.getOrNull() ?: break
                launch { handleConnection(client) }
            }
        }
        beaconJob = scope.launch {
            val sock = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return@launch
            udpSocket = sock
            val total = sources.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            val payload = JSONObject()
                .put("app", "VidGrab")
                .put("device", Build.MODEL ?: "Android")
                .put("port", port)
                .put("count", sources.size)
                .put("totalBytes", total)
                .toString()
                .toByteArray(Charsets.UTF_8)

            while (isActive) {
                val info = LocalNetHelper.inspect(ctx)
                if (info.active) {
                    info.broadcasts.forEach { addr ->
                        runCatching { sock.send(DatagramPacket(payload, payload.size, addr, UDP_PORT)) }
                    }
                }
                delay(1000L)
            }
        }
        return port
    }

    fun stop() {
        runCatching { serverChannel?.close() }
        runCatching { udpSocket?.close() }
        acceptJob?.cancel()
        beaconJob?.cancel()
        scope.cancel()
        tokens.clear()
    }

    private fun handleConnection(ch: SocketChannel) {
        ch.use {
            runCatching {
                ch.socket().tcpNoDelay = true
                ch.socket().sendBufferSize = DIRECT_BUF
                ch.socket().soTimeout = 30_000
                val ins = BufferedInputStream(Channels.newInputStream(ch), 16 * 1024)
                val req = readHttpRequest(ins) ?: return
                val remoteIp = (ch.remoteAddress as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
                val pathOnly = req.path.substringBefore('?')
                val query = req.path.substringAfter('?', "")

                when {
                    req.method == "GET" && pathOnly == "/ping" -> {
                        val total = sources.sumOf { it.sizeBytes.coerceAtLeast(0L) }
                        val json = JSONObject()
                            .put("app", "VidGrab")
                            .put("device", Build.MODEL ?: "Android")
                            .put("count", sources.size)
                            .put("totalBytes", total)
                            .toString()
                        writeText(ch, 200, "OK", "application/json", json)
                    }
                    req.method == "POST" && pathOnly == "/auth" -> handleAuth(ch, remoteIp, req.body)
                    req.method == "POST" && pathOnly == "/progress" -> {
                        val token = req.headers["x-vg-token"].orEmpty()
                        if (!tokens.containsKey(token)) {
                            writeText(ch, 403, "Forbidden", "text/plain", "Token invalide")
                        } else {
                            val o = JSONObject(req.body)
                            onProgressReport(o.optLong("doneBytes", 0L), o.optInt("doneFiles", 0))
                            writeText(ch, 200, "OK", "application/json", "{}")
                        }
                    }
                    req.method == "GET" && pathOnly.startsWith("/file/") -> {
                        val token = req.headers["x-vg-token"] ?: paramOf(query, "token").orEmpty()
                        if (!tokens.containsKey(token)) {
                            writeText(ch, 403, "Forbidden", "text/plain", "Non autorisé")
                            return
                        }
                        val idx = pathOnly.removePrefix("/file/").toIntOrNull()
                        val src = idx?.let { sources.getOrNull(it) }
                        if (src == null) writeText(ch, 404, "Not Found", "text/plain", "Introuvable")
                        else streamFile(ch, idx, src, req.headers["range"])
                    }
                    req.method == "GET" && pathOnly == "/" -> {
                        val qPin = paramOf(query, "pin")
                        if (qPin != null) serveBrowserList(ch, remoteIp, qPin)
                        else writeText(ch, 200, "OK", "text/html; charset=utf-8", htmlPinForm())
                    }
                    else -> writeText(ch, 404, "Not Found", "text/plain", "Not Found")
                }
            }
        }
    }

    private fun handleAuth(ch: SocketChannel, ip: String, body: String) {
        if (isRateLimited(ip)) {
            writeText(ch, 429, "Too Many Requests", "text/plain", "Trop de tentatives. Patientez 60 s.")
            return
        }
        val o = runCatching { JSONObject(body) }.getOrNull()
        val submitted = o?.optString("pin").orEmpty().trim()
        val peer = o?.optString("device").orEmpty().ifBlank { "Appareil VidGrab" }
        if (!MessageDigest.isEqual(pin.toByteArray(), submitted.toByteArray())) {
            val prev = badAttempts[ip]?.first ?: 0
            badAttempts[ip] = (prev + 1) to System.currentTimeMillis()
            writeText(ch, 403, "Forbidden", "text/plain", "Code incorrect")
            return
        }
        badAttempts.remove(ip)
        val tokenBytes = ByteArray(16).also { rng.nextBytes(it) }
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        tokens[token] = peer
        onPeerConnected(peer)

        val arr = JSONArray()
        sources.forEachIndexed { idx, s ->
            arr.put(
                JSONObject().put("index", idx).put("name", s.name).put("ext", s.ext)
                    .put("size", s.sizeBytes).put("durationMs", s.durationMs).put("isApk", s.isApk)
            )
        }
        writeText(ch, 200, "OK", "application/json", JSONObject().put("token", token).put("files", arr).toString())
    }

    private fun streamFile(ch: SocketChannel, index: Int, src: TransferSource, rangeHeader: String?) {
        var pfd: ParcelFileDescriptor? = null
        var fis: FileInputStream? = null
        try {
            val fc: FileChannel = if (src.file != null) {
                FileInputStream(src.file).also { fis = it }.channel
            } else if (src.uri != null) {
                val fd = ctx.contentResolver.openFileDescriptor(src.uri, "r") ?: throw IOException("URI illisible")
                pfd = fd
                FileInputStream(fd.fileDescriptor).also { fis = it }.channel
            } else throw IOException("Source vide")

            fc.use { channel ->
                val total = channel.size().takeIf { it > 0L } ?: src.sizeBytes
                var start = 0L
                var end = total - 1L
                var isPartial = false

                if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                    val spec = rangeHeader.removePrefix("bytes=").trim()
                    val sPart = spec.substringBefore('-').trim()
                    val ePart = spec.substringAfter('-', "").trim()
                    start = sPart.toLongOrNull()?.coerceIn(0L, (total - 1L).coerceAtLeast(0L)) ?: 0L
                    end = ePart.toLongOrNull()?.coerceIn(start, (total - 1L).coerceAtLeast(0L)) ?: (total - 1L)
                    isPartial = true
                }
                val contentLen = (end - start + 1L).coerceAtLeast(0L)
                val mime = if (src.isApk) "application/vnd.android.package-archive" else "application/octet-stream"
                val safeFile = "${src.name.replace("\"", "")}.${src.ext}"

                val hdr = buildString {
                    append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $contentLen\r\n")
                    if (isPartial) append("Content-Range: bytes $start-$end/$total\r\n")
                    append("Content-Disposition: attachment; filename=\"$safeFile\"\r\nConnection: close\r\n\r\n")
                }
                writeFully(ch, ByteBuffer.wrap(hdr.toByteArray(Charsets.UTF_8)))

                var pos = start
                var remaining = contentLen
                var useFallback = false
                var directBuf: ByteBuffer? = null

                while (remaining > 0L) {
                    val want = minOf(CHUNK, remaining)
                    if (!useFallback) {
                        val transferred = try { channel.transferTo(pos, want, ch) } catch (_: IOException) { -1L }
                        if (transferred > 0L) {
                            pos += transferred
                            remaining -= transferred
                            onRawBytesSent(index, transferred)
                            continue
                        }
                        useFallback = true
                    }
                    val buf = directBuf ?: ByteBuffer.allocateDirect(DIRECT_BUF).also { directBuf = it }
                    buf.clear()
                    if (remaining < buf.capacity()) buf.limit(remaining.toInt())
                    val read = channel.read(buf, pos)
                    if (read <= 0) break
                    buf.flip()
                    writeFully(ch, buf)
                    pos += read.toLong()
                    remaining -= read.toLong()
                    onRawBytesSent(index, read.toLong())
                }
            }
        } finally {
            runCatching { fis?.close() }
            runCatching { pfd?.close() }
        }
    }

    private fun serveBrowserList(ch: SocketChannel, ip: String, qPin: String) {
        if (isRateLimited(ip)) {
            writeText(ch, 429, "Too Many Requests", "text/html; charset=utf-8", "<h3>Trop de tentatives. Patientez 60 s.</h3>")
            return
        }
        if (!MessageDigest.isEqual(pin.toByteArray(), qPin.trim().toByteArray())) {
            val prev = badAttempts[ip]?.first ?: 0
            badAttempts[ip] = (prev + 1) to System.currentTimeMillis()
            writeText(ch, 403, "Forbidden", "text/html; charset=utf-8", htmlPinForm("Code incorrect"))
            return
        }
        val tokenBytes = ByteArray(16).also { rng.nextBytes(it) }
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        tokens[token] = "Navigateur"
        onPeerConnected("Navigateur Web")

        val rows = sources.mapIndexed { idx, s ->
            val mb = String.format("%.1f Mo", s.sizeBytes / (1024.0 * 1024.0))
            "<a class='item' href='/file/$idx?token=$token'><b>${s.name}.${s.ext}</b><span>$mb</span></a>"
        }.joinToString("")
        val html = "<!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>body{background:#0B0D14;color:#E8EAF6;font-family:sans-serif;padding:20px}h2{color:#06B6D4}.item{display:flex;justify-content:space-between;background:#171B2B;padding:14px;margin:10px 0;border-radius:14px;color:#fff;text-decoration:none;border:1px solid #2b3252}span{color:#06B6D4}</style>" +
            "<h2>VidGrab · Fichiers partagés (${sources.size})</h2>$rows</html>"
        writeText(ch, 200, "OK", "text/html; charset=utf-8", html)
    }

    private fun htmlPinForm(err: String? = null): String {
        val errHtml = if (err != null) "<p style='color:#F43F5E'>$err</p>" else ""
        return "<!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>body{background:#0B0D14;color:#E8EAF6;font-family:sans-serif;padding:30px;text-align:center}input,button{padding:14px;border-radius:12px;border:none;font-size:18px;margin:8px}button{background:#6366F1;color:#fff;font-weight:bold}</style>" +
            "<h2>VidGrab Transfert</h2>$errHtml<form method='get'><input name='pin' type='number' placeholder='Code 4 chiffres' required><br><button type='submit'>Accéder</button></form></html>"
    }

    private fun isRateLimited(ip: String): Boolean {
        val entry = badAttempts[ip] ?: return false
        if (System.currentTimeMillis() - entry.second > 60_000L) {
            badAttempts.remove(ip)
            return false
        }
        return entry.first >= 5
    }

    private fun writeText(ch: SocketChannel, code: Int, reason: String, mime: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val hdr = "HTTP/1.1 $code $reason\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        writeFully(ch, ByteBuffer.wrap(hdr.toByteArray(Charsets.UTF_8)))
        writeFully(ch, ByteBuffer.wrap(bytes))
    }

    private fun writeFully(ch: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) ch.write(buf)
    }

    private class HttpReq(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readHttpRequest(ins: BufferedInputStream): HttpReq? {
        val headerBytes = ByteArrayOutputStream()
        var prev = 0
        var count = 0
        while (count < 16_384) {
            val b = ins.read()
            if (b < 0) return null
            headerBytes.write(b)
            count++
            if (prev == '\r'.code && b == '\n'.code) {
                val arr = headerBytes.toByteArray()
                if (arr.size >= 4 && arr[arr.size - 4] == '\r'.code.toByte() && arr[arr.size - 3] == '\n'.code.toByte()) break
            }
            prev = b
        }
        val lines = headerBytes.toString("UTF-8").split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
        val body = if (len > 0) {
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = ins.read(buf, off, len - off)
                if (r < 0) break
                off += r
            }
            String(buf, 0, off, Charsets.UTF_8)
        } else ""
        return HttpReq(first[0], first[1], headers, body)
    }

    private fun paramOf(query: String, key: String): String? =
        query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
}
