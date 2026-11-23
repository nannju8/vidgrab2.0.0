package com.vidgrab.app.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

enum class FramePhase { UNKNOWN, LOADING, CLOUDFLARE, DEAD, PLAY_READY }

data class IframeSnapshot(
    val phase: FramePhase = FramePhase.UNKNOWN,
    val origin: String = "",
    val frameHref: String = "",
    val hasPlayButton: Boolean = false,
    val hasVideoElement: Boolean = false,
    val relX: Float = 0.5f,
    val relY: Float = 0.5f,
    val area: Int = 0,
    val detail: String = "",
    val updatedAt: Long = 0L
)

/**
 * Capteur passif injecté dans toutes les <iframe> (même multi-domaines) via androidx.webkit.
 * Remonte en temps réel : chargement, challenge Cloudflare, lecteur mort ou bouton Play prêt + ses coordonnées.
 */
object IframeObserver {

    private const val CHANNEL = "__vgFrame"

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    fun attach(wv: WebView, tab: TabState) {
        if (!isSupported) return
        runCatching {
            WebViewCompat.addWebMessageListener(wv, CHANNEL, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame) return@addWebMessageListener
                val raw = message.data ?: return@addWebMessageListener
                parseAndUpdate(tab, raw, sourceOrigin.toString())
            }
            WebViewCompat.addDocumentStartJavaScript(wv, OBSERVER_JS, setOf("*"))
        }
    }

    private fun parseAndUpdate(tab: TabState, raw: String, origin: String) {
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val phase = when (j.optString("phase")) {
            "loading" -> FramePhase.LOADING
            "cloudflare" -> FramePhase.CLOUDFLARE
            "dead" -> FramePhase.DEAD
            "play_ready" -> FramePhase.PLAY_READY
            else -> FramePhase.UNKNOWN
        }
        val area = j.optInt("area", 0)
        val hasPlay = j.optBoolean("hasPlay", false)
        val hasVideo = j.optBoolean("hasVideo", false)
        val now = System.currentTimeMillis()
        val prev = tab.iframeSnapshot

        // Évite qu'un petit widget secondaire n'écrase la grande iframe vidéo déjà détectée
        val stale = (now - prev.updatedAt) > 1500L
        val sameOrigin = prev.origin == origin || prev.origin.isEmpty()
        val strongerSignal = (hasVideo || hasPlay) && !prev.hasVideoElement && !prev.hasPlayButton
        val largeEnough = area >= (prev.area * 0.65f).toInt()
        if (!stale && !sameOrigin && !strongerSignal && !largeEnough && phase != FramePhase.CLOUDFLARE) {
            return
        }

        tab.iframeSnapshot = IframeSnapshot(
            phase = phase,
            origin = origin,
            frameHref = j.optString("href"),
            hasPlayButton = hasPlay,
            hasVideoElement = hasVideo,
            relX = j.optDouble("rx", 0.5).toFloat().coerceIn(0.08f, 0.92f),
            relY = j.optDouble("ry", 0.5).toFloat().coerceIn(0.08f, 0.92f),
            area = area,
            detail = j.optString("detail"),
            updatedAt = now
        )
    }

    private val OBSERVER_JS: String = """
(function(){
  if (window.top === window || window.__vgObs) return;
  window.__vgObs = true;
  var skipHref = /recaptcha|hcaptcha|googlesyndication|doubleclick|facebook\.com\/plugins|twitter|disqus|chatango|adsterra|exoclick|tsyndicate|juicyads|adskeeper|about:blank/i;
  var lastKey = '';

  function post(payload){
    try {
      if (!window.$CHANNEL || typeof window.$CHANNEL.postMessage !== 'function') return;
      var k = payload.phase + '|' + payload.hasPlay + '|' + payload.hasVideo + '|' + Math.round(payload.rx * 20) + '|' + Math.round(payload.ry * 20);
      if (k === lastKey && payload.phase !== 'play_ready') return;
      lastKey = k;
      window.$CHANNEL.postMessage(JSON.stringify(payload));
    } catch(e){}
  }

  function isVisible(el){
    if (!el) return false;
    try {
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.08) return false;
      var r = el.getBoundingClientRect();
      return r.width >= 8 && r.height >= 8;
    } catch(e){ return false; }
  }

  function findPlayButton(W, H){
    var sels = [
      '.vjs-big-play-button', '.plyr__control--overlaid', '.jw-icon-display', '.fp-playbtn',
      '.dplayer-play-icon', '.art-icon-play', '[aria-label*="play" i]', '[title*="play" i]',
      '[class*="play-btn" i]', '[class*="playbtn" i]', '[class*="play_btn" i]', '[class*="btn-play" i]',
      '[id*="play" i]', 'button'
    ];
    for (var s = 0; s < sels.length; s++){
      var list = document.querySelectorAll(sels[s]);
      for (var i = 0; i < list.length; i++){
        var el = list[i];
        if (!isVisible(el)) continue;
        var r = el.getBoundingClientRect();
        if (r.width < 24 || r.height < 24 || r.width > W * 0.85 || r.height > H * 0.85) continue;
        if (sels[s] === 'button') {
          var txt = (el.innerText || el.getAttribute('aria-label') || el.className || '').toLowerCase();
          var hasSvg = !!el.querySelector('svg,i,path,polygon');
          if (!hasSvg && txt.indexOf('play') < 0 && txt.indexOf('lire') < 0 && txt.indexOf('lecture') < 0) continue;
          if (r.top > H * 0.82 && r.width < 48) continue; // ignore les mini-icônes de la barre du bas
        }
        return {
          rx: (r.left + r.width / 2) / W,
          ry: (r.top + r.height / 2) / H
        };
      }
    }
    return null;
  }

  function hasActiveSpinner(W, H){
    var sels = '.vjs-loading-spinner, .jw-buffer, .plyr__loader, [class*="spinner" i], [class*="loader" i], [class*="loading" i]';
    var els = document.querySelectorAll(sels);
    for (var i = 0; i < els.length; i++){
      var el = els[i];
      if (!isVisible(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.width >= 18 && r.height >= 18 && r.width <= W * 0.6 && r.height <= H * 0.6) return true;
    }
    return false;
  }

  function inspect(){
    try {
      var href = location.href || '';
      if (!href || skipHref.test(href)) return;
      var W = window.innerWidth || 0, H = window.innerHeight || 0;
      var isCfUrl = /challenges\.cloudflare\.com|turnstile|\/cdn-cgi\/challenge-platform/i.test(href);
      if (isCfUrl) {
        if (W >= 50 && H >= 30) {
          post({ phase: 'cloudflare', href: href, hasPlay: false, hasVideo: false, rx: 0.5, ry: 0.5, area: W * H, detail: 'cf-url' });
        }
        return;
      }
      if (W < 160 || H < 90) return;

      var area = W * H;
      var title = (document.title || '').toLowerCase();
      if (title.indexOf('just a moment') >= 0 || title.indexOf('attention required') >= 0 || title.indexOf('checking your browser') >= 0 ||
          document.querySelector('#challenge-running, #cf-challenge-running, .cf-browser-verification')) {
        post({ phase: 'cloudflare', href: href, hasPlay: false, hasVideo: false, rx: 0.5, ry: 0.5, area: area, detail: 'cf-dom' });
        return;
      }

      var cfSub = document.querySelector('iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"]');
      if (cfSub && isVisible(cfSub)) {
        post({ phase: 'cloudflare', href: href, hasPlay: false, hasVideo: false, rx: 0.5, ry: 0.5, area: area, detail: 'cf-subframe' });
        return;
      }

      var vid = null;
      var vids = document.querySelectorAll('video');
      for (var i = 0; i < vids.length; i++){
        var vr = vids[i].getBoundingClientRect();
        if (vr.width >= 120 && vr.height >= 70) { vid = vids[i]; break; }
      }
      var hasVideo = !!vid;
      var playBtn = findPlayButton(W, H);

      if (!hasVideo && !playBtn && document.body) {
        var txt = (document.body.innerText || '').replace(/\s+/g, ' ').trim();
        var low = txt.slice(0, 600).toLowerCase();
        if (low.indexOf('verify you are human') >= 0 || low.indexOf('checking your browser') >= 0 ||
            low.indexOf('checking if the site connection is secure') >= 0 ||
            (low.indexOf('vérification') >= 0 && low.indexOf('humain') >= 0)) {
          post({ phase: 'cloudflare', href: href, hasPlay: false, hasVideo: false, rx: 0.5, ry: 0.5, area: area, detail: 'cf-text' });
          return;
        }
        if (document.readyState === 'complete' && txt.length > 0 && txt.length < 450) {
          if (/file (was|has been) deleted|video (was|has been) (deleted|removed)|video not found|file not found|media not found|no longer available|404 not found|vid[eé]o (introuvable|supprim[eé]e)|fichier.*supprim[eé]|cannot be played/i.test(low)) {
            post({ phase: 'dead', href: href, hasPlay: false, hasVideo: false, rx: 0.5, ry: 0.5, area: area, detail: txt.slice(0, 60) });
            return;
          }
        }
      }

      if (playBtn) {
        post({ phase: 'play_ready', href: href, hasPlay: true, hasVideo: hasVideo, rx: playBtn.rx, ry: playBtn.ry, area: area, detail: 'btn' });
        return;
      }

      if (document.readyState !== 'complete' || hasActiveSpinner(W, H)) {
        post({ phase: 'loading', href: href, hasPlay: false, hasVideo: hasVideo, rx: 0.5, ry: 0.5, area: area, detail: document.readyState });
        return;
      }

      if (hasVideo || document.querySelector('.video-js, .plyr, .jwplayer, #player, #vplayer, [id*="player" i], [class*="player" i]')) {
        var rx = 0.5, ry = 0.5;
        if (vid) {
          var b = vid.getBoundingClientRect();
          rx = (b.left + b.width / 2) / W;
          ry = (b.top + b.height / 2) / H;
        }
        post({ phase: 'play_ready', href: href, hasPlay: false, hasVideo: hasVideo, rx: rx, ry: ry, area: area, detail: hasVideo ? 'video' : 'container' });
        return;
      }

      post({ phase: 'loading', href: href, hasPlay: false, hasVideo: false, rx: 0.5, ry: 0.5, area: area, detail: 'waiting-dom' });
    } catch(e){}
  }

  document.addEventListener('DOMContentLoaded', inspect);
  window.addEventListener('load', inspect);
  setInterval(inspect, 350);
  inspect();
})();
""".trimIndent()
}
