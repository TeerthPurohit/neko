package dev.neko.app.capture

import android.provider.Telephony
import dev.neko.app.NekoApplication
import dev.neko.core.BankSms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Backfills recent bank notices already in the SMS inbox; live messages arrive through [BankSmsReceiver]. */
object SmsInbox {
    suspend fun import(app: NekoApplication, days: Int = 90, limit: Int = 500): Int = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val messages = ArrayList<BankSms>()
        app.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            Telephony.Sms.DATE + ">=?", arrayOf(since.toString()), Telephony.Sms.DATE + " DESC")?.use { cursor ->
            while (cursor.moveToNext() && messages.size < limit) {
                val body = cursor.getString(1).orEmpty()
                if (body.length <= 5000) messages += BankSms(cursor.getString(0).orEmpty(), body, cursor.getLong(2))
            }
        }
        // Oldest first so a pending notice is recorded before the posted notice that completes it.
        var captured = 0
        for (sms in messages.asReversed()) if (app.ledger.capture(sms) != null) captured++
        captured
    }
}
