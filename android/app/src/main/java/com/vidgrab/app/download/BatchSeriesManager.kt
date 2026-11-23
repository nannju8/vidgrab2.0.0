package com.vidgrab.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.vidgrab.app.MainActivity
import com.vidgrab.app.R
import com.vidgrab.app.data.BatchConfig
import com.vidgrab.app.data.BatchState
import com.vidgrab.app.data.BatchStatus
import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.web.BatchUrlPattern
import com.vidgrab.app.web.FramePhase
import com.vidgrab.app.web.IframeObserver
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

/**
 * Orchestrateur du Mode Série :
 * - Choisit d'abord le lecteur déjà actif / 1er lecteur du site (ou le lecteur gagnant mémorisé),
 * - Observe réellement l'intérieur de l'iframe (chargement, Cloudflare, lecteur mort, bouton Play prêt),
 * - Enchaîne tous les lecteurs du 1er jusqu'au dernier en cas d'échec,
 * - N'alerte l'utilisateur (NEEDS_USER) qu'en dernier recours.
 */
object BatchSeriesManager {

    private const val CHANNEL_ALERTS = "batch_alerts"
    private const val NOTIF_BATCH_ID = 4343

    private enum class PlayerAttemptResult { FOUND_STREAM, DEAD, BLOCKED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runnerJob: Job? = null
    private var episodeJob: Job? = null
    private var collectJob: Job? = null

    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state

    private var consecutiveNotFound = 0
    private var currentEpisodeHandled = false
    private var episodeToken = 0L
    private var channelCreated = false

    fun start(config: BatchConfig) {
        val sourceTab = findTab(config.tabId) ?: return
        stop()
        consecutiveNotFound = 0
        currentEpisodeHandled = false
        episodeToken++

        val total = (config.endEp - config.startEp + 1).coerceAtLeast(1)
        var firstRobotEp = config.startEp
        var initialCompleted = 0

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
            _state.value = BatchState(BatchStatus.DONE, null, config.endEp, total, msg, initialCompleted)
            DownloadCenter.events.tryEmit(msg)
            notifyBatch("Détection terminée", msg)
            return
        }

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
        episodeToken++
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
            schedulePickFromTab(tab, cfg, st.currentEp, episodeToken, immediate = true)
            return
        }
        _state.value = st.copy(status = BatchStatus.LOADING_PAGE, message = "Reprise à l'épisode ${st.currentEp}…")
        loadEpisode(tab, cfg, st.currentEp)
    }

    fun skipCurrent() {
        val st = _state.value
        if (st.config == null || st.status == BatchStatus.IDLE || st.status == BatchStatus.DONE) return
        episodeToken++
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        _state.value = st.copy(failedEps = st.failedEps + st.currentEp)
        advanceToNext()
    }

    fun stop() {
        episodeToken++
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

    fun onPageFinished(tabId: Int, url: String) {
        if (url == "about:blank") return
        val st = _state.value
        val cfg = st.config ?: return
        if (cfg.tabId != tabId || currentEpisodeHandled) return
        if (st.status != BatchStatus.LOADING_PAGE && st.status != BatchStatus.NEEDS_USER) return
        val tab = findTab(tabId) ?: return

        if (tab.videos.any { isLikelyEpisode(it) }) {
            schedulePickFromTab(tab, cfg, st.currentEp, episodeToken, immediate = false)
            return
        }
        startPageAutomation(tab, cfg, st.currentEp, episodeToken, quietIfBlocked = (st.status == BatchStatus.NEEDS_USER))
    }

    fun onHttpError(tabId: Int, code: Int) {
        val st = _state.value
        val cfg = st.config ?: return
        if (cfg.tabId != tabId || st.status != BatchStatus.LOADING_PAGE) return

        if (code == 404 || code == 410) {
            consecutiveNotFound++
            episodeToken++
            episodeJob?.cancel()
            collectJob?.cancel()
            _state.value = st.copy(failedEps = st.failedEps + st.currentEp)
            if (consecutiveNotFound >= 2) finishBatch("Arrêt auto : épisodes introuvables (HTTP $code)")
            else advanceToNext()
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
        schedulePickFromTab(tab, cfg, st.currentEp, episodeToken, immediate = false, lastVideo = video)
    }

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
        episodeToken++
        episodeJob?.cancel()
        collectJob?.cancel()
        currentEpisodeHandled = false
        val url = BatchUrlPattern.buildUrl(cfg.urlTemplate, ep, cfg.padWidth)
        _state.value = _state.value.copy(
            status = BatchStatus.LOADING_PAGE,
            currentEp = ep,
            playerIndex = 0,
            playerTotal = 0,
            message = "Chargement Ép. $ep/${cfg.endEp}…"
        )
        TabManager.load(tab, url)
    }

    private fun startPageAutomation(tab: TabState, cfg: BatchConfig, ep: Int, token: Long, quietIfBlocked: Boolean = false) {
        val wv = tab.webView ?: return
        episodeJob?.cancel()
        episodeJob = scope.launch {
            fun validToken(): Boolean = token == episodeToken && !currentEpisodeHandled && isActive
            if (!validToken()) return@launch
            delay(350L)
            if (!validToken()) return@launch

            // 1) Vérifie Cloudflare sur la page principale (laisse ~4 s si challenge auto)
            if (PageAutomator.isBlockedByChallenge(wv)) {
                _state.value = _state.value.copy(
                    status = BatchStatus.AUTOMATING,
                    message = "Ép. $ep : attente vérification Cloudflare…"
                )
                var stillBlocked = true
                for (w in 0 until 8) {
                    delay(500L)
                    if (!validToken()) return@launch
                    if (tab.videos.any { isLikelyEpisode(it) }) {
                        schedulePickFromTab(tab, cfg, ep, token, immediate = true)
                        return@launch
                    }
                    if (!PageAutomator.isBlockedByChallenge(wv)) {
                        stillBlocked = false
                        break
                    }
                }
                if (stillBlocked) {
                    val msg = "Ép. $ep : passez Cloudflare puis lancez la lecture"
                    _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                    if (!quietIfBlocked) notifyBatch("Cloudflare détecté", msg)
                    return@launch
                }
            }

            _state.value = _state.value.copy(
                status = BatchStatus.AUTOMATING,
                message = "Ép. $ep/${cfg.endEp} : repérage des lecteurs…"
            )

            // 2) Découvre la liste complète des lecteurs du site (attend brièvement si rendu JS asynchrone)
            var players = emptyList<PageAutomator.PlayerOption>()
            for (waitDom in 0 until 6) {
                if (!validToken()) return@launch
                if (tab.videos.any { isLikelyEpisode(it) }) {
                    schedulePickFromTab(tab, cfg, ep, token, immediate = true)
                    return@launch
                }
                players = PageAutomator.discoverPlayers(wv)
                if (players.isNotEmpty() || PageAutomator.findPlayerTarget(wv, muteVideo = true) != null) break
                delay(250L)
            }

            // 3) Aucun sélecteur de lecteur : page mono-lecteur intégrée directement
            if (players.isEmpty()) {
                _state.value = _state.value.copy(playerIndex = 1, playerTotal = 1)
                val res = testActivePlayer(tab, cfg, ep, token, "Lecteur principal", 1, 1)
                if (res == PlayerAttemptResult.FOUND_STREAM || !validToken()) return@launch
                val msg = "Ép. $ep : impossible de lancer le lecteur, action requise"
                _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                if (!quietIfBlocked) notifyBatch("Action requise (Ép. $ep)", msg)
                return@launch
            }

            // 4) Ordre de parcours :
            //    - Si un lecteur gagnant est mémorisé (épisode précédent ou clic manuel), on le met en tête,
            //    - Sinon on commence par le 1er lecteur du site (ou celui déjà actif par défaut),
            //    - Puis on enchaîne strictement jusqu'au tout dernier lecteur.
            val ordered = buildSequentialOrder(players, tab.winningPlayerLabel.ifBlank { tab.lastClickedPlayer })
            val totalPlayers = ordered.size

            for ((idx, opt) in ordered.withIndex()) {
                if (!validToken()) return@launch
                val num = idx + 1
                _state.value = _state.value.copy(
                    status = BatchStatus.AUTOMATING,
                    playerIndex = num,
                    playerTotal = totalPlayers,
                    message = "Ép. $ep : lecteur $num/$totalPlayers (${opt.label})…"
                )

                // Si ce n'est pas le lecteur déjà affiché au premier test, on clique/sélectionne ce lecteur
                val alreadyActive = idx == 0 && opt.isSelected
                if (!alreadyActive) {
                    tab.resetIframeTracking()
                    tab.videos.clear()
                    tab.sniffer.reset()
                    PageAutomator.selectPlayerById(wv, opt.id)
                    delay(450L)
                    if (!validToken()) return@launch
                }

                val res = testActivePlayer(tab, cfg, ep, token, opt.label, num, totalPlayers)
                if (res == PlayerAttemptResult.FOUND_STREAM) {
                    tab.winningPlayerLabel = opt.label
                    return@launch
                }
            }

            // 5) Tous les lecteurs du site ont échoué : on alerte seulement maintenant
            if (validToken()) {
                val msg = "Ép. $ep : les $totalPlayers lecteurs ont échoué, action requise"
                _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                if (!quietIfBlocked) notifyBatch("Action requise (Ép. $ep)", msg)
            }
        }
    }

    /**
     * Teste le lecteur actuellement chargé en observant son iframe en temps réel :
     * - Attend la fin du chargement (LOADING),
     * - Attend la résolution auto de Cloudflare dans l'iframe (max ~9 s),
     * - Abandonne tout de suite si DEAD (vidéo supprimée / 404),
     * - Ne clique Play qu'une fois PLAY_READY (coordonnées exactes dans l'iframe),
     * - Sort à la milliseconde où VideoSniffer détecte le flux.
     */
    private suspend fun testActivePlayer(
        tab: TabState,
        cfg: BatchConfig,
        ep: Int,
        token: Long,
        playerLabel: String,
        num: Int,
        totalPlayers: Int
    ): PlayerAttemptResult {
        val wv = tab.webView ?: return PlayerAttemptResult.FAILED
        fun valid(): Boolean = token == episodeToken && !currentEpisodeHandled && kotlin.coroutines.coroutineContext.isActive

        var tapsDone = 0
        var lastTapAt = 0L
        var cfSeenSince = 0L
        var deadSeenSince = 0L
        val startedAt = SystemClock.elapsedRealtime()
        var loadingPausedMs = 0L
        var lastLoopAt = startedAt

        while (valid()) {
            val now = SystemClock.elapsedRealtime()
            val dt = now - lastLoopAt
            lastLoopAt = now

            if (tab.videos.any { isLikelyEpisode(it) }) {
                tab.winningPlayerLabel = playerLabel
                schedulePickFromTab(tab, cfg, ep, token, immediate = false)
                return PlayerAttemptResult.FOUND_STREAM
            }

            // Si le clic sur le bouton lecteur a provoqué un rechargement complet de la page, on attend la fin du chargement
            if (tab.loading) {
                loadingPausedMs += dt
                if (loadingPausedMs > 12_000L) return PlayerAttemptResult.FAILED
                delay(250L)
                continue
            }

            val elapsed = (now - startedAt) - loadingPausedMs
            if (elapsed > 15_000L) {
                return if (cfSeenSince > 0L) PlayerAttemptResult.BLOCKED else PlayerAttemptResult.FAILED
            }

            // 1) Vérifie s'il y a un <video> direct sur la page principale
            val directVideo = PageAutomator.findDirectVideoTarget(wv, muteVideo = true)
            if (directVideo != null) {
                if (now - lastTapAt >= 2_800L && tapsDone < 3) {
                    tapsDone++
                    lastTapAt = now
                    _state.value = _state.value.copy(message = "Ép. $ep · $playerLabel ($num/$totalPlayers) : lecture…")
                    PageAutomator.tapWebView(wv, directVideo)
                }
                delay(250L)
                continue
            }

            // 2) Vérifie les erreurs HTTP de l'iframe (404/410 = mort immédiat, 403/503 = Cloudflare)
            val subErrAt = tab.lastSubframeHttpErrorAt
            val sysNow = System.currentTimeMillis()
            if (subErrAt > 0L && (sysNow - subErrAt) < 4_000L) {
                if (tab.lastSubframeHttpError == 404 || tab.lastSubframeHttpError == 410) {
                    return PlayerAttemptResult.DEAD
                }
            }

            val snap = tab.iframeSnapshot
            val netCfActive = (sysNow - tab.lastSubframeChallengeAt) < 3_500L ||
                (tab.lastSubframeHttpError in listOf(403, 503) && (sysNow - subErrAt) < 4_000L)

            // 3) Lecteur mort confirmé dans l'iframe
            if (snap.phase == FramePhase.DEAD) {
                if (deadSeenSince == 0L) deadSeenSince = now
                if (now - deadSeenSince >= 500L) return PlayerAttemptResult.DEAD
                delay(250L)
                continue
            } else {
                deadSeenSince = 0L
            }

            // 4) Cloudflare dans l'iframe : on patiente sans cliquer (jusqu'à 9 s pour auto-résolution)
            if (snap.phase == FramePhase.CLOUDFLARE || netCfActive) {
                if (cfSeenSince == 0L) cfSeenSince = now
                _state.value = _state.value.copy(message = "Ép. $ep · $playerLabel ($num/$totalPlayers) : Cloudflare…")
                if (now - cfSeenSince >= 9_000L) return PlayerAttemptResult.BLOCKED
                delay(300L)
                continue
            } else {
                cfSeenSince = 0L
            }

            // 5) Chargement en cours dans l'iframe : on attend qu'il se termine
            if (snap.phase == FramePhase.LOADING) {
                _state.value = _state.value.copy(message = "Ép. $ep · $playerLabel ($num/$totalPlayers) : chargement…")
                delay(250L)
                continue
            }

            // 6) Lecteur prêt (Play visible via IframeObserver) OU repli prudent si WebView ne supporte pas IframeObserver
            val readyByObserver = snap.phase == FramePhase.PLAY_READY
            val fallbackQuiet = (!IframeObserver.isSupported || snap.phase == FramePhase.UNKNOWN) &&
                elapsed >= 2_400L &&
                (tab.lastSubframeAt == 0L || (sysNow - tab.lastSubframeAt) >= 1_200L)

            if (readyByObserver || fallbackQuiet) {
                val outerTarget = PageAutomator.findPlayerTarget(wv, muteVideo = true)
                if (outerTarget != null) {
                    if (now - lastTapAt >= 2_800L && tapsDone < 3) {
                        tapsDone++
                        lastTapAt = now
                        _state.value = _state.value.copy(
                            message = "Ép. $ep · $playerLabel ($num/$totalPlayers) : Play ($tapsDone/3)…"
                        )
                        val rx = if (readyByObserver) snap.relX else 0.5f
                        val ry = if (readyByObserver) snap.relY else 0.5f
                        PageAutomator.tapWebView(wv, outerTarget, rx, ry)
                    } else if (tapsDone >= 3 && now - lastTapAt >= 3_800L) {
                        return PlayerAttemptResult.FAILED
                    }
                } else if (elapsed >= 5_000L) {
                    return PlayerAttemptResult.FAILED
                }
            }

            delay(250L)
        }
        return PlayerAttemptResult.FAILED
    }

    private fun buildSequentialOrder(
        players: List<PageAutomator.PlayerOption>,
        winningLabel: String
    ): List<PageAutomator.PlayerOption> {
        if (players.size <= 1) return players
        val preferred = winningLabel.trim().lowercase()
        val winner = if (preferred.isNotBlank()) {
            players.firstOrNull {
                it.label.lowercase() == preferred ||
                    it.label.lowercase().contains(preferred) ||
                    preferred.contains(it.label.lowercase())
            }
        } else null

        if (winner != null) {
            return listOf(winner) + players.filter { it.id != winner.id }
        }
        // Aucun gagnant mémorisé : respecte l'ordre du site (déjà sélectionné en 1er, puis 1er -> dernier)
        val selected = players.firstOrNull { it.isSelected }
        return if (selected != null && selected != players.first()) {
            listOf(selected) + players.filter { it.id != selected.id }
        } else {
            players
        }
    }

    private fun schedulePickFromTab(
        tab: TabState,
        cfg: BatchConfig,
        ep: Int,
        token: Long,
        immediate: Boolean,
        lastVideo: DetectedVideo? = null
    ) {
        if (token != episodeToken || currentEpisodeHandled) return
        collectJob?.cancel()
        collectJob = scope.launch {
            if (!immediate) {
                val quickHit = lastVideo != null && (
                    lastVideo.format != "HLS" ||
                        cfg.targetQuality == "Meilleure" ||
                        lastVideo.resolution.equals(cfg.targetQuality, ignoreCase = true)
                    )
                delay(if (quickHit) 250L else 850L)
            }
            if (token != episodeToken || currentEpisodeHandled) return@launch

            val valid = tab.videos.filter { isLikelyEpisode(it) }
            val best = pickBestVideo(valid, cfg.targetQuality) ?: return@launch

            currentEpisodeHandled = true
            episodeToken++
            episodeJob?.cancel()
            consecutiveNotFound = 0

            val formatted = BatchUrlPattern.formatNum(ep, cfg.padWidth)
            val labeled = best.copy(title = "${cfg.seriesTitle} - Ép. $formatted")
            DownloadCenter.enqueue(labeled, silent = true)

            _state.value = _state.value.copy(
                completedCount = _state.value.completedCount + 1,
                message = "Ép. $ep ajouté (${best.resolution})"
            )
            advanceToNext()
        }
    }

    private fun advanceToNext() {
        val st = _state.value
        val cfg = st.config ?: return
        val nextEp = st.currentEp + 1
        if (nextEp > cfg.endEp) {
            finishBatch("Détection terminée : ${st.completedCount}/${st.totalEps} épisode(s) en file — vous pouvez quitter l'app")
            return
        }
        val tab = findTab(cfg.tabId) ?: run { stop(); return }
        scheduleNextEpisode(tab, cfg, nextEp)
    }

    private fun finishBatch(msg: String) {
        episodeToken++
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        val tabToClose = _state.value.config?.tabId?.let { findTab(it) }?.takeIf { it.isBatchTab }
        _state.value = _state.value.copy(status = BatchStatus.DONE, config = null, message = msg)
        tabToClose?.let { TabManager.closeTab(it) }
        DownloadCenter.events.tryEmit(msg)
        notifyBatch("Détection terminée", msg)
    }

    private fun notifyBatch(title: String, text: String) {
        runCatching {
            val ctx = TabManager.context
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (!channelCreated) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ALERTS, "Alertes Mode Série", NotificationManager.IMPORTANCE_HIGH)
                )
                channelCreated = true
            }
            val open = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIF_BATCH_ID, notif)
        }
    }

    private fun isLikelyEpisode(v: DetectedVideo): Boolean {
        if (v.format == "HLS" && v.durationSec > 0.0 && v.durationSec < 30.0) return false
        if (v.sizeBytes in 1L..2_000_000L) return false
        return true
    }

    private fun pickBestVideo(videos: List<DetectedVideo>, targetQuality: String): DetectedVideo? {
        if (videos.isEmpty()) return null
        if (targetQuality == "Meilleure") return videos.maxByOrNull { resolutionScore(it.resolution) }
        videos.firstOrNull { it.resolution.equals(targetQuality, ignoreCase = true) }?.let { return it }
        val targetPx = targetQuality.removeSuffix("p").toIntOrNull() ?: 720
        return videos.minByOrNull { kotlin.math.abs(resolutionScore(it.resolution) - targetPx) }
    }

    private fun resolutionScore(res: String): Int =
        res.removeSuffix("p").toIntOrNull() ?: 500

    private fun findTab(tabId: Int): TabState? = TabManager.tabs.firstOrNull { it.id == tabId }

    private fun urlsMatch(a: String, b: String): Boolean =
        a.trimEnd('/') == b.trimEnd('/')
}