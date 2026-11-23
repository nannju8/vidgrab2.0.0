package com.vidgrab.app.web

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Automatisation WebView :
 * - Découverte universelle des lecteurs (comparaison entre <select> et groupes de boutons)
 * - Détection Cloudflare sur la page principale
 * - Coordonnées précises du <video>, <iframe> ou bouton Play sur la page pour un tap ciblé (relX, relY)
 */
object PageAutomator {

    data class PlayerOption(
        val id: String,
        val label: String,
        val isSelected: Boolean
    )

    data class PlayerTarget(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val cx: Float,
        val cy: Float,
        val innerW: Float,
        val innerH: Float,
        val isDirectVideo: Boolean,
        val isMainPlayButton: Boolean = false
    )

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
      if (txt.length >= 2 && txt.length <= 32 && !/^(vf|vostfr|vo|fr|eng|all)$/i.test(txt)) {
        VidGrab.onPlayerClicked(txt);
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

    /** Détecte uniquement un vrai blocage Cloudflare / Captcha sur la page principale. */
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

    /**
     * Découvre tous les lecteurs disponibles dans l'ordre naturel du site (1er -> dernier)
     * en comparant le meilleur <select> et le meilleur groupe de boutons.
     */
    suspend fun discoverPlayers(wv: WebView): List<PlayerOption> {
        val js = """
(function(){
  try {
    var langOnly = /^(vf|vostfr|vo|french|english|eng|sub|dub|raw|all|tous|langue|language|qualit[eé]|auto|hd|sd|fhd|uhd)$/i;
    var sortTrap = /^(plus r[eé]cents?|plus anciens?|pertinence|meilleurs?|date|vues|populaire|popular|newest|oldest|top|best|a[- ]z|z[- ]a)$/i;
    var hostWords = /voe|dood|vidmoly|filemoon|streamtape|sibnet|uqload|luluvdo|sendvid|myvi|ok\.ru|netu|waaw|lecteur|player|server|serveur|mirror|stream|hdvid|upstream|mixdrop|mp4upload|yourupload|vk|wish|vidhide|earnvids/i;
    var trapWords = /^(pr[eé]c[eé]dent|suivant|prev|next|partager|share|signaler|report|t[eé]l[eé]charger|download|favori|bookmark|comment|accueil|home|liste|bande[- ]?annonce|trailer|vf|vostfr|vo)$/i;
    var epTrap = /^(ep|ép|episode|épisode|saison|season)?\s*0*\d{1,3}$/i;
    var placeholderOpt = /^(choisir|select|choose|sélectionner|--)/i;

    function cleanLabelForLang(txt){
      return txt.replace(/\([^)]*\)/g, '').replace(/\b(1080p|720p|480p|360p|4k|hd|sd|fhd)\b/gi, '').replace(/[-–|]+/g, ' ').replace(/\s+/g, ' ').trim();
    }

    var anchorY = window.innerHeight * 0.4;
    var mediaEls = document.querySelectorAll('iframe, video');
    for (var m = 0; m < mediaEls.length; m++) {
      var mr = mediaEls[m].getBoundingClientRect();
      var msrc = (mediaEls[m].src || '');
      if (mr.width >= 200 && mr.height >= 120 && msrc.indexOf('challenges.cloudflare.com') < 0) {
        anchorY = mr.top + mr.height / 2;
        break;
      }
    }

    // 1) Évalue tous les <select> sans retour précoce
    var bestSelectItems = null, bestSelectIdx = -1, bestSelectScore = -999;
    var selects = document.querySelectorAll('select');
    for (var s = 0; s < selects.length; s++) {
      var sel = selects[s];
      if (sel.closest && sel.closest('header,footer,nav')) continue;
      var sr = sel.getBoundingClientRect();
      if (sr.width < 24 || sr.height < 16) continue;
      var csS = getComputedStyle(sel);
      if (csS.display === 'none' || csS.visibility === 'hidden') continue;

      var opts = [];
      var langHits = 0, hostHits = 0, epHits = 0, sortHits = 0;
      for (var o = 0; o < sel.options.length; o++) {
        var opt = sel.options[o];
        var ot = (opt.textContent || '').replace(/\s+/g, ' ').trim();
        if (!ot || opt.disabled || placeholderOpt.test(ot)) continue;
        var stripped = cleanLabelForLang(ot);
        if (langOnly.test(ot) || (stripped && langOnly.test(stripped))) langHits++;
        if (sortTrap.test(ot)) sortHits++;
        if (hostWords.test(ot) || hostWords.test(opt.value || '')) hostHits++;
        if (epTrap.test(ot)) epHits++;
        opts.push({ idx: o, label: ot, selected: o === sel.selectedIndex });
      }
      if (opts.length < 1) continue;
      var selMeta = (sel.id + ' ' + sel.name + ' ' + sel.className).toLowerCase();
      var hasPlayerMeta = /player|server|lecteur|mirror|source/.test(selMeta);
      var isLangSelect = (langHits > 0 && hostHits === 0 && langHits >= opts.length * 0.5 && !hasPlayerMeta);
      var isEpSelect = (epHits >= opts.length * 0.6 && hostHits === 0) || /episode|saison|season/.test(selMeta);
      var isSortSelect = (sortHits >= opts.length * 0.5 && hostHits === 0) || /sort|order|comment|filter|tri/.test(selMeta);
      if (isLangSelect || isEpSelect || isSortSelect) continue;

      var sc = 0;
      if (hasPlayerMeta) sc += 50;
      sc += hostHits * 25;
      var sDist = Math.abs((sr.top + sr.height / 2) - anchorY);
      if (sDist < 380) sc += 25;
      else if (sDist > 900) sc -= 20;
      if (opts.length >= 2 && opts.length <= 16) sc += 15;
      if ((opts.length >= 2 || hostHits > 0 || hasPlayerMeta) && sc >= 25 && sc > bestSelectScore) {
        bestSelectScore = sc;
        bestSelectItems = opts;
        bestSelectIdx = s;
      }
    }

    // 2) Évalue les groupes de boutons/onglets
    var rawNodes = Array.prototype.slice.call(document.querySelectorAll(
      'a,button,li,[role="button"],[onclick],[data-src],[data-url],[data-embed],[data-link],[data-player],[data-server],.server,.player,[class*="server"],[class*="player"],[class*="lecteur"]'
    ));

    function validBtn(el){
      if (el.closest && el.closest('header,footer,nav')) return false;
      if (el.querySelector('iframe,video,select')) return false;
      if (el.tagName !== 'A' && el.tagName !== 'BUTTON' && el.querySelector('a,button')) return false;
      var r = el.getBoundingClientRect();
      if (r.width < 20 || r.height < 18 || r.width > window.innerWidth * 0.85 || r.height > 120) return false;
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) <= 0.1) return false;
      var txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      var meta = [el.getAttribute('data-player'), el.getAttribute('data-server'), el.getAttribute('data-name'), el.getAttribute('data-src'), el.getAttribute('data-embed'), el.getAttribute('title')].filter(Boolean).join(' ');
      if (txt.length === 0 && meta.length === 0) return false;
      if (txt.length > 42 && !meta) return false;
      if (trapWords.test(txt) && !hostWords.test(meta)) return false;
      if (/épisode (suivant|précédent)|(next|prev) episode|signaler|télécharger/i.test(txt)) return false;
      if (epTrap.test(txt) && !hostWords.test(meta) && !/lecteur|player|server/i.test(el.parentElement ? el.parentElement.className : '')) return false;
      return true;
    }

    var groups = [];
    var groupMap = new Map();
    for (var i = 0; i < rawNodes.length; i++) {
      var el = rawNodes[i];
      if (!validBtn(el)) continue;
      var p = el.parentElement;
      if (!p) continue;
      // Remonte jusqu'à 3 niveaux si les boutons sont emboîtés dans des wrappers intermédiaires
      for (var up = 0; up < 3; up++) {
        if (!p.parentElement || p.parentElement === document.body || p.parentElement === document.documentElement) break;
        if (p.tagName === 'LI' || p.children.length === 1) {
          p = p.parentElement;
        } else {
          break;
        }
      }
      if (!groupMap.has(p)) {
        var g = { parent: p, items: [] };
        groupMap.set(p, g);
        groups.push(g);
      }
      var arr = groupMap.get(p).items;
      if (arr.indexOf(el) < 0) arr.push(el);
    }

    var bestGroup = null, bestGroupScore = -999;
    for (var gIdx = 0; gIdx < groups.length; gIdx++) {
      var grp = groups[gIdx];
      var items = grp.items;
      if (items.length < 2 && !hostWords.test((items[0].innerText || '') + ' ' + (items[0].className || ''))) continue;
      var pMeta = (grp.parent.id + ' ' + grp.parent.className).toLowerCase();
      var scG = 0;
      if (/player|server|lecteur|mirror|embed|source|stream/.test(pMeta)) scG += 50;
      var gr = grp.parent.getBoundingClientRect();
      var dist = Math.abs((gr.top + gr.height / 2) - anchorY);
      if (dist < 380) scG += 25;
      else if (dist > 900) scG -= 20;
      if (items.length >= 2 && items.length <= 16) scG += 15;

      var hostCnt = 0, metaCnt = 0;
      for (var j = 0; j < items.length; j++) {
        var it = items[j];
        var lbl = (it.innerText || it.textContent || '').replace(/\s+/g, ' ').trim();
        var ex = [it.getAttribute('data-player'), it.getAttribute('data-server'), it.getAttribute('data-embed'), it.getAttribute('data-src'), it.className].filter(Boolean).join(' ');
        if (hostWords.test(lbl + ' ' + ex)) hostCnt++;
        if (it.hasAttribute('data-src') || it.hasAttribute('data-embed') || it.hasAttribute('data-player') || it.hasAttribute('data-server')) metaCnt++;
      }
      scG += hostCnt * 25 + metaCnt * 20;
      if (scG > bestGroupScore && scG >= 25) {
        bestGroupScore = scG;
        bestGroup = items;
      }
    }

    var out = [];
    if (bestSelectItems && (!bestGroup || bestSelectScore > bestGroupScore)) {
      selects[bestSelectIdx].setAttribute('data-vg-sel', String(bestSelectIdx));
      for (var k = 0; k < bestSelectItems.length; k++) {
        out.push({
          id: 'sel_' + bestSelectIdx + '_' + bestSelectItems[k].idx,
          label: bestSelectItems[k].label,
          selected: bestSelectItems[k].selected
        });
      }
    } else if (bestGroup) {
      for (var b = 0; b < bestGroup.length; b++) {
        var btn = bestGroup[b];
        var pid = 'btn_' + b;
        btn.setAttribute('data-vg-pid', pid);
        var label = (btn.innerText || btn.textContent || '').replace(/\s+/g, ' ').trim()
          || btn.getAttribute('data-player') || btn.getAttribute('data-server') || ('Lecteur ' + (b + 1));
        var cls = (btn.className || '').toLowerCase();
        var isSel = /\b(active|selected|current|on|is-active)\b/.test(cls) || btn.getAttribute('aria-selected') === 'true';
        out.push({ id: pid, label: label.slice(0, 36), selected: isSel });
      }
    }
    return JSON.stringify(out);
  } catch(e){}
  return '[]';
})();
""".trimIndent()
        val raw = evalJs(wv, js) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PlayerOption(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    isSelected = o.optBoolean("selected", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Sélectionne précisément un lecteur découvert via son identifiant (`sel_...` ou `btn_...`). */
    suspend fun selectPlayerById(wv: WebView, id: String): Boolean {
        val quotedId = JSONObject.quote(id)
        val js = """
(function(){
  try {
    var pid = $quotedId;
    if (pid.indexOf('sel_') === 0) {
      var parts = pid.split('_');
      var sIdx = parts[1], oIdx = parseInt(parts[2], 10);
      var sel = document.querySelector('select[data-vg-sel="' + sIdx + '"]') || document.querySelectorAll('select')[parseInt(sIdx, 10)];
      if (!sel || isNaN(oIdx) || oIdx < 0 || oIdx >= sel.options.length) return '0';
      if (sel.selectedIndex !== oIdx) {
        sel.selectedIndex = oIdx;
        sel.dispatchEvent(new Event('input', {bubbles: true}));
        sel.dispatchEvent(new Event('change', {bubbles: true}));
      }
      return '1';
    }
    var btn = document.querySelector('[data-vg-pid="' + pid + '"]');
    if (!btn) return '0';
    btn.scrollIntoView({block:'center', behavior:'instant'});
    btn.click();
    return '1';
  } catch(e){}
  return '0';
})();
""".trimIndent()
        return evalJs(wv, js) == "1"
    }

    /** Cherche uniquement un <video> direct sur la page principale (hors iframe). */
    suspend fun findDirectVideoTarget(wv: WebView, muteVideo: Boolean = true): PlayerTarget? =
        findTargetInternal(wv, muteVideo, directOnly = true, includeMainPlayBtn = false)

    /** Cherche le <video>, le vrai <iframe> lecteur ou un bouton Play sur la page principale. */
    suspend fun findPlayerTarget(
        wv: WebView,
        muteVideo: Boolean = true,
        includeMainPlayBtn: Boolean = false
    ): PlayerTarget? =
        findTargetInternal(wv, muteVideo, directOnly = false, includeMainPlayBtn = includeMainPlayBtn)

    private suspend fun findTargetInternal(
        wv: WebView,
        muteVideo: Boolean,
        directOnly: Boolean,
        includeMainPlayBtn: Boolean
    ): PlayerTarget? {
        val muteFlag = if (muteVideo) "true" else "false"
        val dirOnly = if (directOnly) "true" else "false"
        val playFallback = if (includeMainPlayBtn && !directOnly) "true" else "false"
        val js = """
(function(){
  try {
    var W = window.innerWidth || 1, H = window.innerHeight || 1;
    var best = null, bestScore = 0, isDirect = false, isMainBtn = false;
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      var r = v.getBoundingClientRect();
      if (r.width >= 180 && r.height >= 110 && r.width * r.height > bestScore) {
        best = v; bestScore = r.width * r.height * 3; isDirect = true;
        try { v.muted = $muteFlag; v.play(); } catch(e){}
      }
    }
    if (!best && !$dirOnly) {
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
    if (!best && $playFallback) {
      var sels = [
        '.vjs-big-play-button', '.plyr__control--overlaid', '.jw-icon-display',
        '[aria-label*="play" i]', '[title*="play" i]',
        '[class*="play-btn" i]', '[class*="playbtn" i]', '[class*="btn-play" i]', '[id*="play" i]'
      ];
      for (var s = 0; s < sels.length && !best; s++) {
        var list = document.querySelectorAll(sels[s]);
        for (var p = 0; p < list.length; p++) {
          var el = list[p];
          if (el.hasAttribute('data-vg-pid')) continue;
          if (el.closest && el.closest('header,footer,nav')) continue;
          var cs = getComputedStyle(el);
          if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.1) continue;
          var pr = el.getBoundingClientRect();
          if (pr.width >= 32 && pr.height >= 32 && pr.width <= W * 0.85 && pr.height <= H * 0.85) {
            best = el; isMainBtn = true; break;
          }
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
    return JSON.stringify({
      l: br.left, t: br.top, w: br.width, h: br.height,
      cx: cx, cy: cy, iw: W, ih: H, dv: isDirect, mb: isMainBtn
    });
  } catch(e){}
  return '';
})();
""".trimIndent()
        val raw = evalJs(wv, js)
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            PlayerTarget(
                left = o.getDouble("l").toFloat(),
                top = o.getDouble("t").toFloat(),
                width = o.getDouble("w").toFloat().coerceAtLeast(1f),
                height = o.getDouble("h").toFloat().coerceAtLeast(1f),
                cx = o.getDouble("cx").toFloat(),
                cy = o.getDouble("cy").toFloat(),
                innerW = o.getDouble("iw").toFloat().coerceAtLeast(1f),
                innerH = o.getDouble("ih").toFloat().coerceAtLeast(1f),
                isDirectVideo = o.optBoolean("dv", false),
                isMainPlayButton = o.optBoolean("mb", false)
            )
        }.getOrNull()
    }

    fun tapWebView(wv: WebView, target: PlayerTarget, relX: Float = 0.5f, relY: Float = 0.5f) {
        val pageX = target.left + target.width * relX.coerceIn(0.08f, 0.92f)
        val pageY = target.top + target.height * relY.coerceIn(0.08f, 0.92f)
        tapPoint(wv, pageX, pageY, target.innerW, target.innerH)
    }

    fun tapPoint(wv: WebView, pageX: Float, pageY: Float, innerW: Float, innerH: Float) {
        wv.post {
            if (wv.width <= 0 || wv.height <= 0) return@post
            val vx = ((pageX / innerW) * wv.width.toFloat()).coerceIn(1f, wv.width - 1f)
            val vy = ((pageY / innerH) * wv.height.toFloat()).coerceIn(1f, wv.height - 1f)
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
