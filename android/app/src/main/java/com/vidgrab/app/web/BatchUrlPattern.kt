package com.vidgrab.app.web

/** Analyse intelligente d'URL de page web pour extraire et incrémenter le numéro d'épisode. */
object BatchUrlPattern {

    data class NumberMatch(
        val start: Int,
        val end: Int,
        val raw: String,
        val value: Int,
        val padWidth: Int,
        val score: Int
    )

    private val numRegex = Regex("\\d+")
    private val resolutions = setOf(240, 360, 480, 720, 1080, 1440, 2160)
    private val beforeEpHint = Regex("(episode|ep|e|part|chap|ch|num|n)[-_=/]?$", RegexOption.IGNORE_CASE)
    private val afterEpHint = Regex("^[-_]?(episode|ep|vostfr|vf|part|chap)", RegexOption.IGNORE_CASE)
    private val beforeSeasonHint = Regex("(saison|season|s)[-_=/]?$", RegexOption.IGNORE_CASE)
    private val afterSeasonHint = Regex("^[-_]?(saison|season)", RegexOption.IGNORE_CASE)

    /** Repère tous les nombres situés après le nom de domaine et les classe par pertinence. */
    fun findNumbers(url: String): List<NumberMatch> {
        val schemeIdx = url.indexOf("://")
        val pathStart = url.indexOf('/', if (schemeIdx >= 0) schemeIdx + 3 else 0)
        if (pathStart < 0) return emptyList()

        return numRegex.findAll(url, startIndex = pathStart).map { m ->
            val raw = m.value
            val value = raw.toIntOrNull() ?: 0
            val pad = if (raw.length > 1 && raw.startsWith('0')) raw.length else 1
            val before = url.substring(maxOf(0, m.range.first - 14), m.range.first)
            val after = url.substring(m.range.last + 1, minOf(url.length, m.range.last + 15))

            var score = 0
            if (beforeEpHint.containsMatchIn(before)) score += 30
            if (afterEpHint.containsMatchIn(after)) score += 35
            if (beforeSeasonHint.containsMatchIn(before) || afterSeasonHint.containsMatchIn(after)) score -= 25
            if (value in resolutions) score -= 30
            if (raw.length >= 4 && value > 300) score -= 20 // IDs de série (ex: 1308) ou années (2024)
            if (before.endsWith("/v", ignoreCase = true)) score -= 15
            if (value in 1..200) score += 5
            // Léger bonus pour les nombres plus proches de la fin de l'URL
            score += ((m.range.first.toFloat() / url.length.coerceAtLeast(1)) * 6f).toInt()

            NumberMatch(m.range.first, m.range.last + 1, raw, value, pad, score)
        }.toList()
    }

    fun applyPlaceholder(url: String, match: NumberMatch): String =
        url.substring(0, match.start) + "{n}" + url.substring(match.end)

    fun formatNum(n: Int, padWidth: Int): String =
        if (padWidth > 1) n.toString().padStart(padWidth, '0') else n.toString()

    fun buildUrl(template: String, ep: Int, padWidth: Int): String =
        template.replace("{n}", formatNum(ep, padWidth))

    /** Nettoie le titre de la page 1 pour proposer un nom de série propre. */
    fun cleanSeriesTitle(pageTitle: String): String {
        val cleaned = pageTitle
            .replace(Regex("\\s*[-|–—:]\\s*(voiranime|streamdeouf|voir|streaming|vostfr|vf).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(episode|épisode|ep)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+0*\\d{1,3}\\s*(vostfr|vf)?\\s*$", RegexOption.IGNORE_CASE), "")
            .trim(' ', '-', '|', '–', ':')
        return cleaned.ifBlank { pageTitle.trim().take(50) }.ifBlank { "Série" }
    }
}
