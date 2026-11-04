package com.sphinx.peel.push

import com.sphinx.peel.BuildConfig

/// How this app identifies itself to the hub when it registers an FCM token
/// (`setPushTokenForApp`'s `appId`/`pushEnvironment`).
///
/// The id is its own, not `"sphinx"` (the official Android app's: registering
/// as that would overwrite its token on the same account) and not
/// `com.sphinx.peel` (the iOS bundle id, which is also this `applicationId`).
/// Debug and release share one Firebase app, hence one id; FCM has no sandbox,
/// so the environment is always production.
object PushApp {
    /// `BuildConfig.PUSH_APP_ID` (app/build.gradle.kts): "com.sphinx.peel.android".
    val APP_ID: String = BuildConfig.PUSH_APP_ID
    const val ENVIRONMENT = "production"
}
