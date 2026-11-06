package com.sphinx.peel.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.sphinx.peel.BuildConfig
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage

/// Keeps this install's FCM token and registers it with the account's
/// server, the way `Apps/iOS/Peel/PushRegistration.swift`'s `PushCoordinator`
/// does for the APNs token: the token is asked for at every launch (it can
/// change), kept, and sent through the core (`Engine.registerPushToken`)
/// once an account is open *and* connected; again on a new token
/// ([onNewToken]) and after each (re)connect; the same token is not sent
/// twice per engine. The token and the push key are never logged.
///
/// Registers under this app's own id (`PushApp`), not the official app's;
/// it also keeps the sealed chat-name directory current (`PushNameSync`).
///
/// Off, and a no-op, without a Firebase config (`google-services.json`: no
/// `FirebaseApp`) or with `BuildConfig.PUSH_REGISTER_WITH_HUB` false.
object PushRegistrar {
    private const val TAG = "PeelPush"
    private const val FILE = "peel-push"
    private const val TOKEN = "fcm_token"

    @Volatile private var engine: Engine? = null
    @Volatile private var registered: String? = null
    @Volatile private var nameSync: PushNameSync? = null

    /// Whether Firebase is configured in this build.
    fun available(context: Context): Boolean =
        BuildConfig.PUSH_REGISTER_WITH_HUB && FirebaseApp.getApps(context.applicationContext).isNotEmpty()

    /// An account is open (after login or unlock): remember its engine,
    /// fetch the token, and register on every connection it makes.
    fun accountOpened(context: Context, engine: Engine, storage: Storage) {
        val app = context.applicationContext
        this.engine = engine
        registered = null
        if (!available(app)) return
        val sync = PushNameSync(engine, storage, PushNameStore.forContext(app)).also { it.start() }
        nameSync = sync
        engine.onConnected = {
            sync.kick()
            registerIfReady(app, engine)
        }
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
        nameSync?.stop()
        nameSync = null
        engine = null
        registered = null
    }

    /// "Forget this account": the push key goes too (a push for the old
    /// account then cannot be named), and so does the token: the stored copy
    /// is removed (even without Firebase) and `FirebaseMessaging.deleteToken()`
    /// is asked for (best-effort, async, never throws or blocks), so FCM
    /// reports the old token invalid and the mixer drops it on its next push.
    ///
    /// The server-side unregister (`Engine.unregisterPushToken`, the core's
    /// `unregisterPushTokenForApp`) is not done here: no engine exists by now,
    /// so the caller (the "Forget this account" confirm in `YouScreen`) runs it
    /// first, before `engine.cancel()` and any wipe, best effort within 3 s.
    /// What is below is the local fallback when that was offline or failed.
    ///
    /// Only for forgetting: [accountClosed] (lock) keeps the token, it is the
    /// install's and the account comes back.
    fun accountForgotten(context: Context) {
        val app = context.applicationContext
        PushKeyStore.clear(app)
        registered = null
        forgetToken(
            available = available(app),
            removeStored = { app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(TOKEN).apply() },
            deleteToken = {
                FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { t ->
                    if (!t.isSuccessful) Log.d(TAG, "FCM token not deleted: ${t.exception?.javaClass?.simpleName}")
                }
            },
        )
    }

    /// The pure part of [accountForgotten]. The stored token is always
    /// removed; `deleteToken` runs only when push is available, and a
    /// failure of either is logged (never the token) and swallowed.
    internal fun forgetToken(available: Boolean, removeStored: () -> Unit, deleteToken: () -> Unit) {
        runCatching(removeStored).onFailure { Log.d(TAG, "stored push token not removed: ${it.javaClass.simpleName}") }
        if (!available) return
        runCatching(deleteToken).onFailure { Log.d(TAG, "FCM token not deleted: ${it.javaClass.simpleName}") }
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
