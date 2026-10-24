package com.bitchat.android

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bitchat.android.service.MeshService

/**
 * Observes the app's lifecycle and informs MeshService whether the app
 * is in foreground or background.
 *
 * Errors previously seen:
 *   - Unresolved reference 'setAppBackgroundState'
 *   - Cannot infer type for this parameter / Not enough info for 'T'
 *       → These are CASCADE errors from the unresolved function.
 *       They disappear once setAppBackgroundState() exists on MeshService.
 */
class AppLifecycleObserver(
    private val context: Context
) : DefaultLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        Log.d(TAG, "onCreate")
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d(TAG, "App → FOREGROUND")
        // setAppBackgroundState resolved now ✅
        MeshService.getInstance()?.setAppBackgroundState(false)
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.d(TAG, "onResume")
        MeshService.getInstance()?.setAppBackgroundState(false)
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        Log.d(TAG, "onPause")
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "App → BACKGROUND")
        // setAppBackgroundState resolved now ✅
        MeshService.getInstance()?.setAppBackgroundState(true)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        Log.d(TAG, "onDestroy")
        MeshService.getInstance()?.setAppBackgroundState(true)
    }

    companion object {
        private const val TAG = "AppLifecycleObserver"
    }
}
