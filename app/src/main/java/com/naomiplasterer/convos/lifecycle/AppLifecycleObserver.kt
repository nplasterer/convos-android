package com.naomiplasterer.convos.lifecycle

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppLifecycleObserver"

/**
 * Observes the app's lifecycle to detect when it goes to background or foreground.
 * This is useful for battery optimization and future push notification integration.
 *
 * Current behavior:
 * - Logs lifecycle transitions for debugging
 *
 * Future enhancements (when push notifications are added):
 * - Pause XMTP streams when app goes to background
 * - Resume streams when app comes to foreground
 * - Rely on push notifications for background message delivery
 */
@Singleton
class AppLifecycleObserver @Inject constructor() : DefaultLifecycleObserver {

    private var isAppInForeground = false

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.d(TAG, "AppLifecycleObserver registered")
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        isAppInForeground = true
        Log.d(TAG, "📱 App entered FOREGROUND")

        // TODO: When push notifications are implemented:
        // - Resume XMTP conversation and message streams
        // - Sync any missed messages while app was backgrounded
        // - Ensure all clients are properly loaded
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        isAppInForeground = false
        Log.d(TAG, "🌙 App entered BACKGROUND")

        // TODO: When push notifications are implemented:
        // - Pause XMTP streams to save battery
        // - Register for push notifications if not already registered
        // - Cancel any ongoing sync operations
        // - Keep WorkManager tasks running for periodic cleanup
    }

    /**
     * Returns true if the app is currently in the foreground (visible to user).
     */
    fun isInForeground(): Boolean = isAppInForeground
}
