package com.kuva.vpn.ui.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kuva.vpn.R
import com.kuva.vpn.ui.MainActivity
import com.kuva.vpn.core.service.KuvaVpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent foreground notification with status + live speed. */
@Singleton
class VpnNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_status),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notif_channel_status_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun base(b: NotificationCompat.Builder): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, KuvaVpnService::class.java)
                .setAction(KuvaVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return b
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.notif_stop), stop)
            .build()
    }

    fun notifyConnecting(name: String?) {
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.notif_connecting))
            .setContentText(name ?: "")
        nm.notify(ID, base(b))
    }

    fun notifyConnected(name: String?) {
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.notif_connected, name ?: ""))
            .setContentText("")
        nm.notify(ID, base(b))
    }

    fun updateSpeed(up: String, down: String) {
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.notif_speed, up, down))
            .setContentText("")
        nm.notify(ID, base(b))
    }

    fun notifyDisconnected() {
        nm.cancel(ID)
    }

    fun notifyError(message: String) {
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(context.getString(R.string.state_error))
            .setContentText(message)
            .setAutoCancel(true)
        nm.notify(ID + 1, base(b))
    }

    fun cancel() {
        nm.cancel(ID)
        nm.cancel(ID + 1)
    }

    companion object {
        private const val CHANNEL = "kuva_vpn_status"
        private const val ID = 42

        /** Service-internal notification id used with startForeground(). */
        const val FOREGROUND_ID = 42
    }

    /**
     * Builds the notification the SERVICE posts with startForeground().
     * Kept here so the service and the notifier share one channel/id.
     */
    fun buildForegroundNotification(title: String = context.getString(R.string.notif_connecting)): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(title)
            .setContentText("")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
