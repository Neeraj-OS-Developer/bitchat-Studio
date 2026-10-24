package com.bitchat.android

import android.app.Application
import android.util.Log
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.ArtiTorManager

/**
 * Application entry point.
 *
 * Initialization order matters:
 *   1. Tor first — any early network goes over Tor
 *   2. RelayDirectory — loaded from assets/cache before any Nostr call
 *   3. DebugPreferenceManager — read by many other components
 *   4. MeshServicePreferences — read by MeshForegroundService
 *   5. NotificationManager singleton — creates channels + is shared by UI + service
 *   6. AppLifecycleObserver — CRITICAL: tracks foreground/background so the
 *      notification layer can decide whether to fire notifications
 *   7. Foreground service — last, so all dependencies are ready
 *
 * The observer must be initialized BEFORE the foreground service starts so that
 * the first peer-connect notification has a correct background-state signal.
 */
class BitchatApplication : Application() {

    companion object {
        private const val TAG = "BitchatApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: starting application initialization")

        // ─── 1. Tor (first so any early network goes over Tor) ─────────────────
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
            Log.d(TAG, "ArtiTorManager initialized")
        } catch (e: Exception) {
            Log.w(TAG, "ArtiTorManager init failed: ${e.message}")
        }

        // ─── 2. Relay directory (loads assets/nostr_relays.csv) ────────────────
        try {
            RelayDirectory.initialize(this)
            Log.d(TAG, "RelayDirectory initialized")
        } catch (e: Exception) {
            Log.w(TAG, "RelayDirectory init failed: ${e.message}")
        }

        // ─── 3. Location Notes dependencies ───────────────────────────────────
        try {
            com.bitchat.android.nostr.LocationNotesInitializer.initialize(this)
        } catch (_: Exception) { }

        // ─── 4. Favorites persistence (needed by MessageRouter/NostrTransport) ─
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // ─── 5. Warm up Nostr identity so npub is available ────────────────────
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // ─── 6. Theme preference ───────────────────────────────────────────────
        try {
            ThemePreferenceManager.init(this)
        } catch (e: Exception) {
            Log.w(TAG, "Theme init failed: ${e.message}")
        }

        // ─── 7. Debug preferences (must be before anything that reads them) ───
        try {
            com.bitchat.android.ui.debug.DebugPreferenceManager.init(this)
            Log.d(TAG, "DebugPreferenceManager initialized")
        } catch (e: Exception) {
            Log.w(TAG, "DebugPreferenceManager init failed: ${e.message}")
        }

        // ─── 8. Wi-Fi Aware controller (reads DebugPreferenceManager) ─────────
        try {
            val enabled = com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(false)
            com.bitchat.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (e: Exception) {
            Log.w(TAG, "WifiAwareController init failed: ${e.message}")
        }

        // ─── 9. Geohash registries ─────────────────────────────────────────────
        try {
            com.bitchat.android.nostr.GeohashAliasRegistry.initialize(this)
            com.bitchat.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (e: Exception) {
            Log.w(TAG, "Geohash registries init failed: ${e.message}")
        }

        // ─── 10. Mesh service preferences (required by FGS start logic) ────────
        try {
            MeshServicePreferences.init(this)
            Log.d(TAG, "MeshServicePreferences initialized")
        } catch (e: Exception) {
            Log.w(TAG, "MeshServicePreferences init failed: ${e.message}")
        }

        // ─── 11. Pre-warm NotificationManager singleton ────────────────────────
        // Creates DM / Geohash / PeerConnect notification channels and ensures
        // the same instance is used by the UI, the foreground service, and the
        // AppLifecycleObserver. Doing this here avoids a cold-path on the first
        // notification attempt.
        try {
            NotificationManager.getInstance(this)
            Log.d(TAG, "NotificationManager singleton pre-warmed (channels created)")
        } catch (e: Exception) {
            Log.w(TAG, "NotificationManager pre-warm failed: ${e.message}")
        }

        // ─── 12. App lifecycle observer (CRITICAL for notifications) ──────────
        // Without this, isAppInBackground stays false forever and the notification
        // layer will suppress everything as if the app were foreground.
        try {
            AppLifecycleObserver.initialize(this)
            Log.d(TAG, "AppLifecycleObserver initialized — foreground/background tracking active")
        } catch (e: Exception) {
            Log.e(TAG, "AppLifecycleObserver init failed (background notifications disabled): ${e.message}", e)
        }

        // ─── 13. Foreground service (last, so all dependencies are ready) ─────
        try {
            MeshForegroundService.start(this)
        } catch (e: Exception) {
            Log.w(TAG, "MeshForegroundService start failed (will retry when Activity starts): ${e.message}")
        }

        Log.i(TAG, "onCreate: application initialization complete")
    }
}
