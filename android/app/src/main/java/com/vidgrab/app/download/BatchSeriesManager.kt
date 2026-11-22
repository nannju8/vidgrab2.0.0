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

/** Orchestrateur du Mode Série : jeton par épisode, reprise auto après Cloudflare, attente 4G sur iframe. */
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
            message = "Chargement Ép. $ep/${cfg.endEp}…"
        )
        TabManager.load(tab, url)
    }

    private fun startPageAutomation(tab: TabState, cfg: BatchConfig, ep: Int, token: Long, quietIfBlocked: Boolean = false) {
        val wv = tab.webView ?: return
        episodeJob?.cancel()
        episodeJob = scope.launch {
            if (token != episodeToken || currentEpisodeHandled || !isActive) return@launch
            delay(350L)
            if (token != episodeToken || currentEpisodeHandled || !isActive) return@launch

            if (PageAutomator.isBlockedByChallenge(wv)) {
                val msg = "Ép. $ep : passez Cloudflare puis lancez la lecture"
                _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                if (!quietIfBlocked) notifyBatch("Cloudflare détecté", msg)
                return@launch
            }

            _state.value = _state.value.copy(
                status = BatchStatus.AUTOMATING,
                message = "Ép. $ep/${cfg.endEp} : recherche du lecteur…"
            )

            val prefs = buildPlayerPriority(tab, cfg)
            var playerSwitched = false
            for (step in 0 until 8) {
                if (token != episodeToken || currentEpisodeHandled || !isActive) return@launch
                if (tab.videos.any { isLikelyEpisode(it) }) {
                    schedulePickFromTab(tab, cfg, ep, token, immediate = true)
                    return@launch
                }
                val clicked = PageAutomator.selectPlayer(wv, prefs)
                if (clicked != null) {
                    playerSwitched = true
                    _state.value = _state.value.copy(message = "Ép. $ep : lecteur « $clicked »…")
                    break
                }
                if (step >= 1 && PageAutomator.findPlayerTarget(wv, muteVideo = true) != null) break
                delay(250L)
            }

            // Laisse le temps à l'iframe distante de charger ses scripts sur 4G après le choix de lecteur
            if (playerSwitched) {
                delay(900L)
                for (s in 0 until 10) {
                    if (token != episodeToken || currentEpisodeHandled || !isActive) return@launch
                    if (tab.videos.any { isLikelyEpisode(it) }) {
                        schedulePickFromTab(tab, cfg, ep, token, immediate = false)
                        return@launch
                    }
                    val quietFor = System.currentTimeMillis() - tab.lastSubframeAt
                    if (tab.lastSubframeAt > 0L && quietFor >= 700L && PageAutomator.findPlayerTarget(wv, muteVideo = true) != null) break
                    delay(250L)
                }
            }

            for (attempt in 1..3) {
                if (token != episodeToken || currentEpisodeHandled || !isActive) return@launch
                if (tab.videos.any { isLikelyEpisode(it) }) {
                    schedulePickFromTab(tab, cfg, ep, token, immediate = true)
                    return@launch
                }
                var target: PageAutomator.PlayerTarget? = null
                for (w in 0 until 12) {
                    if (token != episodeToken || currentEpisodeHandled || !isActive) return@launch
                    if (tab.videos.any { isLikelyEpisode(it) }) {
                        schedulePickFromTab(tab, cfg, ep, token, immediate = true)
                        return@launch
                    }
                    target = PageAutomator.findPlayerTarget(wv, muteVideo = true)
                    if (target != null) break
                    delay(250L)
                }
                if (target != null) {
                    _state.value = _state.value.copy(message = "Ép. $ep : Play ($attempt/3)…")
                    PageAutomator.tapWebView(wv, target)
                }
                for (r in 0 until 12) { // jusqu'à 3 s par tap sur 4G, sortie immédiate dès qu'un flux arrive
                    if (token != episodeToken || currentEpisodeHandled || !isActive) return@launch
                    if (tab.videos.any { isLikelyEpisode(it) }) {
                        schedulePickFromTab(tab, cfg, ep, token, immediate = false)
                        return@launch
                    }
                    delay(250L)
                }
                if (PageAutomator.isBlockedByChallenge(wv)) {
                    val msg = "Ép. $ep : passez Cloudflare puis lancez la lecture"
                    _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                    notifyBatch("Cloudflare détecté", msg)
                    return@launch
                }
            }

            if (token == episodeToken && !currentEpisodeHandled && isActive) {
                val fallback = PageAutomator.selectPlayer(wv, prefs)
                if (fallback != null) {
                    _state.value = _state.value.copy(message = "Ép. $ep : essai « $fallback »…")
                    delay(1400L)
                    val target = PageAutomator.findPlayerTarget(wv, muteVideo = true)
                    if (target != null) PageAutomator.tapWebView(wv, target)
                    for (r in 0 until 14) {
                        if (token != episodeToken || currentEpisodeHandled || !isActive) return@launch
                        if (tab.videos.any { isLikelyEpisode(it) }) {
                            schedulePickFromTab(tab, cfg, ep, token, immediate = false)
                            return@launch
                        }
                        delay(250L)
                    }
                }
            }

            if (token == episodeToken && !currentEpisodeHandled && isActive) {
                val msg = "Ép. $ep : choisissez le lecteur et cliquez Play"
                _state.value = _state.value.copy(status = BatchStatus.NEEDS_USER, message = msg)
                notifyBatch("Action requise (Ép. $ep)", msg)
            }
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

    private fun buildPlayerPriority(tab: TabState, cfg: BatchConfig): List<String> {
        val list = LinkedHashSet<String>()
        if (tab.lastClickedPlayer.isNotBlank()) list.add(tab.lastClickedPlayer)
        cfg.preferredPlayers.forEach { if (it.isNotBlank()) list.add(it.trim()) }
        listOf("voe", "vidmoly", "doodstream", "dood", "filemoon", "sibnet", "streamtape", "uqload", "luluvdo", "sendvid", "myvi", "ok.ru", "netu", "waaw", "lecteur 1", "player 1")
            .forEach { list.add(it) }
        return list.toList()
    }

    private fun findTab(tabId: Int): TabState? = TabManager.tabs.firstOrNull { it.id == tabId }

    private fun urlsMatch(a: String, b: String): Boolean =
        a.trimEnd('/') == b.trimEnd('/')
}
