package com.sphinx.peel.call

import android.content.pm.ServiceInfo

/// Which foreground-service types a running call declares (`CallService`,
/// docs/phases/15-call-background.md). Pure, so it is JVM-tested: the types
/// are chosen per call at runtime (`ServiceCompat.startForeground`), not just
/// by the manifest, because Android 14 needs each type's permission held and
/// only lets the camera type start while the app is visible.
object ForegroundTypes {
    const val NONE = 0
    const val MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    const val CAMERA = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

    /// The microphone type always (a call is a microphone call); the camera
    /// type only when the person wants video (a video call, or the camera
    /// turned on) and may use the camera. [NONE] when the microphone is not
    /// allowed: there is then no type a call service can start with.
    fun select(micGranted: Boolean, wantsCamera: Boolean, cameraGranted: Boolean): Int {
        if (!micGranted) return NONE
        return if (wantsCamera && cameraGranted) MICROPHONE or CAMERA else MICROPHONE
    }

    /// Whether moving from [current] to [next] only adds a type the app may
    /// not be able to start from the background: the camera type.
    fun addsCamera(current: Int, next: Int): Boolean = (next and CAMERA) != 0 && (current and CAMERA) == 0
}
