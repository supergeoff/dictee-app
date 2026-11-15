package top.supergeoff.dictee

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ProcessingNotifications(context: Context) {
    private val context = context.applicationContext

    fun show(notice: ProcessingResultNotice) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Résultats des traitements", NotificationManager.IMPORTANCE_DEFAULT))
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(notice.title).setContentText(notice.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
                .setAutoCancel(true).build()
            manager.notify(notice.taskId, 0, notification)
        } catch (_: SecurityException) { }
    }

    private companion object { const val CHANNEL = "processing-results" }
}
