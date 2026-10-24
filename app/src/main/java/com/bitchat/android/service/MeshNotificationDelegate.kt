package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.ui.AppNotificationManager

/**
 * Delegate that handles all mesh-related notifications.
 *
 * NOTE: Previously used `com.bitchat.android.ui.NotificationManager` which
 * clashed with android.app.NotificationManager. Now uses AppNotificationManager.
 */
class MeshNotificationDelegate(context: Context) {

    private val appContext: Context = context.applicationContext

    // ─────────────────────────────────────────────────────────────
    // FIX: line 44 — use custom manager via singleton, NOT getSystemService()
    // ─────────────────────────────────────────────────────────────
    private val notificationManager: AppNotificationManager =
        AppNotificationManager.getInstance(appContext)

    // ─────────────────────────────────────────────────────────────
    // Peer connected / disconnected events
    // ─────────────────────────────────────────────────────────────

    fun onPeerConnected(peerId: String, peerName: String) {
        notificationManager.showPeerConnectedNotification(peerId, peerName)
    }

    fun onPeerDisconnected(peerId: String) {
        notificationManager.clearPeerConnectedNotification(peerId)
    }

    // ─────────────────────────────────────────────────────────────
    // Message events
    // ─────────────────────────────────────────────────────────────

    fun onMessageReceived(peerId: String, peerName: String, message: String) {
        notificationManager.showMessageNotification(peerId, peerName, message)
    }

    fun onMessagesRead(peerId: String) {
        notificationManager.clearMessageNotification(peerId)
    }

    // ─────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────

    fun onServiceStarted(title: String, text: String) {
        notificationManager.showServiceNotification(title, text)
    }

    fun onServiceStopped() {
        notificationManager.clearServiceNotification()
    }

    // ─────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────

    fun clearAll() {
        notificationManager.clearAllNotifications()
    }
}
