package dev.neko.app.capture

import android.content.Context
import androidx.work.*
import dev.neko.app.NekoApplication
import dev.neko.app.agent.AgentWork
import dev.neko.app.agent.Watch
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Runs about every 15 minutes, even when Neko is closed, and notes down any bank SMS the live receiver missed. */
class SmsCatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NekoApplication
        if (app.settings.paused || !SmsInbox.canRead(app)) return Result.success()
        return try {
            val now = System.currentTimeMillis()
            // First run looks back a week; later runs overlap by an hour so a slow delivery is never skipped.
            val since = app.settings.get("last_sms_scan", "0").toLong().let { if (it == 0L) now - 7 * 86_400_000L else it - 3_600_000L }
            val changesBefore = app.ledger.changes.value
            val scan = SmsInbox.importSince(app, since)
            val found = scan.found
            // When the inbox held more than one run can read, the next run resumes where this one stopped (the hour overlap is added back above).
            app.settings.put("last_sms_scan", (scan.scannedThrough?.let { it + 3_600_000L } ?: now).toString())
            if (scan.scannedThrough != null) runNow(applicationContext)
            Watch.afterCapture(app, found)
            // A notice can change the ledger without being new (merged into a payment you made, or linked as a transfer), and the agent should hear about that too.
            if (found.isNotEmpty() || app.ledger.changes.value != changesBefore) AgentWork.syncNow(applicationContext)
            Result.success()
        } catch (error: CancellationException) { throw error } catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.success() }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsCatchUpWorker>(15, TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("neko_sms_catch_up", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("neko_sms_catch_up_now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SmsCatchUpWorker>().build())
        }
    }
}
