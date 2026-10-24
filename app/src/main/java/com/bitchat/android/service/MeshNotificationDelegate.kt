package com.bitchat.android.service

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.ui.NotificationTextUtils

/**
 * Headless [MeshDelegate] that lives inside [MeshForegroundService].
 *
 * Purpose:
 *   When the UI is closed, [com.bitchat.android.mesh.UnifiedMeshService] points
 *   its `delegate` at an instance of this class instead of the UI-bound
 *   [com.bitchat.android.ui.ChatViewModel]. This lets the foreground service keep
 *   showing notifications for peer connects, private messages, and mesh mentions
 *   while keeping the mesh pipeline fully alive.
 *
 * What it does NOT do:
 *   - Geohash/Nostr notifications — those are Nostr-driven and managed by
 *     [com.bitchat.android.ui.GeohashViewModel] while the UI is open.
 *   - Channel message notifications — intentional; classic channels are quiet
 *     unless the user is mentioned (which goes through [didReceiveMessage] and
 *     [checkAndTriggerMentionNotification]).
 *   - Channel decryption — UI-only.
 *
 * Thread-safety:
 *   All public entry points are safe to call from any thread; the underlying
 *   [NotificationManager] is thread-safe and [AppStateStore] is synchronized.
 */
class MeshNotificationDelegate(context: Context) : MeshDelegate {

    companion object {
        private const val TAG = "MeshNotificationDelegate"
    }

    private val appContext: Context = context.applicationContext
    private val notificationManager: NotificationManager = NotificationManager.getInstance(appContext)

    // Cache nickname — DataManager's loadNickname() reads SharedPreferences synchronously.
    @Volatile
    private var cachedNickname: String? = null

    // Best-effort context for favorite lookups and message retention.
    private val dataManager: DataManager by lazy { DataManager(appContext) }

    /**
     * Called by [MeshForegroundService] right after installing this delegate.
     *
     * Resets the "currently viewing" state so notifications are not suppressed
     * by stale values left over from the previous UI session.
     */
    fun attach() {
        runCatching {
            notificationManager.setCurrentPrivateChatPeer(null)
            notificationManager.setCurrentGeohash(null)
            Log.d(TAG, "Attached — cleared current chat/geohash view state")
        }.onFailure {
            Log.w(TAG, "attach() failed: ${it.message}")
        }
    }

    /**
     * Called by [MeshForegroundService] before removing this delegate (e.g. on
     * service stop, or when the UI reclaims the delegate on resume). No pending
     * state needs to be flushed; this is a hook for future cleanup.
     */
    fun detach() {
        Log.d(TAG, "Detached")
    }

    // ==========================================================================
    // MeshDelegate — core events
    // ==========================================================================

    override fun didReceiveMessage(message: BitchatMessage) {
        runCatching {
            if (message.isPrivate) {
                handleIncomingPrivateMessage(message)
                return
            }
            if (message.channel != null) {
                // Channel message — do not notify individually. Mention path is
                // covered below via checkAndTriggerMentionNotification() which
                // also runs for channel-scoped messages if the user is mentioned.
                handleChannelMessageMentions(message)
                return
            }
            // Public mesh message — check for mention
            handlePublicMeshMention(message)
        }.onFailure {
            Log.w(TAG, "didReceiveMessage failed: ${it.message}")
        }
    }

    override fun didUpdatePeerList(peers: List<String>) {
        // AppStateStore is already updated by MeshCore per transport; nothing to do.
    }

    override fun didPeerConnect(peerID: String, nickname: String) {
        runCatching {
            notificationManager.showPeerConnectedNotification(peerID, nickname)
        }.onFailure {
            Log.w(TAG, "didPeerConnect failed: ${it.message}")
        }
    }

    override fun didPeerDisconnect(peerID: String) {
        runCatching {
            notificationManager.clearPeerConnectedNotification(peerID)
        }.onFailure {
            Log.w(TAG, "didPeerDisconnect failed: ${it.message}")
        }
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        // Channel leave: no notification needed when UI is closed.
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        runCatching {
            AppStateStore.updatePrivateMessageStatus(
                messageID,
                com.bitchat.android.model.DeliveryStatus.Delivered(recipientPeerID, java.util.Date())
            )
        }.onFailure {
            Log.w(TAG, "didReceiveDeliveryAck failed: ${it.message}")
        }
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        runCatching {
            AppStateStore.updatePrivateMessageStatus(
                messageID,
                com.bitchat.android.model.DeliveryStatus.Read(recipientPeerID, java.util.Date())
            )
        }.onFailure {
            Log.w(TAG, "didReceiveReadReceipt failed: ${it.message}")
        }
    }

    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        // Channel decryption is a UI-only concern; the headless layer doesn't
        // need to decrypt for notification purposes (channel messages are public).
        return null
    }

    override fun getNickname(): String? {
        cachedNickname?.let { return it }
        return runCatching {
            val nick = dataManager.loadNickname()
            cachedNickname = nick
            nick
        }.getOrNull()
    }

    override fun isFavorite(peerID: String): Boolean {
        return runCatching {
            // Fast path: canonical conversation id is a contact_* fingerprint.
            val canonical = ContactDirectory.canonicalConversationId(peerID)
            FavoritesPersistenceService.shared.getFavoriteStatus(canonical)?.isFavorite == true
        }.getOrDefault(false)
    }

    // ==========================================================================
    // Internal helpers
    // ==========================================================================

    private fun handleIncomingPrivateMessage(message: BitchatMessage) {
        val senderPeerID = message.senderPeerID ?: return
        val senderNickname = resolveSenderNickname(message, senderPeerID)
        val preview = runCatching {
            NotificationTextUtils.buildPrivateMessagePreview(message)
        }.getOrDefault(message.content)

        Log.d(TAG, "Private msg from $senderNickname ($senderPeerID): ${preview.take(40)}")
        notificationManager.showPrivateMessageNotification(
            senderPeerID = senderPeerID,
            senderNickname = senderNickname,
            messageContent = preview
        )
    }

    private fun handleChannelMessageMentions(message: BitchatMessage) {
        val channel = message.channel ?: return

        // Geohash channels are Nostr-driven; when the app is closed those
        // subscriptions are inactive. Skip to avoid misleading notifications.
        if (channel.startsWith("geo:")) return

        // Classic channel (#name): only notify if the user is mentioned.
        val myNick = getNickname() ?: return
        if (!contentMentions(content = message.content, nickname = myNick)) return

        val senderNickname = resolveSenderNickname(message, message.senderPeerID)
        notificationManager.showMeshMentionNotification(
            senderNickname = senderNickname,
            messageContent = message.content,
            senderPeerID = message.senderPeerID
        )
    }

    private fun handlePublicMeshMention(message: BitchatMessage) {
        val myNick = getNickname() ?: return
        if (!contentMentions(content = message.content, nickname = myNick)) return

        val senderNickname = resolveSenderNickname(message, message.senderPeerID)
        notificationManager.showMeshMentionNotification(
            senderNickname = senderNickname,
            messageContent = message.content,
            senderPeerID = message.senderPeerID
        )
    }

    /**
     * Best-effort sender name. Uses the peer name if present; falls back to the
     * short peer ID so the notification is never blank.
     */
    private fun resolveSenderNickname(message: BitchatMessage, senderPeerID: String?): String {
        val fromMessage = message.sender.takeIf { it.isNotBlank() && it != senderPeerID }
        if (fromMessage != null) return fromMessage
        return senderPeerID?.take(8)?.takeIf { it.isNotBlank() } ?: "Someone"
    }

    /**
     * Mirrors [com.bitchat.android.ui.MeshDelegateHandler]'s mention rule:
     * @username word-boundary match, case-insensitive.
     */
    private fun contentMentions(content: String, nickname: String): Boolean {
        if (content.isEmpty() || nickname.isEmpty()) return false
        val pattern = Regex("@([\\p{L}0-9_]+)")
        return pattern.findAll(content).any { match ->
            match.groupValues[1].equals(nickname, ignoreCase = true)
        }
    }

    // Suppress unused warning for BitchatMessageType while keeping the import
    // (used indirectly via NotificationTextUtils when the preview is built).
    @Suppress("unused")
    private val messageTypeRef: Class<BitchatMessageType> = BitchatMessageType::class.java
}
