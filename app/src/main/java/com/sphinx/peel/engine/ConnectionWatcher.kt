package com.sphinx.peel.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/// Tells the engine when to reconnect for reasons it can't see itself: the
/// default network changed or came back (`ConnectivityManager.NetworkCallback`,
/// PeelCore's `NWPathMonitor` watch), and the app came to the front
/// (`ProcessLifecycleOwner`, the whole app rather than one activity, so
/// rotating or reopening an activity is not a "resume"). Reconnecting with
/// backoff after a drop is `Engine`'s own. Both triggers skip the event the
/// registration itself replays, so `start()`'s first connect is not raced.
class ConnectionWatcher(context: Context, private val engine: Engine) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(ConnectivityManager::class.java)
    private var current: Network? = null
    private var first = true

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = current != null && current != network
            val wasLost = current == null && !first
            current = network
            if (changed || wasLost) engine.networkChanged()
            first = false
        }

        override fun onLost(network: Network) {
            if (current == network) current = null
        }
    }

    private val lifecycle = object : DefaultLifecycleObserver {
        private var firstStart = true
        override fun onStart(owner: LifecycleOwner) {
            if (firstStart) firstStart = false else engine.resume()
        }
    }

    private var running = false

    fun start() {
        if (running) return
        running = true
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycle)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager.unregisterNetworkCallback(callback) }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycle)
    }
}
