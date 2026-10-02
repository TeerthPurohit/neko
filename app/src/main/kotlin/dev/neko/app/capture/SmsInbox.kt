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

    suspend fun import(app: NekoApplication, days: Int = 90, limit: Int = 500): Int =
        importSince(app, System.currentTimeMillis() - days * 86_400_000L, limit).size

    /** Captures every parseable bank notice received since [sinceMs] and returns the transactions that were new or changed. */
    suspend fun importSince(app: NekoApplication, sinceMs: Long, limit: Int = 500): List<Transaction> = withContext(Dispatchers.IO) {
        if (!canRead(app)) return@withContext emptyList()
        val messages = ArrayList<BankSms>()
        app.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            Telephony.Sms.DATE + ">=?", arrayOf(sinceMs.toString()), Telephony.Sms.DATE + " DESC")?.use { cursor ->
            while (cursor.moveToNext() && messages.size < limit) {
                val body = cursor.getString(1).orEmpty()
                if (body.length <= 5000) messages += BankSms(cursor.getString(0).orEmpty(), body, cursor.getLong(2))
            }
        }
        // Oldest first so a pending notice is recorded before the posted notice that completes it.
        messages.asReversed().mapNotNull { app.ledger.capture(it) }
    }
}
