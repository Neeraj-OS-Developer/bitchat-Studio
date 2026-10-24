package com.bitchat.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bitchat.android.ui.NotificationManager

/**
 * Process-wide lifecycle observer that tracks whether the app is in the foreground
 * (any Activity visible) or background (no Activity visible).
 *
 * Purpose:
 *   [NotificationManager] uses [setAppBackgroundState] to decide whether to suppress
 *   or deliver notifications. Historically this was only called from Activity
 *   lifecycle, which missed the important transition "user swipes away the app but
 *   the foreground service keeps running". This observer is the single source of
 *   truth for that state — both the UI (ChatViewModel) and the background service
 *   (MeshNotificationDelegate) share the same NotificationManager singleton, and
 *   therefore the same state.
 *
 * Integration:
 *   [BitchatApplication.onCreate] calls [initialize].
 *
 * Notes:
 *   - Uses [ProcessLifecycleOwner], so it fires exactly once for the whole process,
 *     regardless of how many Activities come and go.
 *   - Safe to call [initialize] multiple times (idempotent).
 *   - Rotation-safe: ProcessLifecycleOwner debounces ON_STOP by 700ms so quick
 *     Activity switches (rotation, activity-to-activity navigation) do not flip
 *     the state.
 */
object AppLifecycleObserver : DefaultLifecycleObserver {
    private const val TAG = "AppLifecycleObserver"

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    /**
     * Registers the observer with [ProcessLifecycleOwner].
     * Must be called from [android.app.Application.onCreate].
     */
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            val ctx = context.applicationContext
            appContext = ctx

            // Read current state synchronously so the very first notification
            // decisions are correct even before any lifecycle event fires.
            val isForeground = try {
                ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read ProcessLifecycleOwner state yet: ${e.message}")
                false
            }

            // Seed initial state (conservative: if unsure, assume background).
            runCatching {
                NotificationManager.getInstance(ctx).setAppBackgroundState(!isForeground)
            }.onFailure {
                Log.w(TAG, "Failed to seed initial background state: ${it.message}")
            }

            // Register on main thread (ProcessLifecycleOwner requires this).
            Handler(Looper.getMainLooper()).post {
                try {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(this)
                    initialized = true
                    Log.i(TAG, "Lifecycle observer registered. Initial foreground=$isForeground")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register lifecycle observer: ${e.message}", e)
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val ctx = appContext ?: return
        runCatching {
            NotificationManager.getInstance(ctx).setAppBackgroundState(false)
            Log.d(TAG, "App is FOREGROUND — notifications suppressed")
        }.onFailure {
            Log.w(TAG, "onStart: ${it.message}")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val ctx = appContext ?: return
        runCatching {
            NotificationManager.getInstance(ctx).setAppBackgroundState(true)
            Log.d(TAG, "App is BACKGROUND — notifications enabled")
        }.onFailure {
            Log.w(TAG, "onStop: ${it.message}")
        }
    }

    /**
     * Detach the observer. Production never calls this — the process simply dies.
     * Useful for Robolectric tests that recreate the app.
     */
    fun shutdown() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }
        initialized = false
        appContext = null
    }

    /**
     * Returns the current foreground state — useful for debug helpers and tests.
     */
    fun isAppForeground(): Boolean {
        return try {
            ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        } catch (_: Exception) {
            false
        }
    }

    fun isInitialized(): Boolean = initialized
}
