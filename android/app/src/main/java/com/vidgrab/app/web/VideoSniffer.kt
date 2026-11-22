package com.vidgrab.app.web

import android.net.Uri
import android.webkit.CookieManager
import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.data.Http
import com.vidgrab.app.download.HlsParser
import okhttp3.OkHttpClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Détecteur de flux vidéo : analyse du Content-Type + signature binaire (#EXTM3U, ftyp, EBML)
 * pour démasquer les vidéos camouflées en .txt / .png / .js / sans extension.
 */
class VideoSniffer(private val onFound: (DetectedVideo) -> Unit) {

    @Volatile
    var userAgent: String = ""

    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun reset() = seen.clear()

    /** Appelé depuis shouldInterceptRequest (thread IO du WebView). */
    fun onRequest(url: String, reqHeaders: Map<String, String>, pageUrl: String, pageTitle: String) {
        if (!url.startsWith("http")) return
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return
        if (isExcluded(host) || isExcluded(Uri.parse(pageUrl).host)) return
        val ext = extensionOf(uri)
        if (ext in IGNORED_EXT) return
        if (ext != "m3u8" && looksLikeSegment(url)) return
        if (ext !in CANDIDATE_EXT) return
        if (!seen.add(url)) return
        val headers = buildHeaders(reqHeaders, pageUrl, url)
        pool.execute { runCatching { analyze(url, headers, ext, pageUrl, pageTitle) } }
    }

    /** Filet de sécurité : URL remontée par l'écouteur JS sur <video>. */
    fun onJsVideo(src: String, pageUrl: String, pageTitle: String) {
        if (!src.startsWith("http")) return
        val uri = Uri.parse(src)
        val host = uri.host?.lowercase() ?: return
        if (isExcluded(host) || isExcluded(Uri.parse(pageUrl).host)) return
        if (!seen.add(src)) return
        val ext = extensionOf(uri)
        val headers = buildHeaders(emptyMap(), pageUrl, src)
        pool.execute {
            val found = runCatching { analyze(src, headers, ext, pageUrl, pageTitle) }.getOrDefault(false)
            if (!found && ext != "ts" && ext != "m4s") {
                val fmt = when (ext) {
                    "m3u8" -> "HLS"; "webm" -> "WEBM"; "mkv" -> "MKV"; else -> "MP4"
                }
                emit(src, fmt, -1L, guessResolution(src), headers, pageUrl, pageTitle)
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun buildHeaders(req: Map<String, String>, pageUrl: String, url: String): Map<String, String> {
        val h = HashMap<String, String>(req)
        if (userAgent.isNotBlank()) h["User-Agent"] = userAgent
        if (h.keys.none { it.equals("Referer", true) } && pageUrl.isNotBlank()) h["Referer"] = pageUrl
        if (h.keys.none { it.equals("Cookie", true) }) {
            val c: String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!c.isNullOrBlank()) h["Cookie"] = c
        }
        return h
    }

    private class Probe(val code: Int, val contentType: String, val size: Long, val head: ByteArray)

    private fun fetch(url: String, headers: Map<String, String>, range: Boolean, full: Boolean): Probe {
        val h = HashMap<String, String>(headers)
        if (!full) {
            h.keys.filter { it.equals("Origin", true) || it.equals("Cookie", true) }.forEach { h.remove(it) }
        }
        val req = Http.request(url, h, if (range) "bytes=0-1023" else null)
        probeClient.newCall(req).execute().use { r ->
            val ct: String = r.header("Content-Type") ?: ""
            val cr: String? = r.header("Content-Range")
            val fromRange: Long? = cr?.substringAfterLast('/')?.trim()?.toLongOrNull()
            val fromLen: Long? = r.header("Content-Length")?.trim()?.toLongOrNull()
            val size: Long = fromRange ?: fromLen ?: -1L
            val buf = ByteArray(512)
            var n = 0
            if (r.isSuccessful) {
                // Jamais body.string() : 512 octets max puis fermeture immédiate (anti-OOM)
                val ins = r.body?.byteStream()
                if (ins != null) {
                    while (n < 512) {
                        val k = ins.read(buf, n, 512 - n)
                        if (k < 0) break
                        n += k
                    }
                }
            }
            return Probe(r.code, ct, size, buf.copyOf(n))
        }
    }

    private fun probe(url: String, headers: Map<String, String>): Probe? {
        var p = fetch(url, headers, range = true, full = false)
        if (p.code == 400 || p.code == 416 || p.code == 501) p = fetch(url, headers, range = false, full = false)
        val hadOrigin = headers.keys.any { it.equals("Origin", true) }
        if ((p.code == 401 || p.code == 403) && hadOrigin) p = fetch(url, headers, range = true, full = true)
        return if (p.code in 200..299) p else null
    }

    /** @return true si un flux a été détecté et émis. */
    private fun analyze(url: String, headers: Map<String, String>, ext: String, pageUrl: String, pageTitle: String): Boolean {
        val p = probe(url, headers) ?: return false
        val head = p.head
        val ct = p.contentType.lowercase()

        // Segment MPEG-TS déguisé (0x47 aux octets 0 et 188) → rejeté
        if (head.size > 188 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()) return false
        if (ct.contains("mp2t")) return false

        val isM3u8 = head.size >= 7 && String(head, 0, 7, Charsets.ISO_8859_1) == "#EXTM3U"
        val isFtyp = head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
        val isEbml = head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()

        return when {
            isM3u8 -> { emitHls(url, headers, pageUrl, pageTitle); true }
            isFtyp -> directIfBigEnough(url, "MP4", p.size, headers, pageUrl, pageTitle)
            isEbml -> {
                val fmt = if (ct.contains("webm") || ext == "webm") "WEBM" else "MKV"
                directIfBigEnough(url, fmt, p.size, headers, pageUrl, pageTitle)
            }
            ct.startsWith("video/") -> {
                val fmt = when {
                    ct.contains("webm") -> "WEBM"; ct.contains("matroska") -> "MKV"; else -> "MP4"
                }
                directIfBigEnough(url, fmt, p.size, headers, pageUrl, pageTitle)
            }
            ct.contains("mpegurl") -> { emitHls(url, headers, pageUrl, pageTitle); true }
            else -> false
        }
    }

    private fun directIfBigEnough(
        url: String, fmt: String, size: Long, headers: Map<String, String>, pageUrl: String, title: String
    ): Boolean {
        if (size in 0L..200_000L) return false
        emit(url, fmt, size, guessResolution(url), headers, pageUrl, title)
        return true
    }

    private fun emitHls(url: String, headers: Map<String, String>, pageUrl: String, title: String) {
        val text: String? = HlsParser.fetchText(url, headers)
        if (text == null) {
            emit(url, "HLS", -1L, "Auto", headers, pageUrl, title)
            return
        }
        if (HlsParser.isMaster(text)) {
            HlsParser.parseMaster(text, url).take(6).forEach { v ->
                val media: String? = runCatching { HlsParser.fetchText(v.url, headers) }.getOrNull()
                var size: Long = -1L
                var dur = 0.0
                if (media != null) {
                    dur = HlsParser.parseMedia(media, v.url).totalDuration
                    if (dur > 0.0 && v.bandwidth > 0L) size = ((v.bandwidth.toDouble() / 8.0) * dur).toLong()
                }
                emit(v.url, "HLS", size, v.label, headers, pageUrl, title, dur)
            }
        } else {
            val pl = runCatching { HlsParser.parseMedia(text, url) }.getOrNull() ?: return
            if (pl.segments.isEmpty()) return
            emit(url, "HLS", -1L, guessResolution(url), headers, pageUrl, title, pl.totalDuration)
        }
    }

    private fun emit(
        url: String, fmt: String, size: Long, res: String, headers: Map<String, String>,
        pageUrl: String, pageTitle: String, durationSec: Double = 0.0
    ) {
        val fileName = Uri.parse(url).path.orEmpty().substringAfterLast('/', "").substringBeforeLast('.')
        val title = pageTitle.trim().ifBlank { fileName.ifBlank { Uri.parse(url).host.orEmpty() } }.take(90)
        val id = (url.hashCode().toLong() and 0xFFFFFFFFL).toString(16) + "_" + System.nanoTime().toString(16)
        onFound(DetectedVideo(id, url, title, fmt, size, res, pageUrl, headers, durationSec))
    }

    companion object {
        private val EXCLUDED = listOf("youtube.com", "googlevideo.com", "youtu.be", "youtube-nocookie.com")

        private val IGNORED_EXT = setOf(
            "ts", "m4s", "css", "woff", "woff2", "ttf", "otf", "ico", "svg", "gif", "webp", "avif",
            "html", "htm", "map", "wasm", "vtt", "srt", "aac", "mp3", "json", "xml"
        )
        // Extensions directes + extensions de camouflage fréquentes
        private val CANDIDATE_EXT = setOf(
            "mp4", "m4v", "webm", "mkv", "mov", "m3u8", "txt", "png", "jpg", "jpeg", "js", "php", "bin", "dat", ""
        )
        private val resRegex = Regex("(?<![0-9])(2160|1440|1080|720|480|360|240)(?![0-9])")

        private val probeClient: OkHttpClient by lazy {
            Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        }

        private val pool: ThreadPoolExecutor by lazy {
            ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64), ThreadPoolExecutor.DiscardPolicy())
        }

        fun isExcluded(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            val h = host.lowercase()
            return EXCLUDED.any { h == it || h.endsWith(".$it") }
        }

        /** Extension extraite uniquement du dernier segment du chemin (ex: /v1.2/stream/master → ""). */
        fun extensionOf(uri: Uri): String {
            val last = (uri.path ?: "").substringAfterLast('/', "")
            return if (last.contains('.')) last.substringAfterLast('.').lowercase() else ""
        }

        fun looksLikeSegment(url: String): Boolean {
            val l = url.lowercase()
            return l.contains("/seg") || l.contains("seg-") || l.contains("segment") ||
                l.contains("chunk-") || l.contains(".m4s") || l.contains("init.")
        }

        fun guessResolution(url: String): String {
            val m = resRegex.find(url) ?: return "Auto"
            return m.groupValues[1] + "p"
        }
    }
}* Extension extraite uniquement du dernier segment du chemin (ex: /v1.2/stream/master → ""). */
        fun extensionOf(uri: Uri): String {
            val last = (uri.path ?: "").substringAfterLast('/', "")
            return if (last.contains('.')) last.substringAfterLast('.').lowercase() else ""
        }

        fun looksLikeSegment(url: String): Boolean {
            val l = url.lowercase()
            return l.contains("/seg") || l.contains("seg-") || l.contains("segment") ||
                l.contains("chunk-") || l.contains(".m4s") || l.contains("init.")
        }

        fun guessResolution(url: String): String {
            val m = resRegex.find(url) ?: return "Auto"
            return m.groupValues[1] + "p"
        }
    }
}
