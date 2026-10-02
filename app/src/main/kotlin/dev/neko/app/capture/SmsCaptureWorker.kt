package dev.neko.app.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.neko.app.NekoApplication
import dev.neko.app.agent.*
import dev.neko.core.BankSms
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class SmsCaptureWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as NekoApplication
        return try {
            val data=JSONObject(app.settings.decrypt(inputData.getString("encrypted")?:return Result.failure(),"pending_sms"))
            val changesBefore=app.ledger.changes.value
            val tx=app.ledger.capture(BankSms(data.getString("sender"),data.getString("body"),data.getLong("time")))
            if(tx!=null)Notifications.show(applicationContext,"transaction:"+tx.id,"A transaction is ready to review","Neko captured a bank notice. Confirm its details.",tx.id)
            // capture returns null when a notice was merged into a payment you made or linked as an own-account transfer; the ledger still changed, so sync.
            if(tx!=null||app.ledger.changes.value!=changesBefore)AgentWork.syncNow(applicationContext)
            Result.success()
        } catch(error:CancellationException){throw error}catch(_:Exception){if(runAttemptCount<3)Result.retry()else Result.failure()}
    }
}
