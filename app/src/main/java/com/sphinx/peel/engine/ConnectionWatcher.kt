package com.sphinx.peel.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.net.NetworkCapabilities
import com.sphinx.peel.diagnostics.DiagnosticEvent
import com.sphinx.peel.diagnostics.Diagnostics

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
            note(network)
            val changed = current != null && current != network
            val wasLost = current == null && !first
            current = network
            if (changed || wasLost) engine.networkChanged()
            first = false
        }

        override fun onLost(network: Network) {
            if (current == network) {
                Diagnostics.recordNetwork(DiagnosticEvent.NetworkStatus.unsatisfied, DiagnosticEvent.NetworkInterface.none, isExpensive = false, isConstrained = false)
            }
            if (current == network) current = null
        }
    }

    /// A raw change of the network path (iOS `NWPathMonitor` handler's `recordNetwork`).
    private fun note(network: Network) {
        val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
        val via = when {
            caps == null -> DiagnosticEvent.NetworkInterface.other
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DiagnosticEvent.NetworkInterface.wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DiagnosticEvent.NetworkInterface.cellular
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> DiagnosticEvent.NetworkInterface.wiredEthernet
            else -> DiagnosticEvent.NetworkInterface.other
        }
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val constrained = runCatching { manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED }.getOrDefault(false)
        Diagnostics.recordNetwork(DiagnosticEvent.NetworkStatus.satisfied, via, expensive, constrained)
    }

    private val lifecycle = object : DefaultLifecycleObserver {
        private var firstStart = true
        private var leftAt: Long? = null
        override fun onStop(owner: LifecycleOwner) {
            leftAt = System.currentTimeMillis()
        }

        override fun onStart(owner: LifecycleOwner) {
            if (firstStart) {
                firstStart = false
            } else {
                // The engine always reconnects on coming to the front (it cannot tell a good connection
                // from one the system quietly killed): iOS's `foregroundDecision` is always this one here.
                val away = leftAt?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0
                Diagnostics.recordForegroundDecision(DiagnosticEvent.ForegroundAction.reconnect, DiagnosticEvent.ForegroundCause.away, away)
                engine.resume()
            }
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
