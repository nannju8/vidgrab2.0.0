package com.vidgrab.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vidgrab.app.MainActivity
import com.vidgrab.app.R
import com.vidgrab.app.data.BatchConfig
import com.vidgrab.app.data.BatchState
import com.vidgrab.app.data.BatchStatus
import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.web.BatchUrlPattern
import com.vidgrab.app.web.PageAutomator
import com.vidgrab.app.web.TabManager
import com.vidgrab.app.web.TabState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Orchestrateur du Mode Série : tourne dans un onglet Robot dédié, détecte en mode réactif et alerte à la fin. */
object BatchSeriesManager {

    private const val CHANNEL_ALERTS = "batch_alerts"
    private const val NOTIF_BATCH_ID = 4343

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runnerJob: Job? = null
    private var episodeJob: Job? = null
    private var collectJob: Job? = null

    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state

    private var consecutiveNotFound = 0
    private var currentEpisodeHandled = false
    private var channelCreated = false

    fun start(config: BatchConfig) {
        val sourceTab = findTab(config.tabId) ?: return
        stop()
        consecutiveNotFound = 0
        currentEpisodeHandled = false

        val total = (config.endEp - config.startEp + 1).coerceAtLeast(1)
        var firstRobotEp = config.startEp
        var initialCompleted = 0

        // Si l'épisode de départ est déjà détecté sur l'onglet actuel, on l'envoie tout de suite sans recharger
        val firstUrl = BatchUrlPattern.buildUrl(config.urlTemplate, config.startEp, config.padWidth)
        if (urlsMatch(sourceTab.url, firstUrl)) {
            val valid = sourceTab.videos.filter { isLikelyEpisode(it) }
            val best = pickBestVideo(valid, config.targetQuality)
            if (best != null) {
                val formatted = BatchUrlPattern.formatNum(config.startEp, config.padWidth)
                DownloadCenter.enqueue(best.copy(title = "${config.seriesTitle} - Ép. $formatted"), silent = true)
                initialCompleted = 1
                firstRobotEp = config.startEp + 1
            }
        }

        if (firstRobotEp > config.endEp) {
            val msg = "Détection terminée : $initialCompleted/$total épisode(s) en file"
            _state.value = BatchState(
                status = BatchStatus.DONE,
                currentEp = config.endEp,
                totalEps = total,
                completedCount = initialCompleted,
                message = msg
            )
            DownloadCenter.events.tryEmit(msg)
            notifyBatch("Détection terminée", msg)
            return
        }

        // Crée un onglet Robot isolé pour laisser l'utilisateur naviguer librement ailleurs
        val robotTab = TabManager.openBackgroundTab(sourceTab)
        val robotConfig = config.copy(tabId = robotTab.id)

        _state.value = BatchState(
            status = BatchStatus.LOADING_PAGE,
            config = robotConfig,
            currentEp = firstRobotEp,
            totalEps = total,
            completedCount = initialCompleted,
            message = "Robot lancé (Ép. $firstRobotEp → ${config.endEp})…"
        )
        scheduleNextEpisode(robotTab, robotConfig, firstRobotEp)
    }

    fun pause() {
        val st = _state.value
        if (st.status == BatchStatus.IDLE || st.status == BatchStatus.DONE) return
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        _state.value = st.copy(status = BatchStatus.PAUSED, message = "Lot en pause (Ép. ${st.currentEp})")
    }

    fun resume() {
        val st = _state.value
        val cfg = st.config ?: return
        if (st.status != BatchStatus.PAUSED && st.status != BatchStatus.NEEDS_USER) return
        val tab = findTab(cfg.tabId) ?: run { stop(); return }
        if (tab.videos.any { isLikelyEpisode(it) }) {
            schedulePickFromTab(tab, cfg, st.currentEp, immediate = true)
            return
        }
        _state.value = st.copy(status = BatchStatus.LOADING_PAGE, message = "Reprise à l'épisode ${st.currentEp}…")
        loadEpisode(tab, cfg, st.currentEp)
    }

    fun skipCurrent() {
        val st = _state.value
        if (st.config == null || st.status == BatchStatus.IDLE || st.status == BatchStatus.DONE) return
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        _state.value = st.copy(failedEps = st.failedEps + st.currentEp)
        advanceToNext()
    }

    fun stop() {
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        episodeJob = null
        collectJob = null
        runnerJob = null
        currentEpisodeHandled = false
        val tabToClose = _state.value.config?.tabId?.let { findTab(it) }?.takeIf { it.isBatchTab }
        _state.value = BatchState()
        tabToClose?.let { TabManager.closeTab(it) }
    }

    fun stopIfTab(tabId: Int) {
        val st = _state.value
        if (st.config?.tabId == tabId && st.status != BatchStatus.DONE) stop()
    }

    // ---- Événements du WebView / VideoSniffer -----------------------------------------------

    fun onPageFinished(tabId: Int, url: String) {
        val st = _state.value
        val cfg = st.config ?: return
        if (cfg.tabId != tabId || st.status != BatchStatus.LOADING_PAGE || currentEpisodeHandled) return
        val tab = findTab(tabId) ?: return

        if (tab.videos.any { isLikelyEpisode(it) }) {
            schedulePickFromTab(tab, cfg, st.currentEp, immediate = false)
            return
        }
        startPageAutomation(tab, cfg, st.currentEp)
    }

    fun onHttpError(tabId: Int, code: Int) {
        val st = _state.value
        val cfg = st.config ?: return
        if (cfg.tabId != tabId || st.status != BatchStatus.LOADING_PAGE) return

        if (code == 404 || code == 410) {
            consecutiveNotFound++
            episodeJob?.cancel()
            collectJob?.cancel()
            _state.value = st.copy(failedEps = st.failedEps + st.currentEp)
            if (consecutiveNotFound >= 2) {
                finishBatch("Arrêt auto : épisodes introuvables (HTTP $code)")
            } else {
                advanceToNext()
            }
        } else if (code == 403 || code == 503 || code == 429) {
            episodeJob?.cancel()
            val msg = "Ép. ${st.currentEp} : vérification Cloudflare (HTTP $code) requise"
            _state.value = st.copy(status = BatchStatus.NEEDS_USER, message = msg)
            notifyBatch("Action requise", msg)
        }
    }

    fun onVideoDetected(tabId: Int, video: DetectedVideo) {
        val st = _state.value
        val cfg = st.config ?: return
        if (cfg.tabId != tabId || currentEpisodeHandled) return
        if (st.status != BatchStatus.LOADING_PAGE &&
            st.status != BatchStatus.AUTOMATING &&
            st.status != BatchStatus.NEEDS_USER
        ) return
        if (!isLikelyEpisode(video)) return

        val tab = findTab(tabId) ?: return
        schedulePickFromTab(tab, cfg, st.currentEp, immediate = false, lastVideo = video)
    }

    // ---- Enchaînement réactif rapide --------------------------------------------------------

    private fun scheduleNextEpisode(tab: TabState, cfg: BatchConfig, ep: Int) {
        runnerJob?.cancel()
        if (cfg.fastMode || DownloadCenter.activeCount() < DownloadCenter.MAX_PARALLEL) {
            loadEpisode(tab, cfg, ep)
            return
        }
        runnerJob = scope.launch {
            _state.value = _state.value.copy(
                status = BatchStatus.WAITING_SLOT,
                currentEp = ep,
                message = "En attente d'une place avant Ép. $ep/${cfg.endEp}…"
            )
            DownloadCenter.tasks.first { DownloadCenter.activeCount() < DownloadCenter.MAX_PARALLEL }
            val latest = _state.value
            if (latest.config == null || latest.status == BatchStatus.PAUSED || latest.status == BatchStatus.IDLE) return@launch
            loadEpisode(tab, cfg, ep)
        }
    }

    private fun loadEpisode(tab: TabState, cfg: BatchConfig, ep: Int) {
        episodeJob?.cancel()
        collectJob?.cancel()
        currentEpisodeHandled = false
        val url = BatchUrlPattern.buildUrl(cfg.urlTemplate, ep, cfg.padWidth)
        _state.value = _state.value.copy(
            status = BatchStatus.LOADING_PAGE,
            currentEp = ep,
            message = "Chargement Ép. $ep/${cfg.endEp}…"
        )
        TabManager.load(tab, url)
    }

    private fun startPageAutomation(tab: TabState, cfg: BatchConfig, ep: Int) {
        val wv = tab.webView ?: return
        episodeJob?.cancel()
        episodeJob = scope.launch {
            _state.value = _state.value.copy(
                status = BatchStatus.AUTOMATING,
                message = "Ép. $ep/${cfg.endEp} : recherche du lecteur…"
            )
            delay(300L)
            if (currentEpisodeHandled || !isActive) return@launch

            if (PageAutomator.isBlockedByChallenge(wv)) {
                val msg = "Ép. $ep : passez Cloudflare puis lancez la lecture"
                _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                notifyBatch("Cloudflare détecté", msg)
                return@launch
            }

            // 1) Choix réactif du lecteur préféré (polling toutes les 250 ms pendant max 2 s)
            val prefs = buildPlayerPriority(tab, cfg)
            var clickedPlayer: String? = null
            for (step in 0 until 8) {
                if (currentEpisodeHandled || !isActive) return@launch
