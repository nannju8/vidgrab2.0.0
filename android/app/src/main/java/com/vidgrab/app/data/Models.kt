package com.vidgrab.app.data

import android.net.Uri
import java.io.File

/** Flux vidéo détecté par le VideoSniffer. */
data class DetectedVideo(
    val id: String,
    val url: String,
    val title: String,
    /** "MP4", "HLS", "WEBM", "MKV" */
    val format: String,
    val sizeBytes: Long,
    /** "480p", "720p", "1080p" ou "Auto" */
    val resolution: String,
    val pageUrl: String,
    /** En-têtes exacts de la requête d'origine (Referer, Origin, User-Agent, Cookie). */
    val headers: Map<String, String>,
    val durationSec: Double = 0.0
)

enum class DlStatus { QUEUED, RUNNING, PAUSED, WAITING_WIFI, MERGING, DONE, FAILED, CANCELED }

data class DownloadTask(
    val id: String,
    val video: DetectedVideo,
    val status: DlStatus = DlStatus.QUEUED,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val speedBps: Long = 0L,
    val etaSec: Long = -1L,
    val error: String? = null,
    /** Vrai si la tâche a été interrompue par la mort du processus (reprise par WorkManager). */
    val interrupted: Boolean = false
)

data class Bookmark(val title: String, val url: String)

data class HistoryEntry(val title: String, val url: String, val timestamp: Long)

data class LibraryItem(
    /** Clé stable : "pub:<mediaStoreId>" ou "prv:<filename>" */
    val key: String,
    val uri: Uri,
    val title: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAdded: Long,
    val isPrivate: Boolean,
    val file: File?
)

enum class BatchStatus { IDLE, WAITING_SLOT, LOADING_PAGE, AUTOMATING, NEEDS_USER, PAUSED, DONE }

data class BatchConfig(
    val tabId: Int,
    val urlTemplate: String,
    val seriesTitle: String,
    val startEp: Int,
    val endEp: Int,
    val padWidth: Int,
    val targetQuality: String,
    val preferredPlayers: List<String>,
    val fastMode: Boolean = true
)

data class BatchState(
    val status: BatchStatus = BatchStatus.IDLE,
    val config: BatchConfig? = null,
    val currentEp: Int = 0,
    val totalEps: Int = 0,
    val message: String = "",
    val completedCount: Int = 0,
    val failedEps: List<Int> = emptyList()
)
