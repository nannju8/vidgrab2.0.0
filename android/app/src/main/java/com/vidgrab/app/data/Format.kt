package com.vidgrab.app.data

import java.util.Locale

object Format {
    fun size(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val mb: Double = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.FRANCE, "%.2f Go", mb / 1024.0)
        else String.format(Locale.FRANCE, "%.1f Mo", mb)
    }

    fun speed(bps: Long): String {
        val mbs: Double = bps.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.FRANCE, "%.1f Mo/s", mbs)
    }

    fun eta(sec: Long): String {
        if (sec < 0L) return "—"
        val h: Long = sec / 3600L
        val m: Long = (sec % 3600L) / 60L
        val s: Long = sec % 60L
        return when {
            h > 0L -> String.format(Locale.FRANCE, "%dh %02dmin", h, m)
            m > 0L -> String.format(Locale.FRANCE, "%dmin %02ds", m, s)
            else -> String.format(Locale.FRANCE, "%ds", s)
        }
    }

    fun duration(ms: Long): String {
        val total: Long = ms / 1000L
        val h: Long = total / 3600L
        val m: Long = (total % 3600L) / 60L
        val s: Long = total % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
