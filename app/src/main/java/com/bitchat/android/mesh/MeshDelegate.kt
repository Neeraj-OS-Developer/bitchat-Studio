package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage

/**
 * Shared mesh delegate interface for transport-agnostic callbacks.
 *
 * Implementations:
 *   - [com.bitchat.android.ui.ChatViewModel] — receives callbacks while UI is visible.
 *   - [com.bitchat.android.mesh.UnifiedMeshService] — fans events out to the
 *     active top-level delegate (either UI or the headless notification delegate).
 *   - [com.bitchat.android.service.MeshNotificationDelegate] — receives callbacks
 *     while the app UI is closed (owned by [com.bitchat.android.service.MeshForegroundService]).
 *
 * Peer connect/disconnect callbacks:
 *   [didPeerConnect] and [didPeerDisconnect] are emitted after a peer's ANNOUNCE
 *   has been processed (so [nickname] is known) and after a peer is removed from
 *   the mesh (stale timeout, LEAVE packet, or connection drop). Unlike
 *   [didUpdatePeerList], they fire once per actual transition rather than
 *   providing the full snapshot, which is what the notification layer needs.
 */
interface MeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {}
    fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {}

    /**
     * Called once when a peer has fully joined the mesh — i.e. after a verified
     * ANNOUNCE has been processed and the peer has a known nickname.
     *
     * Default no-op so existing implementations remain source-compatible.
     * UI callbacks may refresh the sidebar; the headless
     * [com.bitchat.android.service.MeshNotificationDelegate] shows a low-priority
     * "X connected" notification when the app is in the background.
     *
     * @param peerID 8-byte hex mesh peer ID (16 hex chars)
     * @param nickname The nickname announced by the peer (never blank; falls back to peerID prefix)
     */
    fun didPeerConnect(peerID: String, nickname: String) {}

    /**
     * Called once when a peer leaves the mesh — stale timeout, LEAVE packet, or
     * connection drop with all transports.
     *
     * Default no-op so existing implementations remain source-compatible.
     *
     * @param peerID 8-byte hex mesh peer ID (16 hex chars)
     */
    fun didPeerDisconnect(peerID: String) {}

    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
}
