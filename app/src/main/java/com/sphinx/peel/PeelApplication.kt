package com.sphinx.peel

import android.app.Application
import com.sphinx.peel.call.CallController
import com.sphinx.peel.diagnostics.CrashRecorder
import com.sphinx.peel.diagnostics.Diagnostics
import com.sphinx.peel.diagnostics.DiagnosticEvent

/// Holds what must outlive any Activity: the call (`CallController`,
/// docs/phases/15-call-background.md). Nothing else lives here.
class PeelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // First: a crash anywhere after this is recorded (docs/phases/28-diagnostics.md).
        CrashRecorder.install(this)
        CallController.install(this)
    }

    /// The system's memory warnings (iOS `didReceiveMemoryWarning`): a running process asked to give memory back.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_MODERATE, TRIM_MEMORY_RUNNING_LOW -> Diagnostics.memoryWarning(DiagnosticEvent.PressureLevel.warning)
            TRIM_MEMORY_RUNNING_CRITICAL -> Diagnostics.memoryWarning(DiagnosticEvent.PressureLevel.critical)
        }
    }
}
