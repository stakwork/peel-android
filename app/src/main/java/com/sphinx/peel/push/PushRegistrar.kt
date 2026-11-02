package com.sphinx.peel.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.sphinx.peel.BuildConfig
import com.sphinx.peel.engine.Engine

/// Keeps this install's FCM token and registers it with the account's
/// server, the way `Apps/iOS/Peel/PushRegistration.swift`'s `PushCoordinator`
/// does for the APNs token: the token is asked for at every launch (it can
/// change), kept, and sent through the core (`Engine.registerPushToken`)
/// once an account is open *and* connected; again on a new token
/// ([onNewToken]) and after each (re)connect; the same token is not sent
/// twice per engine. The token and the push key are never logged.
///
/// Not ported: iOS's `appId`/`pushEnvironment` arguments
/// (`setPushTokenForApp`): the bundled sphinxrs bindings only have
/// `setPushToken` — see docs/phases/16-push-and-reliability.md.
///
/// Off, and a no-op, without a Firebase config (`google-services.json`: no
/// `FirebaseApp`) or with `BuildConfig.PUSH_REGISTER_WITH_HUB` false.
object PushRegistrar {
    private const val TAG = "PeelPush"
    private const val FILE = "peel-push"
    private const val TOKEN = "fcm_token"

    @Volatile private var engine: Engine? = null
    @Volatile private var registered: String? = null

    /// Whether Firebase is configured in this build.
    fun available(context: Context): Boolean =
        BuildConfig.PUSH_REGISTER_WITH_HUB && FirebaseApp.getApps(context.applicationContext).isNotEmpty()

    /// An account is open (after login or unlock): remember its engine,
    /// fetch the token, and register on every connection it makes.
    fun accountOpened(context: Context, engine: Engine) {
        val app = context.applicationContext
        this.engine = engine
        registered = null
        if (!available(app)) return
        engine.onConnected = { registerIfReady(app, engine) }
        PushKeyStore.loadOrCreate(app)
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> onNewToken(app, token) }
        }
        // Already connected by the time this runs? Then no `onConnected` is coming.
        registerIfReady(app, engine)
    }

    fun activeEngine(): Engine? = engine

    fun accountClosed() {
        engine?.onConnected = null
        engine = null
        registered = null
    }

    /// FirebaseMessagingService.onNewToken, and the launch-time fetch.
    fun onNewToken(context: Context, token: String) {
        val app = context.applicationContext
        if (token.isEmpty()) return
        app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(TOKEN, token).apply()
        engine?.let { registerIfReady(app, it) }
    }

    fun token(context: Context): String? =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(TOKEN, null)

    private fun registerIfReady(app: Context, engine: Engine) {
        if (!available(app) || this.engine !== engine) return
        val token = token(app)?.takeIf { it.isNotEmpty() } ?: return
        if (registered == token) return
        val key = PushKeyStore.loadOrCreate(app)
        engine.registerPushToken(token, key) { ok ->
            if (ok) registered = token else Log.d(TAG, "push token not registered yet")
        }
    }
}
