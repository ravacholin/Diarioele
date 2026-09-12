package com.capo.diarioclase.recording.service
import android.app.*
import android.content.Context
import androidx.core.app.NotificationCompat
object RecordingNotification {
 const val CHANNEL_ID="recording";const val ID=101
 fun createChannel(context:Context){val manager=context.getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,"Grabación de clase",NotificationManager.IMPORTANCE_LOW))}
 fun build(context:Context):Notification=NotificationCompat.Builder(context,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Diario de clase").setContentText("Grabando. Los bloques cerrados están guardados.").setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
}

