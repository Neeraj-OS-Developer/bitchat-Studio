package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager

/**
 * Process-wide holder to share a single BluetoothMeshService instance
 * between the foreground service and UI (MainActivity/ViewModels).
 *
 * ⭐ NEW: also owns the headless [MeshNotificationDelegate] used when the UI is
 * closed so the foreground service can drive notifications without a ViewModel.
 */
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"

    @Volatile
    var sharedGossipSyncManager: GossipSyncManager? = null
        private set

    private val activeGossipOwners = mutableSetOf<String>()

    @Synchronized
    fun setGossipManager(
        mgr: GossipSyncManager,
        signer: (BitchatPacket) -> BitchatPacket
    ) {
        val previous = sharedGossipSyncManager
        if (previous !== mgr) {
            try { previous?.stop() } catch (_: Exception) { }
        }
        sharedGossipSyncManager = mgr
        mgr.delegate = TransportGossipDelegate(signer)
        if (activeGossipOwners.isNotEmpty()) {
            mgr.start()
        }
    }

    @Synchronized
    fun startSharedGossip(owner: String) {
        val wasIdle = activeGossipOwners.isEmpty()
        activeGossipOwners.add(owner)
        if (wasIdle) {
            sharedGossipSyncManager?.start()
        }
    }

    @Synchronized
    fun stopSharedGossip(owner: String) {
        activeGossipOwners.remove(owner)
        if (activeGossipOwners.isEmpty()) {
            sharedGossipSyncManager?.stop()
        }
    }

    private class TransportGossipDelegate(
        private val signer: (BitchatPacket) -> BitchatPacket
    ) : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) {
            TransportBridgeService.broadcastFromLocal(RoutedPacket(packet))
        }

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            TransportBridgeService.sendToPeerFromLocal(peerID, packet)
        }

        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
            return signer(packet)
        }
    }

    @Volatile
    var meshService: BluetoothMeshService? = null
        private set

    @Volatile
    var unifiedMeshService: UnifiedMeshService? = null
        private set

    // ⭐ NEW: headless notification delegate for the foreground service.
    // Created lazily on first request, reused until clear() is called.
    @Volatile
    private var notificationDelegate: MeshNotificationDelegate? = null

    @Synchronized
    fun getOrCreate(context: Context): BluetoothMeshService {
        val existing = meshService
        if (existing != null) {
            // If the existing instance is healthy, reuse it; otherwise, replace it.
            return try {
                if (existing.isReusable()) {
                    android.util.Log.d(TAG, "Reusing existing BluetoothMeshService instance")
                    existing
                } else {
                    android.util.Log.w(TAG, "Existing BluetoothMeshService not reusable; replacing with a fresh instance")
                    // Best-effort stop before replacing
                    try { existing.stopServices() } catch (e: Exception) {
                        android.util.Log.w(TAG, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = BluetoothMeshService(context.applicationContext)
                    android.util.Log.i(TAG, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    unifiedMeshService = null
                    created
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking service reusability; creating new instance: ${e.message}")
                val created = BluetoothMeshService(context.applicationContext)
                meshService = created
                unifiedMeshService = null
                created
            }
        }
        val created = BluetoothMeshService(context.applicationContext)
        android.util.Log.i(TAG, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        unifiedMeshService = null
        return created
    }

    @Synchronized
    fun getUnifiedOrCreate(context: Context): UnifiedMeshService {
        val bluetooth = getOrCreate(context)
        val existing = unifiedMeshService
        if (existing != null) {
            existing.refreshDelegates()
            return existing
        }
        val created = UnifiedMeshService(context.applicationContext, bluetooth)
        unifiedMeshService = created
        android.util.Log.i(TAG, "Created new UnifiedMeshService")
        return created
    }

    @Synchronized
    fun attach(service: BluetoothMeshService) {
        android.util.Log.d(TAG, "Attaching BluetoothMeshService to holder")
        meshService = service
        unifiedMeshService = null
    }

    // ⭐ NEW: notification delegate management

    /**
     * Get or lazily create the headless [MeshNotificationDelegate].
     * Safe to call from any thread. Same instance is returned until [clear] is called.
     */
    @Synchronized
    fun getNotificationDelegate(context: Context): MeshNotificationDelegate {
        return notificationDelegate ?: MeshNotificationDelegate(context.applicationContext).also {
            notificationDelegate = it
            android.util.Log.i(TAG, "Created MeshNotificationDelegate")
        }
    }

    /**
     * Convenience accessor — returns null if never created yet.
     * Use [getNotificationDelegate] if you want to force creation.
     */
    fun peekNotificationDelegate(): MeshNotificationDelegate? = notificationDelegate

    /**
     * Install the headless delegate on UnifiedMeshService as the active delegate.
     * Call this when the UI goes to background (onPause/onStop).
     * Also (re-)attaches to reset stale view state (currentPrivateChatPeer, currentGeohash).
     */
    @Synchronized
    fun attachNotificationDelegate(context: Context) {
        val unified = unifiedMeshService ?: run {
            android.util.Log.w(TAG, "attachNotificationDelegate: no UnifiedMeshService yet")
            return
        }
        val delegate = getNotificationDelegate(context)
        delegate.attach()
        unified.delegate = delegate
        android.util.Log.d(TAG, "Installed MeshNotificationDelegate as active mesh delegate")
    }

    /**
     * Detach (called on service stop / shutdown).
     * Does not destroy the delegate; the same instance is reused on next start
     * so its internal caches (nickname) survive across service restarts.
     */
    @Synchronized
    fun detachNotificationDelegate() {
        try {
            notificationDelegate?.detach()
        } catch (_: Exception) { }
        try {
            // Only clear if the delegate is currently the active one.
            if (unifiedMeshService?.delegate === notificationDelegate) {
                unifiedMeshService?.delegate = null
            }
        } catch (_: Exception) { }
        android.util.Log.d(TAG, "Detached MeshNotificationDelegate")
    }

    /**
     * ⭐ NEW: Reset peer connect/disconnect tracking on the unified service.
     * Called from panic mode so the next connected peer fires a fresh notification
     * even if the underlying peer ID happens to repeat.
     */
    @Synchronized
    fun resetPeerLifecycleTracking() {
        try {
            unifiedMeshService?.resetPeerLifecycleTracking()
            android.util.Log.d(TAG, "Reset peer lifecycle tracking on UnifiedMeshService")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "resetPeerLifecycleTracking failed: ${e.message}")
        }
    }

    @Synchronized
    fun clear() {
        android.util.Log.d(TAG, "Clearing BluetoothMeshService from holder")
        // Drop notification delegate first so its attach() won't fire on a
        // service that is about to disappear.
        try { notificationDelegate?.detach() } catch (_: Exception) { }
        notificationDelegate = null

        try { sharedGossipSyncManager?.stop() } catch (_: Exception) { }
        sharedGossipSyncManager = null
        activeGossipOwners.clear()
        meshService = null
        unifiedMeshService = null
    }
}
