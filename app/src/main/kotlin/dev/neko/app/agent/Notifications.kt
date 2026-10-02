package dev.neko.app.agent

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.neko.app.MainActivity
import dev.neko.app.R

object Notifications {
    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel("neko_capture","Transaction reviews",NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("neko_agent","Neko check-ins",NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }
    fun show(context: Context,id:String,title:String,body:String,transactionId:String?=null) {
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val intent=Intent(context,MainActivity::class.java).putExtra("transaction_id",transactionId).putExtra("open_agent",transactionId==null).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(context,if(transactionId!=null)"neko_capture"else"neko_agent").setSmallIcon(R.drawable.ic_neko).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(pending).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(Notification.Action.Builder(null,if(transactionId!=null)"Review"else"Open Neko",pending).build()).build()
        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(),notification)
    }
}
