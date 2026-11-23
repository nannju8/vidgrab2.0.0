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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrateur du Mode Série :
 * À chaque épisode, sans exception :
 * 1) Teste d'abord le bouton Play / lecteur déjà préchargé sur la page,
 * 2) Sinon (ou en cas d'échec), teste strictement tous les lecteurs dans l'ordre du site (1er -> 2e -> 3e -> ...),
 * 3) N'alerte l'utilisateur (NEEDS_USER) qu'après avoir tout essayé.
 */
object BatchSeriesManager {

    private const val CHANNEL_ALERTS = "batch_alerts"
    private const val NOTIF_BATCH_ID = 4343
    private const val PAGE_LOAD_TIMEOUT_MS = 20_000L

    private enum class PlayerAttemptResult { FOUND_STREAM, DEAD, BLOCKED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runnerJob: Job? = null
    private var episodeJob: Job? = null
    private var collectJob: Job? = null
    private var loadingTimeoutJob: Job? = null

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
        loadingTimeoutJob?.cancel()
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
        loadingTimeoutJob?.cancel()
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        _state.value = st.copy(failedEps = st.failedEps + st.currentEp)
        advanceToNext()
    }

    fun stop() {
        episodeToken++
        loadingTimeoutJob?.cancel()
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        loadingTimeoutJob = null
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
        loadingTimeoutJob?.cancel()

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
            loadingTimeoutJob?.cancel()
            episodeJob?.cancel()
            collectJob?.cancel()
            _state.value = st.copy(failedEps = st.failedEps + st.currentEp)
            if (consecutiveNotFound >= 2) finishBatch("Arrêt auto : épisodes introuvables (HTTP $code)")
            else advanceToNext()
        }
        // Pour 403/503/429 : on laisse onPageFinished + startPageAutomation vérifier et attendre l'auto-résolution Cloudflare
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
        loadingTimeoutJob?.cancel()
        episodeJob?.cancel()
        collectJob?.cancel()
        currentEpisodeHandled = false
        val token = episodeToken
        val url = BatchUrlPattern.buildUrl(cfg.urlTemplate, ep, cfg.padWidth)
        _state.value = _state.value.copy(
            status = BatchStatus.LOADING_PAGE,
            currentEp = ep,
            playerIndex = 0,
            playerTotal = 0,
            message = "Chargement Ép. $ep/${cfg.endEp}…"
        )
        TabManager.load(tab, url, fromUser = false)

        loadingTimeoutJob = scope.launch {
            delay(PAGE_LOAD_TIMEOUT_MS)
            val st = _state.value
            if (token != episodeToken || currentEpisodeHandled || st.status != BatchStatus.LOADING_PAGE) return@launch
            TabManager.stop(tab)
            if (tab.videos.any { isLikelyEpisode(it) }) {
                schedulePickFromTab(tab, cfg, ep, token, immediate = true)
            } else {
                startPageAutomation(tab, cfg, ep, token, quietIfBlocked = false)
            }
        }
    }

    private fun startPageAutomation(tab: TabState, cfg: BatchConfig, ep: Int, token: Long, quietIfBlocked: Boolean = false) {
        val wv = tab.webView ?: return
        loadingTimeoutJob?.cancel()
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
                message = "Ép. $ep/${cfg.endEp} : recherche Play / lecteurs…"
            )

            // 2) Attend brièvement que le lecteur préchargé ou la liste des lecteurs apparaisse dans le DOM
            var players = emptyList<PageAutomator.PlayerOption>()
            var preloadedTarget: PageAutomator.PlayerTarget? = null
            for (waitDom in 0 until 6) {
                if (!validToken()) return@launch
                if (tab.videos.any { isLikelyEpisode(it) }) {
                    schedulePickFromTab(tab, cfg, ep, token, immediate = true)
                    return@launch
                }
                players = PageAutomator.discoverPlayers(wv)
                preloadedTarget = PageAutomator.findPlayerTarget(wv, muteVideo = true, includeMainPlayBtn = true)
                if (players.isNotEmpty() || preloadedTarget != null) break
                delay(250L)
            }

            // 3) ÉTAPE 1 : Cliquer d'abord sur Play / tester ce qui est déjà préchargé sur la page
            var testedPreloaded = false
            if (preloadedTarget != null) {
                testedPreloaded = true
                _state.value = _state.value.copy(
                    status = BatchStatus.AUTOMATING,
                    playerIndex = 0,
                    playerTotal = players.size,
                    message = "Ép. $ep : test bouton Play / lecteur préchargé…"
                )
                val preRes = testActivePlayer(
                    tab, cfg, ep, token,
                    playerLabel = "Lecteur préchargé",
                    num = 0,
                    totalPlayers = players.size,
                    allowMainPlayBtn = true
                )
                if (preRes == PlayerAttemptResult.FOUND_STREAM || !validToken()) return@launch

                // Si le clic sur Play sur la page a fait apparaître de nouveaux lecteurs
                if (players.isEmpty()) {
                    players = PageAutomator.discoverPlayers(wv)
                }
            }

            if (players.isEmpty()) {
                val msg = "Ép. $ep : impossible de lancer le lecteur, action requise"
                _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                if (!quietIfBlocked) notifyBatch("Action requise (Ép. $ep)", msg)
                return@launch
            }

            // 4) ÉTAPE 2 : Tester strictement tous les lecteurs dans l'ordre du site (1er -> 2e -> 3e -> ...)
            val totalPlayers = players.size
            val singleSelectedCount = players.count { it.isSelected }

            for ((idx, opt) in players.withIndex()) {
                if (!validToken()) return@launch
                val num = idx + 1

                // Si l'étape 1 a déjà testé le lecteur préchargé ET que ce bouton unique était clairement celui actif,
                // inutile de retester exactement le même lecteur une deuxième fois.
                if (testedPreloaded && singleSelectedCount == 1 && opt.isSelected) {
                    continue
                }

                _state.value = _state.value.copy(
                    status = BatchStatus.AUTOMATING,
                    playerIndex = num,
                    playerTotal = totalPlayers,
                    message = "Ép. $ep : lecteur $num/$totalPlayers (${opt.label})…"
                )

                tab.resetIframeTracking()
                tab.videos.clear()
                tab.sniffer.reset()

                var clicked = PageAutomator.selectPlayerById(wv, opt.id)
                if (!clicked) {
                    // Si le DOM a été rechargé/reconstruit par un clic précédent, on re-découvre les lecteurs
                    val fresh = PageAutomator.discoverPlayers(wv)
                    val match = fresh.firstOrNull { it.label.equals(opt.label, ignoreCase = true) }
                        ?: fresh.getOrNull(idx)
                    if (match != null) {
                        clicked = PageAutomator.selectPlayerById(wv, match.id)
                    }
                }
                if (!clicked) continue

                delay(450L)
                if (!validToken()) return@launch

                val res = testActivePlayer(
                    tab, cfg, ep, token,
                    playerLabel = opt.label,
                    num = num,
                    totalPlayers = totalPlayers,
                    allowMainPlayBtn = true
                )
                if (res == PlayerAttemptResult.FOUND_STREAM || !validToken()) return@launch
            }

            // 5) Tous les lecteurs ont échoué : on alerte l'utilisateur
            if (validToken()) {
                val msg = "Ép. $ep : les $totalPlayers lecteurs ont échoué, action requise"
                _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                if (!quietIfBlocked) notifyBatch("Action requise (Ép. $ep)", msg)
            }
        }
    }

    /**
     * Teste le lecteur actuellement affiché :
     * - Si un bouton Play est sur la page principale (sans iframe encore créée), clique dessus immédiatement,
     * - Observe en direct l'iframe via IframeObserver (chargement, Cloudflare, lecteur mort, Play prêt),
     * - Sort dès que VideoSniffer détecte la vidéo.
     */
    private suspend fun testActivePlayer(
        tab: TabState,
        cfg: BatchConfig,
        ep: Int,
        token: Long,
        playerLabel: String,
        num: Int,
        totalPlayers: Int,
        allowMainPlayBtn: Boolean
    ): PlayerAttemptResult {
        val wv = tab.webView ?: return PlayerAttemptResult.FAILED
        val currentJob = currentCoroutineContext()[Job]
        fun valid(): Boolean = token == episodeToken && !currentEpisodeHandled && (currentJob?.isActive != false)

        val labelPrefix = if (num > 0 && totalPlayers > 0) "Ép. $ep · $playerLabel ($num/$totalPlayers)" else "Ép. $ep · $playerLabel"
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
                schedulePickFromTab(tab, cfg, ep, token, immediate = false)
                return PlayerAttemptResult.FOUND_STREAM
            }

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

            // 1) <video> direct sur la page principale
            val directVideo = PageAutomator.findDirectVideoTarget(wv, muteVideo = true)
            if (directVideo != null) {
                if (now - lastTapAt >= 2_800L && tapsDone < 3) {
                    tapsDone++
                    lastTapAt = now
                    _state.value = _state.value.copy(message = "$labelPrefix : lecture…")
                    PageAutomator.tapWebView(wv, directVideo)
                } else if (tapsDone >= 3 && now - lastTapAt >= 3_800L) {
                    return PlayerAttemptResult.FAILED
                }
                delay(250L)
                continue
            }

            // 2) Erreur HTTP 404/410 sur le document HTML d'une iframe
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

            // 4) Cloudflare dans l'iframe : on patiente jusqu'à 9 s sans cliquer
            if (snap.phase == FramePhase.CLOUDFLARE || netCfActive) {
                if (cfSeenSince == 0L) cfSeenSince = now
                _state.value = _state.value.copy(message = "$labelPrefix : Cloudflare…")
                if (now - cfSeenSince >= 9_000L) return PlayerAttemptResult.BLOCKED
                delay(300L)
                continue
            } else {
                cfSeenSince = 0L
            }

            // 5) Chargement en cours dans l'iframe
            if (snap.phase == FramePhase.LOADING) {
                _state.value = _state.value.copy(message = "$labelPrefix : chargement…")
                delay(250L)
                continue
            }

            // 6) Bouton Play sur la page principale (avant même qu'une iframe existe) ou iframe prête
            val outerTarget = PageAutomator.findPlayerTarget(wv, muteVideo = true, includeMainPlayBtn = allowMainPlayBtn)
            val mainPlayReady = outerTarget != null && outerTarget.isMainPlayButton
            val readyByObserver = snap.phase == FramePhase.PLAY_READY
            val fallbackQuiet = (!IframeObserver.isSupported || snap.phase == FramePhase.UNKNOWN) &&
                elapsed >= 2_400L &&
                (tab.lastSubframeAt == 0L || (sysNow - tab.lastSubframeAt) >= 1_200L)

            if (mainPlayReady || readyByObserver || fallbackQuiet) {
                if (outerTarget != null) {
                    if (now - lastTapAt >= 2_800L && tapsDone < 3) {
                        tapsDone++
                        lastTapAt = now
                        _state.value = _state.value.copy(message = "$labelPrefix : Play ($tapsDone/3)…")
                        val rx = if (readyByObserver && !mainPlayReady) snap.relX else 0.5f
                        val ry = if (readyByObserver && !mainPlayReady) snap.relY else 0.5f
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
            loadingTimeoutJob?.cancel()
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
        loadingTimeoutJob?.cancel()
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
