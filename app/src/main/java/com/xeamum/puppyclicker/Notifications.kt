package com.xeamum.puppyclicker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    private const val CHANNEL_SERVICE = "service"
    private const val CHANNEL_CLICKS = "clicks"
    private const val CHANNEL_USED = "used_clicks"
    private const val CHANNEL_UPDATES = "updates"

    const val ID_SERVICE = 1
    private const val ID_CLICKS = 2
    private const val ID_UPDATE = 3
    private const val ID_USED = 4

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CHANNEL_CLICKS, "Clicks", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_USED, "Used clicks", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("Keeping clicks in sync")
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .build()

    @SuppressLint("MissingPermission")
    fun showClicks(context: Context, amount: Int, available: Int) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_CLICKS)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle(if (amount == 1) "You got a click!" else "You got $amount clicks!")
            .setContentText("$available available")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_CLICKS, notification)
    }

    @SuppressLint("MissingPermission")
    fun showUsedClicks(context: Context, amount: Int, available: Int) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_USED)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle(if (amount == 1) "Your partner used a click!" else "Your partner used $amount clicks!")
            .setContentText("$available available")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_USED, notification)
    }

    @SuppressLint("MissingPermission")
    fun showUpdate(context: Context, version: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("Update available")
            .setContentText("Tap to install version $version")
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_UPDATE, notification)
    }

    private fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
