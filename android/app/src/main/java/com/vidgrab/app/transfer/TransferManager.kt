package com.vidgrab.app.transfer

import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vidgrab.app.data.LibraryItem
import com.vidgrab.app.download.DownloadCenter
import com.vidgrab.app.download.DownloadService
import com.vidgrab.app.web.TabManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/** Orchestrateur unique du mini-Xender (Envoi & Réception haute vitesse, état temps réel, ETA). */
object TransferManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state

    private var server: TransferServer? = null
    private var discoveryJob: Job? = null
    private var netWatchJob: Job? = null
    private var receiveJob: Job? = null
    private var progressPushJob: Job? = null

    private var pendingSendItems: List<LibraryItem> = emptyList()
    private var lastSpeedAt = 0L
    private var lastSpeedBytes = 0L
    private var smoothedSpeed = 0L

    fun isActive(): Boolean = _state.value.isActive

    fun refreshNetwork() {
        val ctx = TabManager.context
        val net = LocalNetHelper.inspect(ctx)
        val st = _state.value
        if (st.role == TransferRole.IDLE) return

        if (st.role == TransferRole.SENDING && st.status == TransferStatus.WAITING_NET && net.active) {
            startSending(pendingSendItems, st.includeApk)
            return
        }
        if (st.role == TransferRole.RECEIVING && st.status == TransferStatus.WAITING_NET && net.active) {
            startReceivingMode()
            return
        }
        _state.update {
            it.copy(
                localIp = net.primaryIp,
                status = if (!net.active && (it.status == TransferStatus.READY || it.status == TransferStatus.WAITING_NET))
                    TransferStatus.WAITING_NET else it.status
            )
        }
    }

    // ---- Émission (Envoyer) -----------------------------------------------------------------

    fun startSending(items: List<LibraryItem>, includeApk: Boolean = false) {
        stopInternal()
        pendingSendItems = items
        val ctx = TabManager.context
        val sources = buildSources(items, includeApk)
        val fileStates = sources.mapIndexed { idx, s ->
            TransferFileState(idx, s.name, s.ext, s.sizeBytes, isApk = s.isApk)
        }
        val net = LocalNetHelper.inspect(ctx)
        if (!net.active) {
            _state.value = TransferState(
                role = TransferRole.SENDING,
                status = TransferStatus.WAITING_NET,
                includeApk = includeApk,
                files = fileStates,
                message = "Activez le Wi-Fi ou le Point d'accès pour commencer"
            )
            startNetWatcher()
            return
        }
        if (sources.isEmpty()) {
            _state.value = TransferState(
                role = TransferRole.SENDING,
                status = TransferStatus.IDLE,
                localIp = net.primaryIp,
                includeApk = includeApk,
                message = "Sélectionnez au moins une vidéo"
            )
            return
        }

        val pin = (1000 + SecureRandom().nextInt(9000)).toString()
        resetSpeed()
        val srv = TransferServer(
            ctx = ctx,
            sources = sources,
            pin = pin,
            onPeerConnected = { peer ->
                scope.launch {
                    ensureService()
                    _state.update {
                        it.copy(
                            status = TransferStatus.TRANSFERRING,
                            peerName = peer,
                            message = "Connecté à $peer — transfert en cours…"
                        )
                    }
                }
            },
            onProgressReport = { _, doneFiles ->
                scope.launch { applySenderConfirm(doneFiles) }
            },
            onRawBytesSent = { idx, delta ->
                scope.launch { applyRawBytes(idx, delta) }
            }
        )
        val port = srv.start()
        server = srv
        _state.value = TransferState(
            role = TransferRole.SENDING,
            status = TransferStatus.READY,
            pin = pin,
            localIp = net.primaryIp,
            port = port,
            includeApk = includeApk,
            files = fileStates,
            message = "En attente de l'autre VidGrab…"
        )
        startNetWatcher()
    }

    fun toggleIncludeApk(include: Boolean) {
        if (_state.value.role == TransferRole.SENDING) {
            startSending(pendingSendItems, include)
        }
    }

    // ---- Réception (Recevoir) ---------------------------------------------------------------

    fun startReceivingMode() {
        stopInternal()
        val ctx = TabManager.context
        val net = LocalNetHelper.inspect(ctx)
        if (!net.active) {
            _state.value = TransferState(
                role = TransferRole.RECEIVING,
                status = TransferStatus.WAITING_NET,
                savePrivate = _state.value.savePrivate,
                message = "Activez le Wi-Fi ou le Point d'accès pour détecter l'émetteur"
            )
            startNetWatcher()
            return
        }
        _state.value = TransferState(
            role = TransferRole.RECEIVING,
            status = TransferStatus.READY,
            localIp = net.primaryIp,
            savePrivate = _state.value.savePrivate,
            message = "Recherche des VidGrab sur le réseau…"
        )
        startDiscovery()
        startNetWatcher()
    }

    fun setSavePrivate(priv: Boolean) {
        _state.update { it.copy(savePrivate = priv) }
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = TransferClient.startDiscovery(TabManager.context, scope) { peer ->
            scope.launch {
                _state.update { st ->
                    if (st.role != TransferRole.RECEIVING || st.status != TransferStatus.READY) st
                    else {
                        val updated = st.peers.filterNot { it.ip == peer.ip && it.port == peer.port } + peer
                        st.copy(peers = updated, message = "${updated.size} appareil(s) détecté(s)")
                    }
                }
            }
        }
    }

    fun connectAndReceive(ip: String, port: Int, pin: String) {
        val ctx = TabManager.context
        receiveJob?.cancel()
        receiveJob = scope.launch {
            _state.update {
                it.copy(status = TransferStatus.CONNECTING, error = null, message = "Vérification du code…")
            }
            try {
                val auth = TransferClient.authenticate(ip, port, pin)
                discoveryJob?.cancel()
                resetSpeed()
                val savePrivate = _state.value.savePrivate
                _state.update {
                    it.copy(
                        status = TransferStatus.TRANSFERRING,
                        peerName = it.peers.firstOrNull { p -> p.ip == ip }?.deviceName ?: ip,
                        files = auth.files,
                        message = "Réception en cours…"
                    )
                }
                ensureService()
                startProgressPush(ip, port, auth.token)

                for (f in auth.files) {
                    val saved = TransferClient.downloadFile(ctx, ip, port, auth.token, f, savePrivate) { bytes ->
                        scope.launch { updateReceiveFile(f.index, bytes, done = false, saved = null) }
                    }
                    updateReceiveFile(f.index, f.sizeBytes.coerceAtLeast(1L), done = true, saved = saved)
                    if (!f.isApk) DownloadCenter.libraryChanged.tryEmit(Unit)
                }
                val finalDone = _state.value.totalBytes
                val finalFiles = _state.value.totalFiles
                launch(Dispatchers.IO) { TransferClient.sendProgress(ip, port, auth.token, finalDone, finalFiles) }
                progressPushJob?.cancel()
                _state.update {
                    it.copy(
                        status = TransferStatus.DONE,
                        speedBps = 0L,
                        etaSec = 0L,
                        message = "Transfert terminé : ${it.completedFiles}/${it.totalFiles} fichier(s) reçu(s)"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progressPushJob?.cancel()
                val isAuthErr = e.message?.contains("Code incorrect", true) == true || e.message?.contains("tentatives", true) == true
                _state.update {
                    it.copy(
                        status = if (isAuthErr) TransferStatus.READY else TransferStatus.ERROR,
                        speedBps = 0L,
                        etaSec = -1L,
                        error = e.message ?: "Échec du transfert",
                        message = e.message ?: "Échec du transfert"
                    )
                }
                if (isAuthErr) startDiscovery()
            }
        }
    }

    fun stop() {
        stopInternal()
        _state.value = TransferState()
    }

    private fun stopInternal() {
        netWatchJob?.cancel()
        discoveryJob?.cancel()
        TransferClient.cancelAll()
        receiveJob?.cancel()
        progressPushJob?.cancel()
        server?.stop()
        server = null
    }

    // ---- Helpers vitesse, progression & réseau ----------------------------------------------

    private fun startNetWatcher() {
        netWatchJob?.cancel()
        netWatchJob = scope.launch {
            while (isActive) {
                delay(1500L)
                refreshNetwork()
            }
        }
    }

    private fun startProgressPush(ip: String, port: Int, token: String) {
        progressPushJob?.cancel()
        progressPushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(450L)
                val st = _state.value
                TransferClient.sendProgress(ip, port, token, st.transferredBytes, st.completedFiles)
            }
        }
    }

    private fun updateReceiveFile(index: Int, bytes: Long, done: Boolean, saved: TransferClient.SavedResult?) {
        _state.update { st ->
            val files = st.files.map { f ->
                if (f.index == index) f.copy(
                    transferredBytes = if (done && f.sizeBytes > 0L) f.sizeBytes else bytes,
                    done = done,
                    savedUri = saved?.uri ?: f.savedUri,
                    savedKey = saved?.key ?: f.savedKey
                ) else f
            }
            val totalDone = files.sumOf { it.transferredBytes }
            val totalAll = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            val (spd, eta) = computeSpeed(totalDone, totalAll)
            st.copy(files = files, speedBps = spd, etaSec = eta)
        }
    }

    /** Confirme côté émetteur les fichiers enregistrés par le client sans faire reculer la barre temps réel. */
    private fun applySenderConfirm(doneFiles: Int) {
        _state.update { st ->
            if (st.role != TransferRole.SENDING) return@update st
            val updatedFiles = st.files.mapIndexed { idx, f ->
                if (idx < doneFiles) f.copy(
                    transferredBytes = maxOf(f.transferredBytes, f.sizeBytes),
                    done = true
                ) else f
            }
            val finished = doneFiles >= st.totalFiles && st.totalFiles > 0
            st.copy(
                files = updatedFiles,
                status = if (finished) TransferStatus.DONE else st.status,
                speedBps = if (finished) 0L else st.speedBps,
                etaSec = if (finished) 0L else st.etaSec,
                message = if (finished) "Envoi terminé (${st.totalFiles}/${st.totalFiles})" else st.message
            )
        }
    }

    private fun applyRawBytes(index: Int, delta: Long) {
        _state.update { st ->
            if (st.role != TransferRole.SENDING || st.status == TransferStatus.DONE) return@update st
            val fromBrowser = st.peerName == "Navigateur Web"
            val files = st.files.map { f ->
                if (f.index == index) {
                    val next = (f.transferredBytes + delta).coerceAtMost(f.sizeBytes.coerceAtLeast(0L))
                    f.copy(transferredBytes = next, done = next >= f.sizeBytes && f.sizeBytes > 0L)
                } else f
            }
            val totalDone = files.sumOf { it.transferredBytes }
            val (spd, eta) = computeSpeed(totalDone, st.totalBytes)
            val allDoneByBrowser = fromBrowser && files.isNotEmpty() && files.all { it.done }
            st.copy(
                files = files,
                status = if (allDoneByBrowser) TransferStatus.DONE else TransferStatus.TRANSFERRING,
                speedBps = if (allDoneByBrowser) 0L else spd,
                etaSec = if (allDoneByBrowser) 0L else eta,
                message = if (allDoneByBrowser) "Envoi terminé (${st.totalFiles}/${st.totalFiles})" else st.message
            )
        }
    }

    private fun resetSpeed() {
        lastSpeedAt = SystemClock.elapsedRealtime()
        lastSpeedBytes = 0L
        smoothedSpeed = 0L
    }

    private fun computeSpeed(done: Long, total: Long): Pair<Long, Long> {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastSpeedAt
        if (dt >= 700L) {
            val inst = ((done - lastSpeedBytes).coerceAtLeast(0L) * 1000L) / dt
            smoothedSpeed = if (smoothedSpeed == 0L) inst else (smoothedSpeed * 6L + inst * 4L) / 10L
            lastSpeedAt = now
            lastSpeedBytes = done
        }
        val eta = if (smoothedSpeed > 0L && total > done) (total - done) / smoothedSpeed else -1L
        return smoothedSpeed to eta
    }

    private fun buildSources(items: List<LibraryItem>, includeApk: Boolean): List<TransferSource> {
        val list = items.map { item ->
            val ext = item.file?.extension?.ifBlank { "mp4" } ?: "mp4"
            TransferSource(
                id = item.key,
                name = item.title,
                ext = ext,
                sizeBytes = item.sizeBytes,
                durationMs = item.durationMs,
                uri = item.uri,
                file = item.file,
                isApk = false
            )
        }.toMutableList()
        if (includeApk) {
            val apk = File(TabManager.context.applicationInfo.sourceDir)
            if (apk.exists()) {
                list.add(TransferSource("apk_vidgrab", "VidGrab", "apk", apk.length(), 0L, null, apk, isApk = true))
            }
        }
        return list
    }

    private fun ensureService() {
        runCatching {
            ContextCompat.startForegroundService(TabManager.context, Intent(TabManager.context, DownloadService::class.java))
        }
    }
}
