package com.vidgrab.app.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Worker périodique (15 min) : relance les téléchargements interrompus, de façon idempotente. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Idempotent : si un téléchargement est déjà actif dans DownloadCenter, on ne fait rien.
        if (DownloadCenter.isActive()) return Result.success()
        DownloadCenter.resumeInterruptedIfIdle()
        return Result.success()
    }

    companion object {
        private const val NAME = "vidgrab_resume"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DownloadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
