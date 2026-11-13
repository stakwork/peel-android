package com.sphinx.peel

import android.app.Application
import com.sphinx.peel.call.CallController

/// Holds what must outlive any Activity: the call (`CallController`,
/// docs/phases/15-call-background.md). Nothing else lives here.
class PeelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallController.install(this)
    }
}
