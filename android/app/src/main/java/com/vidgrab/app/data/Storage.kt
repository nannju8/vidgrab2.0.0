package com.vidgrab.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Enregistrement public dans Movies/VidGrab via MediaStore (Android 10 → 15, aucune permission). */
object Storage {
    const val RELATIVE_DIR = "Movies/VidGrab"

    fun safeName(title: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim()
        return cleaned.take(90).ifBlank { "video_${System.currentTimeMillis()}" }
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }

    fun saveToMovies(ctx: Context, file: File, baseName: String): Uri? {
        val ext = file.extension.ifBlank { "mp4" }
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${safeName(baseName)}.$ext")
            put(MediaStore.Video.Media.MIME_TYPE, mimeFor(ext))
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VidGrab")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
            } ?: throw IllegalStateException("openOutputStream null")
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }
}
