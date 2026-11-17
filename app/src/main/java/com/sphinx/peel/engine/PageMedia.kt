package com.sphinx.peel.engine

/// A page's camera and microphone, as plain rules (iOS `OrgPageSheet`'s `requestMediaCapturePermissionFor`, which strut's camera
/// sheet needs: strut's kit draws the camera in its own page, "an app's frame gets no camera of its own"). Granted to the origin
/// Peel loaded, with the system's own permission as the only prompt; never to another origin, and never anything but the camera
/// and the microphone (docs/phases/42-page-host-logs.md).
///
/// Android's request names the asking origin, not its frame (iOS also checks the frame is the main one). An app's frame is
/// sandboxed without `allow-same-origin`, so its origin is opaque and never matches; a same-origin frame of strut's own would.
object PageMedia {
    /// `PermissionRequest.RESOURCE_VIDEO_CAPTURE` / `RESOURCE_AUDIO_CAPTURE` (plain strings here, so this is JVM-testable).
    const val VIDEO = "android.webkit.resource.VIDEO_CAPTURE"
    const val AUDIO = "android.webkit.resource.AUDIO_CAPTURE"

    /// `Manifest.permission.CAMERA` / `RECORD_AUDIO`, both declared (calls and the invoice scanner use them).
    const val CAMERA = "android.permission.CAMERA"
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"

    /// What of [resources] the page may have: the camera and the microphone, asked by the origin Peel loaded ([pageOrigin]); else
    /// nothing (iOS `StrutWallet.mayAnswer` on the origin: scheme, host and port, a default port written or not).
    fun grantable(requestOrigin: String, pageOrigin: String, resources: List<String>): List<String> =
        if (!StrutWallet.mayAnswer(isMainFrame = true, sourceOrigin = requestOrigin, origin = pageOrigin)) {
            emptyList()
        } else {
            resources.filter { it == VIDEO || it == AUDIO }.distinct()
        }

    /// The system permissions [resources] need.
    fun systemPermissions(resources: List<String>): List<String> =
        resources.mapNotNull { permissionFor(it) }.distinct()

    /// What is granted once the system has answered: each resource whose permission the app [holds].
    fun granted(resources: List<String>, holds: (String) -> Boolean): List<String> =
        resources.filter { resource -> permissionFor(resource)?.let(holds) == true }

    private fun permissionFor(resource: String): String? = when (resource) {
        VIDEO -> CAMERA
        AUDIO -> RECORD_AUDIO
        else -> null
    }
}
