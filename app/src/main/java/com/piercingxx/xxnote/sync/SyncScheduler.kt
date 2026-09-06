package com.piercingxx.xxnote.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The one place periodic sync gets scheduled (hardening #3a, §4.4).
 * [SyncWorker.enqueuePeriodic] delegates here, so the unique name, policy,
 * and period have exactly one definition that the expedited path can never
 * drift from.
 *
 * [ensurePeriodic] is idempotent on purpose: app start (MainActivity's
 * credential check) and Setup completion both call it freely, and KEEP makes
 * every call after the first a no-op rather than a schedule reset.
 */
object SyncScheduler {

    /**
     * Enqueues the 15-minute periodic pass unless an identical one is already
     * registered. Doze defers it; that is acceptable and stated (§4.4).
     * No network constraint: ACCESS_NETWORK_STATE is not declared, so
     * WorkManager cannot observe connectivity; the engine already fails
     * closed when the host is unreachable.
     */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal const val UNIQUE_PERIODIC = "xx-note-sync-periodic"
    private const val PERIOD_MINUTES = 15L // WorkManager's floor (§4.4)
}
