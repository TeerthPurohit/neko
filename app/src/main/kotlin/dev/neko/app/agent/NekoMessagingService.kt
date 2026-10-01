package dev.neko.app.agent

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.neko.app.NekoApplication

class NekoMessagingService:FirebaseMessagingService() {
    override fun onNewToken(token:String) { (application as NekoApplication).settings.put("push_token",token);AgentWork.syncNow(this) }
    override fun onMessageReceived(message:RemoteMessage) {
        Notifications.show(this,message.data["activity_id"]?:"neko_update","Neko has an update","Open Neko to see your private update.")
        AgentWork.syncNow(this)
    }
}
