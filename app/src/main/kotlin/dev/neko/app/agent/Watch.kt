package dev.neko.app.agent

import android.content.Context
import androidx.work.*
import dev.neko.app.NekoApplication
import dev.neko.core.*
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Neko's built-in jobs. They run on the phone, so they work while Neko is closed and need neither the backend nor cloud AI. */
object Watch {
    /** Tells the user about new captures (what Neko filed itself, what looks unusual, what needs them), then checks budgets. */
    fun afterCapture(app: NekoApplication, found: List<Transaction>) {
        if (found.isEmpty() || app.settings.paused) return
        val history = app.ledger.db.transactions()
        val unusual = found.mapNotNull { tx -> Autopilot.unusual(tx, history)?.let { tx to it } }
        unusual.forEach { (tx, why) -> Notifications.show(app, "unusual:" + tx.id, "This payment looks unusual", "${Money.rupees(tx.amountPaise)} to ${tx.merchant} is $why. Is it right?", tx.id) }
        val rest = found - unusual.map { it.first }.toSet()
        val review = rest.filter { it.review == ReviewStatus.DRAFT }
        val filed = rest - review.toSet()
        if (review.isNotEmpty()) Notifications.show(app, "transaction:" + review.last().id,
            if (review.size == 1) "A transaction needs a quick look" else "${review.size} transactions need a quick look",
            "Neko wasn't sure about ${if (review.size == 1) "this one" else "these"}. Confirm the details.", review.last().id)
        if (filed.isNotEmpty()) Notifications.show(app, "filed:" + filed.last().id,
            if (filed.size == 1) filed.single().let { "Filed ${Money.rupees(it.amountPaise)} ${if (it.direction == Direction.DEBIT) "to" else "from"} ${it.merchant} as ${it.category.label}" } else "Neko filed ${filed.size} payments",
            "Tap to check. You can undo it in Activity.", filed.last().id)
        budgetAlerts(app)
    }

    fun budgetAlerts(app: NekoApplication) {
        val s = app.settings
        val month = YearMonth.now(Ledger.india)
        val monthBudget = if (s.get("month_budget_month") == month.toString()) s.get("month_budget_paise", "0").toLongOrNull() ?: 0 else 0
        val sent = s.get("budget_alerts_sent").split('\n').filter { it.startsWith("$month|") }.toSet()
        val alerts = Autopilot.budgetAlerts(app.ledger.db.transactions(), app.ledger.db.budgets(), monthBudget, month, sent)
        if (alerts.isEmpty()) return
        alerts.forEach { (key, text) -> Notifications.show(app, "budget:$key", "Budget check", text) }
        s.put("budget_alerts_sent", (sent + alerts.map { it.first }).joinToString("\n"))
    }

    /** Schedules the evening digest for about 9 pm IST every day. */
    fun scheduleDigest(context: Context) {
        val now = ZonedDateTime.now(Ledger.india)
        val next = now.toLocalDate().atTime(21, 0).atZone(Ledger.india).let { if (it.isAfter(now)) it else it.plusDays(1) }
        val request = PeriodicWorkRequestBuilder<DigestWorker>(1, TimeUnit.DAYS).setInitialDelay(java.time.Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("neko_daily_digest", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NekoApplication
        if (app.settings.paused) return Result.success()
        return try {
            val today = LocalDate.now(Ledger.india)
            // WorkManager may run a periodic job late or twice; one digest per day.
            if (app.settings.get("last_digest") == today.toString()) return Result.success()
            val month = YearMonth.from(today)
            val budget = if (app.settings.get("month_budget_month") == month.toString()) app.settings.get("month_budget_paise", "0").toLongOrNull() ?: 0 else 0
            Autopilot.dailyDigest(app.ledger.db.transactions(), today, budget)?.let { Notifications.show(app, "digest:$today", "Your day with Neko", it) }
            app.settings.put("last_digest", today.toString())
            Result.success()
        } catch (error: CancellationException) { throw error } catch (_: Exception) { Result.success() }
    }
}
