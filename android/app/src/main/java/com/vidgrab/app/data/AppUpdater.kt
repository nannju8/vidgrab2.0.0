package com.vidgrab.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long
    ) : UpdateState()
    data class Downloading(val version: String, val downloaded: Long, val total: Long) : UpdateState()
    data class Ready(val version: String, val file: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object AppUpdater {
    // Remplace par ton pseudo GitHub et le nom exact de ton dépôt
    private const val GITHUB_OWNER = "nannju8"
    private const val GITHUB_REPO = "vidgrab2.0.0"

    private val apiUrl: String
        get() = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val client by lazy {
        Http.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) {
            _state.value = UpdateState.Idle
        }
    }

    fun currentVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    suspend fun checkForUpdate(ctx: Context, manual: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Downloading) return@withContext null
        if (manual) _state.value = UpdateState.Checking
        val current = currentVersion(ctx)
        val result = runCatching {
            val req = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val j = JSONObject(r.body?.string().orEmpty())
                val tag = j.optString("tag_name").trim().removePrefix("v").removePrefix("V")
                val notes = j.optString("body").trim()
                val assets = j.optJSONArray("assets")
                var apkUrl = ""
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                Triple(tag, notes, apkUrl to apkSize)
            }
        }
        result.fold(
            onSuccess = { (remoteVer, notes, asset) ->
                val (apkUrl, size) = asset
                if (remoteVer.isNotBlank() && apkUrl.isNotBlank() && isNewer(remoteVer, current)) {
                    _state.value = UpdateState.Available(remoteVer, notes, apkUrl, size)
                    null
                } else {
                    if (manual) _state.value = UpdateState.Idle
                    if (manual) "VidGrab est déjà à jour ($current)" else null
                }
            },
            onFailure = { e ->
                if (manual) {
                    _state.value = UpdateState.Idle
                    "Impossible de vérifier (${e.message ?: "hors ligne"})"
                } else null
            }
        )
    }

    suspend fun downloadAndInstall(ctx: Context, avail: UpdateState.Available) = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val tmp = File(dir, "VidGrab-update.apk.tmp")
        val target = File(dir, "VidGrab-update.apk")
        _state.value = UpdateState.Downloading(avail.version, 0L, avail.sizeBytes)
        try {
            val req = Request.Builder().url(avail.apkUrl).build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body ?: error("Fichier vide")
                val total = body.contentLength().takeIf { it > 0L } ?: avail.sizeBytes
                BufferedInputStream(body.byteStream(), 128 * 1024).use { ins ->
                    FileOutputStream(tmp).use { fos ->
                        val buf = ByteArray(128 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 250L) {
                                _state.value = UpdateState.Downloading(avail.version, done, total)
                                last = now
                            }
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) error("Préparation de l'APK impossible")
            _state.value = UpdateState.Ready(avail.version, target)
            withContext(Dispatchers.Main) { installApk(ctx, target) }
        } catch (e: Exception) {
            tmp.delete()
            _state.value = UpdateState.Error(e.message ?: "Échec du téléchargement")
        }
    }

    fun installApk(ctx: Context, file: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val perm = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(perm)
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val rParts = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val cParts = current.split('.', '-').mapNotNull { it.toIntOrNull() }
        val max = maxOf(rParts.size, cParts.size)
        for (i in 0 until max) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
