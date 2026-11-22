package com.vidgrab.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vidgrab.app.MainActivity
import com.vidgrab.app.R
import com.vidgrab.app.data.DlStatus
import com.vidgrab.app.data.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * ForegroundService (dataSync) : notification de progression + PARTIAL_WAKE_LOCK + WifiLock (HIGH_PERF).
 * Les verrous sont revérifiés à chaque itération de la boucle active.
 */
@Suppress("DEPRECATION")
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wake: PowerManager.WakeLock
    private lateinit var wifi: WifiManager.WifiLock
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
        )
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidgrab:download").apply { setReferenceCounted(false) }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vidgrab:wifi").apply { setReferenceCounted(false) }

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(0, 0L, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        scope.launch {
            DownloadCenter.tasks.collect { list ->
                val active = list.filter {
                    it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED || it.status == DlStatus.MERGING
                }
                if (active.isEmpty()) {
                    releaseLocks()
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                holdLocks()
                val speed: Long = active.sumOf { it.speedBps }
                val total: Long = active.sumOf { it.total }
                val done: Long = active.sumOf { it.downloaded }
                val pct: Int = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                nm.notify(NOTIF_ID, buildNotification(active.size, speed, pct))
                delay(1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) DownloadCenter.pauseAll(interrupted = false)
        return START_NOT_STICKY
    }

    /** Android 15 : limite de 6 h pour les services dataSync. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadCenter.pauseAll(interrupted = true)
        releaseLocks()
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun holdLocks() {
        if (!wake.isHeld) wake.acquire(6L * 60L * 60L * 1000L)
        if (!wifi.isHeld) wifi.acquire()
    }

    private fun releaseLocks() {
        runCatching { if (wake.isHeld) wake.release() }
        runCatching { if (wifi.isHeld) wifi.release() }
    }

    private fun buildNotification(count: Int, speed: Long, pct: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (count <= 1) "Téléchargement en cours" else "$count téléchargements en cours"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(if (count == 0) "Préparation…" else "$pct % • ${Format.speed(speed)}")
            .setProgress(100, pct, count == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Tout mettre en pause", pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 4242
        const val ACTION_PAUSE_ALL = "com.vidgrab.app.PAUSE_ALL"
    }
}
