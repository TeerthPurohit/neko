package dev.neko.app

import android.app.Application
import dev.neko.app.data.*

class NekoApplication: Application() {
    lateinit var settings: SecureSettings; private set
    lateinit var ledger: LedgerRepository; private set
    lateinit var agent: AgentRepository; private set
    lateinit var splitwise: SplitwiseRepository; private set
    override fun onCreate() {
        super.onCreate()
        settings=SecureSettings(this)
        ledger=LedgerRepository(NekoDatabase(this),settings)
        val network=Network();agent=AgentRepository(ledger,network);splitwise=SplitwiseRepository(ledger,network)
        dev.neko.app.agent.Notifications.createChannels(this)
        dev.neko.app.agent.AgentWork.cancelPeriodic(this)
        configureFirebase()
    }
    fun configureFirebase() {
        val appId=settings.get("firebase_app_id");val project=settings.get("firebase_project_id");val apiKey=settings.get("firebase_api_key");val sender=settings.get("firebase_sender_id")
        if(com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
            if(appId.isBlank()||project.isBlank()||apiKey.isBlank()||sender.isBlank())return
            com.google.firebase.FirebaseApp.initializeApp(this,com.google.firebase.FirebaseOptions.Builder().setApplicationId(appId).setProjectId(project).setApiKey(apiKey).setGcmSenderId(sender).build())
        }
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> settings.put("push_token",token);dev.neko.app.agent.AgentWork.syncNow(this) }
    }
}
