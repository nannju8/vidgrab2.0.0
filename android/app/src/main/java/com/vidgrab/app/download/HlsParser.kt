package com.vidgrab.app.download

import com.vidgrab.app.data.Http
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream

/** Parseur HLS natif : playlists maîtres (qualités), playlists média, chiffrement AES-128, EXT-X-MAP. */
object HlsParser {
    data class Variant(val url: String, val height: Int, val bandwidth: Long) {
        val label: String get() = if (height > 0) "${height}p" else "Auto"
    }

    class KeyInfo(val uri: String, val iv: ByteArray?)

    data class Segment(val url: String, val duration: Double, val seq: Long, val key: KeyInfo?)

    data class MediaPlaylist(val segments: List<Segment>, val mapUri: String?, val totalDuration: Double)

    fun fetchText(url: String, headers: Map<String, String>): String? {
        val call = Http.client.newCall(Http.request(url, headers))
        call.execute().use { r ->
            if (!r.isSuccessful) return null
            val ins = r.body?.byteStream() ?: return null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (total > 2_000_000) break
            }
            return out.toString("UTF-8")
        }
    }

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    private fun resolve(base: String, ref: String): String =
        base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    private val attrRegex = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    private fun attrs(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        attrRegex.findAll(line.substringAfter(':')).forEach {
            map[it.groupValues[1]] = it.groupValues[2].trim('"')
        }
        return map
    }

    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        val out = ArrayList<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val a = attrs(line)
                val bw: Long = a["BANDWIDTH"]?.toLongOrNull() ?: 0L
                val height: Int = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull() ?: 0
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) out.add(Variant(resolve(baseUrl, lines[j]), height, bw))
                i = j
            }
            i++
        }
        return out.sortedByDescending { if (it.height > 0) it.height.toLong() else it.bandwidth }
    }

    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        val segments = ArrayList<Segment>()
        var seq = 0L
        var key: KeyInfo? = null
        var mapUri: String? = null
        var pendingDuration = 0.0
        var total = 0.0
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY") -> {
                    val a = attrs(line)
                    val method = a["METHOD"] ?: "NONE"
                    key = when (method) {
                        "AES-128" -> {
                            val uri = a["URI"] ?: throw IllegalStateException("Clé AES manquante")
                            KeyInfo(resolve(baseUrl, uri), a["IV"]?.let { parseIv(it) })
                        }
                        "NONE" -> null
                        else -> throw IllegalStateException("Chiffrement HLS non supporté : $method")
                    }
                }
                line.startsWith("#EXT-X-MAP") -> attrs(line)["URI"]?.let { mapUri = resolve(baseUrl, it) }
                line.startsWith("#EXTINF") -> {
                    val d: Double = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    pendingDuration = d
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments.add(Segment(resolve(baseUrl, line), pendingDuration, seq, key))
                    total += pendingDuration
                    seq++
                    pendingDuration = 0.0
                }
            }
        }
        return MediaPlaylist(segments, mapUri, total)
    }

    private fun parseIv(s: String): ByteArray {
        val hex = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { idx -> hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
    }

    /** IV par défaut = numéro de séquence en big-endian sur 16 octets. */
    fun ivFor(seg: Segment): ByteArray {
        seg.key?.iv?.let { return it }
        val iv = ByteArray(16)
        var v: Long = seg.seq
        for (i in 15 downTo 8) {
            iv[i] = (v and 0xFFL).toByte()
            v = v shr 8
        }
        return iv
    }
}
