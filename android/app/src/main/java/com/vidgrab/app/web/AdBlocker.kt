package com.vidgrab.app.web

/**
 * Bouclier anti-publicités. IMPORTANT : uniquement des noms complets de régies / domaines exacts.
 * Ne jamais ajouter "ad" ou "ads" comme mot-clé générique (faux positifs : uqload, mp4upload...).
 */
object AdBlocker {
    private val adHosts = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "popads", "propellerads", "propellerclick",
        "exoclick", "exosrv", "adsterra", "tsyndicate", "realsrv", "adnxs", "taboola", "outbrain",
        "popcash", "juicyads", "clickadu", "hilltopads", "adcash", "onclickads", "trafficjunky",
        "revcontent", "mgid.com", "ad-maven", "admaven", "adspyglass", "a-ads.com", "bidvertiser",
        "yllix", "zeroredirect", "pushwhy", "richpartners", "profitablegatecpm", "highperformanceformat",
        "wpadmngr", "trafficstars", "adskeeper", "clickaine", "monetag", "acscdn", "ero-advertising"
    )

    private val searchEngines = listOf(
        "google.", "bing.", "duckduckgo.", "yahoo.", "ecosia.", "qwant.", "startpage.", "search.brave."
    )

    private val secondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    fun isAdHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return adHosts.any { h.contains(it) }
    }

    fun isSearchEngine(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return searchEngines.any { h.contains(it) }
    }

    /** Domaine racine avec gestion des suffixes composés (.co.uk, .com.br, ...). */
    fun rootDomain(host: String?): String {
        if (host.isNullOrBlank()) return ""
        val parts = host.lowercase().split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val tld = parts.last()
        val sld = parts[parts.size - 2]
        return if (tld.length == 2 && sld in secondLevel) parts.takeLast(3).joinToString(".")
        else parts.takeLast(2).joinToString(".")
    }

    /**
     * Script injecté : neutralise window.open, supprime les calques invisibles en z-index élevé qui volent les clics,
     * remonte les clics réels sur <a> visibles, et écoute les balises <video> (filet de sécurité du sniffer).
     */
    val INJECTED_JS: String = """
(function(){
  if (window.__vgInit) return; window.__vgInit = true;
  try { window.open = function(){ return null; }; } catch(e){}

  function report(v){
    try {
      var s = v.currentSrc || v.src;
      if (s && s.indexOf('blob:') !== 0 && s.indexOf('http') === 0) { VidGrab.onVideo(s, document.title || ''); }
    } catch(e){}
  }
  function hook(v){
    if (v.__vg) return; v.__vg = true;
    v.addEventListener('loadedmetadata', function(){ report(v); });
    v.addEventListener('play', function(){ report(v); });
    report(v);
  }
  function killOverlays(){
    try {
      var W = window.innerWidth, H = window.innerHeight;
      var els = document.body ? document.body.querySelectorAll('div,a,span,section') : [];
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        var cs = getComputedStyle(e);
        if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
        var z = parseInt(cs.zIndex, 10) || 0;
        if (z < 1000) continue;
        var r = e.getBoundingClientRect();
        if (r.width < W * 0.5 || r.height < H * 0.4) continue;
        if ((e.textContent || '').trim().length > 0) continue;
        if (e.querySelector('video,iframe,img,canvas,input,button')) continue;
        e.style.setProperty('display', 'none', 'important');
        e.style.setProperty('pointer-events', 'none', 'important');
      }
    } catch(e){}
  }
  function scan(){ document.querySelectorAll('video').forEach(hook); killOverlays(); }

  document.addEventListener('click', function(ev){
    try {
      var a = ev.target && ev.target.closest ? ev.target.closest('a') : null;
      if (!a || !ev.isTrusted) return;
      var r = a.getBoundingClientRect();
      var cs = getComputedStyle(a);
      if (r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity) > 0.05) {
        VidGrab.linkClicked(a.href || '');
      }
    } catch(e){}
  }, true);

  var n = 0;
  var timer = setInterval(function(){ scan(); if (++n > 30) clearInterval(timer); }, 1500);
  try {
    new MutationObserver(function(){ document.querySelectorAll('video').forEach(hook); }).observe(document.documentElement, {childList:true, subtree:true});
  } catch(e){}
  scan();
})();
""".trimIndent()
}
