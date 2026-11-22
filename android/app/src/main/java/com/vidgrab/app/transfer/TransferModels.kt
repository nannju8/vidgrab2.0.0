package com.vidgrab.app.transfer

import android.net.Uri
import java.io.File

enum class TransferRole { IDLE, SENDING, RECEIVING }

enum class TransferStatus { IDLE, WAITING_NET, READY, CONNECTING, TRANSFERRING, PAUSED, DONE, ERROR }

data class PeerInfo(
    val ip: String,
    val port: Int,
    val deviceName: String,
    val fileCount: Int,
    val totalBytes: Long
)

data class TransferSource(
    val id: String,
    val name: String,
    val ext: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val uri: Uri?,
    val file: File?,
    val isApk: Boolean = false
)

data class TransferFileState(
    val index: Int,
    val name: String,
    val ext: String,
    val sizeBytes: Long,
    val transferredBytes: Long = 0L,
    val done: Boolean = false,
    val error: String? = null,
    val savedUri: Uri? = null,
    val savedKey: String? = null,
    val isApk: Boolean = false
)

data class TransferState(
    val role: TransferRole = TransferRole.IDLE,
    val status: TransferStatus = TransferStatus.IDLE,
    val pin: String = "",
    val localIp: String = "",
    val port: Int = 8765,
    val peerName: String = "",
    val savePrivate: Boolean = false,
    val includeApk: Boolean = false,
    val files: List<TransferFileState> = emptyList(),
    val peers: List<PeerInfo> = emptyList(),
    val speedBps: Long = 0L,
    val etaSec: Long = -1L,
    val message: String = "",
    val error: String? = null
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    val transferredBytes: Long get() = files.sumOf { it.transferredBytes.coerceAtLeast(0L) }
    val completedFiles: Int get() = files.count { it.done }
    val totalFiles: Int get() = files.size
    val isActive: Boolean get() =
        status == TransferStatus.READY || status == TransferStatus.CONNECTING || status == TransferStatus.TRANSFERRING
}
