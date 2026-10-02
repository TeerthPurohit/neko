package dev.neko.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.*
import dev.neko.app.NekoApplication
import dev.neko.core.*
import org.json.JSONObject

class BankSmsReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION)return
        val messages=Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if(messages.isEmpty())return
        val sender=messages.first().originatingAddress.orEmpty()
        val body=messages.joinToString(""){it.messageBody.orEmpty()}
        if(body.length>5000||SmsParser().parse(BankSms(sender,body,messages.first().timestampMillis))==null)return
        val app=context.applicationContext as NekoApplication
        // The inbox catch-up (every 15 minutes, and below) re-reads this message if encrypting or queueing it fails here.
        try {
            val payload=JSONObject().put("sender",sender).put("body",body).put("time",messages.first().timestampMillis).toString()
            val encrypted=app.settings.encrypt(payload,"pending_sms")
            val work=OneTimeWorkRequestBuilder<SmsCaptureWorker>().setInputData(workDataOf("encrypted" to encrypted)).build()
            WorkManager.getInstance(context).enqueue(work)
        } catch(_:Exception) { SmsCatchUpWorker.runNow(context) }
    }
}
