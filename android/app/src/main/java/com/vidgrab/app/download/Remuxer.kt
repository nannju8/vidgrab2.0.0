package com.vidgrab.app.download

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Fusion sans ré-encodage (.ts → .mp4) via Media3 Transformer, toujours sur le Looper principal. */
object Remuxer {
    suspend fun toMp4(ctx: Context, input: File, output: File): Boolean {
        val done = CompletableDeferred<Boolean>()
        var transformer: Transformer? = null
        output.delete()
        // Instanciation + start() sur le thread principal (sinon crash : pas de Looper sur Dispatchers.IO)
        withContext(Dispatchers.Main) {
            try {
                val t = Transformer.Builder(ctx.applicationContext)
                    .setLooper(Looper.getMainLooper())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(true)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done.complete(false)
                        }
                    })
                    .build()
                transformer = t
                t.start(MediaItem.fromUri(Uri.fromFile(input)), output.absolutePath)
            } catch (e: Exception) {
                done.complete(false)
            }
        }
        return try {
            val ok = done.await()
            ok && output.exists() && output.length() > 0L
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main) { runCatching { transformer?.cancel() } }
            throw e
        }
    }
}
