package dev.neko.app.agent

import android.content.Context
import androidx.work.*
import dev.neko.app.NekoApplication
import kotlinx.coroutines.CancellationException

object AgentWork {
    fun cancelPeriodic(context: Context) {
        // Remove the old polling schedule from upgraded installs. SMS and explicit app events sync on demand.
        WorkManager.getInstance(context).cancelUniqueWork("neko_agent_sync")
    }
    fun syncNow(context: Context) {
        val request=OneTimeWorkRequestBuilder<AgentSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("neko_sync_now",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
}
class AgentSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as NekoApplication
        if(app.settings.paused)return Result.success()
        return try {
            val token=app.settings.get("push_token")
            if(app.settings.deviceToken.isNotBlank()&&token.isNotBlank())app.agent.api("settings","PATCH",org.json.JSONObject().put("push_token",token))
            val result=app.agent.sync()
            if(result!=null) {
                val list=result.getJSONArray("activity");val notified=app.settings.get("last_notified","0").toLong()
                var latest=notified
                for(i in 0 until list.length()) {
                    val item=list.getJSONObject(i);val date=item.getLong("created_at")
                    if(date>notified){ Notifications.show(applicationContext,item.getString("id"),"Neko has an update","Open Neko to see your private update.");latest=maxOf(latest,date) }
                }
                app.settings.put("last_notified",latest.toString())
            }
            if(app.settings.splitwiseKey.isNotBlank())app.splitwise.refresh()
            Result.success()
        }catch(error:CancellationException){throw error}catch(_:Exception){if(runAttemptCount<4)Result.retry()else Result.failure()}
    }
}
