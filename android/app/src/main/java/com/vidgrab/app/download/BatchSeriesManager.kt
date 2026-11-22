package com.vidgrab.app.download

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
import kotlinx.coroutines.launch

/** Orchestrateur du Mode Série : enchaîne les pages une à une, choisit le lecteur, clique Play et met en file. */
object BatchSeriesManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runnerJob: Job? = null
    private var episodeJob: Job? = null
    private var collectJob: Job? = null

    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state

    private var consecutiveNotFound = 0
    private var currentEpisodeHandled = false

    fun start(config: BatchConfig) {
        stop()
        consecutiveNotFound = 0
        currentEpisodeHandled = false
        val total = (config.endEp - config.startEp + 1).coerceAtLeast(1)
        _state.value = BatchState(
            status = BatchStatus.LOADING_PAGE,
            config = config,
            currentEp = config.startEp,
            totalEps = total,
            message = "Démarrage du lot (${config.startEp} → ${config.endEp})…"
        )
        loadOrUseCurrentEpisode(config.startEp)
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
        // Si une vidéo valide est déjà là entre-temps, on la prend tout de suite
        if (tab.videos.any { isLikelyEpisode(it) }) {
            schedulePickFromTab(tab, cfg, st.currentEp, immediate = true)
            return
        }
        _state.value = st.copy(status = BatchStatus.LOADING_PAGE, message = "Reprise à l'épisode ${st.currentEp}…")
        loadEpisode(tab, cfg, st.currentEp)
    }

    fun skipCurrent() {
        val st = _state.value
        if (st.config == null || st.status == BatchStatus.IDLE) return
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
        _state.value = BatchState()
    }

    fun stopIfTab(tabId: Int) {
        if (_state.value.config?.tabId == tabId) stop()
    }

    // ---- Événements du WebView / VideoSniffer -----------------------------------------------

    fun onPageFinished(tabId: Int, url: String) {
        val st = _state.value
        val cfg = st.config ?: return
        if (cfg.tabId != tabId || st.status != BatchStatus.LOADING_PAGE || currentEpisodeHandled) return
        val tab = findTab(tabId) ?: return

        // Si le sniffer a déjà capté le flux pendant le chargement
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
            _state.value = st.copy(
                status = BatchStatus.NEEDS_USER,
                message = "Ép. ${st.currentEp} : vérification Cloudflare (HTTP $code) requise"
            )
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
        schedulePickFromTab(tab, cfg, st.currentEp, immediate = false)
    }

    // ---- Enchaînement interne ---------------------------------------------------------------

    private fun loadOrUseCurrentEpisode(ep: Int) {
        val cfg = _state.value.config ?: return
        val tab = findTab(cfg.tabId) ?: run { stop(); return }
        val expectedUrl = BatchUrlPattern.buildUrl(cfg.urlTemplate, ep, cfg.padWidth)
        // Si l'utilisateur vient de configurer le lot sur la page de l'épisode 1 et qu'elle a déjà des vidéos
        if (urlsMatch(tab.url, expectedUrl) && tab.videos.any { isLikelyEpisode(it) }) {
            schedulePickFromTab(tab, cfg, ep, immediate = true)
        } else {
            loadEpisode(tab, cfg, ep)
        }
    }

    private fun loadEpisode(tab: TabState, cfg: BatchConfig, ep: Int) {
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        currentEpisodeHandled = false
        val url = BatchUrlPattern.buildUrl(cfg.urlTemplate, ep, cfg.padWidth)
        _state.value = _state.value.copy(
            status = BatchStatus.LOADING_PAGE,
            currentEp = ep,
            message = "Chargement de l'épisode $ep / ${cfg.endEp}…"
        )
        if (urlsMatch(tab.url, url) && !tab.loading) {
            onPageFinished(tab.id, url)
        } else {
            TabManager.load(tab, url)
        }
    }

    private fun startPageAutomation(tab: TabState, cfg: BatchConfig, ep: Int) {
        val wv = tab.webView ?: return
        episodeJob?.cancel()
        episodeJob = scope.launch {
            _state.value = _state.value.copy(
                status = BatchStatus.AUTOMATING,
                message = "Ép. $ep/${cfg.endEp} : recherche du lecteur…"
            )
            delay(900L)
            if (currentEpisodeHandled) return@launch

            if (PageAutomator.isBlockedByChallenge(wv)) {
                _state.value = _state.value.copy(
                    status = BatchStatus.NEEDS_USER,
                    message = "Ép. $ep : passez Cloudflare puis lancez la lecture"
                )
                return@launch
            }

            // 1) Choisir le lecteur préféré si présent
            val prefs = buildPlayerPriority(tab, cfg)
            val clickedPlayer = PageAutomator.selectPlayer(wv, prefs)
            if (clickedPlayer != null) {
                _state.value = _state.value.copy(message = "Ép. $ep : lecteur « $clickedPlayer » choisi…")
                delay(2200L)
                if (currentEpisodeHandled) return@launch
            }

            // 2) Taper sur le lecteur (iframe / video) jusqu'à 3 fois pour passer un éventuel shield pub + lancer Play
            for (attempt in 1..3) {
                if (currentEpisodeHandled) return@launch
                if (tab.videos.any { isLikelyEpisode(it) }) {
                    schedulePickFromTab(tab, cfg, ep, immediate = true)
                    return@launch
                }
                if (PageAutomator.isBlockedByChallenge(wv)) {
                    _state.value = _state.value.copy(
                        status = BatchStatus.NEEDS_USER,
                        message = "Ép. $ep : passez Cloudflare puis lancez la lecture"
                    )
                    return@launch
                }
                val target = PageAutomator.findPlayerTarget(wv)
                if (target != null) {
                    _state.value = _state.value.copy(message = "Ép. $ep : clic sur Play ($attempt/3)…")
                    PageAutomator.tapWebView(wv, target)
                }
                delay(2600L)
            }

            // 3) Repli : essayer un autre lecteur si disponible
            if (!currentEpisodeHandled) {
                val fallback = PageAutomator.selectPlayer(wv, prefs)
                if (fallback != null) {
                    _state.value = _state.value.copy(message = "Ép. $ep : essai lecteur « $fallback »…")
                    delay(2200L)
                    val target = PageAutomator.findPlayerTarget(wv)
                    if (target != null) PageAutomator.tapWebView(wv, target)
                    delay(3000L)
                }
            }

            // 4) Si toujours aucun flux, rendre la main proprement à l'utilisateur sans casser le lot
            if (!currentEpisodeHandled) {
                _state.value = _state.value.copy(
                    status = BatchStatus.NEEDS_USER,
                    message = "Ép. $ep : choisissez le lecteur et cliquez Play"
                )
            }
        }
    }

    private fun schedulePickFromTab(tab: TabState, cfg: BatchConfig, ep: Int, immediate: Boolean) {
        if (currentEpisodeHandled) return
        collectJob?.cancel()
        collectJob = scope.launch {
            // Laisse 1,2s à VideoSniffer pour remonter toutes les qualités (480p/720p/1080p) d'une playlist maître HLS
            if (!immediate) delay(1200L)
            if (currentEpisodeHandled) return@launch

            val valid = tab.videos.filter { isLikelyEpisode(it) }
            val best = pickBestVideo(valid, cfg.targetQuality) ?: return@launch

            currentEpisodeHandled = true
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
            finishBatch("Lot terminé : ${st.completedCount}/${st.totalEps} épisode(s) mis en file")
            return
        }
        runnerJob?.cancel()
        runnerJob = scope.launch {
            // Attendre qu'un slot soit libre dans DownloadCenter pour éviter l'expiration des tokens (Voe/Doodstream)
            if (DownloadCenter.activeCount() >= DownloadCenter.MAX_PARALLEL) {
                _state.value = _state.value.copy(
                    status = BatchStatus.WAITING_SLOT,
                    currentEp = nextEp,
                    message = "En attente d'un slot libre avant Ép. $nextEp/${cfg.endEp}…"
                )
                DownloadCenter.tasks.first { DownloadCenter.activeCount() < DownloadCenter.MAX_PARALLEL }
            }
            val latest = _state.value
            if (latest.config == null || latest.status == BatchStatus.PAUSED || latest.status == BatchStatus.IDLE) return@launch
            val tab = findTab(cfg.tabId) ?: run { stop(); return@launch }
            loadEpisode(tab, cfg, nextEp)
        }
    }

    private fun finishBatch(msg: String) {
        episodeJob?.cancel()
        collectJob?.cancel()
        runnerJob?.cancel()
        DownloadCenter.events.tryEmit(msg)
        _state.value = _state.value.copy(status = BatchStatus.DONE, message = msg)
    }

    // ---- Utilitaires de sélection -----------------------------------------------------------

    private fun isLikelyEpisode(v: DetectedVideo): Boolean {
        if (v.format == "HLS" && v.durationSec > 0.0 && v.durationSec < 30.0) return false
        if (v.sizeBytes in 1L..2_000_000L) return false
        return true
    }

    private fun pickBestVideo(videos: List<DetectedVideo>, targetQuality: String): DetectedVideo? {
        if (videos.isEmpty()) return null
        if (targetQuality == "Meilleure") {
            return videos.maxByOrNull { resolutionScore(it.resolution) }
        }
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
        listOf("voe", "vidmoly", "doodstream", "dood", "filemoon", "sibnet", "streamtape", "uqload", "luluvdo", "sendvid", "lecteur", "player")
            .forEach { list.add(it) }
        return list.toList()
    }

    private fun findTab(tabId: Int): TabState? = TabManager.tabs.firstOrNull { it.id == tabId }

    private fun urlsMatch(a: String, b: String): Boolean =
        a.trimEnd('/') == b.trimEnd('/')
}
