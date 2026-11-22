package com.vidgrab.app.web

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** Automatisation WebView : sélection de lecteur (<select> + boutons), détection Cloudflare sans faux positifs et tap Play. */
object PageAutomator {

    data class PlayerTarget(val cx: Float, val cy: Float, val innerW: Float, val innerH: Float)

    val PLAYER_TRACKER_JS: String = """
(function(){
  if (window.__vgTrack) return; window.__vgTrack = true;
  var hosts = /voe|dood|vidmoly|filemoon|streamtape|sibnet|uqload|luluvdo|sendvid|myvi|ok\.ru|netu|waaw|lecteur|player|server|serveur|mirror|stream/i;
  document.addEventListener('click', function(ev){
    try {
      if (!ev.isTrusted || !ev.target || !ev.target.closest) return;
      var el = ev.target.closest('a,button,li,[role="button"],[data-src],[data-url],[data-embed],[data-link],.server,.player,span');
      if (!el || el.querySelector('iframe,video')) return;
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
  document.addEventListener('change', function(ev){
    try {
      var s = ev.target;
      if (!s || s.tagName !== 'SELECT') return;
      var opt = s.options && s.options[s.selectedIndex];
      var txt = opt ? (opt.textContent || '').trim() : '';
      if (txt.length >= 2 && txt.length <= 32 && hosts.test(txt)) VidGrab.onPlayerClicked(txt);
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

    /** Détecte uniquement un vrai blocage Cloudflare / Captcha qui empêche la page vidéo d'exister. */
    suspend fun isBlockedByChallenge(wv: WebView): Boolean {
        val js = """
(function(){
  try {
    var t = (document.title || '').toLowerCase();
    if (t.indexOf('just a moment') >= 0 || t.indexOf('attention required') >= 0 || t.indexOf('checking your browser') >= 0) return '1';
    if (document.querySelector('#challenge-running, #cf-challenge-running')) return '1';
    var hasPlayer = false;
    var els = document.querySelectorAll('video, iframe');
    for (var i = 0; i < els.length; i++) {
      var r = els[i].getBoundingClientRect();
      var src = (els[i].src || '');
      if (r.width >= 200 && r.height >= 120 && src.indexOf('challenges.cloudflare.com') < 0 && src.indexOf('turnstile') < 0) {
        hasPlayer = true; break;
      }
    }
    if (hasPlayer) return '0';
    var b = (document.body ? (document.body.innerText || '') : '').slice(0, 800).toLowerCase();
    if (b.indexOf('verify you are human') >= 0 || b.indexOf('checking your browser') >= 0 || (b.indexOf('vérification') >= 0 && b.indexOf('humain') >= 0)) return '1';
    var cf = document.querySelector('iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"]');
    if (cf) {
      var cr = cf.getBoundingClientRect();
      if (cr.width > 50 && cr.height > 30) return '1';
    }
  } catch(e){}
  return '0';
})();
""".trimIndent()
        return evalJs(wv, js) == "1"
    }

    /** Sélectionne le lecteur préféré via <select> ou bouton/onglet cliquable (jamais un conteneur parent). */
    suspend fun selectPlayer(wv: WebView, preferred: List<String>): String? {
        val kwArray = JSONArray(preferred.map { it.trim().lowercase() }.filter { it.isNotBlank() }).toString()
        val js = """
(function(){
  try {
    var prefs = $kwArray;
    var selects = document.querySelectorAll('select');
    for (var s = 0; s < selects.length; s++) {
      var sel = selects[s];
      var sr = sel.getBoundingClientRect();
      if (sr.width < 20 || sr.height < 16) continue;
      for (var p = 0; p < prefs.length; p++) {
        var kw = prefs[p];
        for (var o = 0; o < sel.options.length; o++) {
          var opt = sel.options[o];
          if (opt.dataset && opt.dataset.vgTried === '1') continue;
          var ot = (opt.textContent || '').trim();
          var ov = (opt.value || '').toLowerCase();
          if ((ot.toLowerCase().indexOf(kw) >= 0 || ov.indexOf(kw) >= 0) && o !== sel.selectedIndex) {
            if (opt.dataset) opt.dataset.vgTried = '1';
            sel.selectedIndex = o;
            sel.dispatchEvent(new Event('change', {bubbles: true}));
            return ot || kw;
          }
        }
      }
    }
    var nodes = Array.prototype.slice.call(document.querySelectorAll(
      'a,button,li,[role="button"],[onclick],[data-src],[data-url],[data-embed],[data-link],.server,.player,[class*="server"],[class*="player"]'
    ));
    function isValidBtn(el){
      if (el.dataset && el.dataset.vgTried === '1') return false;
      if (el.querySelector('iframe,video,select')) return false;
      if (el.tagName !== 'A' && el.tagName !== 'BUTTON' && el.querySelector('a,button')) return false;
      var r = el.getBoundingClientRect();
      if (r.width < 20 || r.height < 18 || r.width > window.innerWidth * 0.9) return false;
      var cs = getComputedStyle(el);
      return cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0.1;
    }
    function labelOf(el){
      var t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      var extra = [el.getAttribute('data-player'), el.getAttribute('data-server'), el.getAttribute('data-name'), el.getAttribute('title'), el.id].filter(Boolean).join(' ');
      return { text: t, full: (t + ' ' + extra).toLowerCase(), hasMeta: extra.length > 0 };
    }
    var candidates = nodes.filter(function(el){
      if (!isValidBtn(el)) return false;
      var info = labelOf(el);
      return (info.text.length >= 1 && info.text.length <= 36) || info.hasMeta;
    });
    for (var p = 0; p < prefs.length; p++) {
      var kw = prefs[p];
      for (var i = 0; i < candidates.length; i++) {
        var el = candidates[i];
        if (el.closest && el.closest('header,footer,nav')) continue;
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

    /** Cherche le <video> ou le vrai <iframe> lecteur (avec src valide) et renvoie son centre. */
    suspend fun findPlayerTarget(wv: WebView, muteVideo: Boolean = true): PlayerTarget? {
        val muteFlag = if (muteVideo) "true" else "false"
        val js = """
(function(){
  try {
    var W = window.innerWidth || 1, H = window.innerHeight || 1;
    var best = null, bestScore = 0;
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      var r = v.getBoundingClientRect();
      if (r.width >= 180 && r.height >= 110 && r.width * r.height > bestScore) {
        best = v; bestScore = r.width * r.height * 3;
        try { v.muted = $muteFlag; v.play(); } catch(e){}
      }
    }
    if (!best) {
      var frames = document.querySelectorAll('iframe');
      var skip = /recaptcha|hcaptcha|turnstile|cloudflare|googlesyndication|doubleclick|facebook\.com\/plugins|twitter|adsterra|exoclick|tsyndicate|juicyads|adskeeper|disqus/i;
      var good = /embed|\/e\/|voe|dood|vidmoly|sibnet|filemoon|stream|player|video|uqload|luluvdo|sendvid|myvi|ok\.ru|netu|waaw/i;
      for (var j = 0; j < frames.length; j++) {
        var f = frames[j];
        var src = (f.src || f.getAttribute('data-src') || '').trim();
        if (!src || src === 'about:blank' || skip.test(src)) continue;
        var fr = f.getBoundingClientRect();
        if (fr.width >= 200 && fr.height >= 120) {
          var sc = fr.width * fr.height * (good.test(src) ? 2 : 1);
          if (sc > bestScore) { best = f; bestScore = sc; }
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
