package dev.neko.app.capture

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Telephony
import dev.neko.app.NekoApplication
import dev.neko.core.BankSms
import dev.neko.core.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads bank notices already in the SMS inbox. Live messages arrive through [BankSmsReceiver]; this is the safety net for
 * messages the system delivered while Neko could not run (battery savers, force-stop, reboot) and the first-run backfill.
 * Capturing is idempotent, so scanning the same message twice never creates a second transaction.
 */
object SmsInbox {
    fun canRead(app: NekoApplication): Boolean = app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun import(app: NekoApplication, days: Int = 90): Int =
        importSince(app, System.currentTimeMillis() - days * 86_400_000L).found.size

    /** [found]: transactions that were new or changed. [scannedThrough]: inbox time of the newest message read, or null when every message since the start was read. */
    class Scan(val found: List<Transaction>, val scannedThrough: Long?)

    /**
     * Captures parseable bank notices received since [sinceMs], oldest first and [pageSize] at a time, so a pending notice is recorded before the
     * posted notice that completes it. Stops after [maxPages]; the caller resumes from [Scan.scannedThrough] instead of skipping what was not read.
     */
    suspend fun importSince(app: NekoApplication, sinceMs: Long, pageSize: Int = 500, maxPages: Int = 20): Scan = withContext(Dispatchers.IO) {
        if (!canRead(app)) return@withContext Scan(emptyList(), null)
        val found = ArrayList<Transaction>()
        var from = sinceMs
        repeat(maxPages) {
            val messages = ArrayList<BankSms>()
            var newest = from
            var rows = 0
            app.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
                Telephony.Sms.DATE + ">=?", arrayOf(from.toString()), Telephony.Sms.DATE + " ASC")?.use { cursor ->
                while (rows < pageSize && cursor.moveToNext()) {
                    rows++
                    newest = cursor.getLong(2)
                    val body = cursor.getString(1).orEmpty()
                    // DATE_SENT is the bank's timestamp from the message itself, the same one BankSmsReceiver sees, so a notice read here and live
                    // gets one fingerprint. Some phones store 0; the inbox time is the fallback.
                    if (body.length <= 5000) messages += BankSms(cursor.getString(0).orEmpty(), body, cursor.getLong(3).takeIf { it > 0 } ?: cursor.getLong(2))
                }
            }
            found += messages.mapNotNull { app.ledger.capture(it) }
            // A short page is the end. A full page resumes at its newest time (inclusive); re-reading those few messages is harmless because capture is idempotent.
            if (rows < pageSize || newest == from) return@withContext Scan(found, null)
            from = newest
        }
        Scan(found, from)
    }
}
