package com.yunian.ai.feature.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yunian.ai.feature.notification.R

object NotificationHelper {
    private const val CHANNEL_ID = "companion_messages"
    private const val CHANNEL_NAME = "虚拟恋人消息"
    private const val CHANNEL_DESCRIPTION = "接收虚拟恋人的主动消息通知"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                enableVibration(true)

                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showCompanionMessageNotification(
        context: Context,
        companionName: String,
        message: String,
        companionId: Long
    ) {
        createNotificationChannel(context)

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("companion_id", companionId)
            putExtra("open_chat", true)
        } ?: Intent(Intent.ACTION_MAIN).apply {
            `package` = context.packageName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("companion_id", companionId)
            putExtra("open_chat", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            companionId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(companionName)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        with(NotificationManagerCompat.from(context)) {
            try {

                val notificationId = ((companionId.toInt() and 0xFFFF) xor (System.currentTimeMillis().toInt() and 0x7FFFFFFF))
                notify(notificationId, notification)
            } catch (e: SecurityException) {

            } catch (e: Exception) {

            }
        }
    }
}
