package com.vidgrab.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.File

/**
 * Bibliothèque : vidéos publiques (MediaStore, Movies/VidGrab) + dossier privé (context.filesDir/private,
 * avec .nomedia, jamais exposé à la Galerie).
 */
object LibraryRepository {

    fun privateDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "private")
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return dir
    }

    fun loadPublic(ctx: Context): List<LibraryItem> {
        val out = ArrayList<LibraryItem>()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("Movies/VidGrab%")
        runCatching {
            ctx.contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id: Long = c.getLong(iId)
                    out.add(
                        LibraryItem(
                            key = "pub:$id",
                            uri = ContentUris.withAppendedId(collection, id),
                            title = c.getString(iName).substringBeforeLast('.'),
                            sizeBytes = c.getLong(iSize),
                            durationMs = c.getLong(iDur),
                            dateAdded = c.getLong(iDate),
                            isPrivate = false,
                            file = null
                        )
                    )
                }
            }
        }
        return out
    }

    fun loadPrivate(ctx: Context): List<LibraryItem> {
        val files = privateDir(ctx).listFiles { f -> f.isFile && f.name != ".nomedia" } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            LibraryItem(
                key = "prv:${f.name}",
                uri = Uri.fromFile(f),
                title = f.nameWithoutExtension,
                sizeBytes = f.length(),
                durationMs = durationOf(f),
                dateAdded = f.lastModified() / 1000L,
                isPrivate = true,
                file = f
            )
        }
    }

    private fun durationOf(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    fun thumbnail(ctx: Context, item: LibraryItem): Bitmap? = runCatching {
        if (item.isPrivate && item.file != null) {
            ThumbnailUtils.createVideoThumbnail(item.file, Size(640, 360), null)
        } else {
            ctx.contentResolver.loadThumbnail(item.uri, Size(640, 360), null)
        }
    }.getOrNull()

    fun delete(ctx: Context, item: LibraryItem): Boolean = runCatching {
        if (item.isPrivate) item.file?.delete() == true
        else ctx.contentResolver.delete(item.uri, null, null) > 0
    }.getOrDefault(false)

    fun rename(ctx: Context, item: LibraryItem, newName: String): Boolean = runCatching {
        val clean = Storage.safeName(newName)
        if (item.isPrivate && item.file != null) {
            val ext = item.file.extension.ifBlank { "mp4" }
            val target = File(item.file.parentFile, "$clean.$ext")
            if (target == item.file) return true
            if (target.exists()) return false
            item.file.renameTo(target)
        } else {
            val ext = publicExtension(ctx, item.uri)
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$clean.$ext")
            }
            ctx.contentResolver.update(item.uri, v, null, null) > 0
        }
    }.getOrDefault(false)

    /** Copie vers le stockage interne (.nomedia) avec extension exacte et nom unique, puis supprime du MediaStore. */
    fun moveToPrivate(ctx: Context, item: LibraryItem): Boolean = runCatching {
        val ext = publicExtension(ctx, item.uri)
        val target = uniquePrivateFile(privateDir(ctx), Storage.safeName(item.title), ext)
        ctx.contentResolver.openInputStream(item.uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
        } ?: return false
        val deleted = ctx.contentResolver.delete(item.uri, null, null) > 0
        if (!deleted) {
            target.delete()
            false
        } else true
    }.getOrDefault(false)

    /** Rend une vidéo privée à la Galerie (Movies/VidGrab). */
    fun moveToPublic(ctx: Context, item: LibraryItem): Boolean = runCatching {
        val f = item.file ?: return false
        val uri = Storage.saveToMovies(ctx, f, item.title)
        if (uri != null) {
            f.delete()
            true
        } else false
    }.getOrDefault(false)

    private fun publicExtension(ctx: Context, uri: Uri): String {
        var ext = ""
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0).orEmpty()
                if (name.contains('.')) ext = name.substringAfterLast('.')
            }
        }
        if (ext.isBlank()) {
            val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
            ext = when {
                mime.contains("matroska") -> "mkv"
                mime.contains("webm") -> "webm"
                mime.contains("mp2t") -> "ts"
                mime.contains("quicktime") -> "mov"
                else -> "mp4"
            }
        }
        return ext.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "mp4" }
    }

    private fun uniquePrivateFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 1
        while (f.exists()) {
            f = File(dir, "${base}_$n.$ext")
            n++
        }
        return f
    }

    fun share(ctx: Context, item: LibraryItem) {
        if (item.isPrivate) return // les fichiers privés ne sont jamais exposés
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Partager la vidéo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("unused")
    val isModern: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
