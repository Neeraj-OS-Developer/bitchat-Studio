package com.bitchat.android

// ─────────────────────────────────────────────────────────────
// IMPORTS (relevant ones)
// ─────────────────────────────────────────────────────────────
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.service.MeshService
import com.bitchat.android.ui.AppNotificationManager          // ✅ Naya import
import kotlinx.coroutines.launch

/**
 * MainActivity — entry point of the app.
 *
 * NOTIFICATION INTEGRATION NOTES:
 *  - Custom class is now `AppNotificationManager` (renamed from NotificationManager)
 *  - This avoids clash with android.app.NotificationManager
 *  - `clearPeerConnectedNotification(peerId)` now exists on AppNotificationManager
 */
class MainActivity : ComponentActivity() {

    // ─────────────────────────────────────────────────────────────
    // FIELDS
    // ─────────────────────────────────────────────────────────────

    // ✅ FIXED type — AppNotificationManager (not NotificationManager)
    private lateinit var notificationManager: AppNotificationManager

    private val appLifecycleObserver by lazy { AppLifecycleObserver(this) }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.w(TAG, "Notification permission denied")
            }
        }

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ FIXED initialization — uses AppNotificationManager singleton
        notificationManager = AppNotificationManager.getInstance(this)

        // Register lifecycle observer (mesh background state)
        lifecycle.addObserver(appLifecycleObserver)

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        // TODO: Your existing setup code goes here:
        // - setContent { ... }
        // - meshService binding
        // - viewModel init
        // - etc.
    }

    override fun onDestroy() {
        lifecycle.removeObserver(appLifecycleObserver)
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────
    // NOTIFICATION HELPERS
    // ─────────────────────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // EXAMPLE USAGE — what fixes line 890
    // ─────────────────────────────────────────────────────────────

    /**
     * Example of the call that was previously failing at MainActivity.kt:890.
     * After Step 1, `clearPeerConnectedNotification` EXISTS on AppNotificationManager,
     * so this compiles cleanly.
     */
    private fun onPeerDisconnected(peerId: String) {
        notificationManager.clearPeerConnectedNotification(peerId)   // ✅ compiles now
    }

    private fun onPeerConnected(peerId: String, peerName: String) {
        notificationManager.showPeerConnectedNotification(peerId, peerName)
    }

    private fun onMessageReceived(peerId: String, peerName: String, message: String) {
        notificationManager.showMessageNotification(peerId, peerName, message)
    }

    private fun onAllPeersCleared() {
        notificationManager.clearAllNotifications()
    }

    // ─────────────────────────────────────────────────────────────
    // SERVICE START/STOP
    // ─────────────────────────────────────────────────────────────

    private fun startMeshService() {
        val intent = Intent(this, MeshService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopMeshService() {
        stopService(Intent(this, MeshService::class.java))
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
