package com.xeamum.puppyclicker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Offline-first sync. Each phone keeps its own copy of the two grow-only totals and the server keeps the max,
 * so clicks made offline are sent later, retries can't double count, and a wiped server is refilled by the phones.
 */
object Sync {
    private const val WORK_NAME = "sync"

    /** Shows the saved totals right away, before the server answers. */
    @Synchronized
    fun showLocal(context: Context) {
        publish(Prefs(context))
    }

    @Synchronized
    fun addClicks(context: Context, amount: Int) {
        val prefs = Prefs(context)
        prefs.localReceived += amount
        ClickRepository.pending.value += amount
        publish(prefs)
    }

    /** Returns false if there are no clicks left. */
    @Synchronized
    fun useClick(context: Context): Boolean {
        val prefs = Prefs(context)
        if (prefs.localReceived - prefs.localUsed <= 0) return false
        prefs.localUsed += 1
        ClickRepository.pending.value += 1
        publish(prefs)
        return true
    }

    @Synchronized
    fun merge(context: Context, server: ClickState): ClickState {
        val prefs = Prefs(context)
        prefs.localReceived = maxOf(prefs.localReceived, server.totalReceived)
        prefs.localUsed = maxOf(prefs.localUsed, server.totalUsed)
        ClickRepository.pending.value = when (prefs.role) {
            Role.SENDER -> prefs.localReceived - server.totalReceived
            else -> prefs.localUsed - server.totalUsed
        }
        return publish(prefs)
    }

    suspend fun push(context: Context): ClickState {
        val prefs = Prefs(context)
        val total = if (prefs.role == Role.SENDER) prefs.localReceived else prefs.localUsed
        return merge(context, prefs.api().sync(total))
    }

    /** Returns true if the server got it now, false if it's queued to send once back online. */
    suspend fun pushOrQueue(context: Context): Boolean =
        try {
            push(context)
            true
        } catch (e: IOException) {
            if (e is ApiException && e.isClientError) throw e
            schedule(context)
            false
        }

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE is safe: push always sends the latest totals.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun publish(prefs: Prefs): ClickState {
        val received = prefs.localReceived
        val used = prefs.localUsed
        return ClickState(maxOf(0, received - used), received, used).also { ClickRepository.state.value = it }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Prefs(applicationContext).isConfigured) return Result.success()
        return try {
            Sync.push(applicationContext)
            Result.success()
        } catch (e: ApiException) {
            if (e.isClientError) Result.failure() else Result.retry()
        } catch (e: IOException) {
            Result.retry()
        }
    }
}
