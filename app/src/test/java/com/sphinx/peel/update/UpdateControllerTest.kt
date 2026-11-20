package com.sphinx.peel.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The state machine over fakes, run inline (Dispatchers.Unconfined) so each call finishes before the assertion.
@RunWith(RobolectricTestRunner::class)
class UpdateControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Store(override var lastCheckMillis: Long = 0, override var dismissedCode: Int = 0) : UpdateStore
    private class Installer(var allowed: Boolean, var archive: PackageFacts? = PackageFacts("p", 118, setOf("k")), var starts: Boolean = true) : ApkInstaller {
        var started = 0
        override fun canInstall() = allowed
        override fun openPermissionSettings() {}
        override fun installedFacts() = PackageFacts("p", 117, setOf("k"))
        override fun archiveFacts(file: File) = archive
        override fun install(file: File): Boolean { started++; return starts }
    }

    private val body = """{"tag_name":"v0.1.18","assets":[{"name":"peel-0.1.18.apk","browser_download_url":"https://x/y.apk","size":4}]}"""

    private fun controller(installer: Installer, store: Store = Store(), enabled: Boolean = true): UpdateController {
        val checker = UpdateChecker({ _, _ -> HttpReply(200, emptyMap(), body) }, store, 117, "0.1.17", clock = { 1L shl 40 })
        val fetch = ApkFetch { object : ApkStream {
            override val length = 4L
            override val input: InputStream = ByteArrayInputStream(ByteArray(4))
            override fun close() {}
        } }
        return UpdateController(checker, ApkDownloader(fetch), installer, File(tmp.root, "updates"), 117, enabled,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined)
    }

    private fun files() = File(tmp.root, "updates").list()!!.size

    @Test fun launchCheckPromptsThenInstalls() {
        val installer = Installer(allowed = true)
        val c = controller(installer)
        c.checkOnLaunch()
        assertTrue(c.state.value is UpdateState.Available)
        assertTrue(c.prompt.value)
        c.update()
        assertTrue(c.state.value is UpdateState.Installing)
        assertEquals(1, installer.started)
        c.resume() // back from a declined installer: offered again
        assertTrue(c.state.value is UpdateState.Available)
    }

    @Test fun permissionStepResumesInstall() {
        val installer = Installer(allowed = false)
        val c = controller(installer)
        c.checkNow(); c.update()
        assertTrue(c.state.value is UpdateState.NeedsPermission)
        c.resume()
        assertTrue(c.state.value is UpdateState.NeedsPermission)
        installer.allowed = true
        c.resume()
        assertTrue(c.state.value is UpdateState.Installing)
    }

    @Test fun abandoningPermissionRemovesTheFile() {
        val c = controller(Installer(allowed = false))
        c.checkNow(); c.update(); c.abandonPermission()
        assertTrue(c.state.value is UpdateState.Available)
        assertEquals(0, files())
    }

    @Test fun wrongSignatureIsRefusedAndDeleted() {
        val installer = Installer(allowed = true, archive = PackageFacts("p", 118, setOf("other")))
        val c = controller(installer)
        c.checkNow(); c.update()
        assertEquals(UpdateFailure.WrongSignature, (c.state.value as UpdateState.Failed).failure)
        assertEquals(0, installer.started)
        assertEquals(0, files())
    }

    @Test fun installerNotFoundIsAMessageNotACrash() {
        val c = controller(Installer(allowed = true, starts = false))
        c.checkNow(); c.update()
        assertEquals(UpdateFailure.InstallBlocked, (c.state.value as UpdateState.Failed).failure)
        assertEquals(0, files())
    }

    @Test fun laterClosesThePromptAndSilencesLaunchChecks() {
        val store = Store()
        val c = controller(Installer(true), store)
        c.checkOnLaunch(); c.later()
        assertFalse(c.prompt.value)
        store.lastCheckMillis = 0
        val c2 = controller(Installer(true), store)
        c2.checkOnLaunch()
        assertTrue(c2.state.value is UpdateState.Idle)
    }

    @Test fun disabledBuildsNeverCheckAutomaticallyAndSayWhyOnATap() {
        val c = controller(Installer(true), enabled = false)
        c.checkOnLaunch()
        assertTrue(c.state.value is UpdateState.Idle)
        c.checkNow()
        assertEquals(UpdateFailure.Disabled, (c.state.value as UpdateState.Failed).failure)
    }
}
