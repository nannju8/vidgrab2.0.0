package com.vidgrab.app.web

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** Automatisation WebView : sélection de lecteur, détection Cloudflare et tap tactile natif sur Play. */
object PageAutomator {

    data class PlayerTarget(val cx: Float, val cy: Float, val innerW: Float, val innerH: Float)

    /** Script toujours injecté pour retenir sur quel bouton de lecteur l'utilisateur clique (Ép. 1). */
    val PLAYER_TRACKER_JS: String = """
(function(){
  if (window.__vgTrack) return; window.__vgTrack = true;
  var hosts = /voe|dood|vidmoly|filemoon|streamtape|sibnet|uqload|luluvdo|sendvid|lecteur|player|server|serveur|mirror|stream/i;
  document.addEventListener('click', function(ev){
    try {
      if (!ev.isTrusted || !ev.target || !ev.target.closest) return;
      var el = ev.target.closest('a,button,li,[role="button"],[data-src],[data-url],[data-embed],[data-link],.server,.player,span,div');
      if (!el) return;
      var txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      var attr = (el.getAttribute('data-player') || el.getAttribute('data-server') || el.className || '');
      if (txt.length >= 2 && txt.length <= 32 && (hosts.test(txt) || hosts.test(attr))) {
        VidGrab.onPlayerClicked(txt);
      } else if (attr && hosts.test(attr)) {
        var m = attr.match(hosts);
        if (m) VidGrab.onPlayerClicked(m[0]);
      }
    } catch(e){}
  }, true);
})();
""".trimIndent()

    private suspend fun evalJs(wv: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            wv.post {
                wv.evaluateJavascript(script) { raw ->
                    if (!cont.isActive) return@evaluateJavascript
                    if (raw == null || raw == "null") {
                        cont.resume(null)
                    } else {
                        val unquoted = runCatching { JSONArray("[$raw]").getString(0) }.getOrNull() ?: raw
                        cont.resume(unquoted)
                    }
                }
            }
        }

    /** Détecte si la page affiche un écran Cloudflare / Turnstile / Captcha. */
    suspend fun isBlockedByChallenge(wv: WebView): Boolean {
        val js = """
(function(){
  try {
    var t = (document.title || '').toLowerCase();
    var b = (document.body ? (document.body.innerText || '') : '').slice(0, 800).toLowerCase();
    if (t.indexOf('just a moment') >= 0 || t.indexOf('instant') >= 0 && b.indexOf('cloudflare') >= 0) return '1';
    if (b.indexOf('verify you are human') >= 0 || b.indexOf('checking your browser') >= 0 || b.indexOf('vérification') >= 0 && b.indexOf('humain') >= 0) return '1';
    if (document.querySelector('iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"], .cf-turnstile, #challenge-running')) return '1';
  } catch(e){}
  return '0';
})();
""".trimIndent()
        return evalJs(wv, js) == "1"
    }

    /** Clique sur le bouton de lecteur correspondant en priorité à [preferred] (sinon un lecteur non encore testé). */
    suspend fun selectPlayer(wv: WebView, preferred: List<String>): String? {
        val kwArray = JSONArray(preferred.map { it.trim().lowercase() }.filter { it.isNotBlank() }).toString()
        val js = """
(function(){
  try {
    var prefs = $kwArray;
    var nodes = Array.prototype.slice.call(document.querySelectorAll(
      'a,button,li,[role="button"],[onclick],[data-src],[data-url],[data-embed],[data-link],.server,.player,[class*="server"],[class*="player"]'
    ));
    function isVisible(el){
      var r = el.getBoundingClientRect();
      if (r.width < 18 || r.height < 16) return false;
      var cs = getComputedStyle(el);
      return cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0.1;
    }
    function labelOf(el){
      var t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      var extra = [el.getAttribute('data-player'), el.getAttribute('data-server'), el.getAttribute('data-name'), el.getAttribute('title'), el.id].filter(Boolean).join(' ');
      return { text: t, full: (t + ' ' + extra).toLowerCase() };
    }
    var candidates = nodes.filter(function(el){
      if (el.dataset && el.dataset.vgTried === '1') return false;
      if (!isVisible(el)) return false;
      var info = labelOf(el);
      return info.text.length <= 40;
    });
    for (var p = 0; p < prefs.length; p++) {
      var kw = prefs[p];
      for (var i = 0; i < candidates.length; i++) {
        var el = candidates[i];
        if (el.closest && el.closest('header,footer,nav')) {
          var l = labelOf(el);
          if (l.full.indexOf(kw) < 0) continue;
        }
        var info = labelOf(el);
        if (info.full.indexOf(kw) >= 0) {
          if (el.dataset) el.dataset.vgTried = '1';
          el.scrollIntoView({block:'center', behavior:'instant'});
          el.click();
          return info.text || kw;
        }
      }
    }
  } catch(e){}
  return '';
})();
""".trimIndent()
        val res = evalJs(wv, js).orEmpty().trim()
        return res.ifBlank { null }
    }

    /** Cherche un <video> direct ou le plus grand <iframe> lecteur et renvoie son centre à l'écran. */
    suspend fun findPlayerTarget(wv: WebView): PlayerTarget? {
        val js = """
(function(){
  try {
    var W = window.innerWidth || 1, H = window.innerHeight || 1;
    var best = null, bestArea = 0;
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      var r = v.getBoundingClientRect();
      if (r.width >= 160 && r.height >= 100 && r.width * r.height > bestArea) {
        best = v; bestArea = r.width * r.height;
        try { v.muted = false; v.play(); } catch(e){}
      }
    }
    if (!best) {
      var frames = document.querySelectorAll('iframe');
      var skip = /recaptcha|hcaptcha|turnstile|cloudflare|googlesyndication|doubleclick|facebook\.com\/plugins|twitter/i;
      for (var j = 0; j < frames.length; j++) {
        var f = frames[j];
        var src = (f.src || f.getAttribute('data-src') || '');
        if (skip.test(src)) continue;
        var fr = f.getBoundingClientRect();
        if (fr.width >= 180 && fr.height >= 110 && fr.width * fr.height > bestArea) {
          best = f; bestArea = fr.width * fr.height;
        }
      }
    }
    if (!best) return '';
    var br = best.getBoundingClientRect();
    if (br.top < 0 || br.bottom > H) {
      best.scrollIntoView({block:'center', behavior:'instant'});
      br = best.getBoundingClientRect();
    }
    var cx = br.left + br.width / 2;
    var cy = br.top + br.height / 2;
    if (cx < 0 || cy < 0 || cx > W || cy > H) return '';
    return JSON.stringify({cx: cx, cy: cy, iw: W, ih: H});
  } catch(e){}
  return '';
})();
""".trimIndent()
        val raw = evalJs(wv, js)
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            PlayerTarget(
                cx = o.getDouble("cx").toFloat(),
                cy = o.getDouble("cy").toFloat(),
                innerW = o.getDouble("iw").toFloat().coerceAtLeast(1f),
                innerH = o.getDouble("ih").toFloat().coerceAtLeast(1f)
            )
        }.getOrNull()
    }

    /** Envoie un vrai toucher natif sur la WebView (traverse les iframes cross-origin comme Voe/Doodstream). */
    fun tapWebView(wv: WebView, target: PlayerTarget) {
        wv.post {
            if (wv.width <= 0 || wv.height <= 0) return@post
            val vx = ((target.cx / target.innerW) * wv.width.toFloat()).coerceIn(1f, wv.width - 1f)
            val vy = ((target.cy / target.innerH) * wv.height.toFloat()).coerceIn(1f, wv.height - 1f)
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, vx, vy, 0)
            wv.dispatchTouchEvent(down)
            down.recycle()
            wv.postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, vx, vy, 0)
                wv.dispatchTouchEvent(up)
                up.recycle()
            }, 60L)
        }
    }
}
