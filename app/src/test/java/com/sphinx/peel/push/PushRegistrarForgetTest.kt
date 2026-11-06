package com.sphinx.peel.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRegistrarForgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = context.getSharedPreferences("peel-push", Context.MODE_PRIVATE)

    @Test fun `token removed and deleteToken invoked once`() {
        var removed = false
        var deleted = 0
        PushRegistrar.forgetToken(true, { removed = true }, { deleted++ })
        assertTrue(removed)
        assertEquals(1, deleted)
    }

    @Test fun `deleteToken throwing does not propagate`() {
        var removed = false
        PushRegistrar.forgetToken(true, { removed = true }, { throw IllegalStateException("boom") })
        assertTrue(removed)
    }

    @Test fun `deleteToken not called when push unavailable but token removed`() {
        var removed = false
        var deleted = false
        PushRegistrar.forgetToken(false, { removed = true }, { deleted = true })
        assertTrue(removed)
        assertFalse(deleted)
    }

    @Test fun `removal failure does not stop deleteToken`() {
        var deleted = false
        PushRegistrar.forgetToken(true, { throw RuntimeException("x") }, { deleted = true })
        assertTrue(deleted)
    }

    @Test fun `forgetting clears stored token and push key without Firebase`() {
        prefs().edit().putString("fcm_token", "tok").commit()
        PushKeyStore.loadOrCreate(context)
        assertEquals("tok", PushRegistrar.token(context))
        PushRegistrar.accountForgotten(context)
        assertNull(PushRegistrar.token(context))
        assertNull(PushKeyStore.existing(context))
    }
}
