package com.bitchat.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Custom notification manager for bitchat-Studio.
 *
 * NOTE: Renamed from `NotificationManager` → `AppNotificationManager`
 * to avoid clash with android.app.NotificationManager.
 */
class AppNotificationManager private constructor(
    private val appContext: Context
) {

    // Explicitly typed as Android's system NotificationManager
    private val systemNotificationManager: AndroidNotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager

    private val notificationManagerCompat: NotificationManagerCompat =
        NotificationManagerCompat.from(appContext)

    init {
        createNotificationChannels()
    }

    // ─────────────────────────────────────────────────────────────
    // Notification Channels
    // ─────────────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_PEER_CONNECTED,
                    "Peer Connected",
                    AndroidNotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications when a peer connects"
                },
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    "Messages",
                    AndroidNotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming message notifications"
                },
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "Background Service",
                    AndroidNotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent mesh service notification"
                }
            )
            channels.forEach { channel ->
                systemNotificationManager.createNotificationChannel(channel)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Show a notification for a connected peer.
     */
    fun showPeerConnectedNotification(peerId: String, peerName: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_PEER_CONNECTED)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Peer Connected")
            .setContentText(peerName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            notificationManagerCompat.notify(peerId.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted — ignore silently
        }
    }

    /**
     * Clear the "peer connected" notification for a specific peer.
     *
     * This method was MISSING and caused:
     *   MainActivity.kt:890 → Unresolved reference 'clearPeerConnectedNotification'
     */
    fun clearPeerConnectedNotification(peerId: String) {
        notificationManagerCompat.cancel(peerId.hashCode())
    }

    /**
     * Show a message notification.
     */
    fun showMessageNotification(peerId: String, peerName: String, message: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(peerName)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            notificationManagerCompat.notify(peerId.hashCode() + 1000, notification)
        } catch (_: SecurityException) {
            // ignore
        }
    }

    /**
     * Clear a message notification for a peer.
     */
    fun clearMessageNotification(peerId: String) {
        notificationManagerCompat.cancel(peerId.hashCode() + 1000)
    }

    /**
     * Clear ALL notifications posted by this app.
     */
    fun clearAllNotifications() {
        notificationManagerCompat.cancelAll()
    }

    /**
     * Show or update the persistent foreground-service notification.
     */
    fun showServiceNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            notificationManagerCompat.notify(SERVICE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // ignore
        }
    }

    /**
     * Clear the foreground-service notification.
     */
    fun clearServiceNotification() {
        notificationManagerCompat.cancel(SERVICE_NOTIFICATION_ID)
    }

    // ─────────────────────────────────────────────────────────────
    // Singleton
    // ─────────────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_PEER_CONNECTED = "bitchat_peer_connected"
        const val CHANNEL_MESSAGES = "bitchat_messages"
        const val CHANNEL_SERVICE = "bitchat_service"
        private const val SERVICE_NOTIFICATION_ID = 9001

        @Volatile
        private var INSTANCE: AppNotificationManager? = null

        /**
         * Explicit return type `AppNotificationManager` fixes:
         *   NotificationManager.kt:74 → Return type mismatch: expected 'NotificationManager', actual 'Any'
         */
        fun getInstance(context: Context): AppNotificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppNotificationManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
